package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.ImagesScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.ImagesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
data object ImagesRoute : NavKey

fun EntryProviderScope<NavKey>.imagesEntry(
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    entry<ImagesRoute> {
        val output = ImagesViewModel.Output(
            openSettings = onSettingsClick,
            openSearch = onSearchClick,
        )
        val viewModel = koinViewModel<ImagesViewModel>()
        SideEffect { viewModel.output = output }
        ImagesScreen(viewModel = viewModel)
    }
}
