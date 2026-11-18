package dev.anilbeesetti.nextplayer.feature.more.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.feature.more.screens.more.MoreScreen
import dev.anilbeesetti.nextplayer.feature.more.screens.more.MoreViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object MoreRoute : NavKey

fun EntryProviderScope<NavKey>.moreEntry(
    onPlayVideo: (Video) -> Unit,
    onPlayUri: (String) -> Unit,
    onOpenComic: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onVaultClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onNetworkClick: () -> Unit,
) {
    entry<MoreRoute> {
        val output = MoreViewModel.Output(
            playVideo = onPlayVideo,
            playUri = onPlayUri,
            openComic = onOpenComic,
            openSettings = onSettingsClick,
            openVault = onVaultClick,
            openPlaylists = onPlaylistsClick,
            openNetwork = onNetworkClick,
        )
        val viewModel = koinViewModel<MoreViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        MoreScreen(viewModel = viewModel)
    }
}

fun NavBackStack<NavKey>.navigateToMore() {
    add(element = MoreRoute)
}
