package dev.anilbeesetti.nextplayer.feature.books.data

import android.content.Context
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.BookFormat
import dev.anilbeesetti.nextplayer.core.model.ChapterRule
import dev.anilbeesetti.nextplayer.feature.books.format.ChapterSplitter
import dev.anilbeesetti.nextplayer.feature.books.format.EpubParser
import dev.anilbeesetti.nextplayer.feature.books.format.TextCodec
import dev.anilbeesetti.nextplayer.feature.books.model.BookContent
import dev.anilbeesetti.nextplayer.feature.books.model.ReaderChapter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * 直接按白名单里的源文件加载正文，不复制到私有目录：
 * - TXT 现场探测编码并切章（规则驱动 + 每书记住的目录覆盖）；
 * - EPUB 现场解包，图片解到 cacheDir/books/reader/<hash>/images。
 * 进程内最多缓存两本书，避免来回切换章节时反复读盘。
 */
@Single
class BookContentLoader(
    private val context: Context,
    private val chapterRuleRepository: ChapterRuleRepository,
    private val overrideStore: ChapterOverrideStore,
) {

    private val epubParser = EpubParser()

    private val contentCache = object : LinkedHashMap<String, BookContent>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookContent>?): Boolean =
            size > 2
    }

    suspend fun load(path: String, forceReload: Boolean = false): BookContent? =
        withContext(Dispatchers.IO) {
            if (!forceReload) {
                synchronized(contentCache) { contentCache[path] }?.let { return@withContext it }
            }
            val file = File(path)
            if (!file.exists() || !file.isFile) return@withContext null
            val content = when (BookFormat.fromPath(path)) {
                BookFormat.EPUB -> loadEpub(file)
                BookFormat.TXT -> loadTxt(file)
            } ?: return@withContext null
            synchronized(contentCache) { contentCache[path] = content }
            content
        }

    fun forget(path: String) {
        synchronized(contentCache) { contentCache.remove(path) }
    }

    /**
     * 用指定规则（可多条，结果按行去重合并）重新分章，记住这份目录并重新加载。
     * 选不到标题时自动回退到默认规则 → 内置常规模式 → 3 万字硬分段。
     */
    suspend fun rechapter(path: String, ruleIds: List<String>): BookContent? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.isFile) return@withContext null
            val decoded = TextCodec.decode(file.readBytes())
            if (decoded.text.isBlank()) return@withContext null
            val fallbackTitle = file.nameWithoutExtension.ifBlank { file.name }
            val rules = ruleIds.mapNotNull { chapterRuleRepository.ruleById(it) }
            val ranges = splitWithFallback(
                text = decoded.text,
                fallbackTitle = fallbackTitle,
                primaryRules = rules,
            )
            overrideStore.save(path, ranges)
            forget(path)
            load(path, forceReload = true)
        }

    /**
     * 用每条规则单独对 [path] 指向的 TXT 全文试分章，返回 ruleId -> 分出的章数。
     * 全文只解码一次，供「重新分章」打开时预览各规则效果。非 TXT / 读不到时返回空。
     */
    suspend fun previewChapterCounts(path: String, rules: List<ChapterRule>): Map<String, Int> =
        withContext(Dispatchers.IO) {
            if (rules.isEmpty()) return@withContext emptyMap()
            val text = runCatching {
                TextCodec.decode(File(path).readBytes()).text
            }.getOrNull() ?: return@withContext emptyMap()
            rules.associate { rule ->
                rule.id to (ChapterSplitter.splitWithHeadings(text, listOf(rule))?.size ?: 0)
            }
        }

    private suspend fun loadTxt(file: File): BookContent? = runCatching {
        val decoded = TextCodec.decode(file.readBytes())
        if (decoded.text.isBlank()) return@runCatching null
        val fallbackTitle = file.nameWithoutExtension.ifBlank { file.name }
        val ranges = chapterRangesFor(file.absolutePath, decoded.text, fallbackTitle)
        val chapters = rangesToChapters(decoded.text, ranges)
        if (chapters.isEmpty()) return@runCatching null
        BookContent(
            path = file.absolutePath,
            title = fallbackTitle,
            author = "",
            chapters = chapters,
            totalChars = decoded.text.length.coerceAtLeast(1),
            bookDir = cacheDirFor(file),
            coverFile = null,
        )
    }.getOrNull()

    private suspend fun chapterRangesFor(
        path: String,
        text: String,
        fallbackTitle: String,
    ): List<ChapterSplitter.Range> {
        // 1. 这本书若被「重新分章」记住过目录，直接用。
        overrideStore.load(path)?.let { return it }
        // 2. 否则用默认规则分章。
        val defaultRule = runCatching { chapterRuleRepository.defaultRule() }.getOrNull()
        return splitWithFallback(
            text = text,
            fallbackTitle = fallbackTitle,
            primaryRules = listOfNotNull(defaultRule),
        )
    }

    /** 主规则分不到标题 → 内置常规模式兜底 → 仍无则 3 万字硬分段。 */
    private suspend fun splitWithFallback(
        text: String,
        fallbackTitle: String,
        primaryRules: List<ChapterRule>,
    ): List<ChapterSplitter.Range> {
        if (primaryRules.isNotEmpty()) {
            ChapterSplitter.splitWithHeadings(text, primaryRules)?.let { return it }
        }
        val fallback = chapterRuleRepository.ruleById(ChapterRuleDefaults.FALLBACK_RULE_ID)
        if (fallback != null) {
            ChapterSplitter.splitWithHeadings(text, listOf(fallback))?.let { return it }
        }
        return ChapterSplitter.split(text = text, fallbackTitle = fallbackTitle)
    }

    private fun rangesToChapters(
        text: String,
        ranges: List<ChapterSplitter.Range>,
    ): List<ReaderChapter> = ranges.mapNotNull { range ->
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(start, text.length)
        if (end <= start) return@mapNotNull null
        ReaderChapter(
            title = range.title,
            text = text.substring(start, end),
            startChar = start,
        )
    }

    private fun loadEpub(file: File): BookContent? = runCatching {
        val bookDir = cacheDirFor(file)
        val imagesDir = File(bookDir, "images").apply { mkdirs() }
        val parsed = epubParser.parse(file) { name, bytes ->
            runCatching {
                File(imagesDir, name).writeBytes(bytes)
                "images/$name"
            }.getOrNull()
        }
        if (parsed.chapters.isEmpty()) return@runCatching null
        var offset = 0
        val chapters = parsed.chapters.map { chapter ->
            val text = chapter.text.trimEnd('\n', ' ')
            val chapterStart = offset
            offset += text.length + 1
            ReaderChapter(title = chapter.title, text = text, startChar = chapterStart)
        }
        BookContent(
            path = file.absolutePath,
            title = parsed.title.ifBlank { file.nameWithoutExtension },
            author = parsed.author,
            chapters = chapters,
            totalChars = offset.coerceAtLeast(1),
            bookDir = bookDir,
            coverFile = parsed.coverBytes?.let { bytes ->
                runCatching {
                    val cover = File(bookDir, parsed.coverFileName ?: "cover.jpg")
                    cover.writeBytes(bytes)
                    cover
                }.getOrNull()
            },
        )
    }.getOrNull()

    private fun cacheDirFor(file: File): File =
        File(context.cacheDir, "books/reader/${BookHashes.sha1(file.absolutePath)}").apply { mkdirs() }
}
