package dev.anilbeesetti.nextplayer.feature.books.search

import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.SearchHistoryRepository
import dev.anilbeesetti.nextplayer.core.domain.SearchMatcher
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfRepository
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

/** 书籍搜索页：与漫画搜索共用搜索历史、常用文件夹和搜索算法。 */
@KoinViewModel
class BookSearchViewModel(
    private val bookFileIndex: BookFileIndex,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
    @InjectedParam internal var output: Output,
    @InjectedParam private val shelfOnly: Boolean = false,
    @InjectedParam private val shelfRepository: BookShelfRepository? = null,
) : MviViewModel<BookSearchUiState, BookSearchUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val openFolder: (String) -> Unit,
        val defaultGroupName: String = "",
    )

    private val stateInternal = MutableStateFlow(value = BookSearchUiState(shelfOnly = shelfOnly))
    override val state: StateFlow<BookSearchUiState> = stateInternal.asStateFlow()

    private val searchQuery = MutableStateFlow(value = "")

    init {
        collectSearchHistory()
        collectPopularFolders()
        collectPreferences()
        collectSearchResults()
        collectShelfGroups()
    }

    private fun collectShelfGroups() {
        val repo = shelfRepository ?: return
        viewModelScope.launch {
            repo.groups.collect { groups ->
                stateInternal.update { it.copy(shelfGroups = groups) }
            }
        }
    }

    private fun collectSearchHistory() {
        viewModelScope.launch {
            searchHistoryRepository.searchHistory.collect { history ->
                stateInternal.update { it.copy(searchHistory = history) }
            }
        }
    }

    private fun collectPopularFolders() {
        // 书架模式不显示常用文件夹
        if (shelfOnly) return
        viewModelScope.launch(defaultDispatcher) {
            combine(
                bookFileIndex.entries,
                preferencesRepository.applicationPreferences,
            ) { entries, preferences ->
                entries.orEmpty().filterBooks(preferences = preferences)
                    .groupBy { it.parentPath }
                    .map { (path, books) -> summarize(path = path, books = books) }
                    .sortedByDescending { it.videosCount }
                    .take(n = POPULAR_FOLDERS_LIMIT)
            }.collect { folders ->
                stateInternal.update { it.copy(popularFolders = folders) }
            }
        }
    }

    private fun collectPreferences() {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                stateInternal.update { it.copy(preferences = prefs.toBookPreferences()) }
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun collectSearchResults() {
        viewModelScope.launch {
            val shelfPathsFlow = if (shelfOnly && shelfRepository != null) {
                shelfRepository.observeItems(groupId = null).map { entries ->
                    entries.map { it.path }.toSet()
                }
            } else {
                flowOf(emptySet())
            }
            searchQuery
                .debounce(timeoutMillis = SEARCH_DEBOUNCE_MS)
                .flatMapLatest { query ->
                    search(query = query, shelfPathsFlow = shelfPathsFlow)
                }
                .collect { results ->
                    stateInternal.update {
                        it.copy(searchResults = results, isSearching = false)
                    }
                }
        }
    }

    /** 文件夹按 name + path 打分，书籍按标题 + 作者 + 路径打分，按相关性降序。 */
    private fun search(query: String, shelfPathsFlow: Flow<Set<String>>): Flow<BookSearchResults> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return flowOf(value = BookSearchResults())
        val booksFlow = combine(
            bookFileIndex.entries,
            preferencesRepository.applicationPreferences,
            shelfPathsFlow,
        ) { entries, preferences, shelfPaths ->
            val all = entries.orEmpty().filterBooks(preferences = preferences)
            if (shelfOnly && shelfPaths.isNotEmpty()) {
                all.filter { it.path in shelfPaths }
            } else {
                all
            }
        }
        return combine(booksFlow, shelfPathsFlow) { books, _ ->
            val matcher = SearchMatcher(query = normalizedQuery)
            val folders = if (shelfOnly) {
                // 书架搜索不显示文件夹结果
                emptyList<Folder>()
            } else {
                books.groupBy { it.parentPath }
                    .map { (path, folderBooks) -> summarize(path = path, books = folderBooks) }
                    .mapNotNull { folder ->
                        val score = matcher.calculateScore(folder.name, folder.path)
                        if (score > 0) folder to score else null
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
            }
            val matchingBooks = books.mapNotNull { book ->
                val score = maxOf(
                    matcher.calculateScore(book.title, book.path),
                    if (book.author.isBlank()) 0 else matcher.calculateScore(book.author, book.path),
                )
                if (score > 0) book to score else null
            }
                .sortedByDescending { it.second }
                .map { it.first }
            BookSearchResults(folders = folders, books = matchingBooks)
        }.flowOn(context = defaultDispatcher)
    }

    override fun onAction(action: BookSearchUiEvent) {
        when (action) {
            BookSearchUiEvent.NavigateUp -> output.navigateUp()
            is BookSearchUiEvent.OpenFolder -> output.openFolder(action.path)
            is BookSearchUiEvent.OnQueryChange -> onQueryChange(query = action.query)
            is BookSearchUiEvent.OnSearch -> onSearch(query = action.query)
            is BookSearchUiEvent.OnHistoryItemClick -> onHistoryItemClick(query = action.query)
            is BookSearchUiEvent.OnRemoveHistoryItem -> removeHistoryItem(query = action.query)
            BookSearchUiEvent.OnClearHistory -> clearHistory()
        }
    }

    private fun onQueryChange(query: String) {
        stateInternal.update {
            it.copy(query = query, isSearching = query.isNotBlank())
        }
        searchQuery.value = query
    }

    private fun onSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch { searchHistoryRepository.addSearchQuery(query = query) }
    }

    private fun onHistoryItemClick(query: String) {
        stateInternal.update {
            it.copy(query = query, isSearching = true)
        }
        searchQuery.value = query
        onSearch(query = query)
    }

    private fun removeHistoryItem(query: String) {
        viewModelScope.launch { searchHistoryRepository.removeSearchQuery(query = query) }
    }

    private fun clearHistory() {
        viewModelScope.launch { searchHistoryRepository.clearHistory() }
    }

    /** 搜索结果加入书架：已有分组直接用，否则用输入的名字在顶级新建，都没给就进「未分组」。 */
    fun addToShelf(groupId: Long?, newGroupName: String, paths: List<String>) {
        val repo = shelfRepository ?: return
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val targetGroupId = when {
                groupId != null -> groupId
                newGroupName.isNotBlank() -> repo.createGroup(name = newGroupName, parentId = null)
                else -> repo.ensureDefaultGroup(output.defaultGroupName)
            }
            repo.addPaths(groupId = targetGroupId, paths = paths)
        }
    }

    private fun List<BookFile>.filterBooks(preferences: ApplicationPreferences): List<BookFile> {
        val roots = preferences.bookFolders.map(::normalizePath).toSet()
        return filter {
            normalizePath(it.whitelistRoot) in roots &&
                (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
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

    private fun ApplicationPreferences.toBookPreferences(): ApplicationPreferences = copy(
        mediaViewMode = bookViewMode,
        mediaLayoutMode = bookFolderLayoutMode,
        imageFolderLayoutMode = bookFolderLayoutMode,
        imageLayoutMode = bookLayoutMode,
        sortBy = bookSortBy,
        sortOrder = bookSortOrder,
        showDurationField = false,
        showFolderDurationField = false,
        showResolutionField = false,
        showPathField = false,
    )

    private companion object {
        const val PATH_SEPARATOR = "/"
        const val SEARCH_DEBOUNCE_MS = 300L
        const val POPULAR_FOLDERS_LIMIT = 5
    }
}

data class BookSearchResults(
    val folders: List<Folder> = emptyList(),
    val books: List<BookFile> = emptyList(),
) {
    val isEmpty: Boolean
        get() = folders.isEmpty() && books.isEmpty()
}

@Stable
data class BookSearchUiState(
    val query: String = "",
    val searchHistory: List<String> = emptyList(),
    val popularFolders: List<Folder> = emptyList(),
    val searchResults: BookSearchResults = BookSearchResults(),
    val isSearching: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    /** 加入书架弹窗用的分组列表（含未分组），从书架仓库实时收集。 */
    val shelfGroups: List<BookShelfGroup> = emptyList(),
    /** true = 书架专用搜索，不搜文件夹。 */
    val shelfOnly: Boolean = false,
)

sealed interface BookSearchUiEvent {
    data object NavigateUp : BookSearchUiEvent
    data class OpenFolder(val path: String) : BookSearchUiEvent
    data class OnQueryChange(val query: String) : BookSearchUiEvent
    data class OnSearch(val query: String) : BookSearchUiEvent
    data class OnHistoryItemClick(val query: String) : BookSearchUiEvent
    data class OnRemoveHistoryItem(val query: String) : BookSearchUiEvent
    data object OnClearHistory : BookSearchUiEvent
}

private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
