package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Factory

/** 漫画库 ViewModel：文件夹树 + 漫画列表，统计方式与图片库保持一致。 */
@Factory
class ComicsViewModel(
    private val comicFileIndex: ComicFileIndex,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    data class Output(
        val openSettings: () -> Unit = {},
        val openSearch: () -> Unit = {},
    )

    var output: Output = Output()

    private val folderPath = MutableStateFlow<String?>(null)

    // 记录进入过的目录，返回时回到上一个访问的目录而不是父目录
    private val folderHistory = mutableListOf<String?>()
    private val comicsInternal = MutableStateFlow<List<ComicFile>?>(null)
    private val refreshing = MutableStateFlow(false)

    /** 进度写库防抖任务：翻页很快时只落最后一次。 */
    private var progressSaveJob: Job? = null

    val state: StateFlow<ComicsUiState> = combine(
        folderPath,
        comicsInternal,
        preferencesRepository.applicationPreferences,
        refreshing,
    ) { path, comics, preferences, isRefreshing ->
        comics.toUiState(
            path = path,
            preferences = preferences,
            refreshing = isRefreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ComicsUiState(
            isLoading = true,
            preferences = preferencesRepository.applicationPreferences.value.toComicPreferences(),
        ),
    )

    init {
        refresh()
        viewModelScope.launch {
            comicFileIndex.entries.collect { comicsInternal.value = it }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                val current = folderPath.value ?: return@collect
                val roots = preferences.comicFolders.map(::normalizePath)
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
            comicFileIndex.requestRefresh(
                whitelistRoots = preferences.comicFolders,
                folderPath = folderPath.value,
            )
            return
        }
        viewModelScope.launch {
            refreshing.value = true
            comicFileIndex.requestRefresh(
                whitelistRoots = preferencesRepository.applicationPreferences.value.comicFolders,
                folderPath = folderPath.value,
                force = true,
            )
            refreshing.value = false
        }
    }

    fun openFolder(path: String) {
        if (path != folderPath.value) folderHistory.add(folderPath.value)
        folderPath.value = path
        refresh()
    }

    fun goBack() {
        if (folderPath.value == null) return
        folderPath.value = folderHistory.removeLastOrNull()
        refresh()
    }

    fun openSettings() = output.openSettings()

    fun openSearch() = output.openSearch()

    /** 快捷设置写回漫画自己的布局/排序偏好，不影响图片库。 */
    fun updatePreferences(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    comicViewMode = preferences.mediaViewMode,
                    // 快捷设置对话框在图片模式下编辑的是 imageFolderLayoutMode / imageLayoutMode
                    comicFolderLayoutMode = preferences.imageFolderLayoutMode,
                    comicLayoutMode = preferences.imageLayoutMode,
                    comicSortBy = preferences.sortBy,
                    comicSortOrder = preferences.sortOrder,
                    showExtensionField = preferences.showExtensionField,
                    showPathField = preferences.showPathField,
                    showResolutionField = preferences.showResolutionField,
                    showSizeField = preferences.showSizeField,
                    showThumbnailField = preferences.showThumbnailField,
                )
            }
        }
    }

    fun recordViewedComic(path: String) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(lastViewedComicPath = path)
            }
        }
    }

    /** 打开漫画前读取上次进度；没有记录时从第一页、默认方向开始。 */
    suspend fun loadProgress(path: String): ComicProgress =
        comicFileIndex.getProgress(path)
            ?: ComicProgress(
                path = path,
                lastPage = 0,
                readingDirection = preferencesRepository.applicationPreferences.value
                    .comicReadingDirection,
            )

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

    private fun ApplicationPreferences.toComicPreferences(): ApplicationPreferences = copy(
        mediaViewMode = comicViewMode,
        // 渲染用 mediaLayoutMode；快捷设置（imageMode）读取 imageFolderLayoutMode，两者保持同步
        mediaLayoutMode = comicFolderLayoutMode,
        imageFolderLayoutMode = comicFolderLayoutMode,
        // 漫画不支持九宫格，历史值统一按网格显示
        imageLayoutMode = if (comicLayoutMode == ImageLayoutMode.NINE_GRID) {
            ImageLayoutMode.GRID
        } else {
            comicLayoutMode
        },
        sortBy = comicSortBy,
        sortOrder = comicSortOrder,
        showDurationField = false,
        showFolderDurationField = false,
        showPlayedProgress = false,
    )

    private fun List<ComicFile>?.toUiState(
        path: String?,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ): ComicsUiState {
        val comicPreferences = preferences.toComicPreferences()
        val roots = preferences.comicFolders.map(::normalizePath).toSet()
        val all = orEmpty().filter {
            normalizePath(it.whitelistRoot) in roots && (preferences.ignoreNoMedia || !it.noMediaHidden)
        }
        val sort = Sort(by = preferences.comicSortBy, order = preferences.comicSortOrder)
        val sortedAll = all.sortedWith(sort.comicComparator())
        val tree = buildTree(root = path, comics = all, sort = sort)
        val folders: List<Folder>
        val comics: List<ComicFile>
        when (preferences.comicViewMode) {
            MediaViewMode.FOLDER_TREE -> {
                folders = tree.folders
                comics = tree.directComics
            }

            MediaViewMode.FOLDERS -> {
                folders = if (path == null) flatFolders(all, sort) else emptyList()
                comics = if (path == null) emptyList() else tree.directComics
            }

            MediaViewMode.VIDEOS -> {
                folders = emptyList()
                comics = if (path == null) sortedAll else sortedAll.comicsUnder(path)
            }
        }
        return ComicsUiState(
            folderPath = path,
            folderName = path?.let { File(it).name },
            folders = folders,
            comics = comics,
            allComics = sortedAll,
            comicFolders = preferences.comicFolders,
            preferences = comicPreferences,
            readingDirection = preferences.comicReadingDirection,
            isLoading = this == null,
            refreshing = refreshing,
        )
    }

    private fun buildTree(root: String?, comics: List<ComicFile>, sort: Sort): ComicsTree {
        if (root == null) {
            val folders = comics.groupBy { it.whitelistRoot }
                .map { (path, rootComics) -> summarize(path, rootComics) }
                .sortedWith(sort.folderComparator())
            return ComicsTree(folders = folders, directComics = emptyList())
        }
        val prefix = root.trimEnd('/') + PATH_SEPARATOR
        val directComics = ArrayList<ComicFile>()
        val comicsByFolder = LinkedHashMap<String, MutableList<ComicFile>>()
        for (comic in comics.comicsUnder(root)) {
            if (comic.parentPath == root) {
                directComics.add(comic)
                continue
            }
            val relative = comic.path.removePrefix(prefix)
            if (relative == comic.path) continue
            val childPath = prefix + relative.substringBefore(PATH_SEPARATOR)
            comicsByFolder.getOrPut(childPath) { ArrayList() }.add(comic)
        }
        return ComicsTree(
            folders = comicsByFolder.map { (path, folderComics) -> summarize(path, folderComics) }
                .sortedWith(sort.folderComparator()),
            directComics = directComics.sortedWith(sort.comicComparator()),
        )
    }

    private fun flatFolders(comics: List<ComicFile>, sort: Sort): List<Folder> =
        comics.groupBy { it.parentPath }
            .map { (path, folderComics) -> summarize(path, folderComics) }
            .sortedWith(sort.folderComparator())

    private fun List<ComicFile>.comicsUnder(path: String): List<ComicFile> {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        return filter { it.path.startsWith(prefix) }
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

    private fun String.isWithin(root: String): Boolean =
        this == root || startsWith(root.trimEnd('/') + PATH_SEPARATOR)

    private companion object {
        const val PATH_SEPARATOR = "/"
        const val PROGRESS_SAVE_DELAY_MS = 300L
    }
}

data class ComicsUiState(
    val folderPath: String? = null,
    val folderName: String? = null,
    val folders: List<Folder> = emptyList(),
    val comics: List<ComicFile> = emptyList(),
    /** 当前筛选条件下全部漫画（已排序），用于查看器在整目录内翻页。 */
    val allComics: List<ComicFile> = emptyList(),
    val comicFolders: List<String> = emptyList(),
    /** 已转换成漫画自己的布局/排序字段，可直接交给通用组件。 */
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val readingDirection: ComicReadingDirection = ComicReadingDirection.RIGHT_TO_LEFT,
    val isLoading: Boolean = false,
    val refreshing: Boolean = false,
)

private data class ComicsTree(val folders: List<Folder>, val directComics: List<ComicFile>)

private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
