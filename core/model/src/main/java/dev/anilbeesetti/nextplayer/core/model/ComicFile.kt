package dev.anilbeesetti.nextplayer.core.model

/** 漫画文件格式。从文件后缀推导，不需要入库。 */
enum class ComicFormat {
    /** zip / cbz：内部按文件名排序的图片集合 */
    ZIP,

    /** pdf：每页一张图，用系统 PdfRenderer 渲染 */
    PDF,
    ;

    companion object {
        fun fromPath(path: String): ComicFormat =
            when (path.substringAfterLast('.', "").lowercase()) {
                "pdf" -> PDF
                else -> ZIP
            }
    }
}

/** 漫画压缩包白名单索引项。一个 zip/cbz/pdf 文件 = 一个 ComicFile。 */
data class ComicFile(
    val path: String,
    val parentPath: String,
    val whitelistRoot: String,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    /** zip 内部图片数 / pdf 页数。 */
    val imageCount: Int,
    /** 扫描时生成的缩略图缓存路径（zip 第一张图 / pdf 第 0 页）。 */
    val coverCachePath: String?,
    val formattedFileSize: String,
    /** 该文件所在目录（或任意上级目录）里有 `.nomedia`。 */
    val noMediaHidden: Boolean = false,
    /** 最近阅读到的页码（从 ComicProgressEntity join 过来），0 表示没看过。 */
    val lastPage: Int = 0,
    /** 是否看完（阅读进度达到看过阈值）。双向标记。 */
    val watched: Boolean = false,
) {
    /** 从 path 后缀推导，不入库。 */
    val format: ComicFormat get() = ComicFormat.fromPath(path)
}
