package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import androidx.core.net.toUri
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.data.mappers.toAudioStreamInfo
import dev.anilbeesetti.nextplayer.core.data.mappers.toFolder
import dev.anilbeesetti.nextplayer.core.data.mappers.toSubtitleStreamInfo
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideo
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideoState
import dev.anilbeesetti.nextplayer.core.data.mappers.toVideoStreamInfo
import dev.anilbeesetti.nextplayer.core.data.models.VideoState
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.entities.MediumStateEntity
import dev.anilbeesetti.nextplayer.core.datastore.datasource.AppPreferencesDataSource
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.services.AudioMediaService
import dev.anilbeesetti.nextplayer.core.media.services.MediaService
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaInfo
import dev.anilbeesetti.nextplayer.core.model.Video
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.io.File
import java.util.Date
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class LocalMediaRepository(
    private val mediumStateDao: MediumStateDao,
    private val mediaService: MediaService,
    private val audioMediaService: AudioMediaService,
    private val appPreferencesDataSource: AppPreferencesDataSource,
    private val context: Context,
) : MediaRepository {

    override fun observeFolders(folderPath: String?): Flow<List<Folder>> =
        mediaService.observeFolders(folderPath = folderPath).map { mediaFolders ->
            mediaFolders.map { it.toFolder() }
        }

    override fun observeAudioFolders(folderPath: String?): Flow<List<Folder>> =
        audioMediaService.observeAudios().map { audios ->
            audios
                .filter { folderPath == null || it.parentPath.startsWith(prefix = folderPath) }
                .groupBy { it.parentPath }
                .map { (path, folderAudios) ->
                    Folder(
                        name = File(path).name,
                        path = path,
                        dateModified = folderAudios.maxOf { it.dateModified },
                        totalSize = folderAudios.sumOf { it.size },
                        totalDuration = folderAudios.sumOf { it.duration },
                        videosCount = folderAudios.size,
                    )
                }
                .sortedBy { it.path.lowercase() }
        }

    override fun observeVideos(folderPath: String?): Flow<List<Video>> = combine(
        mediaService.observeVideos(folderPath = folderPath),
        mediumStateDao.getAll(),
    ) {
            mediaVideos,
            mediumStates,
        ->
        val statesMap = mediumStates.associateBy { it.uriString }
        mediaVideos.map { mediaVideo ->
            val mediaState = statesMap[mediaVideo.uri.toString()]
            mediaVideo.toVideo(mediaState = mediaState)
        }
    }

    override suspend fun fetchFolders(folderPath: String?): List<Folder> =
        mediaService.fetchFolders(folderPath = folderPath).map { it.toFolder() }

    override suspend fun fetchVideos(folderPath: String?): List<Video> {
        // 一次性把播放状态读进来做内存映射，避免每个视频一条 SQL（N+1）
        val states = mediumStateDao.getAll().first().associateBy { it.uriString }
        return mediaService.fetchVideos(folderPath = folderPath).map { mediaVideo ->
            mediaVideo.toVideo(mediaState = states[mediaVideo.uri.toString()])
        }
    }

    override fun observePlaybackHistory(): Flow<List<Video>> = combine(
        mediaService.observeVideos(),
        mediumStateDao.getAll(),
        // 音频元数据先给一份空快照，避免历史列表等待 MediaStore 查询而延迟展示
        audioMediaService.observeAudios().onStart { emit(value = emptyList()) },
    ) {
            mediaVideos,
            mediumStates,
            audios,
        ->
        val videosByUri = mediaVideos.associateBy { it.uri.toString() }
        val audiosByUri = audios.associateBy { it.uriString }
        mediumStates
            .filter { it.lastPlayedTime != null && !it.uriString.toUri().isVaultUri() }
            .sortedByDescending { it.lastPlayedTime }
            .map { state ->
                videosByUri[state.uriString]?.toVideo(mediaState = state)
                    ?: audiosByUri[state.uriString]?.toHistoryVideo(mediaState = state)
                    ?: state.toHistoryVideo()
            }
    }
        // 应用外部删除文件后，历史记录里会留下失效条目，开始收集时清理一次
        .onStart { removeMissingPlaybackHistory() }

    /**
     * 删除已经失效的播放历史：
     * - `file://` 直接检查文件是否还存在；
     * - `content://` 查一次 MediaStore，被外部删除后会查不到对应行；
     * - 网络流、vault 等无法用以上方式判断的记录保留不动。
     */
    private suspend fun removeMissingPlaybackHistory() = withContext(Dispatchers.IO) {
        val states = runCatching { mediumStateDao.getAll().first() }.getOrNull().orEmpty()
        val missing = states
            .filter { state -> state.lastPlayedTime != null }
            .filterNot { state -> state.uriString.toUri().isVaultUri() }
            .filterNot { state -> mediaUriExists(uri = state.uriString.toUri()) }
            .map { state -> state.uriString }
        if (missing.isEmpty()) return@withContext
        runCatching { mediumStateDao.clearLastPlayedTimes(uris = missing) }
    }

    private fun mediaUriExists(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { path -> File(path).exists() } == true
        "content" -> runCatching {
            // 查询失败（例如非 MediaStore 的 provider）时保守保留，避免误删
            context.contentResolver
                .query(uri, arrayOf(BaseColumns._ID), null, null, null)
                ?.use { cursor -> cursor.moveToFirst() }
                ?: true
        }.getOrDefault(true)
        else -> true
    }

    override fun observeTrashVideos(): Flow<List<Video>> =
        mediaService.observeTrashVideos().map { videos -> videos.map { it.toVideo() } }

    override suspend fun getVideoByUri(uri: String): Video? = coroutineScope {
        val mediaVideoDeferred = async { mediaService.findVideo(uri.toUri()) }
        val mediaStateDeferred = async { mediumStateDao.get(uri = uri) }

        val mediaVideo = mediaVideoDeferred.await() ?: return@coroutineScope null
        val mediaState = mediaStateDeferred.await()

        return@coroutineScope mediaVideo.toVideo(mediaState = mediaState)
    }

    override suspend fun getVideoState(uri: String): VideoState? =
        mediumStateDao.get(uri = uri)?.toVideoState()

    override suspend fun getMediaInfo(uri: String): MediaInfo? = withContext(Dispatchers.IO) {
        val video = getVideoByUri(uri = uri) ?: return@withContext null
        val mediaInfo = runCatching {
            MediaInfoBuilder().from(
                context = context,
                uri = uri.toUri(),
            ).build()
        }.getOrNull()
        val result = MediaInfo(
            video = video.copy(format = mediaInfo?.format),
            videoStream = mediaInfo?.videoStream?.toVideoStreamInfo(),
            audioStreams = mediaInfo?.audioStreams?.map { it.toAudioStreamInfo() } ?: emptyList(),
            subtitleStreams =
                mediaInfo?.subtitleStreams?.map { it.toSubtitleStreamInfo() } ?: emptyList(),
        )
        mediaInfo?.release()
        return@withContext result
    }

    override suspend fun clearPlaybackHistory() {
        mediumStateDao.clearPlaybackHistory()
    }

    override suspend fun removePlaybackHistory(uris: List<String>) {
        if (uris.isEmpty()) return
        mediumStateDao.clearLastPlayedTimes(uris = uris)
    }

    override suspend fun updateMediumLastPlayedTime(
        uri: String,
        lastPlayedTime: Long,
        duration: Long?,
    ) {
        val preferences = appPreferencesDataSource.preferences.first()
        val mediaUri = uri.toUri()
        val recordHistory = !preferences.isHistoryPaused &&
            !mediaUri.isVaultUri() &&
            (
                preferences.includeNetworkWatchHistory ||
                    !NetworkUri.isNetworkUri(uri = mediaUri) ||
                    NetworkUri.connectionIdOf(uri = mediaUri) == null
                )
        mediumStateDao.update(uri = uri) { state ->
            state.copy(
                lastPlayedTime = if (recordHistory) lastPlayedTime else state.lastPlayedTime,
                duration = duration ?: state.duration,
            )
        }
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
        mediumStateDao.update(uri = uri) { it.copy(playbackPosition = position) }
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        mediumStateDao.update(uri = uri) { it.copy(audioTrackIndex = audioTrackIndex) }
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        mediumStateDao.update(uri = uri) { it.copy(subtitleTrackIndex = subtitleTrackIndex) }
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
        mediumStateDao.update(uri = uri) { it.copy(videoScale = zoom) }
    }

    override suspend fun addExternalAudioToMedium(uri: String, audioUri: Uri) {
        val stateEntity = mediumStateDao.get(uri = uri) ?: MediumStateEntity(uriString = uri)
        val audio = UriListConverter.fromStringToList(stateEntity.externalAudio)
        if (audioUri in audio) return
        mediumStateDao.upsert(
            stateEntity.copy(
                externalAudio = UriListConverter.fromListToString(
                    urlList =
                        audio + audioUri,
                ),
            ),
        )
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        mediumStateDao.update(uri = uri) { state ->
            val subtitles = UriListConverter.fromStringToList(state.externalSubs)
            state.copy(
                externalSubs = UriListConverter.fromListToString(
                    (subtitles + subtitleUri).distinct(),
                ),
            )
        }
    }

    override suspend fun updateSubtitleDelay(uri: String, delay: Long) {
        mediumStateDao.update(uri = uri) { it.copy(subtitleDelayMilliseconds = delay) }
    }

    override suspend fun updateSubtitleSpeed(uri: String, speed: Float) {
        mediumStateDao.update(uri = uri) { it.copy(subtitleSpeed = speed) }
    }

    private fun Uri.isVaultUri(): Boolean {
        if (scheme != "content" || authority != "${context.packageName}.fileprovider") return false
        val segments = pathSegments
        val root = segments.firstOrNull()
        return root == "vault" ||
            (
                segments.getOrNull(index = 1) == "vault" &&
                    (root == "external_files_path" || root == "files_path")
                )
    }
}

private fun AudioFile.toHistoryVideo(mediaState: MediumStateEntity): Video = Video(
    id = id,
    path = path,
    parentPath = parentPath,
    duration = duration,
    uriString = uriString,
    nameWithExtension = nameWithExtension,
    width = 0,
    height = 0,
    size = size,
    playbackPosition = mediaState.playbackPosition,
    dateModified = dateModified,
    formattedDuration = formattedDuration,
    formattedFileSize = formattedFileSize,
    lastPlayedAt = mediaState.lastPlayedTime?.let(block = ::Date),
    isAudio = true,
)

private fun MediumStateEntity.toHistoryVideo(): Video {
    val uri = uriString.toUri()
    val name = uri.lastPathSegment?.substringAfterLast(delimiter = '/')
        ?: uri.host
        ?: uriString
    return Video(
        id = uriString.hashCode().toLong().absoluteValue,
        path = uriString,
        uriString = uriString,
        nameWithExtension = name,
        duration = duration ?: 0,
        width = 0,
        height = 0,
        size = 0,
        formattedDuration = duration?.let(block = Utils::formatDurationMillis).orEmpty(),
        playbackPosition = playbackPosition,
        lastPlayedAt = lastPlayedTime?.let(block = ::Date),
        isAudio = uri.pathSegments.contains(element = "audio"),
    )
}
