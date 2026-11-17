package dev.anilbeesetti.nextplayer.feature.videopicker.screens.images

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.hasAllFilesAccess
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ImageFileIndex
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class ImagesViewModel(
    private val imageFileIndex: ImageFileIndex,
    private val preferencesRepository: PreferencesRepository,
    private val mediaOperationsService: MediaOperationsService,
    private val context: Context,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class Output(val openSettings: () -> Unit, val openSearch: () -> Unit)

    internal var output: Output = Output(
        openSettings = {},
        openSearch = {},
    )

    private val folderPath = MutableStateFlow<String?>(null)

    // 记录进入过的目录，返回时回到上一个访问的目录而不是父目录
    private val folderHistory = mutableListOf<String?>()
    private val imagesInternal = MutableStateFlow<List<ImageFile>?>(null)
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<ImagesUiState> = combine(
        folderPath,
        imagesInternal,
        preferencesRepository.applicationPreferences,
        refreshing,
    ) { path, images, preferences, isRefreshing ->
        images.toUiState(
            path = path,
            preferences = preferences,
            refreshing = isRefreshing,
        )
    }
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = ImagesUiState(
                isLoading = true,
                hasAllFilesAccess = context.hasAllFilesAccess(),
                preferences = preferencesRepository.applicationPreferences.value
                    .toImagePreferences(),
            ),
        )

    init {
        if (context.hasAllFilesAccess()) refresh()
        viewModelScope.launch(defaultDispatcher) {
            imageFileIndex.entries.collect { imagesInternal.value = it }
        }
        viewModelScope.launch(defaultDispatcher) {
            preferencesRepository.applicationPreferences.collect { preferences ->
                val current = folderPath.value ?: return@collect
                val roots = preferences.imageFolders.map(::normalizePath)
                if (roots.none { current.isWithin(it) }) {
                    folderPath.value = null
                    folderHistory.clear()
                }
            }
        }
    }

    fun onAction(action: ImagesAction) {
        when (action) {
            is ImagesAction.OnFolderClick -> {
                if (action.folderPath != folderPath.value) {
                    folderHistory.add(folderPath.value)
                }
                folderPath.value = action.folderPath
                refresh()
            }

            ImagesAction.OnNavigateUpClick -> {
                if (folderPath.value == null) return
                folderPath.value = folderHistory.removeLastOrNull()
                refresh()
            }

            ImagesAction.OnSearchClick -> output.openSearch()

            ImagesAction.OnSettingsClick -> output.openSettings()

            ImagesAction.OnPermissionAccepted -> refresh()

            ImagesAction.Refresh -> refresh(force = true)

            is ImagesAction.UpdatePreferences -> updatePreferences(action.preferences)

            is ImagesAction.OnImageViewed -> markImageViewed(action.path)

            is ImagesAction.ShareSelected -> shareSelected(action.items)

            is ImagesAction.RenameSelected -> renameImage(
                action.uri,
                action.to,
            )

            is ImagesAction.DeleteSelected -> deleteSelected(action.items)
        }
    }

    private fun refresh(force: Boolean = false) {
        if (!context.hasAllFilesAccess()) {
            imagesInternal.value = emptyList()
            return
        }
        val preferences = preferencesRepository.applicationPreferences.value
        if (!force) {
            imageFileIndex.requestRefresh(
                whitelistRoots = preferences.imageFolders,
                folderPath = folderPath.value,
            )
            return
        }
        viewModelScope.launch {
            refreshing.value = true
            imageFileIndex.requestRefresh(
                whitelistRoots = preferences.imageFolders,
                folderPath = folderPath.value,
                force = true,
            )
            refreshing.value = false
        }
    }

    private fun markImageViewed(path: String) {
        if (preferencesRepository.applicationPreferences.value.lastViewedImagePath == path) return
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(lastViewedImagePath = path)
            }
        }
    }

    private fun updatePreferences(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    imageViewMode = preferences.mediaViewMode,
                    imageFolderLayoutMode = preferences.imageFolderLayoutMode,
                    imageLayoutMode = preferences.imageLayoutMode,
                    imageSortBy = preferences.sortBy,
                    imageSortOrder = preferences.sortOrder,
                    showExtensionField = preferences.showExtensionField,
                    showPathField = preferences.showPathField,
                    showResolutionField = preferences.showResolutionField,
                    showSizeField = preferences.showSizeField,
                    showThumbnailField = preferences.showThumbnailField,
                )
            }
        }
    }

    private fun shareSelected(items: Set<SelectionItem>) {
        val uris = items.toImageUris()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            mediaOperationsService.shareMedia(
                uris,
                mimeType = "image/*",
            )
        }
    }

    private fun renameImage(uri: Uri, to: String) {
        viewModelScope.launch {
            val oldPath = uri.path ?: return@launch
            if (!mediaOperationsService.renameMedia(
                    uri = uri,
                    to = to,
                )
            ) {
                return@launch
            }
            imageFileIndex.removePaths(listOf(oldPath))
            imageFileIndex.requestRefresh(
                whitelistRoots = preferencesRepository.applicationPreferences.value.imageFolders,
                folderPath = File(oldPath).parent,
                force = true,
            )
        }
    }

    private fun deleteSelected(items: Set<SelectionItem>) {
        val images = items.toImageFiles()
        if (images.isEmpty()) return
        viewModelScope.launch {
            val deleted = mediaOperationsService.deleteMedia(
                uris = images.map { it.uriString.toUri() },
                permanently = true,
            )
            if (deleted) imageFileIndex.removePaths(images.map { it.path })
        }
    }

    private fun Set<SelectionItem>.toImageUris(): List<Uri> =
        toImageFiles().map { it.uriString.toUri() }

    private fun Set<SelectionItem>.toImageFiles(): List<ImageFile> {
        val all = allIndexedImages()
        return flatMap { item ->
            when (item) {
                is SelectionItem.Video -> all.filter { it.uriString == item.id }
                is SelectionItem.Folder -> all.imagesUnder(item.path)
            }
        }.distinctBy { it.path }
    }

    private fun allIndexedImages(): List<ImageFile> {
        val preferences = preferencesRepository.applicationPreferences.value
        val roots = preferences.imageFolders.map(::normalizePath).toSet()
        return imagesInternal.value.orEmpty().filter {
            it.whitelistRoot in roots && (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
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

    private fun ApplicationPreferences.imageSort(): Sort = Sort(
        by = imageSortBy,
        order = imageSortOrder,
    )

    private fun List<ImageFile>?.toUiState(
        path: String?,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ): ImagesUiState {
        val imagePreferences = preferences.toImagePreferences()
        val roots = preferences.imageFolders.map(::normalizePath).toSet()
        val all = this.orEmpty().filter {
            it.whitelistRoot in roots && (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
        val sort = preferences.imageSort()
        val sortedAll = all.sortedWith(sort.imageComparator())

        val tree = buildTree(
            root = path,
            images = all,
            sort = sort,
        )
        val folders: List<Folder>
        val images: List<ImageFile>
        when (imagePreferences.mediaViewMode) {
            MediaViewMode.FOLDER_TREE -> {
                folders = tree.folders
                images = tree.directImages
            }

            MediaViewMode.FOLDERS -> {
                folders = if (path == null) {
                    flatFolders(
                        all,
                        sort,
                    )
                } else {
                    emptyList()
                }
                images = if (path == null) emptyList() else tree.directImages
            }

            MediaViewMode.VIDEOS -> {
                folders = emptyList()
                images = if (path == null) sortedAll else sortedAll.imagesUnder(path)
            }
        }
        return ImagesUiState(
            folderPath = path,
            folderName = path?.let { File(it).prettyName },
            folders = folders,
            images = images,
            allImages = sortedAll,
            preferences = imagePreferences,
            isLoading = this == null,
            hasAllFilesAccess = context.hasAllFilesAccess(),
            refreshing = refreshing,
        )
    }

    private fun buildTree(root: String?, images: List<ImageFile>, sort: Sort): ImagesTree {
        if (root == null) {
            val folders = images.groupBy { it.whitelistRoot }
                .map { (path, rootImages) ->
                    summarize(
                        path,
                        rootImages,
                    )
                }
                .sortedWith(sort.folderComparator())
            return ImagesTree(
                folders = folders,
                directImages = emptyList(),
            )
        }
        val prefix = root.trimEnd('/') + PATH_SEPARATOR
        val directImages = ArrayList<ImageFile>()
        val imagesByFolder = LinkedHashMap<String, MutableList<ImageFile>>()
        for (image in images.imagesUnder(root)) {
            if (image.parentPath == root) {
                directImages.add(image)
                continue
            }
            val relative = image.path.removePrefix(prefix)
            if (relative == image.path) continue
            val childPath = prefix + relative.substringBefore(PATH_SEPARATOR)
            imagesByFolder.getOrPut(childPath) { ArrayList() }.add(image)
        }
        return ImagesTree(
            folders = imagesByFolder.map { (path, folderImages) ->
                summarize(
                    path,
                    folderImages,
                )
            }
                .sortedWith(sort.folderComparator()),
            directImages = directImages.sortedWith(sort.imageComparator()),
        )
    }

    private fun flatFolders(images: List<ImageFile>, sort: Sort): List<Folder> =
        images.groupBy { it.parentPath }
            .map { (path, folderImages) ->
                summarize(
                    path,
                    folderImages,
                )
            }
            .sortedWith(sort.folderComparator())

    private fun List<ImageFile>.imagesUnder(path: String): List<ImageFile> {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        return filter { it.path.startsWith(prefix) }
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

    private fun String.isWithin(root: String): Boolean =
        this == root || startsWith(root.trimEnd('/') + PATH_SEPARATOR)

    private companion object {
        const val PATH_SEPARATOR = "/"
    }
}

data class ImagesUiState(
    val folderPath: String? = null,
    val folderName: String? = null,
    val folders: List<Folder> = emptyList(),
    val images: List<ImageFile> = emptyList(),
    /** 当前筛选条件下全部图片（已排序），用于底部按钮「继续查看」 */
    val allImages: List<ImageFile> = emptyList(),
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val isLoading: Boolean = false,
    val hasAllFilesAccess: Boolean = true,
    val refreshing: Boolean = false,
)

private data class ImagesTree(val folders: List<Folder>, val directImages: List<ImageFile>)

sealed interface ImagesAction {
    data class OnFolderClick(val folderPath: String) : ImagesAction
    data object OnNavigateUpClick : ImagesAction
    data class UpdatePreferences(val preferences: ApplicationPreferences) : ImagesAction

    /** 查看器翻到某张图片时记录，供「继续查看」使用 */
    data class OnImageViewed(val path: String) : ImagesAction
    data object OnPermissionAccepted : ImagesAction
    data object OnSearchClick : ImagesAction
    data object OnSettingsClick : ImagesAction
    data class ShareSelected(val items: Set<SelectionItem>) : ImagesAction
    data class RenameSelected(val uri: Uri, val to: String) : ImagesAction
    data class DeleteSelected(val items: Set<SelectionItem>) : ImagesAction
    data object Refresh : ImagesAction
}

private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
