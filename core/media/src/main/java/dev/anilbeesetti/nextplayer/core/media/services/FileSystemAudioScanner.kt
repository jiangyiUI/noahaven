package dev.anilbeesetti.nextplayer.core.media.services

import android.content.Context
import android.media.MediaMetadataRetriever
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.anilbeesetti.nextplayer.core.common.extensions.getStorageVolumes
import java.io.File

private val AUDIO_EXTENSIONS = setOf(
    "mp3",
    "m4a",
    "m4b",
    "aac",
    "flac",
    "ogg",
    "oga",
    "opus",
    "wav",
    "wave",
    "wma",
    "ape",
    "alac",
    "mka",
    "amr",
    "mid",
    "midi",
    "tta",
    "dsf",
    "dff",
    "aiff",
    "aif",
    "3gp",
)

/**
 * 音频文件系统遍历器，规则与视频的 [FileSystemVideoScanner] 完全一致：
 * 只读目录项和 `stat` 信息，不读音频标签；标签由调用方在后台慢慢补齐。
 *
 * 与视频扫描器唯一的区别是**分块回调**：每扫到一批就交给调用方，
 * 这样首次建索引时列表可以边扫边显示，而不是等整盘扫完才一次性出现。
 */
class FileSystemAudioScanner(private val context: Context) {

    /** 扫描到的一个音频文件（只含文件系统能直接给出的信息）。 */
    data class ScannedAudio(
        val path: String,
        val parentPath: String,
        val displayName: String,
        val size: Long,
        val dateModified: Long,
        /** 该文件所在目录（或任意上级目录）里有 `.nomedia`。 */
        val noMediaHidden: Boolean,
    )

    /**
     * 扫描 [folderPath] 子树下的所有音频，扫描过程中按批回调 [onBatch]。
     * 回调是挂起函数：调用方可以在每批到达时写库/发布，界面就能边扫边显示。
     *
     * @param folderPath 为空表示扫描所有存储卷。
     * @return true 表示扫描完整；false 表示有目录读不到（没有权限），
     *         调用方不能据此删除索引里的条目。
     */
    suspend fun scan(folderPath: String?, onBatch: suspend (List<ScannedAudio>) -> Unit): Boolean {
        val batch = ArrayList<ScannedAudio>(BATCH_SIZE)
        var lastFlushAt = System.currentTimeMillis()

        suspend fun flush() {
            if (batch.isEmpty()) return
            onBatch(batch.toList())
            batch.clear()
            lastFlushAt = System.currentTimeMillis()
        }

        suspend fun emit(audio: ScannedAudio) {
            batch.add(audio)
            if (batch.size >= BATCH_SIZE ||
                System.currentTimeMillis() - lastFlushAt >= FLUSH_INTERVAL_MS
            ) {
                flush()
            }
        }

        val complete = if (folderPath != null) {
            val root = File(folderPath)
            // 目录已经不存在（被删除/重命名）：返回空结果，让调用方清掉这份子树的缓存。
            if (!root.exists() || !root.isDirectory) {
                true
            } else {
                walk(
                    directory = root,
                    noMediaHidden = false,
                    onFile = { audio -> emit(audio) },
                )
            }
        } else {
            var failed = false
            for (root in context.getStorageVolumes()) {
                if (!root.isDirectory) continue
                if (!walk(
                        directory = root,
                        noMediaHidden = false,
                        onFile = { audio ->
                            emit(audio)
                        },
                    )
                ) {
                    failed = true
                }
            }
            !failed
        }

        flush()
        return complete
    }

    /**
     * @return false 表示有目录读取失败（权限问题），本次结果不完整。
     */
    private suspend fun walk(
        directory: File,
        noMediaHidden: Boolean,
        onFile: suspend (ScannedAudio) -> Unit,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        val hidden =
            noMediaHidden || children.any {
                it.name.equals(
                    NOMEDIA_FILE,
                    ignoreCase = true,
                )
            }
        var complete = true
        for (child in children) {
            val name = child.name
            val stat = child.statInfo() ?: continue
            if (stat.isDirectory) {
                // 跳过隐藏目录（.thumbnails/.trash 等）和应用私有目录 Android/
                if (name.startsWith(prefix = ".") ||
                    name.equals(
                        other = "Android",
                        ignoreCase = true,
                    )
                ) {
                    continue
                }
                if (!walk(
                        directory = child,
                        noMediaHidden = hidden,
                        onFile = onFile,
                    )
                ) {
                    complete = false
                }
            } else {
                if (name.startsWith(prefix = ".") || !name.hasAudioExtension()) continue
                onFile(
                    ScannedAudio(
                        path = child.path,
                        parentPath = directory.path,
                        displayName = name,
                        size = stat.size,
                        dateModified = stat.modifiedSeconds,
                        noMediaHidden = hidden,
                    ),
                )
            }
        }
        return complete
    }

    private data class StatInfo(
        val isDirectory: Boolean,
        val size: Long,
        val modifiedSeconds: Long,
    )

    private fun File.statInfo(): StatInfo? = try {
        val structStat = Os.stat(path)
        if (structStat.st_mode == 0) {
            fileApiStat()
        } else {
            StatInfo(
                isDirectory = OsConstants.S_ISDIR(structStat.st_mode),
                size = structStat.st_size,
                modifiedSeconds = structStat.st_mtime,
            )
        }
    } catch (_: Throwable) {
        fileApiStat()
    }

    private fun File.fileApiStat(): StatInfo? {
        if (!exists()) return null
        return StatInfo(
            isDirectory = isDirectory,
            size = length(),
            modifiedSeconds = lastModified() / 1000,
        )
    }

    private fun String.hasAudioExtension(): Boolean {
        val extension = substringAfterLast(
            '.',
            missingDelimiterValue = "",
        ).lowercase()
        if (extension.isEmpty()) return false
        if (extension in AUDIO_EXTENSIONS) return true
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.startsWith("audio/") == true
    }

    private companion object {
        const val NOMEDIA_FILE = ".nomedia"
        const val BATCH_SIZE = 200
        const val FLUSH_INTERVAL_MS = 300L
    }
}

data class AudioMetadata(
    val duration: Long,
    val title: String,
    val artist: String,
    val album: String,
)

/**
 * 读取单个音频的时长/标题/歌手/专辑。需要 [MediaMetadataRetriever]，比较慢，
 * 只应在后台补齐元数据时调用。
 */
fun readAudioMetadata(path: String): AudioMetadata? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        AudioMetadata(
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L,
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                .toReadableMetadata(),
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                .toReadableMetadata(),
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                .toReadableMetadata(),
        )
    } catch (_: Throwable) {
        // 解码层对损坏/不支持的格式会抛各种异常，补元数据失败不能影响列表
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
            // ignore
        }
    }
}

/** MediaStore 里没有标签时会写 "<unknown>"，界面上按空处理。 */
private fun String?.toReadableMetadata(): String = this?.takeUnless {
    it.isBlank() || it.equals(
        other = "<unknown>",
        ignoreCase = true,
    )
}.orEmpty()
