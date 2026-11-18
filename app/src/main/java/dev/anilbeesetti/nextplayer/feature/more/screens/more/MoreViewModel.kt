package dev.anilbeesetti.nextplayer.feature.more.screens.more

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class MoreViewModel(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val comicFileIndex: ComicFileIndex,
    @InjectedParam internal var output: Output,
) : MviViewModel<MoreUiState, MoreAction>() {

    private val stateInternal = MutableStateFlow(value = MoreUiState())
    override val state: StateFlow<MoreUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            mediaRepository.observePlaybackHistory()
                .catch { error ->
                    stateInternal.update { it.copy(history = DataState.Error(value = error)) }
                }
                .collect { history ->
                    stateInternal.update { it.copy(history = DataState.Success(value = history)) }
                }
        }
        // 漫画阅读记录和视频历史混排展示：进度表按最近阅读时间倒序
        // 同时跟随索引变化重算，重命名/移动后进度换了路径也能立刻找到对应漫画
        viewModelScope.launch {
            combine(
                comicFileIndex.observeProgress(),
                comicFileIndex.entries,
            ) { progresses, _ -> progresses }
                .catch {
                    stateInternal.update { it.copy(comicHistory = emptyList()) }
                }
                .collect { progresses ->
                    val entries = progresses.mapNotNull { progress ->
                        comicFileIndex.find(progress.path)?.let { comic ->
                            ComicHistoryEntry(comic = comic, progress = progress)
                        }
                    }
                    stateInternal.update { it.copy(comicHistory = entries) }
                }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                stateInternal.update { it.copy(preferences = preferences) }
            }
        }
    }

    override fun onAction(action: MoreAction) {
        when (action) {
            is MoreAction.PlayVideo -> output.playVideo(action.video)

            is MoreAction.PlayUri -> output.playUri(action.uri)

            is MoreAction.OpenComic -> output.openComic(action.path)

            is MoreAction.OpenSettings -> output.openSettings()

            is MoreAction.OpenVault -> output.openVault()

            is MoreAction.OpenPlaylists -> output.openPlaylists()

            is MoreAction.OpenNetwork -> output.openNetwork()

            is MoreAction.ToggleSelection -> toggleSelection(key = action.key)

            is MoreAction.SelectAll -> stateInternal.update { state ->
                state.copy(
                    selectedUris = state.history.result.orEmpty().map { it.uriString }.toSet() +
                        state.comicHistory.map { it.comic.path },
                )
            }

            is MoreAction.ClearSelection -> stateInternal.update {
                it.copy(selectedUris = emptySet())
            }

            is MoreAction.DeleteSelected -> deleteSelected()

            is MoreAction.ClearHistory -> clearHistory()
        }
    }

    private fun toggleSelection(key: String) {
        stateInternal.update { state ->
            val selected = state.selectedUris
            state.copy(
                selectedUris = if (key in selected) {
                    selected - key
                } else {
                    selected + key
                },
            )
        }
    }

    // 只删除选中的历史记录，不动文件
    private fun deleteSelected() {
        val state = stateInternal.value
        val selected = state.selectedUris
        if (selected.isEmpty()) return
        val comicPaths = state.comicHistory.mapTo(HashSet()) { it.comic.path }
        val comicSelection = selected.filter { it in comicPaths }
        val videoSelection = selected.filter { it !in comicPaths }
        viewModelScope.launch {
            if (videoSelection.isNotEmpty()) {
                mediaRepository.removePlaybackHistory(uris = videoSelection)
            }
            comicSelection.forEach { comicFileIndex.deleteProgress(it) }
            stateInternal.update { it.copy(selectedUris = emptySet()) }
        }
    }

    private fun clearHistory() {
        viewModelScope.launch {
            mediaRepository.clearPlaybackHistory()
            comicFileIndex.clearProgress()
        }
    }

    data class Output(
        val playVideo: (Video) -> Unit,
        val playUri: (String) -> Unit,
        val openComic: (String) -> Unit,
        val openSettings: () -> Unit,
        val openVault: () -> Unit,
        val openPlaylists: () -> Unit,
        val openNetwork: () -> Unit,
    )
}

data class MoreUiState(
    val history: DataState<List<Video>> = DataState.Loading,
    val comicHistory: List<ComicHistoryEntry> = emptyList(),
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val selectedUris: Set<String> = emptySet(),
) {
    /** 历史记录总条数（视频/音频 + 漫画）。 */
    val historySize: Int get() = history.result.orEmpty().size + comicHistory.size
}

/** 「更多」页历史列表里的一条漫画阅读记录。 */
data class ComicHistoryEntry(
    val comic: ComicFile,
    val progress: ComicProgress,
)

sealed interface MoreAction {
    data class PlayVideo(val video: Video) : MoreAction
    data class PlayUri(val uri: String) : MoreAction
    data class OpenComic(val path: String) : MoreAction
    data object OpenSettings : MoreAction
    data object OpenVault : MoreAction
    data object OpenPlaylists : MoreAction
    data object OpenNetwork : MoreAction

    data class ToggleSelection(val key: String) : MoreAction
    data object SelectAll : MoreAction
    data object ClearSelection : MoreAction
    data object DeleteSelected : MoreAction

    data object ClearHistory : MoreAction
}
