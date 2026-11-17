package dev.anilbeesetti.nextplayer.core.domain

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.model.isNew
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

/**
 * Produces the hierarchical (tree) view of media for a folder.
 *
 * Each level shows the videos directly inside the current folder plus a [Folder] for every
 * immediate subfolder that contains videos. The top level (folderPath == null) spans all storage
 * volumes: when more than one volume holds videos each volume is shown as a folder ("Internal
 * Storage", a USB drive, …); with a single volume its contents are shown directly.
 */
@Factory
class GetFolderTreeMediaUseCase(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
) {

    operator fun invoke(folderPath: String? = null): Flow<MediaHolder> = combine(
        mediaRepository.observeVideos(folderPath = folderPath),
        preferencesRepository.applicationPreferences,
        flow3 = newVideoClock(),
    ) { videos, preferences, nowMillis ->
        val included = videos.filterNot { it.parentPath in preferences.excludeFolders }
        val sort = Sort(
            by = preferences.sortBy,
            order = preferences.sortOrder,
        )
        val thresholdDays = preferences.newVideoThresholdDays

        if (folderPath != null) {
            mediaUnder(
                root = folderPath,
                videos = included,
                excludeFolders = preferences.excludeFolders,
                sort = sort,
                thresholdDays = thresholdDays,
                nowMillis = nowMillis,
            )
        } else {
            topLevelMedia(
                videos = included,
                excludeFolders = preferences.excludeFolders,
                sort = sort,
                thresholdDays = thresholdDays,
                nowMillis = nowMillis,
            )
        }
    }.flowOn(context = defaultDispatcher)

    private fun topLevelMedia(
        videos: List<Video>,
        excludeFolders: Collection<String>,
        sort: Sort,
        thresholdDays: Int,
        nowMillis: Long,
    ): MediaHolder {
        val volumeRoots = videos.mapNotNull { volumeRootOf(it.path) }.distinct()
        if (volumeRoots.size <= 1) {
            val root = volumeRoots.firstOrNull()
                ?: return MediaHolder(
                    videos = emptyList(),
                    folders = emptyList(),
                )
            return mediaUnder(
                root = root,
                videos = videos,
                excludeFolders = excludeFolders,
                sort = sort,
                thresholdDays = thresholdDays,
                nowMillis = nowMillis,
            )
        }
        val folders = volumeRoots
            .filterNot { it in excludeFolders }
            .map { volumeRoot ->
                summarize(
                    path = volumeRoot,
                    descendantVideos = videosUnder(
                        path = volumeRoot,
                        videos = videos,
                    ),
                    thresholdDays = thresholdDays,
                    nowMillis = nowMillis,
                )
            }
        return MediaHolder(
            videos = emptyList(),
            folders = folders.sortedWith(sort.folderComparator()),
        )
    }

    /**
     * The videos directly inside [root] plus a [Folder] for each immediate subfolder with videos.
     *
     * 只遍历一遍 [videos]：按视频路径算出它所属的一级子目录并同时累加统计信息，
     * 避免「每个子目录都重新过滤整个列表」的 O(n²) 行为。
     */
    private fun mediaUnder(
        root: String,
        videos: List<Video>,
        excludeFolders: Collection<String>,
        sort: Sort,
        thresholdDays: Int,
        nowMillis: Long,
    ): MediaHolder {
        val prefix = root + PATH_SEPARATOR
        val directVideos = ArrayList<Video>()
        val videosByFolder = LinkedHashMap<String, MutableList<Video>>()

        for (video in videos) {
            if (!video.path.startsWith(prefix = prefix)) continue
            if (video.parentPath == root) {
                directVideos.add(element = video)
                continue
            }
            val childPath = prefix +
                video.path.removePrefix(prefix = prefix).substringBefore(PATH_SEPARATOR)
            videosByFolder.getOrPut(key = childPath) { ArrayList() }.add(element = video)
        }

        val folders = videosByFolder.asSequence()
            .filterNot { (path, _) -> path in excludeFolders }
            .map { (path, folderVideos) ->
                summarize(
                    path = path,
                    descendantVideos = folderVideos,
                    thresholdDays = thresholdDays,
                    nowMillis = nowMillis,
                )
            }
            .toList()

        return MediaHolder(
            videos = directVideos.sortedWith(sort.videoComparator()),
            folders = folders.sortedWith(sort.folderComparator()),
        )
    }

    /** Builds a [Folder] aggregating the stats of every video beneath [path] in a single pass. */
    private fun summarize(
        path: String,
        descendantVideos: List<Video>,
        thresholdDays: Int,
        nowMillis: Long,
    ): Folder {
        val prefix = path + PATH_SEPARATOR
        var dateModified = 0L
        var totalSize = 0L
        var totalDuration = 0L
        var videosCount = 0
        var newVideosCount = 0
        val childFolders = HashSet<String>()

        for (video in descendantVideos) {
            if (video.dateModified > dateModified) dateModified = video.dateModified
            totalSize += video.size
            totalDuration += video.duration
            if (video.parentPath == path) {
                videosCount++
            } else {
                childFolders.add(
                    video.path.removePrefix(prefix = prefix).substringBefore(PATH_SEPARATOR),
                )
            }
            if (video.isNew(nowMillis = nowMillis, thresholdDays = thresholdDays)) {
                newVideosCount++
            }
        }

        val file = File(path)
        return Folder(
            name = file.prettyName,
            path = path,
            parentPath = file.parent,
            dateModified = dateModified,
            totalSize = totalSize,
            totalDuration = totalDuration,
            videosCount = videosCount,
            foldersCount = childFolders.size,
            newVideosCount = newVideosCount,
        )
    }

    /** All videos located somewhere beneath [path]. */
    private fun videosUnder(path: String, videos: List<Video>): List<Video> {
        val prefix = path + PATH_SEPARATOR
        return videos.filter { it.path.startsWith(prefix = prefix) }
    }

    /**
     * Infers the storage-volume root that contains [path] from its canonical MediaStore path:
     * "/storage/emulated/<user>" for emulated (internal) storage, or "/storage/<volume>" for
     * removable volumes (SD card / USB OTG). Returns null for unrecognised layouts.
     */
    private fun volumeRootOf(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" ->
                "/storage/emulated/${parts[2]}"

            parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"

            else -> null
        }
    }

    private companion object {
        /** Android 上的路径分隔符固定是 "/"（JVM 单测跑在 Windows 上时 File.separator 不是）。 */
        const val PATH_SEPARATOR = "/"
    }
}
