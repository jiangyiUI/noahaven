package dev.anilbeesetti.nextplayer.settings.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.medialibrary.FolderPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.medialibrary.FolderPreferencesViewModel
import dev.anilbeesetti.nextplayer.settings.screens.medialibrary.MediaLibraryPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.medialibrary.MediaLibraryPreferencesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object MediaLibraryPreferencesRoute : NavKey

@Serializable
object FolderPreferencesRoute : NavKey

@Serializable
object AudioFolderPreferencesRoute : NavKey

@Serializable
object ImageFolderPreferencesRoute : NavKey

@Serializable
object ComicFolderPreferencesRoute : NavKey

@Serializable
object BookFolderPreferencesRoute : NavKey

fun NavBackStack<NavKey>.navigateToMediaLibraryPreferencesScreen() {
    add(element = MediaLibraryPreferencesRoute)
}

fun NavBackStack<NavKey>.navigateToFolderPreferencesScreen() {
    add(element = FolderPreferencesRoute)
}

fun NavBackStack<NavKey>.navigateToAudioFolderPreferencesScreen() {
    add(element = AudioFolderPreferencesRoute)
}

fun NavBackStack<NavKey>.navigateToImageFolderPreferencesScreen() {
    add(element = ImageFolderPreferencesRoute)
}

fun NavBackStack<NavKey>.navigateToComicFolderPreferencesScreen() {
    add(element = ComicFolderPreferencesRoute)
}

fun NavBackStack<NavKey>.navigateToBookFolders() {
    add(element = BookFolderPreferencesRoute)
}

fun EntryProviderScope<NavKey>.mediaLibraryPreferencesEntry(
    onNavigateUp: () -> Unit,
    onFolderSettingClick: () -> Unit,
    onAudioFolderSettingClick: () -> Unit,
    onImageFolderSettingClick: () -> Unit,
    onComicFolderSettingClick: () -> Unit,
    onBookFolderSettingClick: () -> Unit,
    onThumbnailSettingClick: () -> Unit,
) {
    entry<MediaLibraryPreferencesRoute> {
        val output = MediaLibraryPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
            openFolders = onFolderSettingClick,
            openAudioFolders = onAudioFolderSettingClick,
            openImageFolders = onImageFolderSettingClick,
            openComicFolders = onComicFolderSettingClick,
            openBookFolders = onBookFolderSettingClick,
            openThumbnails = onThumbnailSettingClick,
        )
        val viewModel = koinViewModel<MediaLibraryPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        MediaLibraryPreferencesScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.folderPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<FolderPreferencesRoute> {
        val output = FolderPreferencesViewModel.Output(navigateUp = onNavigateUp)
        val viewModel = koinViewModel<FolderPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        FolderPreferencesScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.audioFolderPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<AudioFolderPreferencesRoute> {
        val output = FolderPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
            audioFolders = true,
        )
        val viewModel = koinViewModel<FolderPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        FolderPreferencesScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.imageFolderPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<ImageFolderPreferencesRoute> {
        val output = FolderPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
            imageFolders = true,
        )
        val viewModel = koinViewModel<FolderPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        FolderPreferencesScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.comicFolderPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<ComicFolderPreferencesRoute> {
        val output = FolderPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
            comicFolders = true,
        )
        val viewModel = koinViewModel<FolderPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        FolderPreferencesScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.bookFolderPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<BookFolderPreferencesRoute> {
        val output = FolderPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
            bookFolders = true,
        )
        val viewModel = koinViewModel<FolderPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        FolderPreferencesScreen(viewModel = viewModel)
    }
}
