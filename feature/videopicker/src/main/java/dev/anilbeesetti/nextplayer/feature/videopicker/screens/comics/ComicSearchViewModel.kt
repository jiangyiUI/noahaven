package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.SearchHistoryRepository
import dev.anilbeesetti.nextplayer.core.domain.SearchMatcher
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class ComicSearchViewModel(
    private val comicFileIndex: ComicFileIndex,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
    @InjectedParam internal var output: Output,
) : MviViewModel<ComicSearchUiState, ComicSearchUiEvent>() {

    data class Output(val navigateUp: () -> Unit, val openFolder: (String) -> Unit)

    private val stateInternal = MutableStateFlow(value = ComicSearchUiState())
    override val state: StateFlow<ComicSearchUiState> = stateInternal.asStateFlow()

    private val searchQuery = MutableStateFlow(value = "")

    /** 进度写库防抖任务：翻页很快时只落最后一次。 */
    private var progressSaveJob: Job? = null

    init {
        collectSearchHistory()
        collectPopularFolders()
        collectPreferences()
        collectSearchResults()
    }

    private fun collectSearchHistory() {
        viewModelScope.launch {
            searchHistoryRepository.searchHistory.collect { history ->
                stateInternal.update { it.copy(searchHistory = history) }
            }
        }
    }

    /** 常用文件夹：按直接包含的漫画数量取前几个，与图片搜索页一致。 */
    private fun collectPopularFolders() {
        viewModelScope.launch(defaultDispatcher) {
            combine(
                comicFileIndex.entries,
                preferencesRepository.applicationPreferences,
            ) { entries, preferences ->
                entries.orEmpty().filterComics(preferences = preferences)
                    .groupBy { it.parentPath }
                    .map { (path, folderComics) ->
                        summarize(
                            path = path,
                            comics = folderComics,
                        )
                    }
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
                stateInternal.update { it.copy(preferences = prefs.toComicPreferences()) }
            }
        }
    }

    @OptIn(
        FlowPreview::class,
        ExperimentalCoroutinesApi::class,
    )
    private fun collectSearchResults() {
        viewModelScope.launch {
            searchQuery
                .debounce(timeoutMillis = SEARCH_DEBOUNCE_MS)
                .flatMapLatest { query -> search(query = query) }
                .collect { results ->
                    stateInternal.update {
                        it.copy(
                            searchResults = results,
                            isSearching = false,
                        )
                    }
                }
        }
    }

    /** 与图片/视频搜索一致：文件夹按 name + path 打分，漫画按文件名 + 路径打分，按相关性降序。 */
    private fun search(query: String): Flow<ComicSearchResults> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return flowOf(value = ComicSearchResults())
        }
        return combine(
            comicFileIndex.entries,
            preferencesRepository.applicationPreferences,
        ) { entries, preferences ->
            val comics = entries.orEmpty().filterComics(preferences = preferences)
            val matcher = SearchMatcher(query = normalizedQuery)
            val folders = comics.groupBy { it.parentPath }
                .map { (path, folderComics) ->
                    summarize(
                        path = path,
                        comics = folderComics,
                    )
                }
                .mapNotNull { folder ->
                    val score = matcher.calculateScore(
                        folder.name,
                        folder.path,
                    )
                    if (score > 0) folder to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }
            val matchingComics = comics.mapNotNull { comic ->
                val score = matcher.calculateScore(
                    comic.displayName,
                    comic.path,
                )
                if (score > 0) comic to score else null
            }
                .sortedByDescending { it.second }
                .map { it.first }
            ComicSearchResults(
                folders = folders,
                comics = matchingComics,
            )
        }.flowOn(context = defaultDispatcher)
    }

    override fun onAction(action: ComicSearchUiEvent) {
        when (action) {
            is ComicSearchUiEvent.NavigateUp -> output.navigateUp()
            is ComicSearchUiEvent.OpenFolder -> output.openFolder(action.path)
            is ComicSearchUiEvent.OnQueryChange -> onQueryChange(query = action.query)
            is ComicSearchUiEvent.OnSearch -> onSearch(query = action.query)
            is ComicSearchUiEvent.OnHistoryItemClick -> onHistoryItemClick(query = action.query)
            is ComicSearchUiEvent.OnRemoveHistoryItem -> removeHistoryItem(query = action.query)
            is ComicSearchUiEvent.OnClearHistory -> clearHistory()
            is ComicSearchUiEvent.OnComicViewed -> markComicViewed(path = action.path)
        }
    }

    private fun onQueryChange(query: String) {
        stateInternal.update {
            it.copy(
                query = query,
                isSearching = query.isNotBlank(),
            )
        }
        searchQuery.value = query
    }

    private fun onSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch { searchHistoryRepository.addSearchQuery(query = query) }
    }

    private fun onHistoryItemClick(query: String) {
        stateInternal.update {
            it.copy(
                query = query,
                isSearching = true,
            )
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

    private fun markComicViewed(path: String) {
        if (preferencesRepository.applicationPreferences.value.lastViewedComicPath == path) return
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(lastViewedComicPath = path)
            }
        }
    }

    /** 打开查看器前读取上次进度；没有记录时从第一页、默认方向开始。 */
    suspend fun loadProgress(path: String): ComicProgress =
        comicFileIndex.getProgress(path)
            ?: ComicProgress(
                path = path,
                lastPage = 0,
                readingDirection = preferencesRepository.applicationPreferences.value.comicReadingDirection,
            )

    /** 加密漫画：读取已保存的密码。 */
    suspend fun getComicPassword(path: String): String? = comicFileIndex.getPassword(path)

    /** 加密漫画：密码校验成功后保存。 */
    suspend fun saveComicPassword(path: String, password: String) {
        comicFileIndex.savePassword(path = path, password = password)
    }

    /** 加密漫画：已保存的密码失效时清掉。 */
    suspend fun clearComicPassword(path: String) {
        comicFileIndex.deletePassword(path)
    }

    /** 保存这本漫画的页码和方向，反复调用时只写最后一次。 */
    fun saveProgress(path: String, page: Int, direction: ComicReadingDirection) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DELAY_MS)
            comicFileIndex.saveProgress(
                ComicProgress(
                    path = path,
                    lastPage = page,
                    readingDirection = direction,
                ),
            )
        }
    }

    private fun List<ComicFile>.filterComics(preferences: ApplicationPreferences): List<ComicFile> {
        val roots = preferences.comicFolders.map(::normalizePath).toSet()
        return filter {
            normalizePath(it.whitelistRoot) in roots && (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
    }

    private fun summarize(path: String, comics: List<ComicFile>): Folder {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        var dateModified = 0L
        var totalSize = 0L
        var directCount = 0
        val childFolders = HashSet<String>()
        comics.forEach { comic ->
            if (comic.dateModified > dateModified) dateModified = comic.dateModified
            totalSize += comic.size
            if (comic.parentPath == path) {
                directCount++
            } else {
                childFolders.add(comic.path.removePrefix(prefix).substringBefore(PATH_SEPARATOR))
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

    private fun ApplicationPreferences.toComicPreferences(): ApplicationPreferences = copy(
        mediaViewMode = comicViewMode,
        mediaLayoutMode = comicFolderLayoutMode,
        imageFolderLayoutMode = comicFolderLayoutMode,
        imageLayoutMode = comicLayoutMode,
        sortBy = comicSortBy,
        sortOrder = comicSortOrder,
        showDurationField = false,
        showFolderDurationField = false,
        showPlayedProgress = false,
    )

    private companion object {
        const val PATH_SEPARATOR = "/"
        const val SEARCH_DEBOUNCE_MS = 300L
        const val PROGRESS_SAVE_DELAY_MS = 300L
        const val POPULAR_FOLDERS_LIMIT = 5
    }
}

data class ComicSearchResults(
    val folders: List<Folder> = emptyList(),
    val comics: List<ComicFile> = emptyList(),
) {
    val isEmpty: Boolean
        get() = folders.isEmpty() && comics.isEmpty()
}

@Stable
data class ComicSearchUiState(
    val query: String = "",
    val searchHistory: List<String> = emptyList(),
    val popularFolders: List<Folder> = emptyList(),
    val searchResults: ComicSearchResults = ComicSearchResults(),
    val isSearching: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface ComicSearchUiEvent {
    data object NavigateUp : ComicSearchUiEvent
    data class OpenFolder(val path: String) : ComicSearchUiEvent
    data class OnQueryChange(val query: String) : ComicSearchUiEvent
    data class OnSearch(val query: String) : ComicSearchUiEvent
    data class OnHistoryItemClick(val query: String) : ComicSearchUiEvent
    data class OnRemoveHistoryItem(val query: String) : ComicSearchUiEvent
    data object OnClearHistory : ComicSearchUiEvent
    data class OnComicViewed(val path: String) : ComicSearchUiEvent
}

private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
