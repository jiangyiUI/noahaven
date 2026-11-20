package dev.anilbeesetti.nextplayer.core.model

import java.io.Serializable

/**
 * 一个音频文件（音乐曲目）。
 *
 * 与 [Video] 分开建模：音乐不需要分辨率等视频字段，展示只需要标题、时长、大小与路径，
 * 封面则从音频文件的内嵌封面里读取（见 core:media 的 AudioArtwork）。
 *
 * [playbackPosition] 来自播放状态表，音乐库用它给封面底部画播放进度条。
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
    val playbackPosition: Long? = null,
) : Serializable {

    val displayName: String = nameWithExtension.substringBeforeLast(delimiter = ".")

    /** 播放进度百分比，语义与 [Video.playedPercentage] 一致，用于封面底部进度条。 */
    val playedPercentage: Float? = playbackPosition?.let { playbackPosition ->
        when {
            duration <= 0 -> null
            playbackPosition < 0 -> 1f
            else -> (playbackPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }
    }
}
