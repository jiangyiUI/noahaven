package dev.anilbeesetti.nextplayer.feature.books.reader

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.Bookmark
import dev.anilbeesetti.nextplayer.core.model.ChapterRule
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.books.data.BookContentLoader
import dev.anilbeesetti.nextplayer.feature.books.data.BooksPreferences
import dev.anilbeesetti.nextplayer.feature.books.data.ChapterRuleDefaults
import dev.anilbeesetti.nextplayer.feature.books.data.ChapterRuleRepository
import dev.anilbeesetti.nextplayer.feature.books.model.BookContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class ReaderViewModel(
    private val bookFileIndex: BookFileIndex,
    private val contentLoader: BookContentLoader,
    private val preferences: BooksPreferences,
    private val preferencesRepository: PreferencesRepository,
    private val chapterRuleRepository: ChapterRuleRepository,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
    @InjectedParam private val bookPath: String,
    @InjectedParam internal var output: Output,
) : MviViewModel<ReaderUiState, ReaderAction>() {

    private val stateInternal = MutableStateFlow(ReaderUiState())
    override val state: StateFlow<ReaderUiState> = stateInternal.asStateFlow()

    /** 全部分章规则（内置 + 自建），供「重新分章」列表使用。 */
    val chapterRules: StateFlow<List<ChapterRule>> = chapterRuleRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前默认分章规则 id。 */
    val defaultRuleId: StateFlow<String> = chapterRuleRepository.defaultRuleId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterRuleDefaults.DEFAULT_RULE_ID)

    /** 每条规则对当前书试分章得到的章数（打开「重新分章」时计算）。空 map 表示尚未计算。 */
    private val _chapterCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val chapterCounts: StateFlow<Map<String, Int>> = _chapterCounts.asStateFlow()

    /**
     * 打开「重新分章」时调用：用全部规则对当前书全文各试分一次，把每条规则分出的章数写进 [chapterCounts]。
     * 全文只解码一次（在 [BookContentLoader.previewChapterCounts] 内）。
     */
    fun previewChapterCounts() {
        viewModelScope.launch {
            val allRules = chapterRuleRepository.rules.first()
            if (allRules.isEmpty()) return@launch
            _chapterCounts.value = contentLoader.previewChapterCounts(bookPath, allRules)
        }
    }

    private var saveJob: Job? = null

    init {
        viewModelScope.launch { chapterRuleRepository.ensureSeeded() }
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                stateInternal.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            preferences.customFonts.collect { fonts ->
                stateInternal.update { it.copy(customFonts = fonts) }
            }
        }
        viewModelScope.launch {
            preferences.customThemes.collect { themes ->
                stateInternal.update { it.copy(customThemes = themes) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                val list = prefs.bookBookmarks[bookPath].orEmpty().sortedBy { it.createdAt }
                stateInternal.update { it.copy(bookmarks = list) }
            }
        }
        viewModelScope.launch {
            val book = bookFileIndex.find(bookPath)
            if (book == null) {
                stateInternal.update {
                    it.copy(loading = false, error = ReaderError.BOOK_NOT_FOUND)
                }
                return@launch
            }
            bookFileIndex.touch(bookPath)
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(
                    lastViewedBookPath = bookPath,
                    bookLastViewedAt = prefs.bookLastViewedAt + (bookPath to System.currentTimeMillis()),
                )
            }
            val content = contentLoader.load(bookPath)
            val chapterIndex = content?.let {
                book.chapterIndex.coerceIn(0, it.chapters.lastIndex)
            } ?: book.chapterIndex
            val charOffset = content?.chapters?.getOrNull(chapterIndex)
                ?.let { book.charOffset.coerceIn(0, it.text.length) }
                ?: book.charOffset
            stateInternal.update {
                it.copy(
                    loading = false,
                    error = if (content == null) ReaderError.CONTENT_MISSING else null,
                    book = book,
                    content = content,
                    initialChapter = chapterIndex,
                    initialOffset = charOffset,
                    positionChapter = chapterIndex,
                    positionOffset = charOffset,
                    percent = book.percent,
                    finished = book.finished,
                )
            }
        }
    }

    override fun onAction(action: ReaderAction) {
        when (action) {
            is ReaderAction.UpdateSettings -> preferences.updateSettings(action.transform)

            is ReaderAction.OnPageChanged -> onPageChanged(action = action)

            is ReaderAction.ToggleBookmark -> toggleBookmark(action = action)

            is ReaderAction.RemoveBookmark -> removeBookmark(id = action.id)

            is ReaderAction.ImportFont -> importFont(uri = action.uri)

            is ReaderAction.RemoveFont -> preferences.deleteFont(action.path)

            is ReaderAction.SaveCustomTheme -> saveCustomTheme(action.theme)

            is ReaderAction.DeleteCustomTheme -> preferences.deleteCustomTheme(action.storageKey)

            is ReaderAction.PickThemeImage -> pickThemeImage(action.uri)

            ReaderAction.ClearPendingThemeImage ->
                stateInternal.update { it.copy(pendingThemeImagePath = null) }

            is ReaderAction.SaveChapterRule -> viewModelScope.launch {
                chapterRuleRepository.upsertCustomRule(action.rule, action.setAsDefault)
            }

            is ReaderAction.DeleteChapterRule -> viewModelScope.launch {
                chapterRuleRepository.deleteCustomRule(action.id)
            }

            is ReaderAction.SetDefaultChapterRule -> viewModelScope.launch {
                chapterRuleRepository.setDefaultRuleId(action.id)
            }

            is ReaderAction.Rechapter -> rechapter(ruleIds = action.ruleIds, onDone = action.onDone)

            ReaderAction.SaveNow -> saveNow()
        }
    }

    /**
     * 用指定规则（可多条，结果按行去重合并）重新分章，记住目录并刷新正文。
     * 重分前后保持阅读字符偏移，不丢进度。
     */
    private fun rechapter(ruleIds: List<String>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val old = stateInternal.value
            val oldContent = old.content
            val absolute = oldContent?.let {
                it.chapters.getOrNull(old.positionChapter)?.startChar
                    ?.plus(old.positionOffset)
            } ?: 0
            val newContent = contentLoader.rechapter(bookPath, ruleIds)
            if (newContent == null) {
                onDone(false)
                return@launch
            }
            val newChapter = newContent.chapterIndexAt(absolute)
            val chapterStart = newContent.chapters.getOrNull(newChapter)?.startChar ?: 0
            val newOffset = (absolute - chapterStart).coerceAtLeast(0)
            val percent = if (newContent.totalChars > 0) {
                (absolute.toFloat() / newContent.totalChars).coerceIn(0f, 1f)
            } else {
                0f
            }
            stateInternal.update {
                it.copy(
                    content = newContent,
                    positionChapter = newChapter,
                    positionOffset = newOffset,
                    initialChapter = newChapter,
                    initialOffset = newOffset,
                    percent = percent,
                )
            }
            // 映射后的新进度立刻落盘，避免重分后未翻页就杀进程导致进度错位。
            saveNow()
            onDone(true)
        }
    }

    private fun onPageChanged(action: ReaderAction.OnPageChanged) {
        val prefs = preferencesRepository.applicationPreferences.value
        val thresholdMet = action.percent >= prefs.bookWatchedThreshold / 100f
        val finished = stateInternal.value.finished || action.reachedEnd ||
            (prefs.enableWatchedMarks && thresholdMet)
        stateInternal.update {
            it.copy(
                positionChapter = action.chapterIndex,
                positionOffset = action.charOffset,
                percent = action.percent,
                finished = finished,
            )
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            bookFileIndex.saveProgress(
                path = bookPath,
                chapterIndex = action.chapterIndex,
                charOffset = action.charOffset,
                percent = action.percent,
                finished = finished,
            )
        }
    }

    /**
     * 当前页已有书签则删除，没有则新增。同一页由 chapterIndex + charOffset 判定。
     */
    private fun toggleBookmark(action: ReaderAction.ToggleBookmark) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                val current = prefs.bookBookmarks[bookPath].orEmpty()
                val existing = current.firstOrNull {
                    it.chapterIndex == action.chapterIndex && it.charOffset == action.charOffset
                }
                val updated = if (existing != null) {
                    current.filterNot { it.id == existing.id }
                } else {
                    val now = System.currentTimeMillis()
                    current + Bookmark(
                        id = now,
                        chapterIndex = action.chapterIndex,
                        charOffset = action.charOffset,
                        percent = action.percent,
                        chapterTitle = action.chapterTitle,
                        previewText = action.previewText,
                        createdAt = now,
                    )
                }
                prefs.copy(bookBookmarks = prefs.bookBookmarks + (bookPath to updated))
            }
        }
    }

    private fun removeBookmark(id: Long) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                val current = prefs.bookBookmarks[bookPath].orEmpty()
                val updated = current.filterNot { it.id == id }
                prefs.copy(bookBookmarks = prefs.bookBookmarks + (bookPath to updated))
            }
        }
    }

    private fun importFont(uri: Uri) {
        viewModelScope.launch {
            val path = preferences.importFont(uri) ?: return@launch
            // 导入成功后直接选中这个字体。
            preferences.updateSettings { it.copy(customFontPath = path) }
        }
    }

    private fun saveCustomTheme(theme: ReaderTheme.Custom) {
        preferences.saveCustomTheme(theme)
        // 保存后自动选中该主题（新建或编辑都生效）。
        preferences.updateSettings { current ->
            if (theme.isDark) current.copy(darkTheme = theme) else current.copy(lightTheme = theme)
        }
    }

    private fun pickThemeImage(uri: Uri) {
        viewModelScope.launch {
            val path = preferences.importBackgroundImage(uri) ?: return@launch
            stateInternal.update { it.copy(pendingThemeImagePath = path) }
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        saveImmediately()
    }

    private fun saveImmediately() {
        val current = stateInternal.value
        if (current.content == null) return
        // 返回/退出时 viewModelScope 会被取消，最后一次进度写到应用级作用域里。
        applicationScope.launch {
            bookFileIndex.saveProgress(
                path = bookPath,
                chapterIndex = current.positionChapter,
                charOffset = current.positionOffset,
                percent = current.percent,
                finished = current.finished,
            )
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 350L
    }

    data class Output(
        val onNavigateUp: () -> Unit,
    )
}

enum class ReaderError {
    BOOK_NOT_FOUND,
    CONTENT_MISSING,
}

data class ReaderUiState(
    val loading: Boolean = true,
    val error: ReaderError? = null,
    val book: BookFile? = null,
    val content: BookContent? = null,
    val settings: ReaderSettings = ReaderSettings(),
    /** 用户导入的字体列表。 */
    val customFonts: List<ReaderCustomFont> = emptyList(),
    /** 用户自建的阅读主题。 */
    val customThemes: List<ReaderTheme.Custom> = emptyList(),
    /** 选图导入完成后临时存放的新路径，交给编辑器消费后清零。 */
    val pendingThemeImagePath: String? = null,
    val initialChapter: Int = 0,
    val initialOffset: Int = 0,
    val positionChapter: Int = 0,
    val positionOffset: Int = 0,
    val percent: Float = 0f,
    val finished: Boolean = false,
    /** 当前书籍的书签，按创建时间升序。 */
    val bookmarks: List<Bookmark> = emptyList(),
)

sealed interface ReaderAction {
    data class UpdateSettings(val transform: (ReaderSettings) -> ReaderSettings) : ReaderAction

    data class OnPageChanged(
        val chapterIndex: Int,
        val charOffset: Int,
        val percent: Float,
        val reachedEnd: Boolean,
    ) : ReaderAction

    data class ToggleBookmark(
        val chapterIndex: Int,
        val charOffset: Int,
        val percent: Float,
        val chapterTitle: String,
        val previewText: String,
    ) : ReaderAction

    data class RemoveBookmark(val id: Long) : ReaderAction

    data class ImportFont(val uri: Uri) : ReaderAction

    data class RemoveFont(val path: String) : ReaderAction

    /** 新建或更新一个自定义主题；保存后自动选中它（按 isDark 归入对应组）。 */
    data class SaveCustomTheme(val theme: ReaderTheme.Custom) : ReaderAction

    /** 删除自定义主题（含背景图文件）；若正被使用则回落默认内置主题。 */
    data class DeleteCustomTheme(val storageKey: String) : ReaderAction

    /** 从系统相册选了一张背景图，导入到应用私有目录。 */
    data class PickThemeImage(val uri: Uri) : ReaderAction

    /** 编辑器已消费 pendingThemeImagePath，清零避免下次打开继承。 */
    data object ClearPendingThemeImage : ReaderAction

    /** 新建或更新一条自建分章规则。 */
    data class SaveChapterRule(val rule: ChapterRule, val setAsDefault: Boolean) : ReaderAction

    /** 删除一条自建分章规则。 */
    data class DeleteChapterRule(val id: String) : ReaderAction

    /** 把某条规则设为默认（新书打开时用它分章）。 */
    data class SetDefaultChapterRule(val id: String) : ReaderAction

    /** 用选中的规则（可多条合并）重新分章当前书；完成后回调成功与否。 */
    data class Rechapter(
        val ruleIds: List<String>,
        val onDone: (Boolean) -> Unit,
    ) : ReaderAction

    data object SaveNow : ReaderAction
}
