package dev.anilbeesetti.nextplayer.feature.books.shelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.media.services.TransferEvent
import dev.anilbeesetti.nextplayer.core.media.services.TransferMode
import dev.anilbeesetti.nextplayer.core.media.services.TransferProgress
import dev.anilbeesetti.nextplayer.core.media.services.withPreservedExtension
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfRepository
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferFlowState
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Factory

/** 下拉刷新指示的最短停留时间：扫描太快时也转一下，让用户知道确实刷新过。 */
private const val MIN_REFRESH_INDICATOR_MS = 500L

/** 书架 ViewModel：白名单文件夹树 + 书籍列表，逻辑与漫画库保持一致。 */
@Factory
class BookshelfViewModel(
    private val bookFileIndex: BookFileIndex,
    private val preferencesRepository: PreferencesRepository,
    private val mediaOperationsService: MediaOperationsService,
    private val systemService: SystemService,
    private val bookShelfRepository: BookShelfRepository,
) : ViewModel() {

    data class Output(
        val openBook: (String) -> Unit = {},
        val openSettings: () -> Unit = {},
        val openBookFolders: () -> Unit = {},
        val openSearch: (shelfOnly: Boolean) -> Unit = {},
    )

    var output: Output = Output()

    private val folderPath = MutableStateFlow<String?>(null)

    // 记录进入过的目录，返回时回到上一个访问的目录而不是父目录
    private val folderHistory = mutableListOf<String?>()
    private val booksInternal = MutableStateFlow<List<BookFile>?>(null)
    private val refreshing = MutableStateFlow(false)

    /** 最近阅读过的书（按阅读时间倒序取第一条），用于「继续阅读」被删掉时顺延。 */
    private val recentBookPath = MutableStateFlow<String?>(null)

    /** 复制/移动的进度状态，与音乐/漫画库共用同一套 TransferFlowState。 */
    private val transferFlowState = MutableStateFlow<TransferFlowState>(TransferFlowState.Idle)
    private var transferJob: Job? = null

    val state: StateFlow<BookshelfUiState> = combine(
        folderPath,
        booksInternal,
        preferencesRepository.applicationPreferences,
        refreshing,
        combine(transferFlowState, recentBookPath) { transferFlow, recentPath ->
            transferFlow to recentPath
        },
    ) { path, books, preferences, isRefreshing, transfer ->
        books.toUiState(
            path = path,
            preferences = preferences,
            refreshing = isRefreshing,
            transferFlow = transfer.first,
            recentBookPath = transfer.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = BookshelfUiState(
            isLoading = true,
            preferences = preferencesRepository.applicationPreferences.value.toBookPreferences(),
        ),
    )

    init {
        refresh()
        viewModelScope.launch {
            bookFileIndex.entries.collect { entries ->
                booksInternal.value = entries
                recentBookPath.value = entries.orEmpty()
                    .filter { it.lastOpenedAt > 0L }
                    .maxByOrNull { it.lastOpenedAt }
                    ?.path
            }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                val current = folderPath.value ?: return@collect
                val roots = preferences.bookFolders.map(::normalizePath)
                if (roots.none { current.isWithin(it) }) {
                    folderPath.value = null
                    folderHistory.clear()
                }
            }
        }
    }

    fun refresh(force: Boolean = false) {
        if (!force) {
            val preferences = preferencesRepository.applicationPreferences.value
            bookFileIndex.requestRefresh(
                whitelistRoots = preferences.bookFolders,
                folderPath = folderPath.value,
            )
            return
        }
        viewModelScope.launch {
            refreshing.value = true
            val startedAt = System.currentTimeMillis()
            try {
                bookFileIndex.refreshNow(
                    whitelistRoots = preferencesRepository.applicationPreferences.value.bookFolders,
                    folderPath = folderPath.value,
                )
            } finally {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < MIN_REFRESH_INDICATOR_MS) {
                    delay(MIN_REFRESH_INDICATOR_MS - elapsed)
                }
                refreshing.value = false
            }
        }
    }

    fun openFolder(path: String) {
        if (path != folderPath.value) folderHistory.add(folderPath.value)
        folderPath.value = path
        refresh()
    }

    /**
     * 从搜索页跨页跳转过来时调用：直接定位到 [path]，但把「浏览根 → 白名单根 → … → path 父目录」
     * 整条链路补进 folderHistory，这样按返回键能一级一级回退，而不是直接跳回浏览根。
     * 正常逐级点文件夹仍走 [openFolder]，不受影响。
     */
    fun locateFolder(path: String) {
        val roots = preferencesRepository.applicationPreferences.value.bookFolders.map(::normalizePath)
        val root = roots.firstOrNull { path.isWithin(it) }
        if (root == null) {
            // 找不到所属白名单根，退化为普通打开
            openFolder(path)
            return
        }
        // 收集 path 的所有祖先目录（不含 path 本身），限定在 root 范围内
        val ancestors = mutableListOf<String>()
        var current = File(path).parentFile
        while (current != null && current.path.isWithin(root)) {
            ancestors.add(current.path)
            current = current.parentFile
        }
        ancestors.reverse() // 从浅到深：root, root/child, …, path 的父目录
        folderHistory.clear()
        folderHistory.add(null) // 浏览页根
        folderHistory.addAll(ancestors)
        folderPath.value = path
        refresh()
    }

    fun goBack() {
        if (folderPath.value == null) return
        folderPath.value = folderHistory.removeLastOrNull()
        refresh()
    }

    fun openSettings() = output.openSettings()

    fun openBookFolders() = output.openBookFolders()

    fun openSearch() = output.openSearch(false)

    fun onAction(action: BookshelfAction) {
        when (action) {
            is BookshelfAction.DeleteSelected -> deleteSelected(items = action.items)
            is BookshelfAction.RenameSelected -> renameBook(item = action.item, to = action.to)
            is BookshelfAction.ShareSelected -> shareSelected(items = action.items)
            is BookshelfAction.CopySelected -> transferSelected(
                items = action.items,
                mode = TransferMode.COPY,
            )

            is BookshelfAction.MoveSelected -> transferSelected(
                items = action.items,
                mode = TransferMode.MOVE,
            )

            BookshelfAction.CancelTransfer -> {
                transferJob?.cancel()
                transferJob = null
                transferFlowState.value = TransferFlowState.Idle
            }
        }
    }

    /** 删除选中的书籍文件；文件夹只删其中的书（txt/epub），目录本身和其他文件不动。 */
    private fun deleteSelected(items: Set<SelectionItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val paths = items.toBookPaths()
            if (paths.isNotEmpty()) {
                mediaOperationsService.deleteMedia(
                    uris = paths.map { Uri.fromFile(File(it)) },
                    permanently = true,
                )
                paths.forEach { path -> bookFileIndex.deleteProgress(path) }
            }
            refreshIndex()
        }
    }

    /** 重命名单本书，成功后强制重扫当前目录，阅读进度跟着新路径走。 */
    private fun renameBook(item: SelectionItem.Video, to: String) {
        viewModelScope.launch {
            // 用户把后缀删掉时补回原后缀，否则重命名后文件会从书籍索引里消失
            val newName = to.withPreservedExtension(File(item.path).name)
            val renamed = mediaOperationsService.renameMedia(
                uri = Uri.parse(item.uriString),
                to = newName,
            )
            if (renamed) {
                val newPath = File(File(item.path).parentFile, newName).path
                bookFileIndex.moveProgress(fromPath = item.path, toPath = newPath)
                // 书架记录只存路径，改名后同步过去，各分组显示的书名保持一致
                bookShelfRepository.renamePath(oldPath = item.path, newPath = newPath)
                if (preferencesRepository.applicationPreferences.value.lastViewedBookPath == item.path) {
                    preferencesRepository.updateApplicationPreferences {
                        it.copy(lastViewedBookPath = newPath)
                    }
                }
            }
            refreshIndex()
        }
    }

    private fun shareSelected(items: Set<SelectionItem>) {
        val paths = items.toBookPaths()
        val uris = paths.map { Uri.fromFile(File(it)) }
        if (uris.isEmpty()) return
        val mimeType = when {
            paths.all { it.endsWith(".txt", ignoreCase = true) } -> TEXT_MIME_TYPE
            paths.all { it.endsWith(".epub", ignoreCase = true) } -> EPUB_MIME_TYPE
            else -> "*/*"
        }
        viewModelScope.launch {
            mediaOperationsService.shareMedia(uris = uris, mimeType = mimeType)
        }
    }

    /** 复制/移动：先让用户选目标目录，再收集转移进度，结束后刷新书籍索引。 */
    private fun transferSelected(items: Set<SelectionItem>, mode: TransferMode) {
        val uris = items.toBookPaths().map { Uri.fromFile(File(it)) }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val targetFolder = systemService.pickFolder() ?: return@launch
            transferJob?.cancel()
            transferFlowState.value = TransferFlowState.Processing(
                mode = mode,
                progress = TransferProgress(totalFiles = uris.size),
            )
            transferJob = viewModelScope.launch {
                mediaOperationsService.transferMedia(
                    uris = uris,
                    folderUri = targetFolder,
                    mode = mode,
                )
                    .catch { transferFlowState.value = TransferFlowState.Idle }
                    .collect { event ->
                        when (event) {
                            is TransferEvent.Progress -> {
                                transferFlowState.value = TransferFlowState.Processing(
                                    mode = mode,
                                    progress = event.progress,
                                )
                            }

                            is TransferEvent.Completed -> {
                                // 移动换了路径，阅读进度按路径跟随到新位置
                                event.movedFiles.forEach { moved ->
                                    val fromPath = moved.sourceUri.path ?: return@forEach
                                    bookFileIndex.moveProgress(
                                        fromPath = fromPath,
                                        toPath = moved.destinationPath,
                                    )
                                    // 移动换了路径，书架记录跟着走
                                    bookShelfRepository.renamePath(
                                        oldPath = fromPath,
                                        newPath = moved.destinationPath,
                                    )
                                    if (preferencesRepository.applicationPreferences.value.lastViewedBookPath == fromPath) {
                                        preferencesRepository.updateApplicationPreferences {
                                            it.copy(lastViewedBookPath = moved.destinationPath)
                                        }
                                    }
                                }
                                transferFlowState.value = TransferFlowState.Idle
                                transferJob = null
                                refreshIndex()
                            }
                        }
                    }
            }
        }
    }

    /** 把选择项换算成磁盘上的书籍路径；文件夹展开成其下所有已索引的书。 */
    private fun Set<SelectionItem>.toBookPaths(): List<String> {
        val all = bookFileIndex.entries.value.orEmpty()
        return flatMap { item ->
            when (item) {
                is SelectionItem.Video -> listOf(item.path)

                is SelectionItem.Folder -> {
                    val prefix = item.path.trimEnd('/') + PATH_SEPARATOR
                    all.filter { it.path.startsWith(prefix) }.map { it.path }
                }
            }
        }.distinct()
    }

    private fun refreshIndex() {
        bookFileIndex.requestRefresh(
            whitelistRoots = preferencesRepository.applicationPreferences.value.bookFolders,
            folderPath = folderPath.value,
            force = true,
        )
    }

    /** 快捷设置写回书籍自己的布局/排序偏好，不影响图片/漫画库。 */
    fun updatePreferences(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateBookPreferences(preferences)
        }
    }

    private fun List<BookFile>?.toUiState(
        path: String?,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
        transferFlow: TransferFlowState,
        recentBookPath: String?,
    ): BookshelfUiState {
        val bookPreferences = preferences.toBookPreferences()
        val roots = preferences.bookFolders.map(::normalizePath).toSet()
        val all = orEmpty().filter {
            normalizePath(it.whitelistRoot) in roots && (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
        val sort = Sort(by = preferences.bookSortBy, order = preferences.bookSortOrder)
        val sortedAll = all.sortedWith(sort.bookComparator())
        val tree = buildTree(root = path, books = all, sort = sort)
        val folders: List<Folder>
        val books: List<BookFile>
        when (preferences.bookViewMode) {
            MediaViewMode.FOLDER_TREE -> {
                folders = tree.folders
                books = tree.directBooks
            }

            MediaViewMode.FOLDERS -> {
                folders = if (path == null) flatFolders(all, sort) else emptyList()
                books = if (path == null) emptyList() else tree.directBooks
            }

            MediaViewMode.VIDEOS -> {
                folders = emptyList()
                books = if (path == null) sortedAll else sortedAll.booksUnder(path)
            }
        }
        return BookshelfUiState(
            folderPath = path,
            folderName = path?.let { File(it).name },
            folders = folders,
            books = books,
            allBooks = sortedAll,
            bookFolders = preferences.bookFolders,
            preferences = bookPreferences,
            isLoading = this == null,
            refreshing = refreshing,
            transferFlow = transferFlow,
            recentBookPath = recentBookPath,
        )
    }

    private fun buildTree(root: String?, books: List<BookFile>, sort: Sort): BooksTree {
        if (root == null) {
            val folders = books.groupBy { it.whitelistRoot }
                .map { (path, rootBooks) -> summarize(path, rootBooks) }
                .sortedWith(sort.folderComparator())
            return BooksTree(folders = folders, directBooks = emptyList())
        }
        val prefix = root.trimEnd('/') + PATH_SEPARATOR
        val directBooks = ArrayList<BookFile>()
        val booksByFolder = LinkedHashMap<String, MutableList<BookFile>>()
        for (book in books.booksUnder(root)) {
            if (book.parentPath == root) {
                directBooks.add(book)
                continue
            }
            val relative = book.path.removePrefix(prefix)
            if (relative == book.path) continue
            val childPath = prefix + relative.substringBefore(PATH_SEPARATOR)
            booksByFolder.getOrPut(childPath) { ArrayList() }.add(book)
        }
        return BooksTree(
            folders = booksByFolder.map { (path, folderBooks) -> summarize(path, folderBooks) }
                .sortedWith(sort.folderComparator()),
            directBooks = directBooks.sortedWith(sort.bookComparator()),
        )
    }

    private fun flatFolders(books: List<BookFile>, sort: Sort): List<Folder> =
        books.groupBy { it.parentPath }
            .map { (path, folderBooks) -> summarize(path, folderBooks) }
            .sortedWith(sort.folderComparator())

    private fun List<BookFile>.booksUnder(path: String): List<BookFile> {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        return filter { it.path.startsWith(prefix) }
    }

    private fun summarize(path: String, books: List<BookFile>): Folder {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        var dateModified = 0L
        var totalSize = 0L
        var directCount = 0
        val childFolders = HashSet<String>()
        books.forEach { book ->
            if (book.dateModified > dateModified) dateModified = book.dateModified
            totalSize += book.size
            if (book.parentPath == path) {
                directCount++
            } else {
                childFolders.add(book.path.removePrefix(prefix).substringBefore(PATH_SEPARATOR))
            }
        }
        return Folder(
            name = File(path).prettyName,
            path = path,
            parentPath = File(path).parent,
            dateModified = dateModified,
            totalSize = totalSize,
            videosCount = directCount,
            foldersCount = childFolders.size,
        )
    }

    private fun String.isWithin(root: String): Boolean =
        this == root || startsWith(root.trimEnd('/') + PATH_SEPARATOR)

    private companion object {
        const val PATH_SEPARATOR = "/"
        const val TEXT_MIME_TYPE = "text/plain"
        const val EPUB_MIME_TYPE = "application/epub+zip"
    }
}

data class BookshelfUiState(
    val folderPath: String? = null,
    val folderName: String? = null,
    val folders: List<Folder> = emptyList(),
    val books: List<BookFile> = emptyList(),
    /** 当前筛选条件下全部书籍（已排序），用于搜索/继续阅读兜底。 */
    val allBooks: List<BookFile> = emptyList(),
    val bookFolders: List<String> = emptyList(),
    /** 已转换成书籍自己的布局/排序字段，可直接交给通用组件。 */
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val isLoading: Boolean = false,
    val refreshing: Boolean = false,
    val transferFlow: TransferFlowState = TransferFlowState.Idle,
    /** 最近阅读过的书籍路径，用于「最近查看」失效时兜底。 */
    val recentBookPath: String? = null,
)

/** 书架选择模式下的操作，与漫画库的 ComicsAction 对应。 */
sealed interface BookshelfAction {
    data class DeleteSelected(val items: Set<SelectionItem>) : BookshelfAction
    data class RenameSelected(val item: SelectionItem.Video, val to: String) : BookshelfAction
    data class ShareSelected(val items: Set<SelectionItem>) : BookshelfAction
    data class CopySelected(val items: Set<SelectionItem>) : BookshelfAction
    data class MoveSelected(val items: Set<SelectionItem>) : BookshelfAction
    data object CancelTransfer : BookshelfAction
}

private data class BooksTree(val folders: List<Folder>, val directBooks: List<BookFile>)

/** 书籍自己的布局/排序字段映射，浏览与收藏书架共用，避免两边行为漂移。 */
internal fun ApplicationPreferences.toBookPreferences(): ApplicationPreferences = copy(
    mediaViewMode = bookViewMode,
    // 渲染用 mediaLayoutMode；快捷设置（imageMode）读取 imageFolderLayoutMode，两者保持同步
    mediaLayoutMode = bookFolderLayoutMode,
    imageFolderLayoutMode = bookFolderLayoutMode,
    imageLayoutMode = bookLayoutMode,
    sortBy = bookSortBy,
    sortOrder = bookSortOrder,
    showThumbnailField = bookShowThumbnailField,
    showSizeField = bookShowSizeField,
    showExtensionField = bookShowExtensionField,
    showDurationField = false,
    showFolderDurationField = false,
    showResolutionField = bookShowResolutionField,
    // 书籍浏览 tab 不显示路径字段
    showPathField = false,
)

/** 把快捷设置写回书籍自己的偏好，不影响图片/漫画库。 */
internal suspend fun PreferencesRepository.updateBookPreferences(preferences: ApplicationPreferences) {
    updateApplicationPreferences {
        it.copy(
            bookViewMode = preferences.mediaViewMode,
            bookFolderLayoutMode = preferences.imageFolderLayoutMode,
            bookLayoutMode = preferences.imageLayoutMode,
            bookSortBy = preferences.sortBy,
            bookSortOrder = preferences.sortOrder,
            // 全部写书籍独立字段，不碰全局字段（视频/图片/漫画共用）
            bookShowExtensionField = preferences.showExtensionField,
            bookShowResolutionField = preferences.showResolutionField,
            bookShowSizeField = preferences.showSizeField,
            bookShowThumbnailField = preferences.showThumbnailField,
        )
    }
}

private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
