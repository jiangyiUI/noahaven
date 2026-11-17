package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicHistoryViewerScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicHistoryViewerViewModel
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicSearchScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicSearchViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
data object ComicSearchRoute : NavKey

/** 直接从「更多」页历史记录打开某本漫画。 */
@Serializable
data class ComicViewerRoute(val path: String) : NavKey

fun NavBackStack<NavKey>.navigateToComicSearch() {
    add(element = ComicSearchRoute)
}

fun NavBackStack<NavKey>.navigateToComicViewer(path: String) {
    add(element = ComicViewerRoute(path = path))
}

fun EntryProviderScope<NavKey>.comicSearchEntry(
    onNavigateUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    entry<ComicSearchRoute> {
        val output = ComicSearchViewModel.Output(
            navigateUp = onNavigateUp,
            openFolder = onOpenFolder,
        )
        val viewModel = koinViewModel<ComicSearchViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        ComicSearchScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.comicViewerEntry(onNavigateUp: () -> Unit) {
    entry<ComicViewerRoute> { route ->
        val viewModel = koinViewModel<ComicHistoryViewerViewModel>(
            parameters = { parametersOf(route.path) },
        )
        ComicHistoryViewerScreen(viewModel = viewModel, onBack = onNavigateUp)
    }
}

/**
 * 搜索页点击文件夹后写入，漫画库重新可见时消费。
 * 与图片搜索的 PendingImageFolder 同一套做法。
 */
object PendingComicFolder {
    private var path: String? = null

    fun request(folderPath: String) {
        path = folderPath
    }

    fun consume(): String? = path.also { path = null }
}
