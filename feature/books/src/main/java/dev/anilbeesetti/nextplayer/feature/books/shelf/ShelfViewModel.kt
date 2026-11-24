package dev.anilbeesetti.nextplayer.feature.books.shelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.media.services.withPreservedExtension
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfEntry
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** 书架展示项：同一个文件在「全部」里可能出现多次，移除时按记录 id 一起删掉。 */
data class ShelfBook(
    val book: BookFile,
    val entryIds: List<Long>,
)

data class ShelfUiState(
    val groups: List<BookShelfGroup> = emptyList(),
    val selectedGroupId: Long? = null,
    val books: List<ShelfBook> = emptyList(),
    /** 每个分组的书籍数（按去重后的路径计），用于分组胶囊上的数字。 */
    val groupCounts: Map<Long, Int> = emptyMap(),
    /** 「全部」里的书籍数（跨分组按路径去重）。 */
    val allCount: Int = 0,
    /** 已转换成书籍自己的布局/排序字段，可直接交给通用组件。 */
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val lastViewedBookPath: String? = null,
    val isLoading: Boolean = true,
    val refreshing: Boolean = false,
)

sealed interface ShelfEvent {
    data class Added(val count: Int) : ShelfEvent
}

/** 收藏书架：分组浏览、移除条目、从「浏览」页批量加入。 */
@KoinViewModel
class ShelfViewModel(
    private val repository: BookShelfRepository,
    private val bookFileIndex: BookFileIndex,
    private val preferencesRepository: PreferencesRepository,
    private val mediaOperationsService: MediaOperationsService,
) : ViewModel() {

    data class Output(
        val openBook: (String) -> Unit = {},
        val openSettings: () -> Unit = {},
        val openSearch: (shelfOnly: Boolean) -> Unit = {},
        val openBookFolders: () -> Unit = {},
        /** 没有分组时自动创建的分组名，由调用方传入本地化文案。 */
        val defaultGroupName: String = "",
    )

    var output: Output = Output()

    private val selectedGroupId = MutableStateFlow<Long?>(null)

    private val refreshing = MutableStateFlow(false)

    private val eventsInternal = MutableSharedFlow<ShelfEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ShelfEvent> = eventsInternal

    val state = combine(
        repository.groups,
        repository.observeItems(groupId = null),
        bookFileIndex.entries,
        preferencesRepository.applicationPreferences,
        combine(refreshing, selectedGroupId) { isRefreshing, groupId -> isRefreshing to groupId },
    ) { groups, entries, bookFiles, preferences, refreshAndGroup ->
        toUiState(
            groups = groups,
            entries = entries,
            bookFiles = bookFiles,
            preferences = preferences,
            selectedGroupId = refreshAndGroup.second,
            refreshing = refreshAndGroup.first,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ShelfUiState(
            preferences = preferencesRepository.applicationPreferences.value.toShelfPreferences(),
        ),
    )

    fun selectGroup(groupId: Long?) {
        selectedGroupId.value = groupId
    }

    fun removeFromShelf(entryIds: Collection<Long>) {
        if (entryIds.isEmpty()) return
        viewModelScope.launch {
            repository.removeItems(entryIds)
        }
    }

    /** 加入书架：已有分组直接用，否则用输入的名字新建，都没给就确保存在一个默认分组。 */
    fun addToShelf(groupId: Long?, newGroupName: String, paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val targetGroupId = when {
                groupId != null -> groupId
                newGroupName.isNotBlank() -> repository.createGroup(newGroupName)
                else -> repository.ensureDefaultGroup(output.defaultGroupName)
            }
            val added = repository.addPaths(groupId = targetGroupId, paths = paths)
            eventsInternal.emit(ShelfEvent.Added(count = added))
        }
    }

    fun updatePreferences(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateShelfPreferences(preferences)
        }
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createGroup(name)
        }
    }

    fun renameGroup(groupId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.renameGroup(groupId = groupId, name = name)
        }
    }

    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            repository.deleteGroup(groupId)
            // 删除的正好是当前分组时退回「全部」
            if (selectedGroupId.value == groupId) selectedGroupId.value = null
        }
    }

    /** 把分组挪到这一行的第 [targetIndex] 个（从 0 开始）。 */
    fun moveGroupTo(groupId: Long, targetIndex: Int) {
        viewModelScope.launch {
            repository.moveGroupTo(groupId = groupId, targetIndex = targetIndex)
        }
    }

    /**
     * 重命名实际文件：成功后把索引和所有分组下的记录都更新到新路径，
     * 各分组显示的书名因此保持一致。
     */
    fun renameBook(path: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            // 用户删掉后缀时补回原后缀，避免文件脱离书籍索引
            val name = newName.withPreservedExtension(File(path).name)
            val renamed = mediaOperationsService.renameMedia(
                uri = Uri.fromFile(File(path)),
                to = name,
            )
            if (renamed) {
                val newPath = File(File(path).parentFile, name).path
                bookFileIndex.moveProgress(fromPath = path, toPath = newPath)
                repository.renamePath(oldPath = path, newPath = newPath)
                if (preferencesRepository.applicationPreferences.value.lastViewedBookPath == path) {
                    preferencesRepository.updateApplicationPreferences {
                        it.copy(lastViewedBookPath = newPath)
                    }
                }
            }
            refreshIndex()
        }
    }

    /** 把这本书的书架记录移动到其他分组。 */
    fun moveBookToGroup(book: ShelfBook, groupId: Long?, newGroupName: String) {
        moveBooksToGroup(listOf(book), groupId, newGroupName)
    }

    /** 批量把多本书的书架记录移动到其他分组。 */
    fun moveBooksToGroup(books: List<ShelfBook>, groupId: Long?, newGroupName: String) {
        viewModelScope.launch {
            repository.moveItems(
                ids = books.flatMap { it.entryIds },
                targetGroupId = resolveGroupId(groupId = groupId, newGroupName = newGroupName),
            )
        }
    }

    /** 把这本书复制到其他分组（只复制记录，文件不动）。 */
    fun copyBookToGroup(book: ShelfBook, groupId: Long?, newGroupName: String) {
        copyBooksToGroup(listOf(book), groupId, newGroupName)
    }

    /** 批量把多本书复制到其他分组（只复制记录，文件不动）。 */
    fun copyBooksToGroup(books: List<ShelfBook>, groupId: Long?, newGroupName: String) {
        viewModelScope.launch {
            repository.copyItems(
                ids = books.flatMap { it.entryIds },
                targetGroupId = resolveGroupId(groupId = groupId, newGroupName = newGroupName),
            )
        }
    }

    /** 批量从书架移除。 */
    fun removeBooksFromShelf(books: List<ShelfBook>) {
        viewModelScope.launch {
            repository.removeItems(books.flatMap { it.entryIds })
        }
    }

    private suspend fun resolveGroupId(groupId: Long?, newGroupName: String): Long = when {
        groupId != null -> groupId
        newGroupName.isNotBlank() -> repository.createGroup(newGroupName)
        else -> repository.ensureDefaultGroup(output.defaultGroupName)
    }

    /** 下拉刷新：立即重扫白名单并挂起直到完成，refreshing 驱动刷新指示器。 */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            val startedAt = System.currentTimeMillis()
            try {
                bookFileIndex.refreshNow(
                    whitelistRoots = preferencesRepository.applicationPreferences.value.bookFolders,
                    folderPath = null,
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

    private fun refreshIndex() {
        bookFileIndex.requestRefresh(
            whitelistRoots = preferencesRepository.applicationPreferences.value.bookFolders,
            folderPath = null,
            force = true,
        )
    }

    fun openSettings() = output.openSettings()

    fun openBookFolders() = output.openBookFolders()

    fun openSearch() = output.openSearch(true)

    /**
     * 打开书架上最近读过的一本：
     * 1. 全局最近阅读记录仍在当前可见书架里且文件还在，直接续看；
     * 2. 否则取书架中打开时间最晚的一本；
     * 3. 一本都没读过就退回列表第一本。
     */
    fun continueLastViewedBook() {
        val pool = state.value.books
        if (pool.isEmpty()) return

        fun existing(path: String?): ShelfBook? = path?.let { p ->
            pool.firstOrNull { it.book.path == p && File(it.book.path).exists() }
        }

        val target = existing(state.value.lastViewedBookPath)
            ?: pool.asSequence()
                .filter { it.book.lastOpenedAt > 0L && File(it.book.path).exists() }
                .maxByOrNull { it.book.lastOpenedAt }
            ?: pool.firstOrNull { File(it.book.path).exists() }
            ?: pool.first()
        output.openBook(target.book.path)
    }

    private fun toUiState(
        groups: List<BookShelfGroup>,
        entries: List<BookShelfEntry>,
        bookFiles: List<BookFile>?,
        preferences: ApplicationPreferences,
        selectedGroupId: Long?,
        refreshing: Boolean,
    ): ShelfUiState {
        val visibleEntries = entries.filter {
            selectedGroupId == null || it.groupId == selectedGroupId
        }
        val indexed = bookFiles.orEmpty().associateBy { it.path }
        val shelfBooks = visibleEntries.groupBy { it.path }.map { (path, pathEntries) ->
            ShelfBook(
                book = indexed[path] ?: fallbackBook(path),
                entryIds = pathEntries.map { it.id },
            )
        }
        val sort = Sort(by = preferences.shelfSortBy, order = preferences.shelfSortOrder)
        val baseBookComparator = sort.bookComparator()
        val baseComparator = Comparator<ShelfBook> { a, b -> baseBookComparator.compare(a.book, b.book) }
        val sorted = when (sort.by) {
            Sort.By.SHELF_ADDED -> {
                // 按 BookShelfEntry.addedAt（取同一本书最早的加入时间）排序
                val entryAddedAt = visibleEntries.groupBy { it.path }
                    .mapValues { (_, entries) -> entries.minOf { it.addedAt } }
                shelfBooks.sortedWith(
                    compareBy<ShelfBook> { entryAddedAt[it.book.path] ?: 0L }
                        .then(baseComparator)
                        .run {
                            if (sort.order == Sort.Order.DESCENDING) reversed() else this
                        },
                )
            }

            Sort.By.LAST_VIEWED -> {
                // 按 preferences.bookLastViewedAt 时间戳排序；从未读过的排最后
                val lastViewed = preferences.bookLastViewedAt
                shelfBooks.sortedWith(
                    compareBy<ShelfBook> { lastViewed[it.book.path] ?: Long.MIN_VALUE }
                        .then(baseComparator)
                        .run {
                            if (sort.order == Sort.Order.DESCENDING) reversed() else this
                        },
                )
            }

            else -> shelfBooks.sortedWith(baseComparator)
        }
        return ShelfUiState(
            groups = groups,
            selectedGroupId = selectedGroupId,
            books = sorted,
            groupCounts = entries.groupBy { it.groupId }
                .mapValues { (_, groupEntries) -> groupEntries.map { it.path }.distinct().size },
            allCount = entries.map { it.path }.distinct().size,
            preferences = preferences.toShelfPreferences(),
            lastViewedBookPath = preferences.lastViewedBookPath,
            isLoading = bookFiles == null,
            refreshing = refreshing,
        )
    }

    /** 索引里没有这本书（文件被移走或不在白名单）时仍按路径展示一条记录。 */
    private fun fallbackBook(path: String): BookFile {
        val file = File(path)
        return BookFile(
            path = path,
            parentPath = file.parent.orEmpty(),
            whitelistRoot = file.parent.orEmpty(),
            displayName = file.name,
            title = file.name.substringBeforeLast('.', file.name),
            author = "",
            size = 0L,
            dateModified = 0L,
            coverCachePath = null,
            formattedFileSize = Utils.formatFileSize(0L),
        )
    }
}

/** 把书架 tab 专属字段映射成通用 ApplicationPreferences，供 QuickSettingsDialog 绑定。 */
internal fun ApplicationPreferences.toShelfPreferences(): ApplicationPreferences = copy(
    mediaViewMode = MediaViewMode.VIDEOS, // shelfMode=true 时这块被隐藏，随便给个值
    mediaLayoutMode = MediaLayoutMode.LIST,
    imageFolderLayoutMode = MediaLayoutMode.LIST,
    imageLayoutMode = shelfLayoutMode,
    sortBy = shelfSortBy,
    sortOrder = shelfSortOrder,
    // 书架 tab 只有 4 个字段开关
    showSizeField = shelfShowSizeField,
    showExtensionField = shelfShowExtensionField,
    showPlayedProgress = shelfShowPlayedProgressField,
    showThumbnailField = shelfShowThumbnailField,
    // 其余字段在 shelfMode 下被隐藏，给个合理默认
    showDurationField = false,
    showFolderDurationField = false,
    showPathField = false,
    showResolutionField = false,
)

/** 把快捷设置写回书架 tab 专属偏好，不影响浏览 tab 或图片/漫画库。 */
internal suspend fun PreferencesRepository.updateShelfPreferences(preferences: ApplicationPreferences) {
    updateApplicationPreferences {
        it.copy(
            shelfLayoutMode = preferences.imageLayoutMode,
            shelfSortBy = preferences.sortBy,
            shelfSortOrder = preferences.sortOrder,
            shelfShowSizeField = preferences.showSizeField,
            shelfShowExtensionField = preferences.showExtensionField,
            shelfShowPlayedProgressField = preferences.showPlayedProgress,
            shelfShowThumbnailField = preferences.showThumbnailField,
        )
    }
}

/** 下拉刷新指示器最短显示时长，避免一闪而过。 */
private const val MIN_REFRESH_INDICATOR_MS = 500L
