package dev.anilbeesetti.nextplayer.feature.videopicker.screens.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.MediaLibraryTab
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.LibraryTabRow
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicsScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicsViewModel

/**
 * 「图片」页的 tab 宿主：在图片库和漫画库之间切换，tab 顺序可在顶部长按拖动调整。
 */
@Composable
fun MediaLibraryScreen(
    imagesViewModel: ImagesViewModel,
    comicsViewModel: ComicsViewModel,
    tabsViewModel: LibraryTabsViewModel,
) {
    val tabOrder by tabsViewModel.tabOrder.collectAsStateWithLifecycle()
    // 存 tab 名字而不是枚举本身，rememberSaveable 才能正确保存
    var selectedTabName by rememberSaveable { mutableStateOf("") }
    val order = MediaLibraryTab.sanitize(tabOrder)
    val selectedTab = MediaLibraryTab.entries.firstOrNull { it.name == selectedTabName }
    val currentTab = selectedTab?.takeIf { it in order } ?: order.first()

    val tabBar: @Composable () -> Unit = {
        LibraryTabRow(
            tabs = order,
            selectedTab = currentTab,
            onTabSelected = { selectedTabName = it.name },
            onMoveTab = tabsViewModel::moveTab,
        )
    }

    when (currentTab) {
        MediaLibraryTab.IMAGES -> ImagesScreen(
            viewModel = imagesViewModel,
            tabBar = tabBar,
        )

        MediaLibraryTab.COMICS -> ComicsScreen(
            viewModel = comicsViewModel,
            tabBar = tabBar,
        )
    }
}
