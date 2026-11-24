package dev.anilbeesetti.nextplayer.settings.screens.medialibrary

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.ImageFileIndex
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class FolderPreferencesViewModel(
    mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val systemService: SystemService,
    private val imageFileIndex: ImageFileIndex,
    private val comicFileIndex: ComicFileIndex,
    private val bookFileIndex: BookFileIndex,
    @InjectedParam internal var output: Output,
) : MviViewModel<FolderPreferencesUiState, FolderPreferencesUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val audioFolders: Boolean = false,
        val imageFolders: Boolean = false,
        val comicFolders: Boolean = false,
        val bookFolders: Boolean = false,
    )

    private val stateInternal = MutableStateFlow(
        value = FolderPreferencesUiState(
            audioFolders = output.audioFolders,
            imageFolders = output.imageFolders,
            comicFolders = output.comicFolders,
            bookFolders = output.bookFolders,
            preferences = preferencesRepository.applicationPreferences.value,
        ),
    )
    override val state: StateFlow<FolderPreferencesUiState> = stateInternal.asStateFlow()

    init {
        if (!output.imageFolders && !output.comicFolders && !output.bookFolders) {
            viewModelScope.launch {
                val folders = if (output.audioFolders) {
                    mediaRepository.observeAudioFolders()
                } else {
                    mediaRepository.observeFolders()
                }
                folders.collect {
                    stateInternal.update { current ->
                        current.copy(foldersDataState = DataState.Success(value = it))
                    }
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                stateInternal.update { current ->
                    current.copy(
                        preferences = preferences,
                        foldersDataState = when {
                            output.imageFolders ->
                                DataState.Success(preferences.imageFolders.map(::imageFolder))

                            output.comicFolders ->
                                DataState.Success(preferences.comicFolders.map(::imageFolder))

                            output.bookFolders ->
                                DataState.Success(preferences.bookFolders.map(::imageFolder))

                            else -> current.foldersDataState
                        },
                    )
                }
            }
        }
    }

    override fun onAction(action: FolderPreferencesUiEvent) {
        when (action) {
            is FolderPreferencesUiEvent.NavigateUp -> output.navigateUp()
            is FolderPreferencesUiEvent.UpdateExcludeList -> updateFolder(action.path)
            FolderPreferencesUiEvent.AddImageFolder -> addImageFolder()
            FolderPreferencesUiEvent.AddComicFolder -> addComicFolder()
            FolderPreferencesUiEvent.AddBookFolder -> addBookFolder()
        }
    }

    private fun addImageFolder() {
        viewModelScope.launch {
            val path = systemService.pickFolder()?.toFolderPath() ?: return@launch
            val oldRoots = preferencesRepository.applicationPreferences.value.imageFolders
            if (path in oldRoots) return@launch
            val newRoots = (oldRoots + path).distinct()
            preferencesRepository.updateApplicationPreferences {
                it.copy(imageFolders = newRoots)
            }
            // 新增白名单只扫描这个目录，已有目录不重扫。
            imageFileIndex.requestRefresh(
                whitelistRoots = newRoots,
                folderPath = path,
                force = true,
            )
        }
    }

    private fun addComicFolder() {
        viewModelScope.launch {
            val path = systemService.pickFolder()?.toFolderPath() ?: return@launch
            val oldRoots = preferencesRepository.applicationPreferences.value.comicFolders
            if (path in oldRoots) return@launch
            val newRoots = (oldRoots + path).distinct()
            preferencesRepository.updateApplicationPreferences {
                it.copy(comicFolders = newRoots)
            }
            // 新增白名单只扫描这个目录，已有目录不重扫。
            comicFileIndex.requestRefresh(
                whitelistRoots = newRoots,
                folderPath = path,
                force = true,
            )
        }
    }

    private fun addBookFolder() {
        viewModelScope.launch {
            val path = systemService.pickFolder()?.toFolderPath() ?: return@launch
            val oldRoots = preferencesRepository.applicationPreferences.value.bookFolders
            if (path in oldRoots) return@launch
            val newRoots = (oldRoots + path).distinct()
            preferencesRepository.updateApplicationPreferences {
                it.copy(bookFolders = newRoots)
            }
            // 新增白名单只扫描这个目录，已有目录不重扫。
            bookFileIndex.requestRefresh(
                whitelistRoots = newRoots,
                folderPath = path,
                force = true,
            )
        }
    }

    private fun updateFolder(path: String) {
        viewModelScope.launch {
            val preferences = preferencesRepository.applicationPreferences.value
            when {
                output.imageFolders -> {
                    val newRoots = preferences.imageFolders.toggle(path)
                    preferencesRepository.updateApplicationPreferences {
                        it.copy(imageFolders = newRoots)
                    }
                    if (path in newRoots) {
                        imageFileIndex.requestRefresh(
                            whitelistRoots = newRoots,
                            folderPath = path,
                            force = true,
                        )
                    } else {
                        imageFileIndex.removeRoots(
                            roots = listOf(path),
                            retainedRoots = newRoots,
                        )
                    }
                }

                output.comicFolders -> {
                    val newRoots = preferences.comicFolders.toggle(path)
                    preferencesRepository.updateApplicationPreferences {
                        it.copy(comicFolders = newRoots)
                    }
                    // 重新协调全部白名单：已移除根目录的漫画会被清理。
                    comicFileIndex.requestRefresh(
                        whitelistRoots = newRoots,
                        folderPath = null,
                        force = true,
                    )
                }

                output.bookFolders -> {
                    val newRoots = preferences.bookFolders.toggle(path)
                    preferencesRepository.updateApplicationPreferences {
                        it.copy(bookFolders = newRoots)
                    }
                    // 重新协调全部白名单：已移除根目录的书会被清理。
                    bookFileIndex.requestRefresh(
                        whitelistRoots = newRoots,
                        folderPath = null,
                        force = true,
                    )
                }

                output.audioFolders -> preferencesRepository.updateApplicationPreferences {
                    it.copy(excludeAudioFolders = it.excludeAudioFolders.toggle(path))
                }

                else -> preferencesRepository.updateApplicationPreferences {
                    it.copy(excludeFolders = it.excludeFolders.toggle(path))
                }
            }
        }
    }

    private fun imageFolder(path: String): Folder = Folder(
        name = File(path).prettyName,
        path = path,
        dateModified = File(path).lastModified(),
    )

    private fun Uri.toFolderPath(): String? {
        if (scheme == "file") return path
        if (authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
            ?: return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(
            ':',
            missingDelimiterValue = "",
        ).trim('/')
        val root = if (volume.equals(
                "primary",
                ignoreCase = true,
            )
        ) {
            Environment.getExternalStorageDirectory().path
        } else {
            "/storage/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    private fun List<String>.toggle(path: String): List<String> =
        if (path in this) this - path else this + path
}

data class FolderPreferencesUiState(
    val foldersDataState: DataState<List<Folder>> = DataState.Loading,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val audioFolders: Boolean = false,
    val imageFolders: Boolean = false,
    val comicFolders: Boolean = false,
    val bookFolders: Boolean = false,
)

sealed interface FolderPreferencesUiEvent {
    data object NavigateUp : FolderPreferencesUiEvent
    data object AddImageFolder : FolderPreferencesUiEvent
    data object AddComicFolder : FolderPreferencesUiEvent
    data object AddBookFolder : FolderPreferencesUiEvent
    data class UpdateExcludeList(val path: String) : FolderPreferencesUiEvent
}
