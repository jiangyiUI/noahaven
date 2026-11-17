package dev.anilbeesetti.nextplayer.feature.videopicker.screens.music

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.SearchHistoryRepository
import dev.anilbeesetti.nextplayer.core.domain.SearchMatcher
import dev.anilbeesetti.nextplayer.core.media.index.AudioFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.AudioIndexEntry
import dev.anilbeesetti.nextplayer.core.media.index.toAudioFile
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.Folder
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
class MusicSearchViewModel(
    private val audioFileIndex: AudioFileIndex,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
    @InjectedParam internal var output: Output,
) : MviViewModel<MusicSearchUiState, MusicSearchUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val playAudios: (playlist: List<Uri>, startUri: Uri) -> Unit,
        val openFolder: (String) -> Unit,
    )

    private val stateInternal = MutableStateFlow(value = MusicSearchUiState())
    override val state: StateFlow<MusicSearchUiState> = stateInternal.asStateFlow()

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
                audioFileIndex.entries,
                preferencesRepository.applicationPreferences,
            ) { entries, preferences ->
                val audios = entries.orEmpty().filterAudios(preferences = preferences)
                audios.groupBy { it.parentPath }
                    .map { (path, folderAudios) -> summarize(path = path, audios = folderAudios) }
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
                stateInternal.update { it.copy(preferences = prefs.toMusicPreferences()) }
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

    /** 与视频搜索一致：文件夹按 name + path 打分，曲目按文件名 + 路径打分，按相关性降序。 */
    private fun search(query: String): Flow<MusicSearchResults> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return flowOf(value = MusicSearchResults())
        }
        return combine(
            audioFileIndex.entries,
            preferencesRepository.applicationPreferences,
        ) { entries, preferences ->
            val audios = entries.orEmpty().filterAudios(preferences = preferences)
            val matcher = SearchMatcher(query = normalizedQuery)
            val folders = audios.groupBy { it.parentPath }
                .map { (path, folderAudios) -> summarize(path = path, audios = folderAudios) }
                .mapNotNull { folder ->
                    val score = matcher.calculateScore(folder.name, folder.path)
                    if (score > 0) folder to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }
            val matchingAudios = audios.mapNotNull { audio ->
                val score = matcher.calculateScore(audio.nameWithExtension, audio.path)
                if (score > 0) audio to score else null
            }
                .sortedByDescending { it.second }
                .map { it.first }
            MusicSearchResults(
                folders = folders,
                audios = matchingAudios,
            )
        }.flowOn(context = defaultDispatcher)
    }

    override fun onAction(action: MusicSearchUiEvent) {
        when (action) {
            is MusicSearchUiEvent.NavigateUp -> output.navigateUp()
            is MusicSearchUiEvent.PlayAudio -> playAudio(audio = action.audio)
            is MusicSearchUiEvent.OpenFolder -> openFolder(path = action.path)
            is MusicSearchUiEvent.OnQueryChange -> onQueryChange(query = action.query)
            is MusicSearchUiEvent.OnSearch -> onSearch(query = action.query)
            is MusicSearchUiEvent.OnHistoryItemClick -> onHistoryItemClick(query = action.query)
            is MusicSearchUiEvent.OnRemoveHistoryItem -> removeHistoryItem(query = action.query)
            is MusicSearchUiEvent.OnClearHistory -> clearHistory()
        }
    }

    private fun playAudio(audio: AudioFile) {
        val playlist = stateInternal.value.searchResults.audios.map { it.uriString.toUri() }
        val startUri = audio.uriString.toUri()
        if (startUri !in playlist) return
        // 点播即视为一次有效搜索，写入搜索历史
        addSearchQuery(query = stateInternal.value.query)
        output.playAudios(playlist, startUri)
    }

    private fun openFolder(path: String) {
        addSearchQuery(query = stateInternal.value.query)
        output.openFolder(path)
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
        addSearchQuery(query = query)
    }

    private fun onHistoryItemClick(query: String) {
        stateInternal.update {
            it.copy(
                query = query,
                isSearching = true,
            )
        }
        searchQuery.value = query
        addSearchQuery(query = query)
    }

    private fun addSearchQuery(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch { searchHistoryRepository.addSearchQuery(query = query) }
    }

    private fun removeHistoryItem(query: String) {
        viewModelScope.launch { searchHistoryRepository.removeSearchQuery(query = query) }
    }

    private fun clearHistory() {
        viewModelScope.launch { searchHistoryRepository.clearHistory() }
    }

    private fun List<AudioIndexEntry>.filterAudios(
        preferences: ApplicationPreferences,
    ): List<AudioFile> {
        val excludeFolders = preferences.excludeAudioFolders
        return map { it.toAudioFile() }.filterNot { audio ->
            excludeFolders.any { folder ->
                audio.parentPath == folder ||
                    audio.parentPath.startsWith(prefix = folder.trimEnd('/') + PATH_SEPARATOR)
            }
        }
    }

    private fun summarize(path: String, audios: List<AudioFile>): Folder {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        var dateModified = 0L
        var totalSize = 0L
        var totalDuration = 0L
        var directCount = 0
        val childFolders = HashSet<String>()
        audios.forEach { audio ->
            if (audio.dateModified > dateModified) dateModified = audio.dateModified
            totalSize += audio.size
            totalDuration += audio.duration
            if (audio.parentPath == path) {
                directCount++
            } else {
                childFolders.add(audio.path.removePrefix(prefix = prefix).substringBefore(PATH_SEPARATOR))
            }
        }
        val file = File(path)
        return Folder(
            name = file.prettyName,
            path = path,
            parentPath = file.parent,
            dateModified = dateModified,
            totalSize = totalSize,
            totalDuration = totalDuration,
            videosCount = directCount,
            foldersCount = childFolders.size,
        )
    }

    private fun ApplicationPreferences.toMusicPreferences(): ApplicationPreferences = copy(
        mediaViewMode = musicViewMode,
        mediaLayoutMode = musicLayoutMode,
        sortBy = musicSortBy,
        sortOrder = musicSortOrder,
    )

    private companion object {
        const val PATH_SEPARATOR = "/"
        const val SEARCH_DEBOUNCE_MS = 300L
        const val POPULAR_FOLDERS_LIMIT = 5
    }
}

data class MusicSearchResults(
    val folders: List<Folder> = emptyList(),
    val audios: List<AudioFile> = emptyList(),
) {
    val isEmpty: Boolean
        get() = folders.isEmpty() && audios.isEmpty()
}

@Stable
data class MusicSearchUiState(
    val query: String = "",
    val searchHistory: List<String> = emptyList(),
    val popularFolders: List<Folder> = emptyList(),
    val searchResults: MusicSearchResults = MusicSearchResults(),
    val isSearching: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface MusicSearchUiEvent {
    data object NavigateUp : MusicSearchUiEvent
    data class PlayAudio(val audio: AudioFile) : MusicSearchUiEvent
    data class OpenFolder(val path: String) : MusicSearchUiEvent

    data class OnQueryChange(val query: String) : MusicSearchUiEvent
    data class OnSearch(val query: String) : MusicSearchUiEvent
    data class OnHistoryItemClick(val query: String) : MusicSearchUiEvent
    data class OnRemoveHistoryItem(val query: String) : MusicSearchUiEvent
    data object OnClearHistory : MusicSearchUiEvent
}
