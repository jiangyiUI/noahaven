package dev.anilbeesetti.nextplayer.settings.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.reader.ReaderPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.reader.ReaderPreferencesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object ReaderPreferencesRoute : NavKey

fun NavBackStack<NavKey>.navigateToReaderPreferences() {
    add(element = ReaderPreferencesRoute)
}

fun EntryProviderScope<NavKey>.readerPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<ReaderPreferencesRoute> {
        val output = ReaderPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
        )
        val viewModel = koinViewModel<ReaderPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        ReaderPreferencesScreen(viewModel = viewModel)
    }
}
