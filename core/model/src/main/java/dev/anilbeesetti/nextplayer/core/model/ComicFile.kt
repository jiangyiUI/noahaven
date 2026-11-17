package dev.anilbeesetti.nextplayer.core.model

/** 漫画压缩包白名单索引项。一个 zip/cbz 文件 = 一个 ComicFile。 */
data class ComicFile(
    val path: String,
    val parentPath: String,
    val whitelistRoot: String,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    /** zip 内部按文件名排序后的图片条目数。 */
    val imageCount: Int,
    /** zip 解出的第一张图在缓存目录中的缩略图路径（扫描时生成，可为 null 表示未生成）。 */
    val coverCachePath: String?,
    val formattedFileSize: String,
    /** 该文件所在目录（或任意上级目录）里有 `.nomedia`。 */
    val noMediaHidden: Boolean = false,
)
