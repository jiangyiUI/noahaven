package dev.anilbeesetti.nextplayer.feature.books.format

/**
 * TXT 章节切分。
 *
 * 两个稳定性处理：
 * - 超过 6 万字的章节按行/句读再次切开，避免单章排版一次性测几万字导致卡顿；
 * - 完全匹配不到章节标题时按 3 万字分段，保证任何 TXT 都能进入阅读器。
 */
object ChapterSplitter {

    data class Range(val title: String, val start: Int, val end: Int)

    private const val MAX_CHAPTER_CHARS = 60_000
    private const val FALLBACK_CHUNK_CHARS = 30_000
    private const val MAX_TITLE_LENGTH = 40

    private val headingRegex = Regex(
        """^\s*(?:正文\s*)?(?:第\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\s*[章节回卷集部篇]""" +
            """|(?:Chapter|CHAPTER)\s+\d+""" +
            """|序章|序言|自序|楔子|引子|前言|后记|尾声|番外|终章)""" +
            """(?:[\s:：、.．·—-]+\S.{0,30})?\s*$""",
    )

    private data class Heading(val title: String, val start: Int)

    fun split(text: String, fallbackTitle: String): List<Range> {
        val safeTitle = fallbackTitle.trim().ifBlank { "正文" }
        if (text.isBlank()) return listOf(Range(safeTitle, 0, text.length))

        val headings = findHeadings(text)
        val ranges = when {
            headings.isEmpty() -> chunk(
                text = text,
                start = 0,
                end = text.length,
                titleFor = { "第${it + 1}节" },
            )

            headings.size == 1 -> chunk(
                text = text,
                start = 0,
                end = text.length,
                titleFor = { index ->
                    if (index == 0) headings.first().title else "${headings.first().title}（续$index）"
                },
            )

            else -> buildRanges(text = text, headings = headings)
        }
        return ranges.flatMap { range -> splitLong(text = text, range = range) }
            .filter { it.end > it.start }
            .ifEmpty { listOf(Range(safeTitle, 0, text.length)) }
    }

    private fun findHeadings(text: String): List<Heading> {
        val result = ArrayList<Heading>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newlineIndex = text.indexOf('\n', lineStart)
            val lineEnd = if (newlineIndex == -1) text.length else newlineIndex
            val line = text.substring(lineStart, lineEnd).trim()
            if (line.isNotEmpty() && line.length <= MAX_TITLE_LENGTH && headingRegex.matches(line)) {
                result += Heading(title = line, start = lineStart)
            }
            if (newlineIndex == -1) break
            lineStart = newlineIndex + 1
        }
        return result
    }

    private fun buildRanges(text: String, headings: List<Heading>): List<Range> {
        val result = ArrayList<Range>(headings.size + 1)
        val firstStart = headings.first().start
        if (firstStart > 0 && text.substring(0, firstStart).isNotBlank()) {
            result += Range(title = "前言", start = 0, end = firstStart)
        }
        headings.forEachIndexed { index, heading ->
            val end = headings.getOrNull(index + 1)?.start ?: text.length
            if (end > heading.start) {
                result += Range(title = heading.title, start = heading.start, end = end)
            }
        }
        return result
    }

    private fun splitLong(text: String, range: Range): List<Range> {
        val length = range.end - range.start
        if (length <= MAX_CHAPTER_CHARS) return listOf(range)

        val result = ArrayList<Range>()
        var partStart = range.start
        var partIndex = 0
        while (partStart < range.end) {
            val ideal = partStart + MAX_CHAPTER_CHARS
            if (ideal >= range.end) {
                result += Range(
                    title = partTitle(range.title, partIndex),
                    start = partStart,
                    end = range.end,
                )
                break
            }
            val breakAt = findBreakPosition(text = text, ideal = ideal, limit = range.end)
            if (breakAt <= partStart) {
                result += Range(
                    title = partTitle(range.title, partIndex),
                    start = partStart,
                    end = range.end,
                )
                break
            }
            result += Range(
                title = partTitle(range.title, partIndex),
                start = partStart,
                end = breakAt,
            )
            partStart = breakAt
            partIndex++
        }
        return result
    }

    private fun chunk(
        text: String,
        start: Int,
        end: Int,
        titleFor: (Int) -> String,
    ): List<Range> {
        val result = ArrayList<Range>()
        var partStart = start
        var index = 0
        while (partStart < end) {
            val ideal = partStart + FALLBACK_CHUNK_CHARS
            val partEnd = if (ideal >= end) {
                end
            } else {
                findBreakPosition(text = text, ideal = ideal, limit = end).takeIf {
                    it > partStart
                } ?: ideal
            }
            result += Range(title = titleFor(index), start = partStart, end = partEnd)
            partStart = partEnd
            index++
        }
        return result
    }

    /** 优先切在换行；找不到就在句读处切；再不行硬切，保证一定前进。 */
    private fun findBreakPosition(text: String, ideal: Int, limit: Int): Int {
        val searchStart = maxOf(0, ideal - 4_000)
        val newline = text.lastIndexOf('\n', minOf(ideal, limit - 1))
        if (newline >= searchStart) return newline + 1

        val sentenceStart = maxOf(0, ideal - 2_000)
        val sentenceChars = charArrayOf('。', '！', '？', '；', '，', '.', '!', '?', ';')
        var index = minOf(ideal, limit - 1)
        while (index > sentenceStart) {
            if (text[index] in sentenceChars) return index + 1
            index--
        }
        return minOf(ideal, limit)
    }

    private fun partTitle(base: String, index: Int): String =
        if (index == 0) base else "$base（续${index + 1}）"
}
