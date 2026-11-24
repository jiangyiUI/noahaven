package dev.anilbeesetti.nextplayer.feature.books.reader

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.Bookmark
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.books.data.BookContentLoader
import dev.anilbeesetti.nextplayer.feature.books.data.BooksPreferences
import dev.anilbeesetti.nextplayer.feature.books.model.BookContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
    @InjectedParam private val bookPath: String,
    @InjectedParam internal var output: Output,
) : MviViewModel<ReaderUiState, ReaderAction>() {

    private val stateInternal = MutableStateFlow(ReaderUiState())
    override val state: StateFlow<ReaderUiState> = stateInternal.asStateFlow()

    private var saveJob: Job? = null

    init {
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

            ReaderAction.SaveNow -> saveNow()
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

    data object SaveNow : ReaderAction
}
