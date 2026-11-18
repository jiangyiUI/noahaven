package dev.anilbeesetti.nextplayer.core.model

import java.io.Serializable

/**
 * 一个音频文件（音乐曲目）。
 *
 * 与 [Video] 分开建模：音乐不需要分辨率/播放进度等视频字段，展示只需要标题、时长、大小与路径，
 * 封面则从音频文件的内嵌封面里读取（见 core:media 的 AudioArtwork）。
 */
data class AudioFile(
    val id: Long,
    val path: String,
    val parentPath: String = "",
    val duration: Long,
    val uriString: String,
    val nameWithExtension: String,
    val size: Long,
    val dateModified: Long = 0,
    val formattedDuration: String = "",
    val formattedFileSize: String = "",
    val artist: String = "",
    val album: String = "",
    val title: String = "",
) : Serializable {

    val displayName: String = nameWithExtension.substringBeforeLast(delimiter = ".")
}
