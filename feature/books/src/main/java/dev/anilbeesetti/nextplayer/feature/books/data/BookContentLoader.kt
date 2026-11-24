package dev.anilbeesetti.nextplayer.feature.books.data

import android.content.Context
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.BookFormat
import dev.anilbeesetti.nextplayer.feature.books.format.ChapterSplitter
import dev.anilbeesetti.nextplayer.feature.books.format.EpubParser
import dev.anilbeesetti.nextplayer.feature.books.format.TextCodec
import dev.anilbeesetti.nextplayer.feature.books.model.BookContent
import dev.anilbeesetti.nextplayer.feature.books.model.ReaderChapter
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * 直接按白名单里的源文件加载正文，不复制到私有目录：
 * - TXT 现场探测编码并切章；
 * - EPUB 现场解包，图片解到 cacheDir/books/reader/<hash>/images。
 * 进程内最多缓存两本书，避免来回切换章节时反复读盘。
 */
@Single
class BookContentLoader(private val context: Context) {

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

    private fun loadTxt(file: File): BookContent? = runCatching {
        val decoded = TextCodec.decode(file.readBytes())
        if (decoded.text.isBlank()) return@runCatching null
        val fallbackTitle = file.nameWithoutExtension.ifBlank { file.name }
        val ranges = ChapterSplitter.split(text = decoded.text, fallbackTitle = fallbackTitle)
        val chapters = ranges.mapNotNull { range ->
            val start = range.start.coerceIn(0, decoded.text.length)
            val end = range.end.coerceIn(start, decoded.text.length)
            if (end <= start) return@mapNotNull null
            ReaderChapter(
                title = range.title,
                text = decoded.text.substring(start, end),
                startChar = start,
            )
        }
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
        File(context.cacheDir, "books/reader/${sha1(file.absolutePath)}").apply { mkdirs() }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
