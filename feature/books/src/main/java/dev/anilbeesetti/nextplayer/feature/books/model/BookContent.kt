package dev.anilbeesetti.nextplayer.feature.books.model

import java.io.File

/** 阅读引擎使用的一章正文，startChar 是它在全书纯文本里的起始下标。 */
data class ReaderChapter(
    val title: String,
    val text: String,
    val startChar: Int,
)

/** 打开一本书时加载的内容。章节正文整体常驻内存，随机读只发生在打开时。 */
class BookContent(
    val path: String,
    val title: String,
    val author: String,
    val chapters: List<ReaderChapter>,
    val totalChars: Int,
    /** 本书解析缓存目录（EPUB 图片解压在这里）。 */
    val bookDir: File,
    val coverFile: File?,
) {
    private val chapterStarts: IntArray = IntArray(size = chapters.size) { chapters[it].startChar }

    private val imageAspectCache = HashMap<String, Float>()

    fun imageFile(relativePath: String): File = File(bookDir, relativePath)

    fun chapterIndexAt(charPosition: Int): Int {
        if (chapters.isEmpty()) return 0
        var low = 0
        var high = chapters.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (chapterStarts[mid] <= charPosition) low = mid else high = mid - 1
        }
        return low
    }

    /** 图片宽高比；读不到时按 4:3 处理，避免分页高度为 0 或无穷大。 */
    fun imageAspect(relativePath: String): Float = imageAspectCache.getOrPut(relativePath) {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(imageFile(relativePath).path, options) }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) 4f / 3f else width.toFloat() / height.toFloat()
    }
}
