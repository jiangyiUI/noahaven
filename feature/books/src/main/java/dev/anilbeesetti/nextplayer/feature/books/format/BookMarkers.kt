package dev.anilbeesetti.nextplayer.feature.books.format

/**
 * 正文里的图片占位行。TXT 不会产生图片；EPUB 抽正文时把 `<img>` 换成独立一行，
 * 排版阶段按「块」处理，图片永远不会被分页从中间截断。
 */
object BookMarkers {
    private const val PREFIX = "[[IMG:"
    private const val SUFFIX = "]]"

    fun imageLine(path: String): String = PREFIX + path + SUFFIX

    fun parseImageLine(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(PREFIX) || !trimmed.endsWith(SUFFIX)) return null
        val path = trimmed.substring(PREFIX.length, trimmed.length - SUFFIX.length)
        return path.takeIf { it.isNotBlank() }
    }
}
