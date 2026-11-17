package dev.anilbeesetti.nextplayer.core.media.sync

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import coil3.ImageLoader
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.getStorageVolumes
import dev.anilbeesetti.nextplayer.core.common.extensions.hasAllFilesAccess
import dev.anilbeesetti.nextplayer.core.common.extensions.scanPaths
import dev.anilbeesetti.nextplayer.core.common.extensions.scanStorage
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.dao.PlaylistDao
import dev.anilbeesetti.nextplayer.core.media.services.AudioMediaService
import dev.anilbeesetti.nextplayer.core.media.services.MediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class LocalMediaSynchronizer(
    private val mediumStateDao: MediumStateDao,
    private val playlistDao: PlaylistDao,
    private val imageLoader: ImageLoader,
    private val mediaService: MediaService,
    private val audioMediaService: AudioMediaService,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
    private val context: Context,
) : MediaSynchronizer {

    private var mediaSyncingJob: Job? = null

    override suspend fun refresh(path: String?): Boolean = path?.let {
        context.scanPaths(paths = listOf(element = path))
    }
        ?: context.getStorageVolumes().all { context.scanStorage(it.path) }

    @OptIn(FlowPreview::class)
    override fun startSync() {
        if (mediaSyncingJob?.isActive == true) return
        mediaSyncingJob = combine(
            mediaService.observeVideos().map { media -> media.map { it.uri.toString() } },
            audioMediaService.observeAudios().map { audios -> audios.map { it.uriString } },
        ) { videoUris, audioUris ->
            // 音频历史的 uri 也要算作有效媒体，否则同步时会被当成失效条目删掉
            HashSet<String>(videoUris.size + audioUris.size).apply {
                addAll(videoUris)
                addAll(audioUris)
            }
        }
            .distinctUntilChanged()
            .debounce(SYNC_DEBOUNCE_MILLIS)
            .onEach { uris -> updateMedia(uris = uris) }
            .catch {
                // A failed MediaStore query is not a complete snapshot. Leave stored state alone;
                // startSync can retry after storage permission or the provider becomes available.
            }
            .launchIn(scope = applicationScope)
    }

    override fun stopSync() {
        mediaSyncingJob?.cancel()
    }

    private fun Context.hasStoragePermission(): Boolean = hasAllFilesAccess()
    private suspend fun updateMedia(uris: Set<String>) = withContext(Dispatchers.Default) {
        if (!context.hasStoragePermission()) return@withContext

        val currentMediaUris = uris

        playlistDao.removeMissingLocalItems(existingUris = currentMediaUris)

        val (wantedMediaStates, unwantedMediaStates) = mediumStateDao.getAll().first().partition {
            it.uriString in currentMediaUris ||
                !ContentResolver.SCHEME_CONTENT.equals(
                    it.uriString.toUri().scheme,
                    ignoreCase = true,
                )
        }

        mediumStateDao.delete(unwantedMediaStates.map { it.uriString })

        // Delete unwanted thumbnails
        unwantedMediaStates.forEach { mediaState ->
            try {
                imageLoader.diskCache?.remove(mediaState.uriString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Release external subtitle uri permission if not used by any other media
        launch {
            val currentMediaExternalSubs = wantedMediaStates.flatMap {
                UriListConverter.fromStringToList(it.externalSubs)
            }.toSet()

            unwantedMediaStates.onEach { mediaState ->
                for (sub in UriListConverter.fromStringToList(mediaState.externalSubs)) {
                    if (sub !in currentMediaExternalSubs) {
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                sub,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** 合并短时间内多次媒体库变化（例如后台补元数据时连续 emit），避免反复做全量对账。 */
        const val SYNC_DEBOUNCE_MILLIS = 2_000L
    }
}
