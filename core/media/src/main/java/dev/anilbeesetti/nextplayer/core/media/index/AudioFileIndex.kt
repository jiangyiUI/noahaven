package dev.anilbeesetti.nextplayer.core.media.index

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 磁盘音频文件的索引（磁盘扫描结果的缓存）。
 *
 * 与视频的 [MediaFileIndex] 结构一致：索引先持久化在数据库里，启动时直接读出来秒显；
 * 之后按文件夹做 stat-only 增量刷新。区别只在于扫描是分块发布的——首次建库时列表
 * 会边扫边出现，而不是等整盘扫完再一次性展示。
 */
interface AudioFileIndex {

    /**
     * 当前索引快照。`null` 表示还没从数据库载入完成（此时不应展示「没有音频」）。
     */
    val entries: StateFlow<List<AudioIndexEntry>?>

    /**
     * 请求在后台刷新某个文件夹（null 表示所有存储卷）。按路径节流，不会阻塞调用方。
     * [force] 为 true 时跳过节流（下拉刷新等用户主动操作）。
     */
    fun requestRefresh(folderPath: String?, force: Boolean = false)

    /** 文件已被删除，立即从索引里移除这些路径，让列表马上更新。 */
    fun removePaths(paths: Collection<String>)

    /** 按路径查询单个文件（不存在时返回 null）。 */
    suspend fun find(path: String): AudioIndexEntry?
}

data class AudioIndexEntry(
    val path: String,
    val parentPath: String,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val duration: Long,
    val artist: String,
    val album: String,
    val title: String,
    val metadataLoaded: Boolean,
    val noMediaHidden: Boolean,
)

fun AudioIndexEntry.toAudioFile(): AudioFile = AudioFile(
    id = path.hashCode().toLong(),
    path = path,
    parentPath = parentPath,
    duration = duration,
    uriString = Uri.fromFile(File(path)).toString(),
    nameWithExtension = displayName,
    size = size,
    dateModified = dateModified,
    formattedDuration = Utils.formatDurationMillis(millis = duration),
    formattedFileSize = Utils.formatFileSize(size = size),
    artist = artist,
    album = album,
    title = title,
)
