package dev.anilbeesetti.nextplayer.core.media.index

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.database.dao.ImageFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.entities.ImageFileIndexEntity
import dev.anilbeesetti.nextplayer.core.media.services.FileSystemImageScanner
import dev.anilbeesetti.nextplayer.core.media.services.readImageDimensions
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 白名单图片索引：冷启动直接读 Room，后台 stat 增量扫描并分批发布；
 * 新文件先显示，分辨率再由后台队列分批 bounds 解码补齐。
 */
@Single(binds = [ImageFileIndex::class])
class RoomImageFileIndex(
    private val imageFileIndexDao: ImageFileIndexDao,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) : ImageFileIndex {

    private val scanner = FileSystemImageScanner()
    private val stateMutex = Mutex()
    private val scanMutex = Mutex()
    private val index = HashMap<String, ImageFile>()
    private var loaded = false

    private val lastRefreshAt = HashMap<String, Long>()
    private val dimensionsRequested = HashMap<String, DimensionsRequest>()
    private val dimensionsQueue = Channel<DimensionsRequest>(capacity = Channel.UNLIMITED)

    private data class DimensionsRequest(val path: String, val size: Long, val dateModified: Long)

    private val _entries = MutableStateFlow<List<ImageFile>?>(null)
    override val entries: StateFlow<List<ImageFile>?> = _entries.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) { stateMutex.withLock { loadLocked() } }
        applicationScope.launch(Dispatchers.IO) { drainDimensionsQueue() }
    }

    override fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        force: Boolean,
    ) {
        applicationScope.launch(Dispatchers.IO) {
            refresh(
                whitelistRoots = whitelistRoots,
                folderPath = folderPath,
                minIntervalMillis = when {
                    force -> 0L
                    folderPath == null -> FULL_REFRESH_INTERVAL_MS
                    else -> FOLDER_REFRESH_INTERVAL_MS
                },
            )
        }
    }

    override suspend fun refreshNow(whitelistRoots: Collection<String>, folderPath: String?) {
        withContext(Dispatchers.IO) {
            refresh(
                whitelistRoots = whitelistRoots,
                folderPath = folderPath,
                minIntervalMillis = 0L,
            )
        }
    }

    override fun removePaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val pathSet = paths.toSet()
        applicationScope.launch(Dispatchers.IO) {
            scanMutex.withLock {
                stateMutex.withLock {
                    loadLocked()
                    removeWhereLocked { it.path in pathSet }
                }
            }
        }
    }

    override fun removeRoots(roots: Collection<String>, retainedRoots: Collection<String>) {
        val normalized = roots.map { normalizeRoot(it) }.toSet()
        if (normalized.isEmpty()) return
        val retained = outermostRoots(retainedRoots)
        applicationScope.launch(Dispatchers.IO) {
            scanMutex.withLock {
                stateMutex.withLock {
                    loadLocked()
                    val removedPaths = ArrayList<String>()
                    val changed = ArrayList<ImageFileIndexEntity>()
                    for (entry in index.values.toList()) {
                        if (entry.whitelistRoot !in normalized &&
                            !entry.path.isWithinAny(normalized)
                        ) {
                            continue
                        }
                        val retainedRoot = retained.firstOrNull { entry.path.isWithin(it) }
                        if (retainedRoot == null) {
                            index.remove(entry.path)
                            dimensionsRequested.remove(entry.path)
                            removedPaths.add(entry.path)
                        } else if (entry.whitelistRoot != retainedRoot) {
                            val updated = entry.copy(whitelistRoot = retainedRoot)
                            index[entry.path] = updated
                            changed.add(updated.toEntity())
                        }
                    }
                    if (removedPaths.isEmpty() && changed.isEmpty()) return@withLock
                    deleteChunked(removedPaths)
                    upsertChunked(changed)
                    publishLocked()
                }
            }
        }
    }

    override suspend fun find(path: String): ImageFile? = stateMutex.withLock {
        loadLocked()
        index[path]
    }

    internal suspend fun refresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        minIntervalMillis: Long = 0,
    ) {
        scanMutex.withLock {
            val roots = outermostRoots(whitelistRoots)
            val key = folderPath ?: ALL_ROOTS_KEY
            val now = System.currentTimeMillis()
            stateMutex.withLock {
                loadLocked()
                val last = lastRefreshAt[key]
                if (last != null && minIntervalMillis > 0 && now - last < minIntervalMillis) return
                lastRefreshAt[key] = now
                if (folderPath == null) {
                    removeWhereLocked { it.whitelistRoot !in roots }
                } else {
                    removeWhereLocked {
                        it.whitelistRoot !in roots && it.path.isWithin(folderPath)
                    }
                }
            }

            val targets = if (folderPath == null) {
                roots.map { it to it }
            } else {
                roots.filter { folderPath.isWithin(it) }.map { it to folderPath }
            }
            for ((root, target) in targets) {
                val seen = HashSet<String>()
                val complete = scanner.scan(
                    folderPath = target,
                    whitelistRoot = root,
                    onBatch = { batch ->
                        stateMutex.withLock {
                            applyBatchLocked(
                                batch = batch,
                                seen = seen,
                            )
                        }
                    },
                )
                // 权限读取失败时保留旧索引，不能把未读到误判成已删除。
                if (!complete) continue
                stateMutex.withLock {
                    removeMissingLocked(
                        root = root,
                        target = target,
                        seen = seen,
                    )
                }
            }
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val rows = runCatching { imageFileIndexDao.getAll() }.getOrElse { emptyList() }
        rows.forEach { row ->
            val image = row.toImageFile()
            index[image.path] = image
            if (!image.dimensionsLoaded) requestDimensionsLocked(image)
        }
        loaded = true
        publishLocked()
    }

    private suspend fun applyBatchLocked(
        batch: List<FileSystemImageScanner.ScannedImage>,
        seen: MutableSet<String>,
    ) {
        val changed = ArrayList<ImageFileIndexEntity>(batch.size)
        for (file in batch) {
            seen.add(file.path)
            val current = index[file.path]
            val contentChanged = current != null &&
                (current.size != file.size || current.dateModified != file.dateModified)
            val otherChanged = current != null && (
                current.parentPath != file.parentPath ||
                    current.whitelistRoot != file.whitelistRoot ||
                    current.nameWithExtension != file.displayName ||
                    current.noMediaHidden != file.noMediaHidden
                )
            if (current != null && !contentChanged && !otherChanged) continue

            val entry = ImageFile(
                path = file.path,
                parentPath = file.parentPath,
                whitelistRoot = file.whitelistRoot,
                uriString =
                    current?.uriString ?: android.net.Uri.fromFile(File(file.path)).toString(),
                nameWithExtension = file.displayName,
                size = file.size,
                dateModified = file.dateModified,
                width = if (contentChanged) 0 else current?.width ?: 0,
                height = if (contentChanged) 0 else current?.height ?: 0,
                noMediaHidden = file.noMediaHidden,
                dimensionsLoaded = if (contentChanged) {
                    false
                } else {
                    current?.dimensionsLoaded ?: false
                },
                formattedFileSize = dev.anilbeesetti.nextplayer.core.common.Utils.formatFileSize(
                    file.size,
                ),
            )
            index[file.path] = entry
            changed.add(entry.toEntity())
            if (!entry.dimensionsLoaded) requestDimensionsLocked(entry)
        }
        if (changed.isEmpty()) return
        upsertChunked(changed)
        publishLocked()
    }

    private suspend fun removeMissingLocked(root: String, target: String, seen: Set<String>) {
        val wholeRoot = normalizeRoot(target) == root
        removeWhereLocked { entry ->
            entry.whitelistRoot == root &&
                (wholeRoot || entry.path.isWithin(target)) &&
                entry.path !in seen
        }
    }

    private suspend fun removeWhereLocked(predicate: (ImageFile) -> Boolean) {
        val removed = index.values.filter(predicate)
        if (removed.isEmpty()) return
        removed.forEach {
            index.remove(it.path)
            dimensionsRequested.remove(it.path)
        }
        deleteChunked(removed.map { it.path })
        publishLocked()
    }

    private suspend fun drainDimensionsQueue() {
        while (currentCoroutineContext().isActive) {
            val first = dimensionsQueue.receive()
            val batch = ArrayList<DimensionsRequest>(DIMENSIONS_BATCH_SIZE)
            batch.add(first)
            while (batch.size < DIMENSIONS_BATCH_SIZE) {
                batch.add(dimensionsQueue.tryReceive().getOrNull() ?: break)
            }
            val readings = batch.map { request ->
                request to readImageDimensions(request.path)
            }
            stateMutex.withLock {
                val updated = ArrayList<ImageFileIndexEntity>(readings.size)
                for ((request, dimensions) in readings) {
                    if (dimensionsRequested[request.path] == request) {
                        dimensionsRequested.remove(request.path)
                    }
                    val current = index[request.path] ?: continue
                    if (current.size != request.size ||
                        current.dateModified != request.dateModified
                    ) {
                        if (!current.dimensionsLoaded) requestDimensionsLocked(current)
                        continue
                    }
                    if (current.dimensionsLoaded) continue
                    val entry = current.copy(
                        width = dimensions.first,
                        height = dimensions.second,
                        dimensionsLoaded = true,
                    )
                    index[request.path] = entry
                    updated.add(entry.toEntity())
                }
                if (updated.isNotEmpty()) {
                    upsertChunked(updated)
                    publishLocked()
                }
            }
        }
    }

    private fun requestDimensionsLocked(image: ImageFile) {
        val request = DimensionsRequest(
            path = image.path,
            size = image.size,
            dateModified = image.dateModified,
        )
        if (dimensionsRequested[image.path] == request) return
        dimensionsRequested[image.path] = request
        dimensionsQueue.trySend(request)
    }

    private fun publishLocked() {
        _entries.value = index.values.toList()
    }

    private suspend fun upsertChunked(entries: List<ImageFileIndexEntity>) {
        entries.chunked(UPSERT_CHUNK_SIZE).forEach { imageFileIndexDao.upsertAll(it) }
    }

    private suspend fun deleteChunked(paths: List<String>) {
        paths.chunked(DELETE_CHUNK_SIZE).forEach { imageFileIndexDao.deleteByPaths(it) }
    }

    private companion object {
        const val ALL_ROOTS_KEY = "/"
        const val FOLDER_REFRESH_INTERVAL_MS = 15_000L
        const val FULL_REFRESH_INTERVAL_MS = 60_000L
        const val DIMENSIONS_BATCH_SIZE = 32
        const val UPSERT_CHUNK_SIZE = 90
        const val DELETE_CHUNK_SIZE = 900
    }
}

private fun ImageFile.toEntity(): ImageFileIndexEntity = ImageFileIndexEntity(
    path = path,
    parentPath = parentPath,
    whitelistRoot = whitelistRoot,
    displayName = nameWithExtension,
    size = size,
    dateModified = dateModified,
    noMediaHidden = noMediaHidden,
    width = width,
    height = height,
    dimensionsLoaded = dimensionsLoaded,
)

private fun normalizeRoot(path: String): String =
    File(path).absolutePath.trimEnd(File.separatorChar).ifEmpty { File.separator }

private fun outermostRoots(roots: Collection<String>): List<String> = roots
    .filter { it.isNotBlank() }
    .map(::normalizeRoot)
    .distinct()
    .filter { root ->
        roots.map(::normalizeRoot).none { other ->
            other != root &&
                root.isWithin(other)
        }
    }

private fun String.isWithin(root: String): Boolean =
    this == root || startsWith(root.trimEnd(File.separatorChar) + File.separator)

private fun String.isWithinAny(roots: Collection<String>): Boolean = roots.any { isWithin(it) }
