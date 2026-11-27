package dev.anilbeesetti.nextplayer

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.navigation.TopLevelDestination
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
                val fullOrder = sanitizeTopLevelDestinationOrder(
                    preferences.topLevelDestinationOrder,
                )
                // 底栏可能隐藏了部分按钮，UI 传的是「可见」按钮的索引；先映射回完整顺序里的
                // 目标再移动，保持被隐藏按钮的相对位置不变。「更多」固定最后、不可拖。
                val visible = fullOrder.filter {
                    it == TopLevelDestination.MORE ||
                        it.name !in preferences.hiddenTopLevelDestinations
                }
                val fromDest = visible.getOrNull(fromIndex)
                val toDest = visible.getOrNull(toIndex)
                if (fromDest == null || toDest == null) {
                    preferences
                } else {
                    val reordered = fullOrder.moveDestination(
                        fromIndex = fullOrder.indexOf(fromDest),
                        toIndex = fullOrder.indexOf(toDest),
                    )
                    if (reordered == fullOrder) {
                        preferences
                    } else {
                        preferences.copy(
                            topLevelDestinationOrder = reordered.map { it.name },
                        )
                    }
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
