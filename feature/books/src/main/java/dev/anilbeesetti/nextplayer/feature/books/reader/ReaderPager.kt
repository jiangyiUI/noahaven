package dev.anilbeesetti.nextplayer.feature.books.reader

import dev.anilbeesetti.nextplayer.feature.books.model.BookContent
import kotlin.math.roundToInt

/**
 * 阅读位置与翻页。章节内部的页由 [ChapterPaginator] 生成，这里只管理：
 * - 当前章节/页；
 * - 翻到下一章时优先从「接缝页已经消费到的偏移」开始，避免重复；
 * - 往回翻时回到上一章并停在最后一页；
 * - 进度百分比使用「章节起始字符 + 页起始偏移」/全书字符数。
 */
class ReaderPager(
    private val content: BookContent,
    private val paginator: ChapterPaginator,
) {

    private val anchors = HashMap<Int, Int>()

    private val pageCache = object : LinkedHashMap<Long, List<ReaderPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ReaderPage>>?): Boolean =
            size > 6
    }

    var chapterIndex: Int = 0
        private set

    var pages: List<ReaderPage> = emptyList()
        private set

    var pageIndex: Int = 0
        private set

    val currentPage: ReaderPage? get() = pages.getOrNull(pageIndex)

    val chapterTitle: String get() = content.chapters.getOrNull(chapterIndex)?.title.orEmpty()

    val bookPercent: Float
        get() {
            val page = currentPage
            val chapterStart = content.chapters.getOrNull(chapterIndex)?.startChar ?: 0
            val absolute = chapterStart + (page?.startOffset ?: 0)
            return (absolute.toFloat() / content.totalChars).coerceIn(0f, 1f)
        }

    val isAtBookEnd: Boolean
        get() = chapterIndex == content.chapters.lastIndex && pageIndex == pages.lastIndex

    fun open(chapterIndex: Int, charOffset: Int): Boolean {
        if (content.chapters.isEmpty()) return false
        val target = chapterIndex.coerceIn(0, content.chapters.lastIndex)
        val chapter = content.chapters[target]
        val anchor = charOffset.coerceIn(0, chapter.text.length)
        anchors[target] = anchor

        val list = paginate(target = target, anchor = anchor)
        if (list.isEmpty()) return false
        this.chapterIndex = target
        pages = list
        pageIndex = 0
        return true
    }

    /**
     * 打开到某章节的指定字符偏移：对整章从 0 分页，再落到包含该偏移的页。
     *
     * 与 [open] 直接从锚点开始分页不同，这里保留锚点之前的页，因此：
     * - 页脚页码是该偏移在整章中的真实页号（而不是恒为 1）；
     * - 首次按「上一页」会回到上一页，而不是直接跳回上一章。
     * 翻到下一章/上一章的接缝续读仍走 [open]，避免重复已读内容。
     */
    fun openAt(chapterIndex: Int, charOffset: Int): Boolean {
        if (content.chapters.isEmpty()) return false
        val target = chapterIndex.coerceIn(0, content.chapters.lastIndex)
        if (!open(chapterIndex = target, charOffset = 0)) return false
        val local = charOffset.coerceIn(0, content.chapters[target].text.length)
        val index = pages.indexOfLast { it.startOffset <= local }
        pageIndex = index.coerceIn(0, pages.lastIndex)
        anchors[target] = local
        return true
    }

    fun goNext(): Boolean {
        if (pageIndex < pages.lastIndex) {
            pageIndex++
            return true
        }
        val page = currentPage
        val tailIndex = page?.tailChapterIndex
        var target = tailIndex ?: (chapterIndex + 1).takeIf { it < content.chapters.size }
            ?: return false
        var offset = if (tailIndex != null) page.tailConsumed else 0
        // 下一章可能为空章，或接缝页已把它排完，继续找后面能打开的一章。
        while (target < content.chapters.size) {
            if (open(chapterIndex = target, charOffset = offset)) return true
            target++
            offset = 0
        }
        return false
    }

    fun goPrevious(): Boolean {
        if (pageIndex > 0) {
            pageIndex--
            return true
        }
        var target = chapterIndex - 1
        while (target >= 0) {
            if (open(chapterIndex = target, charOffset = anchors[target] ?: 0)) {
                pageIndex = pages.lastIndex
                return true
            }
            target--
        }
        return false
    }

    fun jumpToChapter(chapterIndex: Int): Boolean {
        if (content.chapters.isEmpty()) return false
        var target = chapterIndex.coerceIn(0, content.chapters.lastIndex)
        while (target < content.chapters.size) {
            if (open(chapterIndex = target, charOffset = 0)) return true
            target++
        }
        return false
    }

    /** 只在当前章内跳页：0 表示本章第一页，1 表示本章最后一页。 */
    fun jumpToPageInChapter(progress: Float): Boolean {
        if (pages.isEmpty()) return false
        val target = (progress.coerceIn(0f, 1f) * pages.lastIndex).roundToInt()
        pageIndex = target.coerceIn(0, pages.lastIndex)
        return true
    }

    /** 按全书字符进度跳转，用于「全文页码」模式的进度条。 */
    fun jumpToPercent(percent: Float): Boolean {
        if (content.chapters.isEmpty()) return false
        val targetChar = (percent.coerceIn(0f, 1f) * content.totalChars).toInt()
        val targetChapter = content.chapterIndexAt(targetChar)
        if (!open(chapterIndex = targetChapter, charOffset = 0)) return false
        val local = (targetChar - content.chapters[targetChapter].startChar).coerceAtLeast(0)
        val index = pages.indexOfLast { it.startOffset <= local }
        pageIndex = index.coerceIn(0, pages.lastIndex)
        return true
    }

    private fun paginate(target: Int, anchor: Int): List<ReaderPage> {
        val key = (target.toLong() shl 32) or (anchor.toLong() and 0xFFFFFFFFL)
        synchronized(pageCache) { pageCache[key] }?.let { return it }

        val chapter = content.chapters[target]
        val nextChapter = content.chapters.getOrNull(target + 1)
        val list = paginator.paginate(
            chapterIndex = target,
            chapter = chapter,
            startOffset = anchor,
            nextChapterIndex = if (nextChapter != null) target + 1 else null,
            nextChapter = nextChapter,
        )
        synchronized(pageCache) { pageCache[key] = list }
        return list
    }

    companion object {
        /** 分页缓存依赖排版参数与屏幕尺寸，参数变化时由界面重建 pager 而非刷新缓存。 */
        const val CACHE_SIZE_HINT = 6
    }
}
