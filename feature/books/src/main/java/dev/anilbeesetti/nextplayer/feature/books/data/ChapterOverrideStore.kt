package dev.anilbeesetti.nextplayer.feature.books.data

import android.content.Context
import dev.anilbeesetti.nextplayer.feature.books.format.ChapterSplitter
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

/**
 * 每本书「重新分章」后记住的目录覆盖。
 * 存在 filesDir 下（而非 cacheDir），杀进程/重开 App 都保留；
 * 只有用户再次执行重新分章时才会被覆盖。
 */
@Single
class ChapterOverrideStore(private val context: Context) {

    @Serializable
    internal data class StoredRange(val title: String, val start: Int, val end: Int)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(path: String): File =
        File(dir(), "${BookHashes.sha1(path)}.json").also { it.parentFile?.mkdirs() }

    private fun dir(): File = File(context.filesDir, "books/chapter_overrides").apply { mkdirs() }

    fun load(path: String): List<ChapterSplitter.Range>? {
        val file = fileFor(path)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<List<StoredRange>>(file.readText())
                .map { ChapterSplitter.Range(it.title, it.start, it.end) }
        }.getOrNull()
    }

    fun save(path: String, ranges: List<ChapterSplitter.Range>) {
        val stored = ranges.map { StoredRange(it.title, it.start, it.end) }
        runCatching { fileFor(path).writeText(json.encodeToString(stored)) }
    }

    fun clear(path: String) {
        runCatching { fileFor(path).delete() }
    }
}
