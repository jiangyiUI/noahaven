package dev.anilbeesetti.nextplayer.feature.books.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import dev.anilbeesetti.nextplayer.feature.books.format.BookMarkers
import dev.anilbeesetti.nextplayer.feature.books.model.ReaderChapter
import kotlin.math.roundToInt

sealed interface PageBlock {
    data class Text(val text: String, val indent: Boolean) : PageBlock
    data class Heading(val text: String) : PageBlock
    data class Image(val path: String, val heightPx: Int) : PageBlock
    data object Divider : PageBlock
}

data class ReaderPage(
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val blocks: List<PageBlock>,
    val usedHeight: Float = 0f,
    /** 当前页底部接上的下一章（跨章接缝），null 表示没有接。 */
    val tailChapterIndex: Int? = null,
    val tailConsumed: Int = 0,
)

/**
 * 按行精确分页：
 * - 只按整行切分，页尾最后一个 block 的内容永远完整，渲染时再 clip 到内容区下边界；
 * - 图片作为独立块，不参与行切分，避免「图片被分页从中间截断」；
 * - 单段超过 1500 字先按句读切块，避免一次 measure 一个几万字的段落；
 * - 最后一页若还有两行以上空间，自动接上下一章开头（跨章接缝），并记录 consumed 偏移，
 *   用户翻到下一章时从该偏移继续，不会重复也不会跳字。
 */
class ChapterPaginator(
    private val textMeasurer: TextMeasurer,
    private val bodyStyle: TextStyle,
    private val headingStyle: TextStyle,
    private val widthPx: Int,
    private val heightPx: Int,
    private val paragraphSpacingPx: Float,
    private val headingSpacingPx: Float,
    private val imageAspect: (String) -> Float,
    private val firstLineIndent: Boolean = true,
    private val justifyText: Boolean = true,
) {

    private class Paragraph(
        val start: Int,
        val end: Int,
        val text: String,
        val imagePath: String?,
        val startsParagraph: Boolean,
    )

    private class FillResult(
        val blocks: List<PageBlock>,
        val consumed: Int,
        val used: Float,
    )

    private val lineHeightPx: Float by lazy {
        measure(text = "国", style = bodyStyle, indent = false).size.height.toFloat()
    }

    fun paginate(
        chapterIndex: Int,
        chapter: ReaderChapter,
        startOffset: Int,
        nextChapterIndex: Int?,
        nextChapter: ReaderChapter?,
    ): List<ReaderPage> {
        val text = chapter.text
        if (text.isBlank()) return emptyList()

        val pages = ArrayList<ReaderPage>()
        var cursor = startOffset.coerceIn(0, text.length)
        var includeTitle = cursor == 0
        var guard = 0
        while (cursor < text.length && guard++ < 20_000) {
            val fill = fillPage(
                text = text,
                start = cursor,
                budget = heightPx.toFloat(),
                title = if (includeTitle) chapter.title else null,
            )
            if (fill.blocks.isEmpty()) break
            val pageEnd = fill.consumed.coerceIn(cursor, text.length)
            pages += ReaderPage(
                chapterIndex = chapterIndex,
                startOffset = cursor,
                endOffset = pageEnd,
                blocks = fill.blocks,
                usedHeight = fill.used,
            )
            if (pageEnd <= cursor) break
            cursor = pageEnd
            includeTitle = false
        }

        if (pages.isEmpty()) {
            val rest = text.substring(cursor)
            // 起点之后只剩空白（常见于章节末尾的换行），不要生成空白页。
            if (rest.isBlank()) return emptyList()
            pages += ReaderPage(
                chapterIndex = chapterIndex,
                startOffset = cursor,
                endOffset = text.length,
                blocks = listOf(PageBlock.Text(rest.trim(), indent = true)),
                usedHeight = lineHeightPx,
            )
        }

        appendSeamTail(
            pages = pages,
            nextChapterIndex = nextChapterIndex,
            nextChapter = nextChapter,
        )
        return pages
    }

    private fun appendSeamTail(
        pages: MutableList<ReaderPage>,
        nextChapterIndex: Int?,
        nextChapter: ReaderChapter?,
    ) {
        if (nextChapterIndex == null || nextChapter == null) return
        if (nextChapter.text.isBlank()) return
        val last = pages.lastOrNull() ?: return
        val remaining = heightPx.toFloat() - last.usedHeight
        if (remaining < lineHeightPx * 2.4f) return

        // 留出「· · ·」分隔行的位置，否则接缝内容会让整页超出一行行距。
        val dividerHeight = lineHeightPx + paragraphSpacingPx
        val tailBudget = remaining - dividerHeight
        if (tailBudget <= 0f) return

        val tail = fillPage(
            text = nextChapter.text,
            start = 0,
            budget = tailBudget,
            title = nextChapter.title,
        )
        if (tail.consumed <= 0) return
        // 下一章太短，接缝页已经把它整章排完：不接缝，让下一章完整出现在自己的页面里。
        if (tail.consumed >= nextChapter.text.length) return
        pages[pages.lastIndex] = last.copy(
            blocks = last.blocks + PageBlock.Divider + tail.blocks,
            tailChapterIndex = nextChapterIndex,
            tailConsumed = tail.consumed,
        )
    }

    private fun fillPage(
        text: String,
        start: Int,
        budget: Float,
        title: String?,
    ): FillResult {
        var used = 0f
        var cursor = start
        val blocks = ArrayList<PageBlock>(16)

        if (!title.isNullOrBlank()) {
            val titleLayout = measure(text = title, style = headingStyle, indent = false)
            val titleHeight = titleLayout.size.height + headingSpacingPx
            if (titleHeight <= budget * 0.65f) {
                blocks += PageBlock.Heading(title)
                used += titleHeight
                // 很多 TXT 的第一行就是章节标题，已经作为标题块渲染过，正文里跳过它。
                val first = nextParagraph(text = text, from = start)
                if (first != null && sameText(first.text, title)) {
                    cursor = first.end
                }
            }
        }

        while (cursor < text.length) {
            val paragraph = nextParagraph(text = text, from = cursor) ?: break

            val imagePath = paragraph.imagePath
            if (imagePath != null) {
                val aspect = imageAspect(imagePath).takeIf { it.isFinite() && it > 0.05f }
                    ?: (4f / 3f)
                val naturalHeight = widthPx / aspect
                val imageHeight = naturalHeight.coerceAtMost(heightPx * 0.72f)
                val imageTotal = imageHeight + paragraphSpacingPx
                if (used + imageTotal > budget) {
                    // 整页排版时图片已限制在页高 72% 以内，允许独占一页；
                    // 接缝页剩余空间不足时直接不接，避免图片被页尾裁切。
                    if (blocks.isNotEmpty() || budget < heightPx * 0.72f) break
                }
                blocks += PageBlock.Image(path = imagePath, heightPx = imageHeight.roundToInt())
                used += imageTotal
                cursor = paragraph.end
                continue
            }

            val remaining = budget - used - paragraphSpacingPx
            val layout = measure(text = paragraph.text, style = bodyStyle, indent = paragraph.startsParagraph)
            val fitLines = linesThatFit(layout = layout, available = remaining)
            if (fitLines <= 0) {
                if (blocks.isEmpty()) {
                    // 连一行都放不下时至少放一行，避免死循环（极小的分页区域）。
                    val lineEnd = layout.getLineEnd(0)
                    val piece = paragraph.text.substring(0, lineEnd).trimEnd()
                    blocks += PageBlock.Text(text = piece, indent = paragraph.startsParagraph)
                    cursor = paragraph.start + lineEnd
                    used += layout.getLineBottom(0) + paragraphSpacingPx
                }
                break
            }

            val endChar = layout.getLineEnd(fitLines - 1)
            val piece = paragraph.text.substring(0, endChar).trimEnd()
            if (piece.isNotEmpty()) {
                blocks += PageBlock.Text(text = piece, indent = paragraph.startsParagraph)
            }
            used += layout.getLineBottom(fitLines - 1) + paragraphSpacingPx
            cursor = paragraph.start + endChar
            if (fitLines < layout.lineCount) break
        }

        return FillResult(blocks = blocks, consumed = cursor.coerceAtLeast(start), used = used)
    }

    private fun linesThatFit(layout: TextLayoutResult, available: Float): Int {
        if (available <= 0f) return 0
        var count = 0
        while (count < layout.lineCount) {
            if (layout.getLineBottom(count) > available) break
            count++
        }
        return count
    }

    private fun nextParagraph(text: String, from: Int): Paragraph? {
        var position = from
        while (position < text.length) {
            val newline = text.indexOf('\n', position)
            val lineEnd = if (newline == -1) text.length else newline
            val lineStart = text.lastIndexOf('\n', position - 1).let { it + 1 }

            var firstNonSpace = lineStart
            while (firstNonSpace < lineEnd && text[firstNonSpace].isWhitespace()) firstNonSpace++
            var start = position
            while (start < lineEnd && text[start].isWhitespace()) start++
            if (start >= lineEnd) {
                position = lineEnd + 1
                continue
            }
            var end = lineEnd
            while (end > start && text[end - 1].isWhitespace()) end--

            val startsParagraph = start == firstNonSpace
            if (end - start > MAX_PARAGRAPH_CHARS) {
                end = start + findSentenceCut(text = text, start = start, limit = end)
            }
            val raw = text.substring(start, end)
            if (raw.isBlank()) {
                position = lineEnd + 1
                continue
            }
            return Paragraph(
                start = start,
                end = end,
                text = raw,
                imagePath = BookMarkers.parseImageLine(raw),
                startsParagraph = startsParagraph,
            )
        }
        return null
    }

    private fun findSentenceCut(text: String, start: Int, limit: Int): Int {
        val sentenceChars = charArrayOf('。', '！', '？', '；', '…', '.', '!', '?', ';')
        var index = minOf(start + MAX_PARAGRAPH_CHARS, limit)
        val lowerBound = start + MAX_PARAGRAPH_CHARS / 2
        var probe = index - 1
        while (probe > lowerBound) {
            if (text[probe] in sentenceChars) return probe + 1 - start
            probe--
        }
        return index - start
    }

    private fun sameText(first: String, second: String): Boolean {
        val normalize = { value: String -> value.filterNot { it.isWhitespace() } }
        return normalize(first) == normalize(second)
    }

    private fun measure(text: String, style: TextStyle, indent: Boolean): TextLayoutResult {
        // 关闭首行缩进时，分页测量也不能按缩进算，否则首行宽度会与实际渲染不一致。
        // 两端对齐必须测量与渲染同时开/关（对齐不改变行数，但保持一致最稳妥）。
        val indentEnabled = indent && firstLineIndent
        val annotated = when {
            indentEnabled && justifyText -> AnnotatedString(
                text = text,
                paragraphStyle = ParagraphStyle(
                    textIndent = TextIndent(firstLine = 2.em),
                    textAlign = TextAlign.Justify,
                ),
            )

            indentEnabled -> AnnotatedString(
                text = text,
                paragraphStyle = ParagraphStyle(textIndent = TextIndent(firstLine = 2.em)),
            )

            justifyText -> AnnotatedString(
                text = text,
                paragraphStyle = ParagraphStyle(textAlign = TextAlign.Justify),
            )

            else -> AnnotatedString(text = text)
        }
        return textMeasurer.measure(
            text = annotated,
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            constraints = Constraints(maxWidth = widthPx),
        )
    }

    private companion object {
        const val MAX_PARAGRAPH_CHARS = 1_500
    }
}
