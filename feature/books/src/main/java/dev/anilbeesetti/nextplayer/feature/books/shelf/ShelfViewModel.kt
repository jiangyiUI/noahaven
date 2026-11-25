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

/** 书架展示项：同一个文件可能在多个分组里出现，移除时按记录 id 一起删掉。 */
data class ShelfBook(
    val book: BookFile,
    val entryIds: List<Long>,
)

/** 状态栏筛选：全部 / 未读 / 阅读中 / 读完。判定依据见 [ShelfViewModel.statusOf]。 */
enum class ShelfStatus { ALL, UNREAD, READING, FINISHED }

data class ShelfUiState(
    /** 全部分组（扁平，含 parentId），UI 按当前层级自行过滤。 */
    val groups: List<BookShelfGroup> = emptyList(),
    /** 固定「未分组」的 id。 */
    val ungroupedId: Long? = null,
    /** 当前进入的分组：null 表示在书架首页（显示顶级分组 + 未分组的书），非 null 表示在该分组内。 */
    val navigatedGroupId: Long? = null,
    /** 当前状态栏选中项。 */
    val selectedStatus: ShelfStatus = ShelfStatus.ALL,
    /** 当前展示的书籍：全部状态下是当前分组直接的书；其他状态下是全书架按状态过滤后的扁平列表。 */
    val books: List<ShelfBook> = emptyList(),
    /** 全书架去重后的扁平书籍列表，用于状态栏计数和非全部状态过滤。 */
    val allBooks: List<ShelfBook> = emptyList(),
    /** 每个状态的书籍数（含 ALL），全书架去重后统计。 */
    val statusCounts: Map<ShelfStatus, Int> = emptyMap(),
    /** 每个分组递归的书籍数（自身 + 所有子孙分组，按路径去重），用于文件夹卡片数字。 */
    val groupCounts: Map<Long, Int> = emptyMap(),
    /** 每个分组递归的书籍总大小（按路径去重），用于文件夹卡片显示大小。 */
    val groupSizes: Map<Long, Long> = emptyMap(),
    /** 每个分组直接的书籍记录数（不含子孙），用于判断「该分组本身有没有书」。 */
    val directCounts: Map<Long, Int> = emptyMap(),
    /** 书架全部书籍数（跨分组按路径去重），用于空状态判断。 */
    val allCount: Int = 0,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val lastViewedBookPath: String? = null,
    val isLoading: Boolean = true,
    val refreshing: Boolean = false,
)

sealed interface ShelfEvent {
    data class Added(val count: Int) : ShelfEvent
    /** 已在书架而被跳过的书：path -> 所在分组 id。一次携带全部，UI 合并成一个对话框展示。 */
    data class AlreadyInShelf(val pathToGroup: Map<String, Long>) : ShelfEvent
}

/** 收藏书架：多级分组浏览、移除条目、从「浏览」页批量加入。 */
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
        /** 固定「未分组」的名称，由调用方传入本地化文案。 */
        val defaultGroupName: String = "",
    )

    var output: Output = Output()

    /** 当前进入的分组 id，null=书架首页（顶级分组 + 未分组的书）。 */
    private val navigatedGroupId = MutableStateFlow<Long?>(null)

    /** 当前状态栏选中项。 */
    private val selectedStatus = MutableStateFlow(ShelfStatus.ALL)

    private val refreshing = MutableStateFlow(false)

    private val eventsInternal = MutableSharedFlow<ShelfEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ShelfEvent> = eventsInternal

    val state = combine(
        repository.groups,
        repository.observeItems(groupId = null),
        bookFileIndex.entries,
        preferencesRepository.applicationPreferences,
        combine(refreshing, navigatedGroupId, selectedStatus) { r, ng, ss -> Triple(r, ng, ss) },
    ) { groups, entries, bookFiles, preferences, nav ->
        toUiState(
            groups = groups,
            entries = entries,
            bookFiles = bookFiles,
            preferences = preferences,
            refreshing = nav.first,
            navigatedGroupId = nav.second,
            selectedStatus = nav.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ShelfUiState(
            preferences = preferencesRepository.applicationPreferences.value.toShelfPreferences(),
        ),
    )

    /** 确保固定「未分组」存在（屏幕设置好 output 后调用一次）。 */
    fun ensureUngrouped() {
        viewModelScope.launch { repository.ensureUngroupedGroup(output.defaultGroupName) }
    }

    /** 点进某个分组：进入该分组内（子分组文件夹 + 该分组直接的书），固定在「全部」状态下。 */
    fun onGroupClick(groupId: Long) {
        val group = state.value.groups.firstOrNull { it.id == groupId } ?: return
        if (group.isUngrouped) return
        selectedStatus.value = ShelfStatus.ALL
        navigatedGroupId.value = groupId
    }

    /** 切换状态栏：非全部状态一律回到首页，不保留分组下钻。 */
    fun onStatusSelected(status: ShelfStatus) {
        selectedStatus.value = status
        if (status != ShelfStatus.ALL) {
            navigatedGroupId.value = null
        }
    }

    /** 返回上一层：在分组内则回到父分组（或首页）；已在首页不消费。 */
    fun onBack(): Boolean {
        val current = navigatedGroupId.value ?: return false
        val parent = state.value.groups.firstOrNull { it.id == current }?.parentId
        navigatedGroupId.value = parent
        return true
    }

    fun removeFromShelf(entryIds: Collection<Long>) {
        if (entryIds.isEmpty()) return
        viewModelScope.launch { repository.removeItems(entryIds) }
    }

    /** 加入书架：已有分组直接用，否则用输入的名字新建（当前层级），都没给就进「未分组」。 */
    fun addToShelf(groupId: Long?, newGroupName: String, paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val targetGroupId = when {
                groupId != null -> groupId
                newGroupName.isNotBlank() -> repository.createGroup(
                    name = newGroupName,
                    parentId = navigatedGroupId.value,
                )
                else -> repository.ensureDefaultGroup(output.defaultGroupName)
            }
            val result = repository.addPaths(groupId = targetGroupId, paths = paths)
            if (result.added > 0) {
                eventsInternal.emit(ShelfEvent.Added(count = result.added))
            }
            if (result.alreadyExists.isNotEmpty()) {
                eventsInternal.emit(ShelfEvent.AlreadyInShelf(pathToGroup = result.alreadyExists))
            }
        }
    }

    fun updatePreferences(preferences: ApplicationPreferences) {
        viewModelScope.launch { preferencesRepository.updateShelfPreferences(preferences) }
    }

    /** 分组栏最右的添加按钮：在当前所在层级新建分组。 */
    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createGroup(name = name, parentId = navigatedGroupId.value) }
    }

    /** 长按分组 → 添加子分组。 */
    fun createSubGroup(parentId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createSubGroup(parentId = parentId, name = name) }
    }

    fun renameGroup(groupId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameGroup(groupId = groupId, name = name) }
    }

    /** 删除分组及其所有子孙分组和书籍记录（「未分组」受保护）。 */
    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            repository.deleteGroupRecursive(groupId)
            val nav = navigatedGroupId.value
            if (nav != null && nav in subtreeGroupIds(state.value.groups, rootId = groupId, includeSelf = true)) {
                navigatedGroupId.value = null
            }
        }
    }

    /** 把分组挪到同级的第 [targetIndex] 个（从 0 开始），只在同父兄弟间重排。 */
    fun moveGroupTo(groupId: Long, targetIndex: Int) {
        viewModelScope.launch { repository.moveGroupTo(groupId = groupId, targetIndex = targetIndex) }
    }

    /** 移动整个分组子树到目标父分组下，targetParentId=null 表示移到顶级。 */
    fun moveGroupSubtreeTo(groupId: Long, targetParentId: Long?) {
        viewModelScope.launch { repository.moveGroupSubtree(groupId = groupId, newParentId = targetParentId) }
    }

    /** 删除确认弹窗要用：子树含多少个分组、多少本（去重）书。 */
    fun deleteImpact(groupId: Long): Pair<Int, Int> {
        val state = state.value
        val groupCount = subtreeGroupIds(state.groups, rootId = groupId, includeSelf = true).size
        val bookCount = state.groupCounts[groupId] ?: 0
        return groupCount to bookCount
    }

    fun renameBook(path: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
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

    fun moveBookToGroup(book: ShelfBook, groupId: Long?, newGroupName: String) {
        moveBooksToGroup(listOf(book), groupId, newGroupName)
    }

    fun moveBooksToGroup(books: List<ShelfBook>, groupId: Long?, newGroupName: String) {
        viewModelScope.launch {
            repository.moveItems(
                ids = books.flatMap { it.entryIds },
                targetGroupId = resolveGroupId(groupId = groupId, newGroupName = newGroupName),
            )
        }
    }

    fun removeBooksFromShelf(books: List<ShelfBook>) {
        viewModelScope.launch { repository.removeItems(books.flatMap { it.entryIds }) }
    }

    /** 批量更改书架书籍的已读/未读状态（只改索引里的 finished 标记，不动文件）。 */
    fun setBooksFinished(paths: List<String>, finished: Boolean) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            paths.forEach { bookFileIndex.setFinished(path = it, finished = finished) }
        }
    }

    private suspend fun resolveGroupId(groupId: Long?, newGroupName: String): Long = when {
        groupId != null -> groupId
        newGroupName.isNotBlank() -> repository.createGroup(name = newGroupName, parentId = navigatedGroupId.value)
        else -> repository.ensureDefaultGroup(output.defaultGroupName)
    }

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
        refreshing: Boolean,
        navigatedGroupId: Long?,
        selectedStatus: ShelfStatus,
    ): ShelfUiState {
        val ungroupedId = groups.firstOrNull { it.isUngrouped }?.id
        val directCounts = entries.groupingBy { it.groupId }.eachCount()
        val indexed = bookFiles.orEmpty().associateBy { it.path }
        val sizeByPath = indexed.mapValues { (_, b) -> b.size }

        // 全书架去重扁平列表（跨分组），用于状态栏计数和非全部状态过滤。
        val allBooksRaw = entries.groupBy { it.path }.map { (path, pathEntries) ->
            ShelfBook(book = indexed[path] ?: fallbackBook(path), entryIds = pathEntries.map { it.id })
        }
        val allBooks = sortShelfBooks(allBooksRaw, entries, preferences)

        // 当前分组直接的书：首页=未分组的书；进入分组=该分组直接的书。
        val effectiveDisplayed = navigatedGroupId ?: ungroupedId
        val visibleEntries = entries.filter { it.groupId == effectiveDisplayed }
        val displayedBooksRaw = visibleEntries.groupBy { it.path }.map { (path, pathEntries) ->
            ShelfBook(book = indexed[path] ?: fallbackBook(path), entryIds = pathEntries.map { it.id })
        }
        val displayedBooks = sortShelfBooks(displayedBooksRaw, visibleEntries, preferences)

        val books = if (selectedStatus == ShelfStatus.ALL) {
            displayedBooks
        } else {
            allBooks.filter { statusOf(it.book) == selectedStatus }
        }

        val statusCounts = mapOf(
            ShelfStatus.ALL to allBooks.size,
            ShelfStatus.UNREAD to allBooks.count { statusOf(it.book) == ShelfStatus.UNREAD },
            ShelfStatus.READING to allBooks.count { statusOf(it.book) == ShelfStatus.READING },
            ShelfStatus.FINISHED to allBooks.count { statusOf(it.book) == ShelfStatus.FINISHED },
        )

        return ShelfUiState(
            groups = groups,
            ungroupedId = ungroupedId,
            navigatedGroupId = navigatedGroupId,
            selectedStatus = selectedStatus,
            books = books,
            allBooks = allBooks,
            statusCounts = statusCounts,
            groupCounts = recursiveGroupCounts(groups = groups, entries = entries),
            groupSizes = recursiveGroupSizes(groups = groups, entries = entries, sizeByPath = sizeByPath),
            directCounts = directCounts,
            allCount = allBooks.size,
            preferences = preferences.toShelfPreferences(),
            lastViewedBookPath = preferences.lastViewedBookPath,
            isLoading = bookFiles == null,
            refreshing = refreshing,
        )
    }

    /** 阅读状态判定：读完优先，其次从未打开=未读，其余=阅读中。 */
    private fun statusOf(book: BookFile): ShelfStatus = when {
        book.finished -> ShelfStatus.FINISHED
        book.lastOpenedAt == 0L -> ShelfStatus.UNREAD
        else -> ShelfStatus.READING
    }

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

/** 每个分组递归的去重书籍数（自身 + 所有子孙）。 */
internal fun recursiveGroupCounts(
    groups: List<BookShelfGroup>,
    entries: List<BookShelfEntry>,
): Map<Long, Int> {
    val byParent = groups.groupBy { it.parentId }
    val pathsByGroup = entries.groupBy { it.groupId }
        .mapValues { (_, es) -> es.map { it.path }.distinct() }
    val memo = mutableMapOf<Long, Set<String>>()
    fun pathsOf(id: Long): Set<String> = memo.getOrPut(id) {
        val set = pathsByGroup[id].orEmpty().toMutableSet()
        for (child in byParent[id].orEmpty()) set.addAll(pathsOf(child.id))
        set
    }
    return groups.associate { it.id to pathsOf(it.id).size }
}

/** 每个分组递归的去重书籍总大小（自身 + 所有子孙，按路径去重后累加）。 */
internal fun recursiveGroupSizes(
    groups: List<BookShelfGroup>,
    entries: List<BookShelfEntry>,
    sizeByPath: Map<String, Long>,
): Map<Long, Long> {
    val byParent = groups.groupBy { it.parentId }
    val pathsByGroup = entries.groupBy { it.groupId }
        .mapValues { (_, es) -> es.map { it.path }.distinct() }
    val memo = mutableMapOf<Long, Set<String>>()
    fun pathsOf(id: Long): Set<String> = memo.getOrPut(id) {
        val set = pathsByGroup[id].orEmpty().toMutableSet()
        for (child in byParent[id].orEmpty()) set.addAll(pathsOf(child.id))
        set
    }
    return groups.associate { it.id to pathsOf(it.id).sumOf { sizeByPath[it] ?: 0L } }
}

/** 按书架偏好排序书籍列表（用于展示列表与全书架扁平列表）。 */
internal fun sortShelfBooks(
    books: List<ShelfBook>,
    entries: List<BookShelfEntry>,
    preferences: ApplicationPreferences,
): List<ShelfBook> {
    val sort = Sort(by = preferences.shelfSortBy, order = preferences.shelfSortOrder)
    val baseBookComparator = sort.bookComparator()
    val baseComparator = Comparator<ShelfBook> { a, b -> baseBookComparator.compare(a.book, b.book) }
    return when (sort.by) {
        Sort.By.SHELF_ADDED -> {
            val entryAddedAt = entries.groupBy { it.path }
                .mapValues { (_, es) -> es.minOf { it.addedAt } }
            books.sortedWith(
                compareBy<ShelfBook> { entryAddedAt[it.book.path] ?: 0L }
                    .then(baseComparator)
                    .run { if (sort.order == Sort.Order.DESCENDING) reversed() else this },
            )
        }

        Sort.By.LAST_VIEWED -> {
            val lastViewed = preferences.bookLastViewedAt
            books.sortedWith(
                compareBy<ShelfBook> { lastViewed[it.book.path] ?: Long.MIN_VALUE }
                    .then(baseComparator)
                    .run { if (sort.order == Sort.Order.DESCENDING) reversed() else this },
            )
        }

        else -> books.sortedWith(baseComparator)
    }
}

/** 某分组及其所有子孙分组的 id 集合。 */
internal fun subtreeGroupIds(
    groups: List<BookShelfGroup>,
    rootId: Long,
    includeSelf: Boolean,
): Set<Long> {
    val byParent = groups.groupBy { it.parentId }
    val result = mutableSetOf<Long>()
    if (includeSelf) result += rootId
    val queue = ArrayDeque<Long>().apply { add(rootId) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (child in byParent[current].orEmpty()) {
            if (result.add(child.id)) queue.add(child.id)
        }
    }
    return result
}

/** 把书架 tab 专属字段映射成通用 ApplicationPreferences，供 QuickSettingsDialog 绑定。 */
internal fun ApplicationPreferences.toShelfPreferences(): ApplicationPreferences = copy(
    mediaViewMode = MediaViewMode.VIDEOS,
    mediaLayoutMode = MediaLayoutMode.LIST,
    imageFolderLayoutMode = MediaLayoutMode.LIST,
    imageLayoutMode = shelfLayoutMode,
    sortBy = shelfSortBy,
    sortOrder = shelfSortOrder,
    showSizeField = shelfShowSizeField,
    showExtensionField = shelfShowExtensionField,
    showPlayedProgress = shelfShowPlayedProgressField,
    showThumbnailField = shelfShowThumbnailField,
    showDurationField = false,
    showFolderDurationField = false,
    showPathField = false,
    showResolutionField = false,
)

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

private const val MIN_REFRESH_INDICATOR_MS = 500L
