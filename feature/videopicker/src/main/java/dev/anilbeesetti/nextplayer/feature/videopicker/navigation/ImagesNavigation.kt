package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicsViewModel
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.ImagesViewModel
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.LibraryTabsViewModel
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.MediaLibraryScreen
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
data object ImagesRoute : NavKey

fun EntryProviderScope<NavKey>.imagesEntry(
    onSettingsClick: () -> Unit,
    onImageFoldersClick: () -> Unit,
    onComicFoldersClick: () -> Unit,
    onSearchClick: () -> Unit,
    onComicSearchClick: () -> Unit,
) {
    entry<ImagesRoute> {
        val imagesOutput = ImagesViewModel.Output(
            openSettings = onSettingsClick,
            openImageFolders = onImageFoldersClick,
            openSearch = onSearchClick,
        )
        val imagesViewModel = koinViewModel<ImagesViewModel>()
        SideEffect { imagesViewModel.output = imagesOutput }

        val comicsOutput = ComicsViewModel.Output(
            openSettings = onSettingsClick,
            openComicFolders = onComicFoldersClick,
            openSearch = onComicSearchClick,
        )
        val comicsViewModel = koinViewModel<ComicsViewModel>()
        SideEffect { comicsViewModel.output = comicsOutput }

        MediaLibraryScreen(
            imagesViewModel = imagesViewModel,
            comicsViewModel = comicsViewModel,
            tabsViewModel = koinViewModel<LibraryTabsViewModel>(),
        )
    }
}
