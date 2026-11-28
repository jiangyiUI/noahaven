package dev.anilbeesetti.nextplayer.settings.backup

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * reeden 完整备份（reeden_full_backup.zip）的阅读进度解析与匹配，纯 JVM 实现以便单测。
 *
 * 备份结构：外层 zip -> metadata.zip -> book.json。book.json 是书籍数组，进度字段：
 *  - read_progress：万分比 0..10000，10000=读完；0/缺失=未读
 *  - last_read_section_index / last_read_para_index / last_read_element_index：旧 app
 *    自己排版引擎的坐标，与本 app 的章节划分和字符偏移都对不上，同步时一律忽略，
 *    只按百分比定位（见 ApplicationPreferences.bookPendingPercent）
 *  - is_deleted：0=正常 1=删除
 */
object ReadingProgressBackupParser {

    /** 读完的万分比阈值（含）。 */
    const val FINISHED_PROGRESS = 10000

    /** 匹配接受线：低于该分的候选视为没匹配上。 */
    private const val ACCEPT_SCORE = 55

    /** LCS 相似度接受线。 */
    private const val LCS_THRESHOLD = 0.62f

    /** 短名禁用 LCS，避免两三字短名误配；长度至少 4 才走相似度。 */
    private const val LCS_MIN_LENGTH = 4

    private val json = Json { ignoreUnknownKeys = true }

    private val WHITESPACE_REGEX = Regex("""\s+""")

    /** 去数字时连带去掉的范围/分隔符号，让「秋辞1-19」与「秋辞」能归为同名。 */
    private val SENSITIVE_CHARS_REGEX = Regex("""[\d\s\-–—_·.・、:：()（）\[\]【】]+""")

    @Serializable
    data class LegacyProgressBook(
        val title: String = "",
        @SerialName("read_progress") val readProgress: Int? = null,
        @SerialName("is_deleted") val isDeleted: Int? = null,
        val type: String? = null,
    ) {
        /** 未被删除：is_deleted 缺失或为 0。 */
        val isActive: Boolean get() = isDeleted == null || isDeleted == 0
    }

    /** 一条可同步的旧 app 阅读进度，只保留百分比定位所需信息。 */
    data class LegacyProgressEntry(
        val title: String,
        /** 映射到本 app 的 0f..1f。 */
        val percent: Float,
        val finished: Boolean,
    )

    /** 本机书籍的匹配参照，由 BookFile 投影而来，保证解析器不依赖 Android 类型。 */
    data class LocalBookRef(
        val path: String,
        val title: String,
        val fileNameWithoutExtension: String,
        /** 本机是否已有阅读进度（lastOpenedAt>0），已有则同步时跳过避免覆盖。 */
        val hasProgress: Boolean,
    )

    data class MatchedProgress(
        val entry: LegacyProgressEntry,
        val local: LocalBookRef,
        val score: Int,
    )

    data class MatchResult(
        val matched: List<MatchedProgress>,
        val notFoundTitles: List<String>,
    )

    /** 容错解析 book.json 文本，损坏/为空返回空列表。 */
    fun parseBooksJson(jsonText: String?): List<LegacyProgressBook> =
        runCatching {
            jsonText?.let { json.decodeFromString<List<LegacyProgressBook>>(it) }
        }.getOrNull().orEmpty()

    /** 挑出有阅读进度的未删除书籍，并把万分比换算成本 app 的进度模型。 */
    fun selectProgressEntries(books: List<LegacyProgressBook>): List<LegacyProgressEntry> =
        books.mapNotNull { book ->
            val progress = book.readProgress ?: return@mapNotNull null
            if (!book.isActive || book.title.isBlank() || progress <= 0) return@mapNotNull null
            LegacyProgressEntry(
                title = book.title.trim(),
                percent = (progress.toFloat() / FINISHED_PROGRESS).coerceIn(0f, 1f),
                finished = progress >= FINISHED_PROGRESS,
            )
        }

    /**
     * 从备份文件字节里取出 book.json 文本：
     * zip（含嵌套 zip，如外层 reeden_full_backup.zip 内的 metadata.zip）递归查找 book.json；
     * 非 zip 时按原始 JSON 文本处理（兼容用户直接选解压后的 book.json）。
     */
    fun extractBookJson(bytes: ByteArray): String? {
        if (bytes.size < 2) return null
        val isZip = bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() // PK
        if (!isZip) {
            val text = bytes.toString(Charsets.UTF_8)
            return text.takeIf { it.contains("\"title\"") }
        }
        return findBookJsonInZip(bytes, depth = 0)
    }

    private fun findBookJsonInZip(bytes: ByteArray, depth: Int): String? {
        if (depth > 3) return null
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    when {
                        name.equals("book.json", ignoreCase = true) ->
                            return zis.readBytes().toString(Charsets.UTF_8)

                        name.endsWith(".zip", ignoreCase = true) -> {
                            val nested = zis.readBytes()
                            findBookJsonInZip(nested, depth + 1)?.let { return it }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }

    /**
     * 旧书与本机书一对一匹配：先算所有达标配对的分数，再按分数从高到低贪心占用，
     * 保证一本本机书最多被一条旧进度占用。
     */
    fun match(
        entries: List<LegacyProgressEntry>,
        locals: List<LocalBookRef>,
    ): MatchResult {
        data class Candidate(val local: LocalBookRef, val names: List<String>)

        val candidates = locals.map { local ->
            val names = listOf(local.title, local.fileNameWithoutExtension)
                .map(::normalize)
                .filter { it.isNotBlank() }
                .distinct()
            Candidate(local, names)
        }

        data class PairScore(val entryIndex: Int, val candidateIndex: Int, val score: Int)

        val pairs = ArrayList<PairScore>()
        entries.forEachIndexed { entryIndex, entry ->
            val entryName = normalize(entry.title)
            candidates.forEachIndexed { candidateIndex, candidate ->
                val best = candidate.names.maxOfOrNull { scoreBetween(entryName, it) } ?: 0
                if (best >= ACCEPT_SCORE) pairs += PairScore(entryIndex, candidateIndex, best)
            }
        }

        pairs.sortByDescending { it.score }
        val takenEntries = HashSet<Int>()
        val takenCandidates = HashSet<Int>()
        val matched = ArrayList<MatchedProgress>()
        for (pair in pairs) {
            if (pair.entryIndex in takenEntries || pair.candidateIndex in takenCandidates) continue
            takenEntries += pair.entryIndex
            takenCandidates += pair.candidateIndex
            matched += MatchedProgress(
                entry = entries[pair.entryIndex],
                local = candidates[pair.candidateIndex].local,
                score = pair.score,
            )
        }

        val notFound = entries.mapIndexedNotNull { index, entry ->
            entry.title.takeIf { index !in takenEntries }
        }
        return MatchResult(matched = matched, notFoundTitles = notFound)
    }

    /** 单名对单名评分：精确 100 / 去数字精确 88 / 包含 55..90 / LCS 相似度（封顶 87）。 */
    private fun scoreBetween(entry: String, candidate: String): Int {
        if (entry.isBlank() || candidate.isBlank()) return 0
        if (entry == candidate) return 100

        val entryStripped = stripSensitive(entry)
        val candidateStripped = stripSensitive(candidate)
        if (entryStripped.isNotBlank() && entryStripped == candidateStripped) return 88

        if (entry.length >= 2 && candidate.length >= 2 &&
            (entry in candidate || candidate in entry)
        ) {
            val shorter = minOf(entry.length, candidate.length)
            val longer = maxOf(entry.length, candidate.length)
            return 55 + 35 * shorter / longer
        }

        if (entry.length >= LCS_MIN_LENGTH && candidate.length >= LCS_MIN_LENGTH) {
            val ratio = lcsRatio(entry, candidate)
            if (ratio >= LCS_THRESHOLD) return (ratio * 100).toInt().coerceAtMost(87)
        }
        return 0
    }

    private fun normalize(name: String): String =
        name.trim().lowercase().replace(WHITESPACE_REGEX, "")

    private fun stripSensitive(name: String): String =
        name.replace(SENSITIVE_CHARS_REGEX, "")

    /** 最长公共子序列长度 / 较长串长度，0..1。 */
    private fun lcsRatio(a: String, b: String): Float {
        val dp = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var prev = 0
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) {
                    prev + 1
                } else {
                    maxOf(dp[j], dp[j - 1])
                }
                prev = temp
            }
        }
        val lcsLength = dp[b.length]
        return lcsLength.toFloat() / maxOf(a.length, b.length)
    }
}
