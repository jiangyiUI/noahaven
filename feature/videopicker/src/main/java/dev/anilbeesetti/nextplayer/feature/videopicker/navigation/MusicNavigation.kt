package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import android.net.Uri
import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.music.MusicScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.music.MusicViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
data object MusicRoute : NavKey

fun EntryProviderScope<NavKey>.musicEntry(
    onPlayAudios: (playlist: List<Uri>, startUri: Uri) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    entry<MusicRoute> {
        val output = MusicViewModel.Output(
            playAudios = onPlayAudios,
            openSettings = onSettingsClick,
            openSearch = onSearchClick,
        )
        val viewModel = koinViewModel<MusicViewModel>()
        SideEffect { viewModel.output = output }
        MusicScreen(viewModel = viewModel)
    }
}
