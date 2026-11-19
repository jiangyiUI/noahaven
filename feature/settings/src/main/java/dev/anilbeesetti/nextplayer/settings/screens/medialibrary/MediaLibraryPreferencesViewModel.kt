package dev.anilbeesetti.nextplayer.settings.screens.medialibrary

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class MediaLibraryPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val mediaRepository: MediaRepository,
    @InjectedParam internal var output: Output,
) : MviViewModel<MediaLibraryPreferencesUiState, MediaLibraryPreferencesUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val openFolders: () -> Unit,
        val openAudioFolders: () -> Unit,
        val openImageFolders: () -> Unit,
        val openComicFolders: () -> Unit,
        val openThumbnails: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(value = MediaLibraryPreferencesUiState())
    override val state: StateFlow<MediaLibraryPreferencesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect {
                stateInternal.update { currentState -> currentState.copy(preferences = it) }
            }
        }
    }

    override fun onAction(action: MediaLibraryPreferencesUiEvent) {
        when (action) {
            is MediaLibraryPreferencesUiEvent.NavigateUp -> output.navigateUp()

            is MediaLibraryPreferencesUiEvent.OpenFolders -> output.openFolders()

            is MediaLibraryPreferencesUiEvent.OpenAudioFolders -> output.openAudioFolders()

            is MediaLibraryPreferencesUiEvent.OpenImageFolders -> output.openImageFolders()

            is MediaLibraryPreferencesUiEvent.OpenComicFolders -> output.openComicFolders()

            is MediaLibraryPreferencesUiEvent.OpenThumbnails -> output.openThumbnails()

            is MediaLibraryPreferencesUiEvent.ToggleMarkLastPlayedMedia ->
                toggleMarkLastPlayedMedia()

            is MediaLibraryPreferencesUiEvent.ToggleIgnoreNoMedia ->
                toggleIgnoreNoMedia()

            is MediaLibraryPreferencesUiEvent.SetWatchHistoryEnabled -> setWatchHistoryEnabled(
                action.enabled,
            )

            is MediaLibraryPreferencesUiEvent.ToggleIncludeNetworkWatchHistory ->
                toggleIncludeNetworkWatchHistory()

            is MediaLibraryPreferencesUiEvent.UpdateNewVideoThreshold -> updateNewVideoThreshold(
                action.days,
            )

            is MediaLibraryPreferencesUiEvent.ToggleEnableWatchedMarks ->
                toggleEnableWatchedMarks()

            is MediaLibraryPreferencesUiEvent.UpdateVideoWatchedThreshold ->
                updateVideoWatchedThreshold(action.threshold)

            is MediaLibraryPreferencesUiEvent.UpdateComicWatchedThreshold ->
                updateComicWatchedThreshold(action.threshold)
        }
    }

    private fun toggleMarkLastPlayedMedia() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(markLastPlayedMedia = !it.markLastPlayedMedia)
            }
        }
    }

    private fun toggleIgnoreNoMedia() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(ignoreNoMedia = !it.ignoreNoMedia)
            }
        }
    }

    private fun setWatchHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(isHistoryPaused = !enabled)
            }
            if (!enabled) mediaRepository.clearPlaybackHistory()
        }
    }

    private fun toggleIncludeNetworkWatchHistory() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(includeNetworkWatchHistory = !it.includeNetworkWatchHistory)
            }
        }
    }

    private fun updateNewVideoThreshold(days: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    newVideoThresholdDays = days.coerceIn(
                        minimumValue = 0,
                        maximumValue = 30,
                    ),
                )
            }
        }
    }

    private fun toggleEnableWatchedMarks() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(enableWatchedMarks = !it.enableWatchedMarks)
            }
        }
    }

    private fun updateVideoWatchedThreshold(threshold: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(videoWatchedThreshold = threshold.coerceIn(50, 100))
            }
        }
    }

    private fun updateComicWatchedThreshold(threshold: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(comicWatchedThreshold = threshold.coerceIn(50, 100))
            }
        }
    }
}

data class MediaLibraryPreferencesUiState(
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface MediaLibraryPreferencesUiEvent {
    data object NavigateUp : MediaLibraryPreferencesUiEvent
    data object OpenFolders : MediaLibraryPreferencesUiEvent
    data object OpenAudioFolders : MediaLibraryPreferencesUiEvent
    data object OpenImageFolders : MediaLibraryPreferencesUiEvent
    data object OpenComicFolders : MediaLibraryPreferencesUiEvent
    data object OpenThumbnails : MediaLibraryPreferencesUiEvent

    data object ToggleMarkLastPlayedMedia : MediaLibraryPreferencesUiEvent
    data object ToggleIgnoreNoMedia : MediaLibraryPreferencesUiEvent
    data class SetWatchHistoryEnabled(val enabled: Boolean) : MediaLibraryPreferencesUiEvent
    data object ToggleIncludeNetworkWatchHistory : MediaLibraryPreferencesUiEvent
    data class UpdateNewVideoThreshold(val days: Int) : MediaLibraryPreferencesUiEvent
    data object ToggleEnableWatchedMarks : MediaLibraryPreferencesUiEvent
    data class UpdateVideoWatchedThreshold(val threshold: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateComicWatchedThreshold(val threshold: Int) : MediaLibraryPreferencesUiEvent
}
