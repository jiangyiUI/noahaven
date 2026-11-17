package dev.anilbeesetti.nextplayer.feature.videopicker.screens.images

import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.SearchHistoryRepository
import dev.anilbeesetti.nextplayer.core.domain.SearchMatcher
import dev.anilbeesetti.nextplayer.core.media.index.ImageFileIndex
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
class ImageSearchViewModel(
    private val imageFileIndex: ImageFileIndex,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
    @InjectedParam internal var output: Output,
) : MviViewModel<ImageSearchUiState, ImageSearchUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val openFolder: (String) -> Unit,
    )

    private val stateInternal = MutableStateFlow(value = ImageSearchUiState())
    override val state: StateFlow<ImageSearchUiState> = stateInternal.asStateFlow()

    private val searchQuery = MutableStateFlow(value = "")

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

    private fun collectPopularFolders() {
        viewModelScope.launch(defaultDispatcher) {
            combine(
                imageFileIndex.entries,
                preferencesRepository.applicationPreferences,
            ) { entries, preferences ->
                entries.orEmpty().filterImages(preferences = preferences)
                    .groupBy { it.parentPath }
                    .map { (path, folderImages) -> summarize(path = path, images = folderImages) }
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
                stateInternal.update { it.copy(preferences = prefs.toImagePreferences()) }
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

    /** 与视频搜索一致：文件夹按 name + path 打分，图片按文件名 + 路径打分，按相关性降序。 */
    private fun search(query: String): Flow<ImageSearchResults> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return flowOf(value = ImageSearchResults())
        }
        return combine(
            imageFileIndex.entries,
            preferencesRepository.applicationPreferences,
        ) { entries, preferences ->
            val images = entries.orEmpty().filterImages(preferences = preferences)
            val matcher = SearchMatcher(query = normalizedQuery)
            val folders = images.groupBy { it.parentPath }
                .map { (path, folderImages) -> summarize(path = path, images = folderImages) }
                .mapNotNull { folder ->
                    val score = matcher.calculateScore(folder.name, folder.path)
                    if (score > 0) folder to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }
            val matchingImages = images.mapNotNull { image ->
                val score = matcher.calculateScore(image.nameWithExtension, image.path)
                if (score > 0) image to score else null
            }
                .sortedByDescending { it.second }
                .map { it.first }
            ImageSearchResults(
                folders = folders,
                images = matchingImages,
            )
        }.flowOn(context = defaultDispatcher)
    }

    override fun onAction(action: ImageSearchUiEvent) {
        when (action) {
            is ImageSearchUiEvent.NavigateUp -> output.navigateUp()
            is ImageSearchUiEvent.OpenFolder -> output.openFolder(action.path)
            is ImageSearchUiEvent.OnQueryChange -> onQueryChange(query = action.query)
            is ImageSearchUiEvent.OnSearch -> onSearch(query = action.query)
            is ImageSearchUiEvent.OnHistoryItemClick -> onHistoryItemClick(query = action.query)
            is ImageSearchUiEvent.OnRemoveHistoryItem -> removeHistoryItem(query = action.query)
            is ImageSearchUiEvent.OnClearHistory -> clearHistory()
            is ImageSearchUiEvent.OnImageViewed -> markImageViewed(path = action.path)
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

    /** 查看器翻页时记录最后浏览的图片，供图片库「继续查看」使用。 */
    private fun markImageViewed(path: String) {
        if (preferencesRepository.applicationPreferences.value.lastViewedImagePath == path) return
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(lastViewedImagePath = path)
            }
        }
    }

    private fun List<ImageFile>.filterImages(
        preferences: ApplicationPreferences,
    ): List<ImageFile> {
        val roots = preferences.imageFolders.map(::normalizePath).toSet()
        return filter {
            it.whitelistRoot in roots && (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
    }

    private fun summarize(path: String, images: List<ImageFile>): Folder {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        var dateModified = 0L
        var totalSize = 0L
        var directCount = 0
        val childFolders = HashSet<String>()
        images.forEach { image ->
            if (image.dateModified > dateModified) dateModified = image.dateModified
            totalSize += image.size
            if (image.parentPath == path) {
                directCount++
            } else {
                childFolders.add(image.path.removePrefix(prefix).substringBefore(PATH_SEPARATOR))
            }
        }
        val file = File(path)
        return Folder(
            name = file.prettyName,
            path = path,
            parentPath = file.parent,
            dateModified = dateModified,
            totalSize = totalSize,
            videosCount = directCount,
            foldersCount = childFolders.size,
        )
    }

    private fun ApplicationPreferences.toImagePreferences(): ApplicationPreferences = copy(
        mediaViewMode = imageViewMode,
        // 文件夹沿用 MediaLayoutMode，图片布局单独传给 ImageView
        mediaLayoutMode = imageFolderLayoutMode,
        sortBy = imageSortBy,
        sortOrder = imageSortOrder,
        showDurationField = false,
        showFolderDurationField = false,
        showPlayedProgress = false,
    )

    private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    private companion object {
        const val PATH_SEPARATOR = "/"
        const val SEARCH_DEBOUNCE_MS = 300L
        const val POPULAR_FOLDERS_LIMIT = 5
    }
}

data class ImageSearchResults(
    val folders: List<Folder> = emptyList(),
    val images: List<ImageFile> = emptyList(),
) {
    val isEmpty: Boolean
        get() = folders.isEmpty() && images.isEmpty()
}

@Stable
data class ImageSearchUiState(
    val query: String = "",
    val searchHistory: List<String> = emptyList(),
    val popularFolders: List<Folder> = emptyList(),
    val searchResults: ImageSearchResults = ImageSearchResults(),
    val isSearching: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface ImageSearchUiEvent {
    data object NavigateUp : ImageSearchUiEvent
    data class OpenFolder(val path: String) : ImageSearchUiEvent

    data class OnQueryChange(val query: String) : ImageSearchUiEvent
    data class OnSearch(val query: String) : ImageSearchUiEvent
    data class OnHistoryItemClick(val query: String) : ImageSearchUiEvent
    data class OnRemoveHistoryItem(val query: String) : ImageSearchUiEvent
    data object OnClearHistory : ImageSearchUiEvent
    data class OnImageViewed(val path: String) : ImageSearchUiEvent
}
