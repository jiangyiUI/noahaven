package dev.anilbeesetti.nextplayer.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.books.navigation.PendingBookFolder
import dev.anilbeesetti.nextplayer.feature.books.navigation.PendingBookLocate
import dev.anilbeesetti.nextplayer.feature.books.navigation.bookReaderEntry
import dev.anilbeesetti.nextplayer.feature.books.navigation.bookSearchEntry
import dev.anilbeesetti.nextplayer.feature.books.navigation.bookshelfEntry
import dev.anilbeesetti.nextplayer.feature.books.navigation.navigateToBookReader
import dev.anilbeesetti.nextplayer.feature.books.navigation.navigateToBookSearch
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToBookFolders
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

/**
 * 书籍顶级 tab 的导航图，root 为 BookshelfRoute（书架/浏览宿主）。
 * 搜索页、阅读页都压在书籍自己的 back stack 里，与其他 tab 互不影响。
 */
fun EntryProviderScope<NavKey>.booksNavGraph(backStack: NavBackStack<NavKey>) {
    bookshelfEntry(
        onOpenBook = { path -> backStack.navigateToBookReader(path = path) },
        onOpenSettings = backStack::navigateToSettings,
        onOpenBookFolders = backStack::navigateToBookFolders,
        onOpenSearch = backStack::navigateToBookSearch,
    )

    bookSearchEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onOpenFolder = { path ->
            PendingBookFolder.request(folderPath = path)
            backStack.removeLastIfNotRoot()
        },
        onOpenBook = { path -> backStack.navigateToBookReader(path = path) },
        onLocateBook = { path ->
            // 浏览 tab 搜索点击书籍：写入待定位，pop 回浏览页后由其消费并定位
            PendingBookLocate.request(bookPath = path)
            backStack.removeLastIfNotRoot()
        },
    )

    bookReaderEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
}
