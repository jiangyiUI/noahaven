package dev.anilbeesetti.nextplayer.feature.more.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.feature.more.screens.more.MoreScreen
import dev.anilbeesetti.nextplayer.feature.more.screens.more.MoreViewModel
import dev.anilbeesetti.nextplayer.feature.more.screens.trash.TrashScreen
import dev.anilbeesetti.nextplayer.feature.more.screens.trash.TrashViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object MoreRoute : NavKey

@Serializable
object TrashRoute : NavKey

fun EntryProviderScope<NavKey>.moreEntry(
    onPlayVideo: (Video) -> Unit,
    onPlayUri: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onTrashClick: () -> Unit,
    onVaultClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onNetworkClick: () -> Unit,
) {
    entry<MoreRoute> {
        val output = MoreViewModel.Output(
            playVideo = onPlayVideo,
            playUri = onPlayUri,
            openSettings = onSettingsClick,
            openTrash = onTrashClick,
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

fun EntryProviderScope<NavKey>.trashEntry(
    onNavigateUp: () -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    entry<TrashRoute> {
        val output = TrashViewModel.Output(
            navigateUp = onNavigateUp,
            playVideo = onPlayVideo,
        )
        val viewModel = koinViewModel<TrashViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        TrashScreen(viewModel = viewModel)
    }
}

fun NavBackStack<NavKey>.navigateToMore() {
    add(element = MoreRoute)
}

fun NavBackStack<NavKey>.navigateToTrash() {
    add(element = TrashRoute)
}
