package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import android.net.Uri
import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.music.MusicSearchScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.music.MusicSearchViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
data object MusicSearchRoute : NavKey

fun NavBackStack<NavKey>.navigateToMusicSearch() {
    add(element = MusicSearchRoute)
}

/**
 * 搜索页点击文件夹后写入，音乐库重新可见时消费。
 * 这样不必为音乐库再压入一个带参路由（音乐库本身是顶部标签的根路由）。
 */
object PendingMusicFolder {
    private var path: String? = null

    fun request(folderPath: String) {
        path = folderPath
    }

    fun consume(): String? = path.also { path = null }
}

fun EntryProviderScope<NavKey>.musicSearchEntry(
    onNavigateUp: () -> Unit,
    onPlayAudios: (playlist: List<Uri>, startUri: Uri) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    entry<MusicSearchRoute> {
        val output = MusicSearchViewModel.Output(
            navigateUp = onNavigateUp,
            playAudios = onPlayAudios,
            openFolder = onOpenFolder,
        )
        val viewModel = koinViewModel<MusicSearchViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        MusicSearchScreen(viewModel = viewModel)
    }
}
