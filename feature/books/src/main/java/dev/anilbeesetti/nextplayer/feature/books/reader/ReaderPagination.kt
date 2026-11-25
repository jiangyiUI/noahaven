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
}

data class ReaderPage(
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val blocks: List<PageBlock>,
    val usedHeight: Float = 0f,
)

/**
 * 按行精确分页：
 * - 只按整行切分，页尾最后一个 block 的内容永远完整，渲染时再 clip 到内容区下边界；
 * - 图片作为独立块，不参与行切分，避免「图片被分页从中间截断」；
 * - 单段超过 1500 字先按句读切块，避免一次 measure 一个几万字的段落；
 * - 每章独立分页，章节标题另起一页、以标题样式渲染，不与上一章拼接。
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

        return pages
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

            val layout = measure(text = paragraph.text, style = bodyStyle, indent = paragraph.startsParagraph)
            val totalLines = layout.lineCount
            // 整段（连同其后的段间距）能放进本页时，才为它预留段间距；否则这一段会在页内
            // 断开、成为本页最后一块，后面不会再有内容，就不该预留段间距——否则会白白空出
            // 接近一个段间距的高度（段间距越大越明显），表现为「这段还没结束，页底却空了一节」。
            val fullHeight = if (totalLines > 0) pieceMeasuredHeight(layout, paragraph, totalLines) else 0f
            val fitsWhole = totalLines > 0 && used + fullHeight + paragraphSpacingPx <= budget
            val remaining = if (fitsWhole) budget - used - paragraphSpacingPx else budget - used
            // 用「实际要渲染的文本片段」的测量高度来决定能放几行，而不是用行数 × 推算行高估算。
            // 对每种行数候选测一次 piece，取最高不超过 remaining 的。这样 used 的累计与渲染端
            // Text 的真实高度逐块一致，无论 lineHeightStyle / includeFontPadding 让单行测量值与
            // 渲染值有多大偏差，都不会再逐段高估、提前断页，页底留白被压到不足一行且各页一致。
            var fitLines = 0
            var fitHeight = 0f
            if (totalLines > 0 && remaining > 0f) {
                var lo = 1
                var hi = totalLines
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val h = pieceMeasuredHeight(layout, paragraph, mid)
                    if (h <= remaining) {
                        fitLines = mid
                        fitHeight = h
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
            }
            if (fitLines <= 0) {
                if (blocks.isEmpty()) {
                    // 连一行都放不下时至少放一行，避免死循环（极小的分页区域）。
                    val lineEnd = layout.getLineEnd(0)
                    val piece = paragraph.text.substring(0, lineEnd).trimEnd()
                    if (piece.isNotEmpty()) {
                        blocks += PageBlock.Text(text = piece, indent = paragraph.startsParagraph)
                        cursor = paragraph.start + lineEnd
                        used += pieceMeasuredHeight(layout, paragraph, 1) + paragraphSpacingPx
                    }
                }
                break
            }

            val endChar = layout.getLineEnd(fitLines - 1)
            val piece = paragraph.text.substring(0, endChar).trimEnd()
            if (piece.isNotEmpty()) {
                blocks += PageBlock.Text(text = piece, indent = paragraph.startsParagraph)
                used += fitHeight + paragraphSpacingPx
            }
            cursor = paragraph.start + endChar
            if (fitLines < totalLines) break
        }

        // 页内最后一段后面没有内容，不再预留其尾部间距，把这部分高度还给正文，减少页底留白。
        if (blocks.isNotEmpty()) {
            val lastSpacing = when (blocks.last()) {
                is PageBlock.Heading -> headingSpacingPx
                else -> paragraphSpacingPx
            }
            used = (used - lastSpacing).coerceAtLeast(0f)
        }
        return FillResult(blocks = blocks, consumed = cursor.coerceAtLeast(start), used = used)
    }

    // 测量某段前 lines 行拼成的文本片段的真实渲染高度。fullLayout 是整段的测量结果，
    // 复用它的换行位置（getLineEnd）来切出片段，再单独测量片段——这个高度与渲染端 Text
    // 对同一段文本的实际高度完全一致（同 style、同宽度、同 density），所以分页累计
    // 不再依赖推算行高，从根源上消除测量值与渲染值的偏差。
    private fun pieceMeasuredHeight(
        fullLayout: TextLayoutResult,
        paragraph: Paragraph,
        lines: Int,
    ): Float {
        if (lines <= 0) return 0f
        val endChar = fullLayout.getLineEnd(lines - 1)
        val piece = paragraph.text.substring(0, endChar).trimEnd()
        if (piece.isEmpty()) return 0f
        return measure(text = piece, style = bodyStyle, indent = paragraph.startsParagraph)
            .size
            .height
            .toFloat()
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
