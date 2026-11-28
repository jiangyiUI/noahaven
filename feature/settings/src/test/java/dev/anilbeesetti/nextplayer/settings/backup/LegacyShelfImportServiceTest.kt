package dev.anilbeesetti.nextplayer.settings.backup

import dev.anilbeesetti.nextplayer.settings.backup.LegacyShelfImportService.LegacyBook
import dev.anilbeesetti.nextplayer.settings.backup.LegacyShelfImportService.LegacyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧 app（noahaven）book.json 的删除标记是 is_deleted（0/1），
 * 早期实现误判 deleted_at 字段，导致已删除的书全部漏进未匹配清单。
 * 这里锁定真实数据格式的解析与过滤行为。
 */
class LegacyShelfImportServiceTest {

    private val categoriesJson = """
        [
          {"id":"c1","name":"未分组","parent_id":null},
          {"id":"c2","name":"加料","parent_id":null},
          {"id":"c3","name":"长篇","parent_id":"c2"}
        ]
    """.trimIndent()

    /** 字段结构对齐用户真实导出的 book.json（含大量未知字段）。 */
    private val booksJson = """
        [
          {"id":"b1","title":"正常书","category_id":"c1","is_deleted":0,
           "is_local":1,"sync_title":"《正常书》01-10","url":"books/b1/b1","type":"txt"},
          {"id":"b2","title":"已删除的书","category_id":"c2","is_deleted":1,
           "is_local":1,"read_progress":10000,"url":"books/b2/b2","type":"txt"},
          {"id":"b3","title":"老格式没有删除字段","category_id":null,
           "is_local":0,"url":"books/b3/b3"},
          {"id":"b4","title":"deleted_at 非空的其他导出","category_id":"c3",
           "deleted_at":"2026-01-01 00:00:00"}
        ]
    """.trimIndent()

    @Test
    fun filtersOutBooksMarkedDeletedAndKeepsActiveOnes() {
        val parsed = LegacyShelfImportService.parseLegacyExport(categoriesJson, booksJson)
        val categories = parsed.first
        val activeBooks = parsed.second

        assertEquals(3, categories.size)
        assertEquals(listOf("b1", "b3"), activeBooks.map { it.id })
        // 已删除的两本（is_deleted=1 / deleted_at 非空）都不能进入导入预览
        assertFalse(activeBooks.any { it.id == "b2" })
        assertFalse(activeBooks.any { it.id == "b4" })
    }

    @Test
    fun isActive_matchesRealNoahavenFlagSemantics() {
        assertTrue(LegacyBook(id = "a", title = "t", isDeleted = 0).isActive)
        assertTrue(LegacyBook(id = "a", title = "t").isActive) // 字段缺失视为正常
        assertFalse(LegacyBook(id = "a", title = "t", isDeleted = 1).isActive)
        assertFalse(
            LegacyBook(id = "a", title = "t", isDeleted = 0, deletedAt = "2026-01-01").isActive,
        )
    }

    @Test
    fun unknownFieldsFromRealExportAreIgnored() {
        // 真实记录里还有 section_num/word_count/pin 等十几个字段，不能导致解析失败
        val books = LegacyShelfImportService.parseLegacyExport(
            categoriesJson = "[]",
            booksJson = """
                [{"id":"x","update_at":"2026-10-16 03:12:46","create_at":"2025-08-14T03:19:32.000Z",
                  "title":"禁忌之恋1-59","url":"books/x/x","section_num":60,"is_local":1,
                  "is_private":0,"charset":"utf-8","type":"txt","read_progress":4,
                  "sort_index":1024,"word_count":317326,"size":990895,"pin":0,
                  "read_round_no":1,"is_deleted":0}]
            """.trimIndent(),
        ).second

        assertEquals(1, books.size)
        assertEquals("禁忌之恋1-59", books.single().title)
    }

    @Test
    fun deletedBookWithAllRealWorldFieldsIsExcluded() {
        val books = LegacyShelfImportService.parseLegacyExport(
            categoriesJson = "[]",
            booksJson = """
                [{"id":"d","title":"我成了父亲与妻子的月老续写（深绿版）",
                  "url":"books/d/d","section_num":41,"is_local":1,"is_private":0,
                  "charset":"utf-8","type":"txt","read_progress":10,"pin":0,
                  "read_round_no":1,"is_deleted":1}]
            """.trimIndent(),
        ).second

        assertTrue(books.isEmpty())
    }

    @Test
    fun malformedOrEmptyInputYieldsEmptyListsInsteadOfCrashing() {
        val broken = LegacyShelfImportService.parseLegacyExport("{broken", "[")
        assertTrue(broken.first.isEmpty())
        assertTrue(broken.second.isEmpty())

        val empty = LegacyShelfImportService.parseLegacyExport(null, null)
        assertTrue(empty.first.isEmpty())
        assertTrue(empty.second.isEmpty())
    }

    @Test
    fun categoriesWithParentChainsAreParsedUnchanged() {
        val categories = LegacyShelfImportService.parseLegacyExport(categoriesJson, "[]").first
        val byId = categories.associateBy(LegacyCategory::id)

        assertEquals("长篇", byId["c3"]?.name)
        assertEquals("c2", byId["c3"]?.parentId)
        assertEquals(null, byId["c2"]?.parentId)
    }
}
