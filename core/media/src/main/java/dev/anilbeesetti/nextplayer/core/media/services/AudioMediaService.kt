package dev.anilbeesetti.nextplayer.core.media.services

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.media.index.AudioFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.AudioIndexEntry
import dev.anilbeesetti.nextplayer.core.media.index.toAudioFile
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

private val AUDIO_COLLECTION_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

/**
 * 读取音频文件，供「音乐」页使用。数据来源与视频完全对齐：
 *
 * 1. MediaStore：系统收录的音频，用 content:// uri 播放。
 * 2. [AudioFileIndex]：`.nomedia` 目录里的音频（MediaStore 不收）走磁盘索引，
 *    启动读库秒显、按文件夹增量刷新、首次建库时边扫边出现。
 *
 * 封面不在这里读，由界面按需从音频文件内嵌封面里取。
 */
@Single
class AudioMediaService(private val context: Context, private val audioFileIndex: AudioFileIndex) {

    private companion object {
        private val AUDIO_PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE,
        )
    }

    suspend fun fetchAudios(folderPath: String? = null): List<AudioFile> =
        withContext(context = Dispatchers.IO) {
            mergedAudios(
                mediaStoreAudios = queryAudios(),
                indexEntries = audioFileIndex.entries.value.orEmpty(),
                folderPath = folderPath,
            )
        }

    /**
     * 观察音频集合：MediaStore 变化或磁盘索引（分块发布）变化都会触发重新合并，
     * 所以首次建索引时列表是边扫边变长的。
     */
    fun observeAudios(): Flow<List<AudioFile>> = combine(
        mediaStoreAudios(),
        audioFileIndex.entries,
    ) { mediaStoreAudios, indexEntries ->
        mergedAudios(
            mediaStoreAudios = mediaStoreAudios,
            indexEntries = indexEntries.orEmpty(),
            folderPath = null,
        )
    }
        .flowOn(context = Dispatchers.IO)

    /** 请求在后台刷新某个文件夹的磁盘索引（null 表示所有存储卷）。 */
    fun requestRefresh(folderPath: String?, force: Boolean = false) {
        audioFileIndex.requestRefresh(
            folderPath = folderPath,
            force = force,
        )
    }

    private fun mediaStoreAudios(): Flow<List<AudioFile>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            AUDIO_COLLECTION_URI,
            true,
            observer,
        )
        // 重新订阅时也查一次：应用从后台回来时能重新确认外部变化
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        .map { queryAudios() }
        .distinctUntilChanged()

    /**
     * 合并结果：同一个路径两边都有时以 MediaStore 为准（content:// 播放更稳），
     * 磁盘索引只补 MediaStore 里没有的（主要是 `.nomedia` 目录）。
     */
    private fun mergedAudios(
        mediaStoreAudios: List<AudioFile>,
        indexEntries: List<AudioIndexEntry>,
        folderPath: String?,
    ): List<AudioFile> {
        val prefix = folderPath?.trimEnd('/')?.plus("/")
        val result = ArrayList<AudioFile>(mediaStoreAudios.size + indexEntries.size)
        val knownPaths = HashSet<String>(mediaStoreAudios.size * 2)
        for (audio in mediaStoreAudios) {
            knownPaths.add(audio.path)
            if (prefix == null || audio.path.startsWith(prefix = prefix)) result.add(audio)
        }
        for (entry in indexEntries) {
            if (prefix != null && !entry.path.startsWith(prefix = prefix)) continue
            if (!knownPaths.add(entry.path)) continue
            result.add(entry.toAudioFile())
        }
        result.sortBy { audio -> audio.nameWithExtension.lowercase(locale = Locale.ROOT) }
        return result
    }

    private fun queryAudios(): List<AudioFile> = try {
        context.contentResolver.query(
            AUDIO_COLLECTION_URI,
            AUDIO_PROJECTION,
            null,
            null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} ASC",
        )?.use { cursor -> cursor.toAudioFiles() } ?: emptyList()
    } catch (_: Exception) {
        // 存储卷暂时不可用等情况不能让音乐页崩溃
        emptyList()
    }

    private fun Cursor.toAudioFiles(): List<AudioFile> {
        val idIndex =
            getColumnIndex(MediaStore.Audio.Media._ID).takeIf { it >= 0 } ?: return emptyList()
        val dataIndex = getColumnIndex(MediaStore.Audio.Media.DATA).takeIf { it >= 0 }
            ?: return emptyList()
        val nameIndex = getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME).takeIf { it >= 0 }
            ?: return emptyList()
        val durationIndex = getColumnIndex(MediaStore.Audio.Media.DURATION).takeIf { it >= 0 }
            ?: return emptyList()
        val sizeIndex =
            getColumnIndex(MediaStore.Audio.Media.SIZE).takeIf { it >= 0 } ?: return emptyList()
        val dateModifiedIndex = getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            .takeIf { it >= 0 } ?: return emptyList()
        val artistIndex = getColumnIndex(MediaStore.Audio.Media.ARTIST)
        val albumIndex = getColumnIndex(MediaStore.Audio.Media.ALBUM)
        val titleIndex = getColumnIndex(MediaStore.Audio.Media.TITLE)

        val audios = ArrayList<AudioFile>(count)
        while (moveToNext()) {
            val path = getString(dataIndex) ?: continue
            val id = getLong(idIndex)
            val duration = getLong(durationIndex)
            val size = getLong(sizeIndex)
            audios.add(
                AudioFile(
                    id = id,
                    path = path,
                    parentPath = File(path).parent ?: "/",
                    duration = duration,
                    uriString = ContentUris.withAppendedId(
                        AUDIO_COLLECTION_URI,
                        id,
                    ).toString(),
                    nameWithExtension = getString(nameIndex) ?: File(path).name,
                    size = size,
                    dateModified = getLong(dateModifiedIndex),
                    formattedDuration = Utils.formatDurationMillis(millis = duration),
                    formattedFileSize = Utils.formatFileSize(size = size),
                    artist = artistIndex?.let { index ->
                        getString(index).toReadableMetadata()
                    }.orEmpty(),
                    album = albumIndex?.let { index ->
                        getString(index).toReadableMetadata()
                    }.orEmpty(),
                    title = titleIndex?.let { index ->
                        getString(index).toReadableMetadata()
                    }.orEmpty(),
                ),
            )
        }
        return audios
    }

    /** MediaStore 里没有标签时会写 "<unknown>"，界面上按空处理。 */
    private fun String?.toReadableMetadata(): String = this?.takeUnless {
        it.isBlank() || it.equals(
            other = "<unknown>",
            ignoreCase = true,
        )
    }.orEmpty()
}
