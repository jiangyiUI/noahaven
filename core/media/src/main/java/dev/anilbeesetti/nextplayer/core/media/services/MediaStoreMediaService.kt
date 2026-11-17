package dev.anilbeesetti.nextplayer.core.media.services

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.VIDEO_COLLECTION_URI
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.datastore.datasource.AppPreferencesDataSource
import dev.anilbeesetti.nextplayer.core.media.index.MediaFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.toMediaVideo
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * [MediaService] implementation that queries the Android MediaStore for video files.
 *
 * 性能上的两条主线：
 * 1. MediaStore 整表只查一次并缓存（用变更序号失效），所有观察者共享，不再按文件夹做
 *    `DATA LIKE` 全表扫描。
 * 2. `.nomedia` 文件夹里的视频走 [MediaFileIndex]：索引持久化在数据库里，启动先从缓存秒显，
 *    再在后台对被访问的文件夹做 stat-only 增量刷新（新增/删除/重命名都会无感更新）。
 */
@Single
class MediaStoreMediaService(
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
    private val preferencesDataSource: AppPreferencesDataSource,
    private val mediaFileIndex: MediaFileIndex,
) : MediaService {

    companion object {
        private const val OBSERVER_DEBOUNCE_MS = 250L

        private val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
        )
    }

    /** MediaStore 快照的版本号：每次收到变更通知或重新订阅时自增。 */
    private val changeToken = AtomicLong(0L)

    private val snapshotMutex = Mutex()
    private var snapshotToken = -1L
    private var snapshotVideos: List<MediaVideo> = emptyList()

    private val ignoreNoMedia = preferencesDataSource.preferences
        .map { preferences -> preferences.ignoreNoMedia }
        .distinctUntilChanged()

    /**
     * 所有观察者共享的唯一一个 [ContentObserver]。变更通知（媒体扫描时常常是一大批）用
     * [debounce] 合并，避免下游反复全量查询。
     */
    @OptIn(FlowPreview::class)
    private val mediaChanges: Flow<Long> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(element = changeToken.incrementAndGet())
            }
        }
        context.contentResolver.registerContentObserver(
            VIDEO_COLLECTION_URI,
            true,
            observer,
        )
        // 重新订阅时也发一次：应用从后台回来时能重新确认外部变化
        trySend(element = changeToken.incrementAndGet())
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }
        .debounce(OBSERVER_DEBOUNCE_MS.milliseconds)
        .shareIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1,
        )

    override fun observeFolders(folderPath: String?): Flow<List<MediaFolder>> =
        observeMedia(folderPath = folderPath) { ignoreNoMedia, token ->
            toFolders(videos = allVideos(ignoreNoMedia = ignoreNoMedia, token = token).under(folderPath))
        }

    override fun observeVideos(folderPath: String?): Flow<List<MediaVideo>> =
        observeMedia(folderPath = folderPath) { ignoreNoMedia, token ->
            allVideos(ignoreNoMedia = ignoreNoMedia, token = token).under(folderPath)
        }

    override fun observeTrashVideos(): Flow<List<MediaVideo>> = mediaChanges
        .map {
            safeQuery {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) queryTrashVideos() else emptyList()
            }
        }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    /**
     * 订阅某个文件夹时，先立刻用索引缓存出结果，同时在后台只重扫这棵子树：
     * 新增的补上、删掉的移除、改名/移动的按路径差异无感更新。
     */
    private fun <T> observeMedia(
        folderPath: String?,
        transform: suspend (ignoreNoMedia: Boolean, token: Long) -> List<T>,
    ): Flow<List<T>> = combine(
        mediaChanges,
        ignoreNoMedia,
        mediaFileIndex.entries,
    ) { token, ignore, _ -> token to ignore }
        .onStart {
            mediaFileIndex.requestRefresh(folderPath = folderPath)
            // 每次都拿一个全新的快照序号：从后台回来/换文件夹时能重新确认 MediaStore 的真实状态
            emit(changeToken.incrementAndGet() to ignoreNoMedia.first())
        }
        .map { (token, ignore) -> safeQuery { transform(ignore, token) } }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    override suspend fun fetchFolders(folderPath: String?): List<MediaFolder> =
        withContext(Dispatchers.IO) {
            toFolders(videos = fetchVideos(folderPath = folderPath))
        }

    override suspend fun fetchVideos(folderPath: String?): List<MediaVideo> =
        withContext(Dispatchers.IO) {
            val ignoreNoMedia = preferencesDataSource.preferences.first().ignoreNoMedia
            allVideos(ignoreNoMedia = ignoreNoMedia, token = changeToken.get()).under(folderPath)
        }

    override suspend fun findVideo(uri: Uri): MediaVideo? = withContext(Dispatchers.IO) {
        // 文件系统扫描出来的视频（.nomedia 文件夹里的）用的是 file:// uri
        if (uri.scheme == "file") {
            val path = uri.path ?: return@withContext null
            mediaFileIndex.find(path = path)?.let { entry ->
                return@withContext entry.toMediaVideo()
            }
            val file = File(path)
            return@withContext if (file.exists() && file.isFile) {
                file.toFileSystemMediaVideo()
            } else {
                null
            }
        }
        return@withContext try {
            context.contentResolver.query(
                uri,
                VIDEO_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                cursor.toMediaVideo()
            }
        } catch (e: Exception) {
            // uri isn't a MediaStore video uri (e.g. a vault FileProvider uri) and the
            // provider rejected the MediaStore-specific projection outright. Not finding a
            // MediaStore row for it is the correct outcome, not a crash.
            null
        }
    }

    override suspend fun findFolder(path: String): MediaFolder? = withContext(Dispatchers.IO) {
        val folderFile = File(path)
        if (!folderFile.exists()) return@withContext null

        val allVideos = fetchVideos(folderPath = path)
        if (allVideos.isEmpty()) return@withContext null

        val directVideos = allVideos.filter { File(it.path).parent == path }
        val directSubfolders = allVideos
            .mapNotNull { video ->
                File(video.path).parentFile?.walkUp()?.firstOrNull { it.parent == path }
            }
            .distinctBy { it.path }

        return@withContext MediaFolder(
            path = folderFile.path,
            name = folderFile.prettyName,
            dateModified = allVideos.maxOfOrNull { it.dateModified } ?: folderFile.lastModified(),
            totalSize = allVideos.sumOf { it.size },
            totalDuration = allVideos.sumOf { it.duration },
            videosCount = directVideos.size,
            foldersCount = directSubfolders.size,
        )
    }

    /**
     * Walks up the directory tree from this file's parent to the root.
     */
    private fun File.walkUp() = generateSequence(seed = parentFile) { it.parentFile }

    /**
     * MediaStore 快照 + 文件索引的合并结果。同一个文件两边都有时以 MediaStore 为准
     * （content:// uri 播放更稳）。
     */
    private suspend fun allVideos(ignoreNoMedia: Boolean, token: Long): List<MediaVideo> {
        val storeVideos = mediaStoreSnapshot(token = token)
        val entries = mediaFileIndex.entries.value ?: return storeVideos
        if (entries.isEmpty()) return storeVideos

        val videos = ArrayList<MediaVideo>(storeVideos.size + entries.size)
        videos.addAll(storeVideos)
        val knownPaths = HashSet<String>(storeVideos.size * 2)
        storeVideos.forEach { video -> knownPaths.add(video.path) }
        for (entry in entries) {
            if (entry.noMediaHidden && !ignoreNoMedia) continue
            if (!knownPaths.add(entry.path)) continue
            videos.add(entry.toMediaVideo())
        }
        videos.sortBy { video -> video.displayName }
        return videos
    }

    /**
     * 整表查询 MediaStore，并按 [changeToken] 做缓存：一次变更只查一遍，所有观察者共享。
     */
    private suspend fun mediaStoreSnapshot(token: Long): List<MediaVideo> = snapshotMutex.withLock {
        if (token <= snapshotToken) return@withLock snapshotVideos
        val videos = queryAllMediaStoreVideos()
        snapshotVideos = videos
        snapshotToken = token
        videos
    }

    private fun toFolders(videos: List<MediaVideo>): List<MediaFolder> =
        videos.groupBy { video -> File(video.path).parentFile }
            .mapNotNull { (folder, folderVideos) ->
                folder?.let {
                    MediaFolder(
                        path = it.path,
                        name = it.prettyName,
                        dateModified =
                            folderVideos.maxOfOrNull { video -> video.dateModified } ?: 0L,
                        totalSize = folderVideos.sumOf { video -> video.size },
                        totalDuration = folderVideos.sumOf { video -> video.duration },
                        videosCount = folderVideos.size,
                        foldersCount = 0,
                    )
                }
            }

    private fun List<MediaVideo>.under(folderPath: String?): List<MediaVideo> {
        if (folderPath == null) return this
        val prefix = if (folderPath.endsWith('/')) folderPath else "$folderPath/"
        return filter { video -> video.path.startsWith(prefix) }
    }

    private suspend fun <T> safeQuery(query: suspend () -> List<T>): List<T> = try {
        query()
    } catch (e: IllegalArgumentException) {
        // A volume can disappear before MediaProvider opens it. Handle each
        // notification separately so observers can recover when storage returns.
        if (e.message?.startsWith(prefix = "Volume ") == true &&
            e.message?.endsWith(suffix = " not found") == true
        ) {
            emptyList()
        } else {
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryTrashVideos(): List<MediaVideo> {
        val queryArgs = Bundle().apply {
            putInt(
                MediaStore.QUERY_ARG_MATCH_TRASHED,
                MediaStore.MATCH_ONLY,
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Video.Media.DATE_MODIFIED),
            )
            putInt(
                android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
        }
        return queryVideoCursor {
            context.contentResolver.query(
                VIDEO_COLLECTION_URI,
                VIDEO_PROJECTION,
                queryArgs,
                null,
            )
        }
    }

    private fun queryAllMediaStoreVideos(): List<MediaVideo> {
        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
        return queryVideoCursor {
            context.contentResolver.query(
                VIDEO_COLLECTION_URI,
                VIDEO_PROJECTION,
                null,
                null,
                sortOrder,
            )
        }
    }

    private fun queryVideoCursor(query: () -> Cursor?): List<MediaVideo> {
        val mediaVideos = mutableListOf<MediaVideo>()
        val cursor = checkNotNull(value = query()) {
            "MediaStore returned no cursor for the video query"
        }
        cursor.use {
            while (cursor.moveToNext()) {
                val video = cursor.toMediaVideo() ?: continue
                mediaVideos.add(element = video)
            }
        }
        return mediaVideos
    }

    /**
     * Converts the current row to a [MediaVideo], or null if this cursor doesn't actually
     * expose MediaStore's video columns.
     *
     * [findVideo] runs this against whatever content uri it's given, which isn't always a
     * MediaStore uri - vault playback, for example, passes a [androidx.core.content.FileProvider]
     * uri whose cursor only ever has DISPLAY_NAME/SIZE columns. Querying MediaStore-specific
     * columns (DATA, DURATION, etc) against that cursor has no matching column, and
     * [Cursor.getColumnIndexOrThrow] throws IllegalArgumentException in that case. That
     * exception was previously unhandled here, which crashed the coroutine awaiting this result
     * and made vault playback close immediately. Resolving column indices safely up front avoids
     * that crash and simply reports "not found" for any uri that isn't a MediaStore video uri.
     */
    private fun Cursor.toMediaVideo(): MediaVideo? {
        val dataIndex =
            getColumnIndex(MediaStore.Video.Media.DATA).takeIf { it >= 0 } ?: return null
        val idIndex = getColumnIndex(MediaStore.Video.Media._ID).takeIf { it >= 0 } ?: return null
        val displayNameIndex =
            getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME).takeIf { it >= 0 } ?: return null
        val durationIndex =
            getColumnIndex(MediaStore.Video.Media.DURATION).takeIf { it >= 0 } ?: return null
        val widthIndex =
            getColumnIndex(MediaStore.Video.Media.WIDTH).takeIf { it >= 0 } ?: return null
        val heightIndex =
            getColumnIndex(MediaStore.Video.Media.HEIGHT).takeIf { it >= 0 } ?: return null
        val sizeIndex =
            getColumnIndex(MediaStore.Video.Media.SIZE).takeIf { it >= 0 } ?: return null
        val dateModifiedIndex =
            getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED).takeIf { it >= 0 } ?: return null
        val dateAddedIndex =
            getColumnIndex(MediaStore.Video.Media.DATE_ADDED).takeIf { it >= 0 } ?: return null

        val path = getString(dataIndex) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val id = getLong(idIndex)
        return MediaVideo(
            id = id,
            path = path,
            title = file.name,
            parentPath = file.parent ?: "/",
            uri = ContentUris.withAppendedId(
                VIDEO_COLLECTION_URI,
                id,
            ),
            displayName = getString(displayNameIndex) ?: file.name,
            duration = getLong(durationIndex),
            width = getInt(widthIndex),
            height = getInt(heightIndex),
            size = getLong(sizeIndex),
            dateModified = getLong(dateModifiedIndex),
            dateAdded = getLong(dateAddedIndex),
        )
    }
}
