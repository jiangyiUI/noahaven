package dev.anilbeesetti.nextplayer.feature.books.format

/**
 * 极简 HTML/XHTML 正文抽取。EPUB 的排版标签千奇百怪，这里只关心：
 * 段落边界、标题、以及按顺序出现的图片。所有标签最终都会被剥掉，
 * 图片由调用方落盘后替换成占位行，排版阶段再作为独立块处理。
 */
object HtmlText {

    data class Result(
        val text: String,
        val heading: String?,
        val title: String?,
    )

    private val headRegex = Regex("""<head\b[^>]*>.*?</head>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val titleRegex = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val scriptRegex = Regex("""<(script|style|svg)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val rubyRegex = Regex("""<r[pt]\b[^>]*>.*?</r[pt]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val commentRegex = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val headingRegex = Regex("""<h([1-3])\b[^>]*>(.*?)</h\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val imgRegex = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val blockTagRegex = Regex(
        """</?(p|div|br|hr|li|ul|ol|tr|td|th|table|section|article|header|footer|blockquote|h[1-6])\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val tagRegex = Regex("""<[^>]+>""")
    private val numericEntityRegex = Regex("""&#(x[0-9a-fA-F]+|\d+);""")
    private val namedEntityRegex = Regex("""&([a-zA-Z]+);""")

    private val namedEntities = mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "copy" to "©",
        "middot" to "·",
        "times" to "×",
        "laquo" to "«",
        "raquo" to "»",
    )

    /**
     * @param imageSink 图片落盘回调，返回可写进正文的相对路径；返回 null 表示这张图跳过。
     */
    fun extract(html: String, imageSink: (src: String) -> String?): Result {
        val title = titleRegex.find(html)?.groupValues?.get(1)?.let(::cleanInline)
        val heading = headingRegex.find(html)?.groupValues?.get(2)?.let(::cleanInline)
            ?.takeIf { it.isNotBlank() }

        var body = html.replace(commentRegex, "")
        body = body.replace(headRegex, "")
        body = body.replace(scriptRegex, "")
        body = body.replace(rubyRegex, "")

        body = imgRegex.replace(body) { match ->
            val src = attr(match.value, "src") ?: attr(match.value, "data-src")
            val marker = src?.let(imageSink)?.let(BookMarkers::imageLine)
            if (marker != null) "\n$marker\n" else "\n"
        }

        body = body.replace(blockTagRegex, "\n")
        body = body.replace(tagRegex, "")
        body = decodeEntities(body)
        body = normalizeLines(body)

        return Result(text = body, heading = heading, title = title)
    }

    fun cleanInline(raw: String): String =
        decodeEntities(raw.replace(tagRegex, " "))
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizeLines(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .asSequence()
            .map { line -> line.replace('\u00A0', ' ').replace(Regex("""[ \t\u3000]+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun decodeEntities(raw: String): String {
        var result = numericEntityRegex.replace(raw) { match ->
            val value = match.groupValues[1]
            val codePoint = if (value.startsWith("x", ignoreCase = true)) {
                value.substring(1).toIntOrNull(16)
            } else {
                value.toIntOrNull()
            }
            codePoint?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        result = namedEntityRegex.replace(result) { match ->
            namedEntities[match.groupValues[1].lowercase()] ?: match.value
        }
        return result
    }

    private fun attr(tag: String, name: String): String? {
        val regex = Regex(
            """\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(tag) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }
}
