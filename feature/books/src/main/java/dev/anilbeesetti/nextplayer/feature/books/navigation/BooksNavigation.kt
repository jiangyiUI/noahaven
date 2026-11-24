package dev.anilbeesetti.nextplayer.feature.books.navigation

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderScreen
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderViewModel
import dev.anilbeesetti.nextplayer.feature.books.search.BookSearchScreen
import dev.anilbeesetti.nextplayer.feature.books.search.BookSearchViewModel
import dev.anilbeesetti.nextplayer.feature.books.shelf.BookTabsViewModel
import dev.anilbeesetti.nextplayer.feature.books.shelf.BooksLibraryScreen
import dev.anilbeesetti.nextplayer.feature.books.shelf.BookshelfViewModel
import dev.anilbeesetti.nextplayer.feature.books.shelf.ShelfViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
data object BookshelfRoute : NavKey

@Serializable
data class BookSearchRoute(
    /** true = 书架专用搜索，只搜书架里的书，不搜文件夹。false = 浏览 tab 的全局搜索。 */
    val shelfOnly: Boolean = false,
) : NavKey

/** 阅读页按白名单里的文件绝对路径打开，不再复制到私有目录。 */
@Serializable
data class BookReaderRoute(val path: String) : NavKey

fun NavBackStack<NavKey>.navigateToBookshelf() {
    add(element = BookshelfRoute)
}

fun NavBackStack<NavKey>.navigateToBookSearch(shelfOnly: Boolean = false) {
    add(element = BookSearchRoute(shelfOnly = shelfOnly))
}

fun NavBackStack<NavKey>.navigateToBookReader(path: String) {
    add(element = BookReaderRoute(path = path))
}

fun EntryProviderScope<NavKey>.bookshelfEntry(
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBookFolders: () -> Unit,
    onOpenSearch: (Boolean) -> Unit,
) {
    entry<BookshelfRoute> {
        val context = LocalContext.current
        val defaultGroupName = context.getString(R.string.book_shelf_default_group)
        val browseViewModel = koinViewModel<BookshelfViewModel>()
        val shelfViewModel = koinViewModel<ShelfViewModel>()
        val tabsViewModel = koinViewModel<BookTabsViewModel>()
        SideEffect {
            browseViewModel.output = BookshelfViewModel.Output(
                openBook = onOpenBook,
                openSettings = onOpenSettings,
                openBookFolders = onOpenBookFolders,
                openSearch = { onOpenSearch(false) },
            )
            shelfViewModel.output = ShelfViewModel.Output(
                openBook = onOpenBook,
                openSettings = onOpenSettings,
                openSearch = { onOpenSearch(true) },
                openBookFolders = onOpenBookFolders,
                defaultGroupName = defaultGroupName,
            )
        }
        BooksLibraryScreen(
            browseViewModel = browseViewModel,
            shelfViewModel = shelfViewModel,
            tabsViewModel = tabsViewModel,
            defaultGroupName = defaultGroupName,
        )
    }
}

fun EntryProviderScope<NavKey>.bookSearchEntry(
    onNavigateUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    entry<BookSearchRoute> { route ->
        val output = BookSearchViewModel.Output(
            navigateUp = onNavigateUp,
            openFolder = onOpenFolder,
        )
        val viewModel = if (route.shelfOnly) {
            val shelfRepo = org.koin.compose.koinInject<dev.anilbeesetti.nextplayer.feature.books.data.BookShelfRepository>()
            koinViewModel<BookSearchViewModel>(
                parameters = { parametersOf(output, true, shelfRepo) },
            )
        } else {
            koinViewModel<BookSearchViewModel>(
                parameters = { parametersOf(output, false, null) },
            )
        }
        SideEffect { viewModel.output = output }
        BookSearchScreen(viewModel = viewModel, onOpenBook = onOpenBook)
    }
}

fun EntryProviderScope<NavKey>.bookReaderEntry(
    onNavigateUp: () -> Unit,
) {
    entry<BookReaderRoute> { route ->
        val output = ReaderViewModel.Output(onNavigateUp = onNavigateUp)
        val viewModel = koinViewModel<ReaderViewModel>(
            parameters = { parametersOf(route.path, output) },
        )
        SideEffect { viewModel.output = output }
        ReaderScreen(viewModel = viewModel)
    }
}

/**
 * 搜索页点击文件夹后写入，书架重新可见时消费。
 * 与 PendingComicFolder / PendingImageFolder 同一套做法。
 */
object PendingBookFolder {
    private var path: String? = null

    fun request(folderPath: String) {
        path = folderPath
    }

    fun consume(): String? = path.also { path = null }
}
