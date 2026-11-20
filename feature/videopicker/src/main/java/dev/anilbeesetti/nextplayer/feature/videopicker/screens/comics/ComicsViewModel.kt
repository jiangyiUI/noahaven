package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.media.services.TransferEvent
import dev.anilbeesetti.nextplayer.core.media.services.TransferMode
import dev.anilbeesetti.nextplayer.core.media.services.TransferProgress
import dev.anilbeesetti.nextplayer.core.media.services.withPreservedExtension
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
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

/** 漫画库 ViewModel：文件夹树 + 漫画列表，统计方式与图片库保持一致。 */
@Factory
class ComicsViewModel(
    private val comicFileIndex: ComicFileIndex,
    private val preferencesRepository: PreferencesRepository,
    private val mediaOperationsService: MediaOperationsService,
    private val systemService: SystemService,
) : ViewModel() {

    data class Output(
        val openSettings: () -> Unit = {},
        val openComicFolders: () -> Unit = {},
        val openSearch: () -> Unit = {},
    )

    var output: Output = Output()

    private val folderPath = MutableStateFlow<String?>(null)

    // 记录进入过的目录，返回时回到上一个访问的目录而不是父目录
    private val folderHistory = mutableListOf<String?>()
    private val comicsInternal = MutableStateFlow<List<ComicFile>?>(null)
    private val refreshing = MutableStateFlow(false)

    /** 最近阅读过的漫画（按阅读时间倒序取第一条），用于「最近查看」被删掉时继续下一本。 */
    private val recentComicPath = MutableStateFlow<String?>(null)

    /** 进度写库防抖任务：翻页很快时只落最后一次。 */
    private var progressSaveJob: Job? = null

    /** 复制/移动的进度状态，与音乐库共用同一套 TransferFlowState。 */
    private val transferFlowState = MutableStateFlow<TransferFlowState>(TransferFlowState.Idle)
    private var transferJob: Job? = null

    val state: StateFlow<ComicsUiState> = combine(
        folderPath,
        comicsInternal,
        preferencesRepository.applicationPreferences,
        refreshing,
        combine(transferFlowState, recentComicPath) { transferFlow, recentPath ->
            transferFlow to recentPath
        },
    ) { path, comics, preferences, isRefreshing, transfer ->
        comics.toUiState(
            path = path,
            preferences = preferences,
            refreshing = isRefreshing,
            transferFlow = transfer.first,
            recentComicPath = transfer.second,
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
            comicFileIndex.observeProgress().collect { progresses ->
                recentComicPath.value = progresses.firstOrNull()?.path
            }
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
            val startedAt = System.currentTimeMillis()
            try {
                comicFileIndex.refreshNow(
                    whitelistRoots = preferencesRepository.applicationPreferences.value.comicFolders,
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

    fun goBack() {
        if (folderPath.value == null) return
        folderPath.value = folderHistory.removeLastOrNull()
        refresh()
    }

    fun openSettings() = output.openSettings()

    fun openComicFolders() = output.openComicFolders()

    fun openSearch() = output.openSearch()

    /** 长按进入选择模式后的操作入口，与音乐库的 onAction 保持一致。 */
    fun onAction(action: ComicsAction) {
        when (action) {
            is ComicsAction.DeleteSelected -> deleteSelected(items = action.items)
            is ComicsAction.RenameSelected -> renameComic(item = action.item, to = action.to)
            is ComicsAction.ShareSelected -> shareSelected(items = action.items)
            is ComicsAction.CopySelected -> transferSelected(
                items = action.items,
                mode = TransferMode.COPY,
            )

            is ComicsAction.MoveSelected -> transferSelected(
                items = action.items,
                mode = TransferMode.MOVE,
            )

            ComicsAction.CancelTransfer -> {
                transferJob?.cancel()
                transferJob = null
                transferFlowState.value = TransferFlowState.Idle
            }
        }
    }

    /** 删除选中的压缩包，文件夹只删其中的 zip，自底向上清掉空目录。 */
    private fun deleteSelected(items: Set<SelectionItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val paths = items.toComicPaths()
            if (paths.isNotEmpty()) {
                mediaOperationsService.deleteMedia(
                    uris = paths.map { Uri.fromFile(File(it)) },
                    permanently = true,
                )
                paths.forEach { path ->
                    comicFileIndex.deleteProgress(path)
                    comicFileIndex.deletePassword(path)
                }
            }
            val protectedRoots = preferencesRepository.applicationPreferences.value.comicFolders
                .map(::normalizePath)
            items.filterIsInstance<SelectionItem.Folder>()
                .forEach { folder -> deleteEmptyDirectories(File(folder.path), protectedRoots) }
            refreshIndex()
        }
    }

    /** 重命名单个漫画压缩包，成功后强制重扫当前目录。 */
    private fun renameComic(item: SelectionItem.Video, to: String) {
        viewModelScope.launch {
            // 用户把后缀删掉时补回原后缀，否则重命名后文件会从漫画索引里消失
            val newName = to.withPreservedExtension(File(item.path).name)
            val renamed = mediaOperationsService.renameMedia(
                uri = Uri.parse(item.uriString),
                to = newName,
            )
            // 阅读进度按路径存，改名成功后要跟着挪，否则「更多」页的记录会失效
            if (renamed) {
                val newPath = File(File(item.path).parentFile, newName).path
                comicFileIndex.moveProgress(fromPath = item.path, toPath = newPath)
                // 密码记录同样按路径存，跟着改名后的位置走
                comicFileIndex.movePassword(fromPath = item.path, toPath = newPath)
                // 「最近查看」高亮存的是路径，也要改成新路径
                if (preferencesRepository.applicationPreferences.value.lastViewedComicPath == item.path) {
                    preferencesRepository.updateApplicationPreferences {
                        it.copy(lastViewedComicPath = newPath)
                    }
                }
            }
            refreshIndex()
        }
    }

    private fun shareSelected(items: Set<SelectionItem>) {
        val paths = items.toComicPaths()
        val uris = paths.map { Uri.fromFile(File(it)) }
        if (uris.isEmpty()) return
        // 按选中文件的格式决定分享 MIME：全 PDF 用 application/pdf，全 zip/cbz 用 application/zip，
        // 混选时退到 */* 让接收方自己嗅探
        val mimeType = when {
            paths.all { it.endsWith(".pdf", ignoreCase = true) } -> PDF_MIME_TYPE
            paths.none { it.endsWith(".pdf", ignoreCase = true) } -> COMIC_MIME_TYPE
            else -> "*/*"
        }
        viewModelScope.launch {
            mediaOperationsService.shareMedia(uris = uris, mimeType = mimeType)
        }
    }

    /** 复制/移动：先让用户选目标目录，再收集转移进度，结束后刷新漫画索引。 */
    private fun transferSelected(items: Set<SelectionItem>, mode: TransferMode) {
        val uris = items.toComicPaths().map { Uri.fromFile(File(it)) }
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
                                    comicFileIndex.moveProgress(
                                        fromPath = fromPath,
                                        toPath = moved.destinationPath,
                                    )
                                    comicFileIndex.movePassword(
                                        fromPath = fromPath,
                                        toPath = moved.destinationPath,
                                    )
                                    // 「最近查看」高亮同样按路径，跟着移动后的位置走
                                    if (preferencesRepository.applicationPreferences.value.lastViewedComicPath == fromPath) {
                                        preferencesRepository.updateApplicationPreferences {
                                            it.copy(lastViewedComicPath = moved.destinationPath)
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

    /** 把选择项换算成磁盘上的 zip 路径；文件夹展开成其下所有已索引的漫画。 */
    private fun Set<SelectionItem>.toComicPaths(): List<String> {
        val all = comicFileIndex.entries.value.orEmpty()
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

    /** 自底向上删除空目录；还有内容的目录保持原样。 */
    private fun deleteEmptyDirectories(directory: File, protectedRoots: List<String>) {
        val children = directory.listFiles() ?: return
        children.filter { it.isDirectory }.forEach { deleteEmptyDirectories(it, protectedRoots) }
        if (directory.listFiles().isNullOrEmpty()) {
            // 白名单根目录及它们的上级不能删
            val dirPath = normalizePath(directory.path)
            val isProtected = dirPath in protectedRoots ||
                protectedRoots.any { it.startsWith("$dirPath/") }
            if (!isProtected) directory.delete()
        }
    }

    private fun refreshIndex() {
        comicFileIndex.requestRefresh(
            whitelistRoots = preferencesRepository.applicationPreferences.value.comicFolders,
            folderPath = folderPath.value,
            force = true,
        )
    }

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

    /** 打开加密漫画前解析可用密码：已保存的、同文件指纹匹配的、或其他漫画复用来的。 */
    suspend fun resolveComicPassword(path: String): String? = comicFileIndex.resolvePassword(path)

    /** 密码校验成功后保存，之后可直接打开。 */
    suspend fun saveComicPassword(path: String, password: String) {
        comicFileIndex.savePassword(path = path, password = password)
        // 有了密码才能数出页数、生成封面，强制重扫一次
        refresh(force = true)
    }

    /** 已保存的密码失效时清掉，让用户重新输入。 */
    suspend fun clearComicPassword(path: String) {
        comicFileIndex.deletePassword(path)
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

    /** 保存这本漫画的页码和方向，反复调用时只写最后一次。双向判定 watched。 */
    fun saveProgress(path: String, page: Int, direction: ComicReadingDirection) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DELAY_MS)
            val prefs = preferencesRepository.applicationPreferences.value
            val total = comicsInternal.value?.find { it.path == path }?.imageCount ?: 0
            val watched = prefs.enableWatchedMarks && total > 0 &&
                page.toFloat() / total >= prefs.comicWatchedThreshold / 100f
            comicFileIndex.saveProgress(
                ComicProgress(
                    path = path,
                    lastPage = page,
                    readingDirection = direction,
                    watched = watched,
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
    )

    private fun List<ComicFile>?.toUiState(
        path: String?,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
        transferFlow: TransferFlowState,
        recentComicPath: String?,
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
            transferFlow = transferFlow,
            recentComicPath = recentComicPath,
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
        const val COMIC_MIME_TYPE = "application/zip"
        const val PDF_MIME_TYPE = "application/pdf"
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
    val transferFlow: TransferFlowState = TransferFlowState.Idle,
    /** 最近阅读过的漫画路径，用于「最近查看」失效时兜底。 */
    val recentComicPath: String? = null,
)

/** 漫画库选择模式下的操作，与音乐库的 MusicAction 对应。 */
sealed interface ComicsAction {
    data class DeleteSelected(val items: Set<SelectionItem>) : ComicsAction
    data class RenameSelected(val item: SelectionItem.Video, val to: String) : ComicsAction
    data class ShareSelected(val items: Set<SelectionItem>) : ComicsAction
    data class CopySelected(val items: Set<SelectionItem>) : ComicsAction
    data class MoveSelected(val items: Set<SelectionItem>) : ComicsAction
    data object CancelTransfer : ComicsAction
}

private data class ComicsTree(val folders: List<Folder>, val directComics: List<ComicFile>)

private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
