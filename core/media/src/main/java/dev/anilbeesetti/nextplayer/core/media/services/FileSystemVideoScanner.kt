package dev.anilbeesetti.nextplayer.core.media.services

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.anilbeesetti.nextplayer.core.common.extensions.getStorageVolumes
import java.io.File

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m2ts", "m4v",
    "3gp", "wmv", "mpg", "mpeg", "rmvb", "ogv", "rm", "divx", "vob",
)

/**
 * 文件系统遍历器，只读取目录项和 `stat` 信息（大小/修改时间），**不**读取视频元数据。
 *
 * 元数据（时长/分辨率）需要 [MediaMetadataRetriever]，每个文件要几十毫秒，因此拆成两步：
 * 扫描只负责发现「文件还在不在、有没有变化」，元数据由调用方在后台慢慢补齐并缓存。
 */
class FileSystemVideoScanner(private val context: Context) {

    /** 扫描到的一个视频文件（只含文件系统能直接给出的信息）。 */
    data class ScannedVideo(
        val path: String,
        val parentPath: String,
        val displayName: String,
        val size: Long,
        val dateModified: Long,
        /** 该文件所在目录（或任意上级目录）里有 `.nomedia`。 */
        val noMediaHidden: Boolean,
    )

    /**
     * 扫描 [folderPath] 子树下的所有视频。
     *
     * @param folderPath 为空表示扫描所有存储卷。
     * @return 扫描结果；若某个目录无法读取（没有权限），返回 null —— 调用方必须放弃本次
     *         差异更新，避免把「读不到」误判成「文件被删了」。
     */
    fun scan(folderPath: String?): List<ScannedVideo>? {
        val videos = ArrayList<ScannedVideo>()
        if (folderPath != null) {
            val root = File(folderPath)
            // 目录已经不存在（被删除/重命名）：返回空列表，让调用方清掉这份子树的缓存。
            if (!root.exists() || !root.isDirectory) return emptyList()
            if (!walk(directory = root, noMediaHidden = false, out = videos)) return null
            return videos
        }

        var failed = false
        for (root in context.getStorageVolumes()) {
            if (!root.isDirectory) continue
            if (!walk(directory = root, noMediaHidden = false, out = videos)) failed = true
        }
        return if (failed) null else videos
    }

    /**
     * @return false 表示有目录读取失败（权限问题），本次结果不完整。
     */
    private fun walk(
        directory: File,
        noMediaHidden: Boolean,
        out: MutableList<ScannedVideo>,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        val hidden = noMediaHidden || children.any { it.name.equals(NOMEDIA_FILE, ignoreCase = true) }
        var complete = true
        for (child in children) {
            val name = child.name
            val stat = child.statInfo() ?: continue
            if (stat.isDirectory) {
                // 跳过隐藏目录（.thumbnails/.trash 等）和应用私有目录 Android/，避免无意义的遍历
                if (name.startsWith(".") || name.equals("Android", ignoreCase = true)) continue
                if (!walk(directory = child, noMediaHidden = hidden, out = out)) complete = false
            } else {
                if (name.startsWith(".") || !name.hasVideoExtension()) continue
                out.add(
                    ScannedVideo(
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

    /**
     * 一次系统调用拿到「是不是目录 + 大小 + 修改时间」。
     *
     * [File.isDirectory] / [File.length] / [File.lastModified] 每个都是一次 stat，文件多的时候
     * 差两三倍；[Os.stat] 不行（比如单元测试环境）时退回 File API。
     */
    private fun File.statInfo(): StatInfo? = try {
        val structStat = Os.stat(path)
        if (structStat.st_mode == 0) {
            // 某些环境下 Os.stat 不会真正填充结构体，退回 File API
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

    private fun String.hasVideoExtension(): Boolean {
        val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension.isEmpty()) return false
        if (extension in VIDEO_EXTENSIONS) return true
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.startsWith("video/") == true
    }

    private companion object {
        const val NOMEDIA_FILE = ".nomedia"
    }
}

data class VideoMetadata(
    val duration: Long,
    val width: Int,
    val height: Int,
)

/**
 * 读取单个视频的时长/分辨率。需要 [MediaMetadataRetriever]，比较慢，只应在后台补齐元数据时调用。
 *
 * @return 读不出来（文件损坏/格式不支持）时返回 null。
 */
fun readVideoMetadata(path: String): VideoMetadata? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        VideoMetadata(
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L,
            width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0,
            height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0,
        )
    } catch (_: Throwable) {
        // 解码器/native 层对损坏或不支持的格式会抛各种异常，补元数据失败不能影响播放
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
            // ignore
        }
    }
}

/**
 * 用文件对象构造 [MediaVideo]，时长/分辨率用 [MediaMetadataRetriever] 读取，读不到就填 0。
 */
fun File.toFileSystemMediaVideo(): MediaVideo {
    val metadata = readVideoMetadata(path = path)
    val lastModifiedSeconds = lastModified() / 1000
    return MediaVideo(
        id = path.hashCode().toLong(),
        uri = Uri.fromFile(this),
        path = path,
        title = name,
        parentPath = parent ?: "/",
        displayName = name,
        duration = metadata?.duration ?: 0L,
        size = length(),
        width = metadata?.width ?: 0,
        height = metadata?.height ?: 0,
        dateModified = lastModifiedSeconds,
        dateAdded = lastModifiedSeconds,
    )
}
