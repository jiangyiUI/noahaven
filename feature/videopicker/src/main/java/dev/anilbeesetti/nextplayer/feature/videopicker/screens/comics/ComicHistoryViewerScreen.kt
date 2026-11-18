package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ComicArchiveReader
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/**
 * 从「更多」页历史记录直接打开某本漫画。与漫画库里的查看器共用 [ComicViewer]，
 * 进度、翻页方向、最近阅读记录都按同一套规则保存。
 */
@KoinViewModel
class ComicHistoryViewerViewModel(
    private val comicFileIndex: ComicFileIndex,
    private val preferencesRepository: PreferencesRepository,
    @InjectedParam private val path: String,
) : ViewModel() {

    data class UiState(
        val path: String = "",
        val loading: Boolean = true,
        val entries: List<String> = emptyList(),
        val initialIndex: Int = 0,
        val direction: ComicReadingDirection = ComicReadingDirection.RIGHT_TO_LEFT,
    )

    private val stateInternal = MutableStateFlow(UiState(path = path))
    val state: StateFlow<UiState> = stateInternal.asStateFlow()

    private var lastPage: Int = 0

    init {
        viewModelScope.launch {
            val entries = ComicArchiveReader.listImageEntryNames(path)
            val progress = comicFileIndex.getProgress(path)
            lastPage = progress?.lastPage?.coerceIn(0, maxOf(entries.lastIndex, 0)) ?: 0
            stateInternal.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    initialIndex = lastPage,
                    direction = progress?.readingDirection
                        ?: ComicReadingDirection.RIGHT_TO_LEFT,
                )
            }
            preferencesRepository.updateApplicationPreferences { preferences ->
                preferences.copy(lastViewedComicPath = path)
            }
        }
    }

    fun onProgressChanged(page: Int, direction: ComicReadingDirection) {
        lastPage = page
        saveProgress(page = page, direction = direction)
    }

    fun onDirectionChange(direction: ComicReadingDirection) {
        stateInternal.update { it.copy(direction = direction) }
        saveProgress(page = lastPage, direction = direction)
    }

    private fun saveProgress(page: Int, direction: ComicReadingDirection) {
        viewModelScope.launch {
            comicFileIndex.saveProgress(
                ComicProgress(
                    path = path,
                    lastPage = page,
                    readingDirection = direction,
                ),
            )
        }
    }
}

@Composable
fun ComicHistoryViewerScreen(
    viewModel: ComicHistoryViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BindTopLevelBottomBarVisible(visible = false)

    LaunchedEffect(state.loading, state.entries.isEmpty()) {
        if (!state.loading && state.entries.isEmpty()) onBack()
    }

    if (state.loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (state.entries.isNotEmpty()) {
        ComicViewer(
            images = ComicImageList(
                zipPath = state.path,
                entries = state.entries,
                readingDirection = state.direction,
            ),
            initialIndex = state.initialIndex,
            readingDirection = state.direction,
            onProgressChanged = viewModel::onProgressChanged,
            onDirectionChange = viewModel::onDirectionChange,
            onBack = onBack,
        )
    }
}
