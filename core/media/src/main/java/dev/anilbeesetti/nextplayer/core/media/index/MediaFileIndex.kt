package dev.anilbeesetti.nextplayer.core.media.index

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.media.services.MediaVideo
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 磁盘视频文件的索引（磁盘扫描结果的缓存）。
 *
 * 索引先持久化在数据库里，启动时直接读出来秒显；之后按文件夹做增量刷新：
 * 新增的文件补进来、删掉的移除、重命名/移动的按路径差异自然更新。
 */
interface MediaFileIndex {

    /**
     * 当前索引快照。`null` 表示还没从数据库载入完成（此时不应展示「媒体库为空」）。
     */
    val entries: StateFlow<List<MediaIndexEntry>?>

    /**
     * 请求在后台刷新某个文件夹（null 表示所有存储卷）。按路径节流，不会阻塞调用方。
     */
    fun requestRefresh(folderPath: String?)

    /** 文件已被删除，立即从索引里移除这些路径，让列表马上更新。 */
    fun removePaths(paths: Collection<String>)

    /** 按路径查询单个文件（不存在时返回 null）。 */
    suspend fun find(path: String): MediaIndexEntry?
}

data class MediaIndexEntry(
    val path: String,
    val parentPath: String,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val duration: Long,
    val width: Int,
    val height: Int,
    val metadataLoaded: Boolean,
    val noMediaHidden: Boolean,
)

fun MediaIndexEntry.toMediaVideo(): MediaVideo = MediaVideo(
    id = path.hashCode().toLong(),
    uri = Uri.fromFile(File(path)),
    path = path,
    title = displayName,
    parentPath = parentPath,
    displayName = displayName,
    duration = duration,
    size = size,
    width = width,
    height = height,
    dateModified = dateModified,
    dateAdded = dateModified,
)
