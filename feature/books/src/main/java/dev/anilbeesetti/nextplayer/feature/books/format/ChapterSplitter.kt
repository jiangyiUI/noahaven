package dev.anilbeesetti.nextplayer.feature.books.format

import dev.anilbeesetti.nextplayer.core.model.ChapterRule

/**
 * TXT 章节切分（规则驱动）。
 *
 * - 传入的 [ChapterRule] 列表按顺序匹配；同一行被多条规则命中时取列表中靠前的那条。
 * - 简单模式规则的正则由 prefix/numberType/suffix 自动生成；正则模式直接用 chapterRegex。
 * - 启用分卷（useVolume）的规则，分卷行也会被当作标题行切出来。
 * - 完全匹配不到章节标题时按 3 万字分段，保证任何 TXT 都能进入阅读器；
 *   超过 6 万字的章节按行/句读再次切开，避免单章排版卡顿。
 */
object ChapterSplitter {

    data class Range(val title: String, val start: Int, val end: Int)

    private const val MAX_CHAPTER_CHARS = 60_000
    private const val FALLBACK_CHUNK_CHARS = 30_000

    private data class Heading(val title: String, val start: Int)

    /** 「相邻重复目录」29/30 规则用到的章节行判定（第X章）。 */
    private val chapterMarkerRegex =
        Regex("第[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}章")

    /** 相邻重复目录判定的最大间隔（字符数）。 */
    private const val DEDUP_NEAR_DISTANCE = 200

    /**
     * 按规则切出标题段，不做 3 万字兜底。
     * 返回 null 表示一条标题都没匹配到（调用方应回退到默认规则或硬分段）。
     */
    fun splitWithHeadings(text: String, rules: List<ChapterRule>): List<Range>? {
        if (text.isBlank()) return null
        val headings = findHeadings(text = text, rules = rules)
        if (headings.isEmpty()) return null
        val ranges = if (headings.size == 1) {
            listOf(Range(title = headings.first().title, start = 0, end = text.length))
        } else {
            buildRanges(text = text, headings = headings)
        }
        return ranges.flatMap { range -> splitLong(text = text, range = range) }
            .filter { it.end > it.start }
            .ifEmpty { null }
    }

    fun split(
        text: String,
        fallbackTitle: String,
        rules: List<ChapterRule> = emptyList(),
    ): List<Range> {
        val safeTitle = fallbackTitle.trim().ifBlank { "正文" }
        if (text.isBlank()) return listOf(Range(safeTitle, 0, text.length))

        val ranges = splitWithHeadings(text = text, rules = rules) ?: chunk(
            text = text,
            start = 0,
            end = text.length,
            titleFor = { "第${it + 1}节" },
        )
        return ranges.ifEmpty { listOf(Range(safeTitle, 0, text.length)) }
    }

    private fun findHeadings(text: String, rules: List<ChapterRule>): List<Heading> {
        val compiled = rules.mapNotNull { rule ->
            val chapter = rule.compileChapter()
            val volume = rule.compileVolume()
            if (chapter == null && volume == null) null else rule to (chapter to volume)
        }
        if (compiled.isEmpty()) return emptyList()

        val result = ArrayList<Heading>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newlineIndex = text.indexOf('\n', lineStart)
            val lineEnd = if (newlineIndex == -1) text.length else newlineIndex
            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                for ((rule, regexes) in compiled) {
                    val (chapter, volume) = regexes
                    val match = chapter?.find(trimmed) ?: volume?.find(trimmed)
                    if (match != null) {
                        val title = match.value.trim().ifBlank { trimmed }
                        result += Heading(title = title, start = lineStart)
                        break
                    }
                    // 同一行只取第一条命中的规则，其余忽略。
                }
            }
            if (newlineIndex == -1) break
            lineStart = newlineIndex + 1
        }
        return applyDedup(headings = result, text = text, ruleIds = rules.map { it.id }.toSet())
    }

    /** 处理 builtin_29 / builtin_30：相邻重复目录分别取前一个 / 后一个。 */
    private fun applyDedup(
        headings: List<Heading>,
        text: String,
        ruleIds: Set<String>,
    ): List<Heading> {
        val keepEarlier = "builtin_29" in ruleIds
        val keepLater = "builtin_30" in ruleIds
        if ((!keepEarlier && !keepLater) || headings.size < 2) return headings

        val remove = BooleanArray(headings.size)
        for (i in 0 until headings.lastIndex) {
            if (remove[i]) continue
            val current = headings[i]
            val next = headings[i + 1]
            if (next.start - current.start > DEDUP_NEAR_DISTANCE) continue
            if (isChapterMarkerLine(text, current.start) && isChapterMarkerLine(text, next.start)) {
                if (keepEarlier) remove[i + 1] = true else if (keepLater) remove[i] = true
            }
        }
        return headings.filterIndexed { index, _ -> !remove[index] }
    }

    private fun isChapterMarkerLine(text: String, lineStart: Int): Boolean {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline == -1) text.length else newline
        return chapterMarkerRegex.containsMatchIn(text.substring(lineStart, lineEnd))
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
