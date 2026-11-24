package dev.anilbeesetti.nextplayer.core.model

/** 电子书格式，由文件后缀推导，不入库。 */
enum class BookFormat {
    TXT,
    EPUB,
    ;

    companion object {
        fun fromPath(path: String): BookFormat =
            when (path.substringAfterLast('.', "").lowercase()) {
                "epub" -> EPUB
                else -> TXT
            }
    }
}

/** 书籍白名单索引项。一个 txt/epub 文件 = 一个 BookFile。 */
data class BookFile(
    val path: String,
    val parentPath: String,
    val whitelistRoot: String,
    /** 带后缀的文件名，用于分享/重命名。 */
    val displayName: String,
    /** 书架展示的标题：EPUB 元数据标题，没有时用去掉后缀的文件名。 */
    val title: String,
    /** EPUB 元数据作者，TXT 或没有元数据时为空串。 */
    val author: String,
    val size: Long,
    val dateModified: Long,
    /** 扫描时抽取的封面缓存路径，没有封面时为 null。 */
    val coverCachePath: String?,
    val formattedFileSize: String,
    /** 该文件所在目录（或任意上级目录）里有 `.nomedia`。 */
    val noMediaHidden: Boolean = false,
    /** 阅读进度：章节下标（TXT 按切分后的章节，EPUB 按 spine）。 */
    val chapterIndex: Int = 0,
    /** 章节内字符偏移（排版后坐标）。 */
    val charOffset: Int = 0,
    /** 0f..1f。 */
    val percent: Float = 0f,
    val lastOpenedAt: Long = 0L,
    /** 是否看完（阅读进度达到看过阈值）。双向标记。 */
    val finished: Boolean = false,
) {
    val format: BookFormat get() = BookFormat.fromPath(path)

    /** 只要开过就显示进度条轨道，哪怕进度是 0，与视频/漫画一致。 */
    val hasProgress: Boolean get() = lastOpenedAt > 0L
}
