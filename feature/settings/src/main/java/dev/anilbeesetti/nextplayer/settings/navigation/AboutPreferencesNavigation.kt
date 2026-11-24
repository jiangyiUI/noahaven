package dev.anilbeesetti.nextplayer.settings.navigation

import android.widget.Toast
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.about.AboutPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.about.AboutPreferencesViewModel
import dev.anilbeesetti.nextplayer.settings.screens.about.LibrariesScreen
import dev.anilbeesetti.nextplayer.settings.screens.about.LibrariesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object AboutPreferencesRoute : NavKey

@Serializable
object LibrariesRoute : NavKey

fun NavBackStack<NavKey>.navigateToAboutPreferences() {
    add(element = AboutPreferencesRoute)
}

fun NavBackStack<NavKey>.navigateToLibraries() {
    add(element = LibrariesRoute)
}

fun EntryProviderScope<NavKey>.aboutPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<AboutPreferencesRoute> {
        val context = LocalContext.current
        val output = AboutPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
            showMessage = {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            },
        )
        val viewModel = koinViewModel<AboutPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        AboutPreferencesScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.librariesEntry(onNavigateUp: () -> Unit) {
    entry<LibrariesRoute> {
        val output = LibrariesViewModel.Output(navigateUp = onNavigateUp)
        val viewModel = koinViewModel<LibrariesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        LibrariesScreen(viewModel = viewModel)
    }
}
