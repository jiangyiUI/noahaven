package dev.anilbeesetti.nextplayer.core.model

/** 白名单目录中的一个图片文件，不进入视频/音频播放器和播放历史。 */
data class ImageFile(
    val path: String,
    val parentPath: String,
    val whitelistRoot: String,
    val uriString: String,
    val nameWithExtension: String,
    val size: Long,
    val dateModified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val noMediaHidden: Boolean = false,
    val dimensionsLoaded: Boolean = false,
    val formattedFileSize: String = "",
) {
    val displayName: String = nameWithExtension.substringBeforeLast(delimiter = ".")
    val extension: String = nameWithExtension.substringAfterLast(delimiter = ".", missingDelimiterValue = "")
    val hasDimensions: Boolean get() = width > 0 && height > 0
}
