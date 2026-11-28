package dev.anilbeesetti.nextplayer.settings.backup

import android.content.Context
import android.net.Uri
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * 从 reeden 完整备份同步阅读进度到本机书籍：
 * 1. 读取用户选的 zip/json（SAF Uri），递归解出 book.json；
 * 2. 只取「阅读中 / 已读完」（read_progress>0 且未删除）的书；
 * 3. 按书名与本机白名单书籍模糊匹配（一对一），本机已有进度的跳过，避免覆盖新进度；
 * 4. 确认后把百分比、读完标记写入 BookFileIndex（保证书架状态/进度条正确），
 *    同时在偏好里登记「待定位百分比」；阅读器首次打开该书时按全书百分比换算落点，
 *    定位后自动清除登记，不依赖旧 app 的章节划分。
 */
@Single
class ReadingProgressImportService(
    private val context: Context,
    private val bookFileIndex: BookFileIndex,
    private val preferencesRepository: PreferencesRepository,
) {

    /** 预览里的一条匹配：旧书 -> 本机书。 */
    data class ProgressPreviewItem(
        val legacyTitle: String,
        val localPath: String,
        val localTitle: String,
        val percent: Float,
        val finished: Boolean,
        /** 本机这本书已经有阅读进度，确认同步时会跳过。 */
        val alreadyHasProgress: Boolean,
    )

    data class ReadingProgressPreview(
        val totalWithProgress: Int,
        val finishedCount: Int,
        val items: List<ProgressPreviewItem>,
        val notFoundTitles: List<String>,
    ) {
        val matchedCount: Int get() = items.size
        val skippedCount: Int get() = items.count { it.alreadyHasProgress }
        val writableCount: Int get() = items.count { !it.alreadyHasProgress }
    }

    /** 读备份并生成预览；文件无法识别/没有 book.json 时返回 null。 */
    suspend fun readAndPreview(uri: Uri): ReadingProgressPreview? = withContext(Dispatchers.IO) {
        val bytes = readBytes(uri) ?: return@withContext null
        val booksJson = ReadingProgressBackupParser.extractBookJson(bytes) ?: return@withContext null

        val books = ReadingProgressBackupParser.parseBooksJson(booksJson)
        val entries = ReadingProgressBackupParser.selectProgressEntries(books)
        if (entries.isEmpty()) {
            return@withContext ReadingProgressPreview(
                totalWithProgress = 0,
                finishedCount = 0,
                items = emptyList(),
                notFoundTitles = emptyList(),
            )
        }

        val localBooks = bookFileIndex.entries.filterNotNull().first()
        val locals = localBooks.map { book ->
            ReadingProgressBackupParser.LocalBookRef(
                path = book.path,
                title = book.title,
                fileNameWithoutExtension = book.displayName.substringBeforeLast('.'),
                hasProgress = book.lastOpenedAt > 0L,
            )
        }

        val matchResult = ReadingProgressBackupParser.match(entries, locals)
        val items = matchResult.matched.map { matched ->
            ProgressPreviewItem(
                legacyTitle = matched.entry.title,
                localPath = matched.local.path,
                localTitle = matched.local.title,
                percent = matched.entry.percent,
                finished = matched.entry.finished,
                alreadyHasProgress = matched.local.hasProgress,
            )
        }
        ReadingProgressPreview(
            totalWithProgress = entries.size,
            finishedCount = entries.count { it.finished },
            items = items,
            notFoundTitles = matchResult.notFoundTitles,
        )
    }

    /** 写入所有「本机尚无进度」的匹配项，返回实际写入条数。 */
    suspend fun applyImport(preview: ReadingProgressPreview): Int = withContext(Dispatchers.IO) {
        val pending = HashMap<String, Float>()
        var written = 0
        preview.items.forEach { item ->
            if (item.alreadyHasProgress) return@forEach
            // 章节/偏移先落 0：真正的落点由首次打开时的待定位百分比换算得到
            bookFileIndex.saveProgress(
                path = item.localPath,
                chapterIndex = 0,
                charOffset = 0,
                percent = item.percent,
                finished = item.finished,
            )
            pending[item.localPath] = item.percent
            written++
        }
        if (pending.isNotEmpty()) {
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(bookPendingPercent = prefs.bookPendingPercent + pending)
            }
        }
        written
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
}
