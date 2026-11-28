package dev.anilbeesetti.nextplayer.settings.backup

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对齐用户真实 reeden_full_backup.zip 的数据格式：
 * 外层 zip -> metadata.zip -> book.json，read_progress 为万分比，is_deleted 为 0/1。
 */
class ReadingProgressBackupParserTest {

    private val booksJson = """
        [
          {"id":"b1","title":"秋辞1-19","type":"txt","is_deleted":0,
           "read_progress":500,"last_read_section_index":1,
           "last_read_para_index":10,"last_read_element_index":35,
           "section_num":20,"word_count":12345},
          {"id":"b2","title":"贞观绿苒庄","type":"txt","is_deleted":0,
           "read_progress":10000,"last_read_section_index":126},
          {"id":"b3","title":"未读的书","type":"txt","is_deleted":0,
           "read_progress":0,"last_read_section_index":0},
          {"id":"b4","title":"没有进度字段","type":"epub","is_deleted":0},
          {"id":"b5","title":"已删除但有进度","type":"txt","is_deleted":1,
           "read_progress":8000,"last_read_section_index":5},
          {"id":"b6","title":"  带空格的标题  ","type":"txt","is_deleted":0,
           "read_progress":4,"last_read_section_index":0}
        ]
    """.trimIndent()

    @Test
    fun parseBooksJson_ignoresUnknownFieldsAndToleratesBrokenInput() {
        val books = ReadingProgressBackupParser.parseBooksJson(booksJson)
        assertEquals(6, books.size)
        assertEquals(500, books[0].readProgress)
        assertEquals("txt", books[0].type)
        // JSON 里的章节/段落坐标字段已不再建模，靠 ignoreUnknownKeys 静默忽略
        // 缺失字段走默认值，不抛异常
        assertNull(books[3].readProgress)
        assertTrue(ReadingProgressBackupParser.parseBooksJson("{broken").isEmpty())
        assertTrue(ReadingProgressBackupParser.parseBooksJson(null).isEmpty())
    }

    @Test
    fun selectProgressEntries_keepsOnlyActiveBooksWithProgressAndMapsFields() {
        val entries = ReadingProgressBackupParser.selectProgressEntries(
            ReadingProgressBackupParser.parseBooksJson(booksJson),
        )

        // b3 进度 0、b4 无字段、b5 已删除，都被过滤；剩 b1/b2/b6
        assertEquals(listOf("秋辞1-19", "贞观绿苒庄", "带空格的标题"), entries.map { it.title })

        val reading = entries.first { it.title == "秋辞1-19" }
        assertEquals(0.05f, reading.percent, 0.0001f)
        assertEquals(false, reading.finished)

        val finished = entries.first { it.title == "贞观绿苒庄" }
        assertEquals(1f, finished.percent, 0.0001f)
        assertEquals(true, finished.finished)
    }

    @Test
    fun match_exactAndDigitInsensitiveAndOneToOneAssignment() {
        val entries = ReadingProgressBackupParser.selectProgressEntries(
            ReadingProgressBackupParser.parseBooksJson(booksJson),
        )
        val locals = listOf(
            ref(path = "/books/秋辞.txt", title = "秋辞"),
            ref(path = "/books/贞观绿苒庄.txt", title = "贞观绿苒庄"),
            ref(path = "/books/带空格的标题.epub", title = "带空格的标题"),
        )

        val result = ReadingProgressBackupParser.match(entries, locals)

        assertEquals(3, result.matched.size)
        assertTrue(result.notFoundTitles.isEmpty())
        val byLegacy = result.matched.associateBy { it.entry.title }
        // 「秋辞1-19」去数字后与「秋辞」同名
        assertEquals("/books/秋辞.txt", byLegacy.getValue("秋辞1-19").local.path)
        assertEquals("/books/贞观绿苒庄.txt", byLegacy.getValue("贞观绿苒庄").local.path)
    }

    @Test
    fun match_unmatchedTitlesAreReportedAndNoLocalBookClaimedTwice() {
        val entries = ReadingProgressBackupParser.selectProgressEntries(
            ReadingProgressBackupParser.parseBooksJson(booksJson),
        )
        // 本机只有两本书，其中一本同时像两个旧书名时也只能被占用一次
        val locals = listOf(
            ref(path = "/books/秋辞.txt", title = "秋辞"),
            ref(path = "/books/ unrelated.txt", title = "完全不同的另一本书名在这里"),
        )

        val result = ReadingProgressBackupParser.match(entries, locals)

        assertEquals(1, result.matched.size)
        assertEquals("/books/秋辞.txt", result.matched.single().local.path)
        // 其余旧书全部进未匹配，且不含已匹配的书名
        assertEquals(2, result.notFoundTitles.size)
        assertTrue("秋辞1-19" !in result.notFoundTitles)
    }

    @Test
    fun match_skipsLocalBooksThatAlreadyHaveProgressViaRefFlag() {
        // hasProgress 只影响服务层写入决策，匹配本身仍应命中
        val entries = ReadingProgressBackupParser.selectProgressEntries(
            ReadingProgressBackupParser.parseBooksJson(booksJson),
        )
        val locals = listOf(
            ref(path = "/books/贞观绿苒庄.txt", title = "贞观绿苒庄", hasProgress = true),
        )

        val result = ReadingProgressBackupParser.match(entries, locals)

        assertEquals(1, result.matched.size)
        assertTrue(result.matched.single().local.hasProgress)
    }

    @Test
    fun extractBookJson_acceptsRawJsonText() {
        val extracted = ReadingProgressBackupParser.extractBookJson(booksJson.toByteArray())
        assertEquals(
            6,
            ReadingProgressBackupParser.parseBooksJson(extracted).size,
        )
    }

    @Test
    fun extractBookJson_recursesIntoNestedZipLikeRealBackup() {
        // 内层 metadata.zip：book.json + 无关文件
        val innerZip = zipBytes(
            "book.json" to booksJson.toByteArray(),
            "settings.json" to """{"x":1}""".toByteArray(),
        )
        // 外层备份：manifest.json + metadata.zip
        val outerZip = zipBytes(
            "manifest.json" to """{"format":"reeden_full_backup"}""".toByteArray(),
            "metadata.zip" to innerZip,
        )

        val extracted = ReadingProgressBackupParser.extractBookJson(outerZip)
        assertEquals(
            6,
            ReadingProgressBackupParser.parseBooksJson(extracted).size,
        )
    }

    @Test
    fun extractBookJson_returnsNullForGarbageAndZipWithoutBookJson() {
        assertNull(ReadingProgressBackupParser.extractBookJson("not a zip or json".toByteArray()))
        val zipWithoutBook = zipBytes("read_record.json" to "[]".toByteArray())
        assertNull(ReadingProgressBackupParser.extractBookJson(zipWithoutBook))
    }

    private fun ref(path: String, title: String, hasProgress: Boolean = false) =
        ReadingProgressBackupParser.LocalBookRef(
            path = path,
            title = title,
            fileNameWithoutExtension = path.substringAfterLast('/').substringBeforeLast('.'),
            hasProgress = hasProgress,
        )

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
