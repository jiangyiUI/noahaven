package dev.anilbeesetti.nextplayer.core.domain

/**
 * Intelligent search matcher that supports grep-style multi-word search.
 *
 * Ranking priority (highest to lowest):
 * 1. Exact phrase match (e.g., "stranger 2019" matches "stranger 2019")
 * 2. All words appear in order with gaps (e.g., "stranger 2019" matches "stranger things 2019")
 * 3. All words appear anywhere (e.g., "stranger 2019" matches "2019 stranger things")
 *
 * 视频、音乐、图片搜索共用同一套匹配与排序规则，保证三处搜索结果一致。
 */
class SearchMatcher(query: String) {
    private val queryLower = query.lowercase()
    private val queryWords = query.lowercase().split(regex = Regex(pattern = "\\s+")).filter {
        it.isNotBlank()
    }

    private val wordsInOrderPattern: Regex? = if (queryWords.size > 1) {
        Regex(
            queryWords.joinToString(separator = ".*") { Regex.escape(literal = it) },
            RegexOption.IGNORE_CASE,
        )
    } else {
        null
    }

    /**
     * Calculate a relevance score for the given texts.
     * Returns 0 if no match, higher scores for better matches.
     */
    fun calculateScore(vararg texts: String): Int {
        var bestScore = 0

        for (text in texts) {
            val textLower = text.lowercase()
            val score = calculateTextScore(textLower = textLower)
            if (score > bestScore) {
                bestScore = score
            }
        }

        return bestScore
    }

    private fun calculateTextScore(textLower: String): Int {
        // Level 1: Exact phrase match (highest priority)
        if (textLower.contains(other = queryLower)) {
            // Bonus for exact match at word boundary
            val wordBoundaryBonus = if (isWordBoundaryMatch(
                    text = textLower,
                    query = queryLower,
                )
            ) {
                50
            } else {
                0
            }
            // Bonus for match at start
            val startBonus = if (textLower.startsWith(prefix = queryLower)) 30 else 0
            return 1000 + wordBoundaryBonus + startBonus
        }

        // For single word queries, no further matching needed
        if (queryWords.size <= 1) {
            return 0
        }

        // Level 2: All words appear in order (with gaps allowed)
        // e.g., "stranger 2019" matches "stranger things 2019"
        wordsInOrderPattern?.let { pattern ->
            if (pattern.containsMatchIn(input = textLower)) {
                return 500
            }
        }

        // Level 3: All words appear somewhere in the text (any order)
        // e.g., "stranger 2019" matches "2019 stranger things"
        val allWordsPresent = queryWords.all { word -> textLower.contains(other = word) }
        if (allWordsPresent) {
            // Count how many words match at word boundaries for bonus
            val boundaryMatches = queryWords.count { word ->
                isWordBoundaryMatch(
                    text = textLower,
                    query = word,
                )
            }
            return 200 + (boundaryMatches * 20)
        }

        return 0
    }

    /**
     * Check if the query appears at a word boundary in the text.
     * Word boundaries are: start of string, space, underscore, dash, dot, etc.
     */
    private fun isWordBoundaryMatch(text: String, query: String): Boolean {
        val index = text.indexOf(string = query)
        if (index == -1) return false

        // Check if match is at start or preceded by word boundary
        val isAtStart = index == 0
        val isPrecededByBoundary = index > 0 && text[index - 1].isWordBoundary()

        return isAtStart || isPrecededByBoundary
    }

    private fun Char.isWordBoundary(): Boolean = this in listOf(
        ' ',
        '_',
        '-',
        '.',
        '/',
        '\\',
        '[',
        ']',
        '(',
        ')',
    )
}
