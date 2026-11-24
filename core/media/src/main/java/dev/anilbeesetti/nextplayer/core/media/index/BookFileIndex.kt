package dev.anilbeesetti.nextplayer.core.media.index

import dev.anilbeesetti.nextplayer.core.model.BookFile
import kotlinx.coroutines.flow.StateFlow

/** 书籍白名单索引。与漫画库一致：扫描白名单目录下的 txt/epub，索引入库。 */
interface BookFileIndex {
    val entries: StateFlow<List<BookFile>?>

    fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        force: Boolean = false,
    )

    /** 立即刷新并挂起直到本次扫描完成：下拉刷新用，不受自动刷新的节流间隔限制。 */
    suspend fun refreshNow(whitelistRoots: Collection<String>, folderPath: String?)

    suspend fun find(path: String): BookFile?

    /** 保存阅读进度（章节 + 章节内偏移 + 百分比 + 看完标记）。 */
    suspend fun saveProgress(
        path: String,
        chapterIndex: Int,
        charOffset: Int,
        percent: Float,
        finished: Boolean,
    )

    /** 标记打开过（即使没翻页也记录最近阅读时间）。 */
    suspend fun touch(path: String)

    suspend fun setFinished(path: String, finished: Boolean)

    /** 重命名/移动后，把进度记录挪到新路径。 */
    suspend fun moveProgress(fromPath: String, toPath: String)

    /** 删除单本书的阅读进度。 */
    suspend fun deleteProgress(path: String)
}
