package dev.anilbeesetti.nextplayer.feature.books.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.BookLibraryTab
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** 书籍页顶部 tab 的顺序状态，顺序持久化在偏好里。 */
@KoinViewModel
class BookTabsViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val tabOrder = preferencesRepository.applicationPreferences
        .map { BookLibraryTab.sanitize(it.bookLibraryTabOrder) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BookLibraryTab.defaultOrder,
        )

    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences ->
                val order = BookLibraryTab.sanitize(preferences.bookLibraryTabOrder).toMutableList()
                if (fromIndex !in order.indices || toIndex !in order.indices) {
                    return@updateApplicationPreferences preferences
                }
                order.add(toIndex, order.removeAt(fromIndex))
                preferences.copy(bookLibraryTabOrder = order)
            }
        }
    }
}
