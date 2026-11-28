package dev.anilbeesetti.nextplayer.core.media.services

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dev.anilbeesetti.nextplayer.core.common.extensions.deleteMedia
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.common.extensions.scanPaths
import dev.anilbeesetti.nextplayer.core.common.extensions.updateMedia
import dev.anilbeesetti.nextplayer.core.common.service.SuspendActivityResultLauncher
import dev.anilbeesetti.nextplayer.core.common.service.registerForSuspendActivityResult
import dev.anilbeesetti.nextplayer.core.media.index.AudioFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.ImageFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.MediaFileIndex
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single
class LocalMediaOperationsService(
    private val context: Context,
    private val mediaFileIndex: MediaFileIndex,
    private val audioFileIndex: AudioFileIndex,
    private val imageFileIndex: ImageFileIndex,
) : MediaOperationsService {

    private lateinit var activity: Activity
    private val contentResolver = context.contentResolver
    private var mediaRequestLauncher:
        SuspendActivityResultLauncher<IntentSenderRequest, ActivityResult>? = null
    private var storagePermissionLauncher: SuspendActivityResultLauncher<String, Boolean>? = null

    override fun initialize(activity: ComponentActivity) {
        this.activity = activity
        mediaRequestLauncher = activity.registerForSuspendActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        )
        storagePermissionLauncher = activity.registerForSuspendActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        )
    }

    override suspend fun deleteMedia(uris: List<Uri>, permanently: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val (fileUris, mediaStoreUris) = uris.partition { uri -> uri.scheme == "file" }
            // 先记下 MediaStore 条目的真实路径：删除/移入回收站后这几行就查不到了，
            // 但磁盘索引里还留着同路径的缓存，不摘掉的话列表会把已删的文件重新显示出来
            val mediaStorePaths = resolveMediaStorePaths(uris = mediaStoreUris)
            val mediaStoreDeleted = when {
                mediaStoreUris.isEmpty() -> true

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    if (permanently) {
                        deleteMediaR(uris = mediaStoreUris)
                    } else {
                        trashMediaR(uris = mediaStoreUris)
                    }

                else -> deleteMediaBelowR(uris = mediaStoreUris)
            }
            if (mediaStoreDeleted) removeIndexedPaths(paths = mediaStorePaths)
            mediaStoreDeleted && deleteFileSystemMedia(uris = fileUris)
        }

    private fun resolveMediaStorePaths(uris: List<Uri>): List<String> {
        if (uris.isEmpty()) return emptyList()
        val paths = mutableListOf<String>()
        for (uri in uris) {
            runCatching {
                context.contentResolver
                    .query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.DATA),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(0)?.let { path ->
                                paths.add(path)
                            }
                        }
                    }
            }
        }
        return paths
    }

    /** 把已不存在的路径从各类磁盘索引里立刻摘掉，并刷新它们所在的文件夹。 */
    private fun removeIndexedPaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        mediaFileIndex.removePaths(paths = paths)
        audioFileIndex.removePaths(paths = paths)
        imageFileIndex.removePaths(paths = paths)
        paths.forEach { path ->
            val parentPath = File(path).parent ?: return@forEach
            mediaFileIndex.requestRefresh(folderPath = parentPath)
            audioFileIndex.requestRefresh(folderPath = parentPath)
        }
    }

    /**
     * `.nomedia` 文件夹里的文件不在 MediaStore 里，系统的删除/回收站请求都拿不到它们的 uri，
     * 只能直接删文件；删完刷新磁盘索引，列表才会跟着更新。
     */
    private fun deleteFileSystemMedia(uris: List<Uri>): Boolean {
        var success = true
        val deletedPaths = mutableListOf<String>()
        for (uri in uris) {
            val path = uri.path
            if (path == null) {
                success = false
                continue
            }
            val file = File(path)
            val deleted = runCatching { file.delete() }.getOrDefault(defaultValue = false)
            if (deleted || !file.exists()) {
                deletedPaths.add(path)
            } else {
                success = false
            }
        }
        // 索引里立刻移除，列表马上更新；再顺手刷新文件夹，把同目录的新增/改名补进来
        removeIndexedPaths(paths = deletedPaths)
        return success
    }

    override suspend fun restoreMedia(uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        return@withContext Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            trashMediaR(
                uris = uris,
                trashed = false,
            )
    }

    override suspend fun renameMedia(uri: Uri, to: String): Boolean = withContext(Dispatchers.IO) {
        // 各库的输入框只显示不带后缀的名字，用户删掉后缀时统一补回原后缀；
        // 目录重命名不补后缀——文件夹名允许含点（如「My.Comics」），不能被当成扩展名补回去
        val isDirectory = uri.scheme == "file" && uri.path?.let { File(it).isDirectory } == true
        val targetName = if (isDirectory) {
            to
        } else {
            originalDisplayName(uri = uri)?.let { to.withPreservedExtension(it) } ?: to
        }
        return@withContext when {
            uri.scheme == "file" -> renameFileMedia(
                uri = uri,
                to = targetName,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> renameMediaR(
                uri = uri,
                to = targetName,
            )

            else -> renameMediaBelowR(
                uri = uri,
                to = targetName,
            )
        }
    }

    /** 取文件当前的名字（含后缀），用于重命名时补回后缀；取不到就返回 null。 */
    private fun originalDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).name }
        } else {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }.getOrNull()

    private suspend fun renameFileMedia(uri: Uri, to: String): Boolean = runCatching {
        val oldFile = uri.path?.let(::File) ?: return@runCatching false
        val newFile = File(
            oldFile.parentFile,
            to,
        )
        if (newFile.exists() || !oldFile.renameTo(newFile)) return@runCatching false
        removeIndexedPaths(paths = listOf(oldFile.path))
        context.scanPaths(paths = listOf(newFile.path))
        true
    }.getOrDefault(defaultValue = false)

    override suspend fun shareMedia(uris: List<Uri>, mimeType: String) {
        val sharedUris = uris.map { uri ->
            if (uri.scheme == "file") {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(checkNotNull(uri.path)),
                )
            } else {
                uri
            }
        }
        val intent = Intent.createChooser(
            Intent().apply {
                type = mimeType
                action = Intent.ACTION_SEND_MULTIPLE
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(sharedUris),
                )
            },
            null,
        )
        activity.startActivity(intent)
    }

    override suspend fun moveMedia(targets: Map<Uri, File>): Map<Uri, File?> = moveMedia(
        targets = targets,
        onMoveCommitted = null,
    )

    /**
     * The internal callback is a deterministic synchronization seam for commit-boundary tests.
     * Production calls use [moveMedia] and pay no callback cost.
     */
    internal suspend fun moveMedia(
        targets: Map<Uri, File>,
        onMoveCommitted: (suspend (Uri) -> Unit)?,
    ): Map<Uri, File?> {
        val uris = targets.keys.toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mediaStoreUris = uris.filter { it.authority == MediaStore.AUTHORITY }
            if (mediaStoreUris.isNotEmpty()) {
                val granted = try {
                    requestWriteAccessR(uris = mediaStoreUris)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!granted) return targets.keys.associateWith { null }
            }
        }

        val callerJob = currentCoroutineContext()[Job]
        return withContext(context = NonCancellable) {
            withContext(Dispatchers.IO) io@{
                val movedFiles = linkedMapOf<Uri, File?>()
                val entries = targets.entries.iterator()
                var moveCommitted = false
                while (entries.hasNext()) {
                    val (uri, destination) = entries.next()
                    val movedFile = try {
                        callerJob?.ensureActive()
                        val sourceFile = context.getPath(uri = uri)?.let { File(it) }
                        if (sourceFile == null) {
                            null
                        } else {
                            destination.parentFile?.mkdirs()
                            moveFileWithoutOverwrite(
                                source = sourceFile,
                                destination = destination,
                            ) { callerJob?.ensureActive() }
                        }
                    } catch (e: CancellationException) {
                        if (!moveCommitted) throw e
                        movedFiles[uri] = null
                        while (entries.hasNext()) movedFiles[entries.next().key] = null
                        return@io movedFiles
                    } catch (e: Exception) {
                        null
                    }
                    movedFiles[uri] = movedFile
                    moveCommitted = moveCommitted || movedFile != null
                    if (movedFile != null) onMoveCommitted?.invoke(uri)
                }
                movedFiles
            }
        }
    }

    /** Copies into an exclusively-created destination before atomically committing source deletion. */
    internal fun moveFileWithoutOverwrite(
        source: File,
        destination: File,
        ensureActive: () -> Unit,
    ): File? {
        if (!source.exists() || !destination.createNewFile()) return null
        val expectedBytes = source.length()

        val copiedBytes = try {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(size = DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(
                            buffer,
                            0,
                            read,
                        )
                        copied += read
                    }
                    output.fd.sync()
                    ensureActive()
                    copied
                }
            }
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: Exception) {
            destination.delete()
            return null
        }

        // Cancellation before this point rolls back the destination. From here, the move is committed.
        if (copiedBytes != expectedBytes || !source.delete()) {
            destination.delete()
            return null
        }
        return destination
    }

    override fun transferMedia(
        uris: List<Uri>,
        folderUri: Uri,
        mode: TransferMode,
    ): Flow<TransferEvent> = flow {
        val destinationDir = folderUri.toTreeDocumentUri()

        // Moving a file into the folder it already lives in is a no-op, so keep those aside.
        val (sameFolder, sources) = if (mode == TransferMode.MOVE) {
            uris.partition {
                isInFolder(
                    uri = it,
                    folderDocUri = destinationDir,
                )
            }
        } else {
            emptyList<Uri>() to uris
        }

        if (mode == TransferMode.MOVE && sources.isNotEmpty()) {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestWriteAccessR(uris = sources.filter { it.authority == MediaStore.AUTHORITY })
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED ||
                    storagePermissionLauncher?.launch(
                        input = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == true
            }
            if (!granted) {
                emit(
                    TransferEvent.Completed(
                        result = TransferResult(
                            succeeded = 0,
                            failed = sources.size,
                            sameFolderSkipped = sameFolder.size,
                        ),
                    ),
                )
                return@flow
            }
        }

        val sizes = sources.map { sizeOf(uri = it) }
        val overallTotal = sizes.sum()

        val createdPaths = mutableListOf<String>()
        val movedFiles = mutableListOf<MovedFile>()
        var originalsNotDeleted = false
        var overallCopied = 0L
        var succeeded = 0

        sources.forEachIndexed { index, source ->
            val name = context.getFilenameFromUri(uri = source).takeIf { it.isNotBlank() }
            val currentTotal = sizes[index]

            fun progress(copied: Long) = TransferProgress(
                totalFiles = sources.size,
                currentIndex = index,
                currentName = name,
                currentBytesCopied = copied,
                currentBytesTotal = currentTotal,
                overallBytesCopied = overallCopied + copied,
                overallBytesTotal = overallTotal,
            )

            emit(TransferEvent.Progress(progress = progress(copied = 0)))
            if (name == null) return@forEachIndexed

            if (mode == TransferMode.MOVE) {
                when (
                    moveWithinVolume(
                        source = source,
                        destinationDir = destinationDir,
                    )
                ) {
                    DirectMoveResult.MOVED -> {
                        succeeded++
                        emit(TransferEvent.Progress(progress = progress(copied = currentTotal)))
                        overallCopied += currentTotal
                        return@forEachIndexed
                    }

                    DirectMoveResult.FAILED -> return@forEachIndexed

                    DirectMoveResult.FALLBACK -> Unit
                }
            }

            val copied =
                copyToDocument(
                    source = source,
                    destinationDir = destinationDir,
                    name = name,
                    expectedSize = currentTotal,
                ) { copiedSoFar ->
                    emit(TransferEvent.Progress(progress = progress(copied = copiedSoFar)))
                } ?: return@forEachIndexed

            succeeded++
            overallCopied += copied.bytes
            val destinationPath = context.getPath(copied.uri)
            if (destinationPath != null) createdPaths.add(element = destinationPath)
            if (mode == TransferMode.MOVE) {
                // 换了实际位置的文件要记下新旧对应关系，调用方据此迁移播放进度和历史
                if (destinationPath != null) {
                    movedFiles.add(
                        MovedFile(
                            sourceUri = source,
                            destinationPath = destinationPath,
                        ),
                    )
                }
                // Finish each committed move even if cancellation arrives after the copy.
                val deleted = withContext(NonCancellable) {
                    contentResolver.deleteMedia(uri = source)
                }
                originalsNotDeleted = originalsNotDeleted || !deleted
            }
        }

        if (createdPaths.isNotEmpty()) context.scanPaths(paths = createdPaths)

        emit(
            TransferEvent.Completed(
                result = TransferResult(
                    succeeded = succeeded,
                    failed = sources.size - succeeded,
                    sameFolderSkipped = sameFolder.size,
                    originalsNotDeleted = originalsNotDeleted,
                ),
                movedFiles = movedFiles,
            ),
        )
    }.flowOn(Dispatchers.IO)

    private fun isInFolder(uri: Uri, folderDocUri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = folderDocUri.mediaStoreFolder()
            val source = mediaStoreLocation(uri = uri)
            if (target != null && source != null) return target == source.folder
        }
        val folderPath =
            runCatching { context.getPath(uri = folderDocUri) }.getOrNull() ?: return false
        val filePath = runCatching { context.getPath(uri = uri) }.getOrNull() ?: return false
        return File(filePath).parent == folderPath
    }

    private enum class DirectMoveResult { MOVED, FALLBACK, FAILED }

    private data class StorageFolder(val volume: String, val relativePath: String)
    private data class MediaStoreLocation(val folder: StorageFolder, val path: String)

    private fun Uri.mediaStoreFolder(): StorageFolder? {
        if (authority != "com.android.externalstorage.documents") return null
        val parts = DocumentsContract.getDocumentId(this).split(
            ':',
            limit = 2,
        )
        if (parts.size != 2) return null
        val path = parts[1].trimEnd('/')
        if (path.isEmpty() || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return null
        }
        return StorageFolder(
            volume = if (parts[0].equals(
                    "primary",
                    ignoreCase = true,
                )
            ) {
                "external_primary"
            } else {
                parts[0].lowercase(Locale.ROOT)
            },
            relativePath = "$path/",
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreLocation(uri: Uri): MediaStoreLocation? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATA,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val volume = cursor.getString(0) ?: return@use null
                val folder = cursor.getString(1) ?: return@use null
                val path = cursor.getString(2) ?: return@use null
                MediaStoreLocation(
                    folder = StorageFolder(
                        volume = volume,
                        relativePath = folder,
                    ),
                    path = path,
                )
            }
        }.getOrNull()
    }

    private suspend fun moveWithinVolume(source: Uri, destinationDir: Uri): DirectMoveResult {
        if (source.authority != MediaStore.AUTHORITY) return DirectMoveResult.FALLBACK
        val target = destinationDir.mediaStoreFolder() ?: return DirectMoveResult.FALLBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val before = mediaStoreLocation(uri = source) ?: return DirectMoveResult.FALLBACK
            if (before.folder.volume != target.volume || !File(before.path).isFile) {
                return DirectMoveResult.FALLBACK
            }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                val updated = contentResolver.updateMedia(
                    uri = source,
                    contentValues = ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            target.relativePath,
                        )
                    },
                )
                if (updated) {
                    DirectMoveResult.MOVED
                } else {
                    // A provider failure can happen after the file has already moved.
                    val after = mediaStoreLocation(uri = source)
                    when {
                        after?.folder == target && File(after.path).isFile -> DirectMoveResult.MOVED
                        after == before && File(before.path).isFile -> DirectMoveResult.FALLBACK
                        else -> DirectMoveResult.FAILED
                    }
                }
            }
        }

        if (target.volume != "external_primary") return DirectMoveResult.FALLBACK
        val sourceFile = context.getPath(uri = source)?.let { File(it) }
            ?: return DirectMoveResult.FALLBACK
        val folder = File(
            Environment.getExternalStorageDirectory(),
            target.relativePath,
        )
        val sameFilesystem = runCatching {
            Os.stat(sourceFile.path).st_dev == Os.stat(folder.path).st_dev
        }.getOrDefault(false)
        if (!sameFilesystem) return DirectMoveResult.FALLBACK
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            moveLegacyFile(
                source,
                sourceFile,
                folder,
            )
        }
    }

    private suspend fun moveLegacyFile(uri: Uri, source: File, folder: File): DirectMoveResult {
        var destination = File(
            folder,
            source.name,
        )
        var suffix = 0
        var reserved = false
        var moved = false
        var rollbackReserved = false
        try {
            while (!destination.createNewFile()) {
                suffix++
                val extension = source.extension.takeIf {
                    it.isNotEmpty()
                }?.let { ".$it" }.orEmpty()
                destination = File(
                    folder,
                    "${source.nameWithoutExtension} ($suffix)$extension",
                )
            }
            reserved = true
            if (!source.renameTo(destination)) return DirectMoveResult.FALLBACK
            reserved = false
            moved = true
            if (contentResolver.updateMedia(
                    uri = uri,
                    contentValues = ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DATA,
                            destination.path,
                        )
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            destination.name,
                        )
                    },
                )
            ) {
                return DirectMoveResult.MOVED
            }
            val storedPath = runCatching { context.getPath(uri = uri) }.getOrNull()
            if (storedPath == destination.path) return DirectMoveResult.MOVED
            if (storedPath != source.path) return DirectMoveResult.FAILED
            // Roll back only into a path reserved by this move, never over another file.
            if (!source.createNewFile()) return DirectMoveResult.FAILED
            rollbackReserved = true
            if (!destination.renameTo(source)) return DirectMoveResult.FAILED
            rollbackReserved = false
            return DirectMoveResult.FALLBACK
        } catch (_: Exception) {
            return if (moved) DirectMoveResult.FAILED else DirectMoveResult.FALLBACK
        } finally {
            if (reserved) destination.delete()
            if (rollbackReserved) source.delete()
        }
    }

    private data class CopyResult(val uri: Uri, val bytes: Long)

    private suspend fun copyToDocument(
        source: Uri,
        destinationDir: Uri,
        name: String,
        expectedSize: Long,
        onProgress: suspend (Long) -> Unit,
    ): CopyResult? {
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(File(name).extension.lowercase()) ?: "video/*"
        val destUri = runCatching {
            DocumentsContract.createDocument(
                contentResolver,
                destinationDir,
                mimeType,
                name,
            )
        }.getOrNull() ?: return null

        return try {
            val bytes = contentResolver.openInputStream(source)?.use { input ->
                contentResolver.openOutputStream(destUri)?.use { output ->
                    copyStream(
                        input = input,
                        output = output,
                        expectedTotal = expectedSize,
                        onCopied = onProgress,
                    )
                } ?: error(message = "Unable to open output stream for $destUri")
            } ?: error(message = "Unable to open input stream for $source")
            check(value = expectedSize <= 0 || bytes == expectedSize) {
                "Incomplete copy of $source"
            }
            CopyResult(
                uri = destUri,
                bytes = bytes,
            )
        } catch (e: CancellationException) {
            deleteDocumentQuietly(uri = destUri)
            throw e
        } catch (e: Exception) {
            deleteDocumentQuietly(uri = destUri)
            null
        }
    }

    private fun deleteDocumentQuietly(uri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(
                contentResolver,
                uri,
            )
        }
    }

    private fun Uri.toTreeDocumentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(
        this,
        DocumentsContract.getTreeDocumentId(this),
    )

    private fun sizeOf(uri: Uri): Long = runCatching {
        contentResolver.openFileDescriptor(
            uri,
            "r",
        )?.use {
            it.statSize.coerceAtLeast(minimumValue = 0)
        }
    }.getOrNull() ?: 0L

    private suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        expectedTotal: Long,
        onCopied: suspend (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(size = DEFAULT_BUFFER_SIZE)
        val reportThreshold = maxOf(
            a = expectedTotal / 100,
            b = 64L * 1024,
        )
        var copied = 0L
        var lastReported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(
                buffer,
                0,
                read,
            )
            copied += read
            if (copied - lastReported >= reportThreshold) {
                onCopied(copied)
                lastReported = copied
            }
        }
        output.flush()
        onCopied(copied)
        return copied
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestWriteAccessR(uris: List<Uri>): Boolean = runMediaWriteRequests(
        uris = uris.filter {
            context.checkUriPermission(
                it,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) != PackageManager.PERMISSION_GRANTED
        },
        itemExists = ::mediaExists,
        request = ::requestWriteR,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestWriteR(uris: List<Uri>): Boolean = requestMediaAccess(
        pendingIntent = MediaStore.createWriteRequest(
            contentResolver,
            uris,
        ),
    )

    private suspend fun requestMediaAccess(pendingIntent: PendingIntent): Boolean =
        mediaRequestLauncher?.launch(
            input = IntentSenderRequest.Builder(pendingIntent = pendingIntent).build(),
        )?.resultCode == Activity.RESULT_OK

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun deleteMediaR(uris: List<Uri>): Boolean = runMediaRequests(
        items = uris,
        itemExists = ::mediaExists,
        request = ::requestDeleteR,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun trashMediaR(uris: List<Uri>, trashed: Boolean = true): Boolean =
        runMediaRequests(
            items = uris,
            itemExists = ::mediaExists,
            request = {
                requestTrashR(
                    uris = it,
                    trashed = trashed,
                )
            },
        )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestTrashR(uris: List<Uri>, trashed: Boolean): Boolean =
        requestMediaAccess(
            pendingIntent = MediaStore.createTrashRequest(
                contentResolver,
                uris,
                trashed,
            ),
        )

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestDeleteR(uris: List<Uri>): Boolean = requestMediaAccess(
        pendingIntent = MediaStore.createDeleteRequest(
            contentResolver,
            uris,
        ),
    )

    private fun mediaExists(uri: Uri): Boolean = runCatching {
        contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { cursor -> cursor.moveToFirst() } == true
    }.getOrDefault(defaultValue = false)

    private suspend fun deleteMediaBelowR(uris: List<Uri>): Boolean = uris.map { uri ->
        contentResolver.deleteMedia(uri = uri)
    }.all { it }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun renameMediaR(uri: Uri, to: String): Boolean =
        requestWriteAccessR(uris = listOf(element = uri)) && contentResolver.updateMedia(
            uri = uri,
            contentValues = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    to,
                )
            },
        )

    private suspend fun renameMediaBelowR(uri: Uri, to: String): Boolean = runCatching {
        val oldFile = context.getPath(uri = uri)?.let { File(it) } ?: throw Error()
        val newFile = File(
            oldFile.parentFile,
            to,
        )
        oldFile.renameTo(newFile).also { success ->
            if (success) {
                contentResolver.updateMedia(
                    uri = uri,
                    contentValues = ContentValues().apply {
                        put(
                            MediaStore.Files.FileColumns.DISPLAY_NAME,
                            to,
                        )
                        put(
                            MediaStore.Files.FileColumns.TITLE,
                            to,
                        )
                        put(
                            MediaStore.Files.FileColumns.DATA,
                            newFile.path,
                        )
                    },
                )
            }
        }
    }.getOrNull() ?: false
}
