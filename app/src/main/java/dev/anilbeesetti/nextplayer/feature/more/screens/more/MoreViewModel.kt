package dev.anilbeesetti.nextplayer.feature.more.screens.more

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class MoreViewModel(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
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

            is MoreAction.OpenSettings -> output.openSettings()

            is MoreAction.OpenTrash -> output.openTrash()

            is MoreAction.OpenVault -> output.openVault()

            is MoreAction.OpenPlaylists -> output.openPlaylists()

            is MoreAction.OpenNetwork -> output.openNetwork()

            is MoreAction.ToggleSelection -> toggleSelection(uriString = action.uriString)

            is MoreAction.SelectAll -> stateInternal.update { state ->
                state.copy(
                    selectedUris = state.history.result.orEmpty().map { it.uriString }.toSet(),
                )
            }

            is MoreAction.ClearSelection -> stateInternal.update {
                it.copy(selectedUris = emptySet())
            }

            is MoreAction.DeleteSelected -> deleteSelected()

            is MoreAction.ClearHistory -> clearHistory()
        }
    }

    private fun toggleSelection(uriString: String) {
        stateInternal.update { state ->
            val selected = state.selectedUris
            state.copy(
                selectedUris = if (uriString in selected) {
                    selected - uriString
                } else {
                    selected + uriString
                },
            )
        }
    }

    // 只删除选中的历史记录，不动文件
    private fun deleteSelected() {
        val uris = stateInternal.value.selectedUris.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            mediaRepository.removePlaybackHistory(uris = uris)
            stateInternal.update { it.copy(selectedUris = emptySet()) }
        }
    }

    private fun clearHistory() {
        viewModelScope.launch { mediaRepository.clearPlaybackHistory() }
    }

    data class Output(
        val playVideo: (Video) -> Unit,
        val playUri: (String) -> Unit,
        val openSettings: () -> Unit,
        val openTrash: () -> Unit,
        val openVault: () -> Unit,
        val openPlaylists: () -> Unit,
        val openNetwork: () -> Unit,
    )
}

data class MoreUiState(
    val history: DataState<List<Video>> = DataState.Loading,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val selectedUris: Set<String> = emptySet(),
)

sealed interface MoreAction {
    data class PlayVideo(val video: Video) : MoreAction
    data class PlayUri(val uri: String) : MoreAction
    data object OpenSettings : MoreAction
    data object OpenTrash : MoreAction
    data object OpenVault : MoreAction
    data object OpenPlaylists : MoreAction
    data object OpenNetwork : MoreAction

    data class ToggleSelection(val uriString: String) : MoreAction
    data object SelectAll : MoreAction
    data object ClearSelection : MoreAction
    data object DeleteSelected : MoreAction

    data object ClearHistory : MoreAction
}
