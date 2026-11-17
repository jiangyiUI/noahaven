package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.ImageSearchScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.images.ImageSearchViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
data object ImageSearchRoute : NavKey

fun NavBackStack<NavKey>.navigateToImageSearch() {
    add(element = ImageSearchRoute)
}

/**
 * 搜索页点击文件夹后写入，图片库重新可见时消费。
 * 这样不必为图片库再压入一个带参路由（图片库本身是顶部标签的根路由）。
 */
object PendingImageFolder {
    private var path: String? = null

    fun request(folderPath: String) {
        path = folderPath
    }

    fun consume(): String? = path.also { path = null }
}

fun EntryProviderScope<NavKey>.imageSearchEntry(
    onNavigateUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    entry<ImageSearchRoute> {
        val output = ImageSearchViewModel.Output(
            navigateUp = onNavigateUp,
            openFolder = onOpenFolder,
        )
        val viewModel = koinViewModel<ImageSearchViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        ImageSearchScreen(viewModel = viewModel)
    }
}
