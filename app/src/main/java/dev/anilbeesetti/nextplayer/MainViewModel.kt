package dev.anilbeesetti.nextplayer

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.navigation.moveDestination
import dev.anilbeesetti.nextplayer.navigation.sanitizeTopLevelDestinationOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(private val preferencesRepository: PreferencesRepository) :
    MviViewModel<MainActivityUiState, Nothing>() {

    private val stateInternal =
        MutableStateFlow<MainActivityUiState>(value = MainActivityUiState.Loading)
    override val state: StateFlow<MainActivityUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                stateInternal.update { MainActivityUiState.Success(preferences = preferences) }
            }
        }
    }

    fun moveTopLevelDestination(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences ->
                val destinations = sanitizeTopLevelDestinationOrder(
                    preferences.topLevelDestinationOrder,
                )
                val reordered = destinations.moveDestination(fromIndex, toIndex)
                if (reordered == destinations) {
                    preferences
                } else {
                    preferences.copy(
                        topLevelDestinationOrder = reordered.map { it.name },
                    )
                }
            }
        }
    }

    // Navigation reorder writes preferences directly; the MVI action type remains unused.
    override fun onAction(action: Nothing) = Unit
}

sealed interface MainActivityUiState {
    object Loading : MainActivityUiState
    data class Success(val preferences: ApplicationPreferences) : MainActivityUiState
}
