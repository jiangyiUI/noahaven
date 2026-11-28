package dev.anilbeesetti.nextplayer.feature.videopicker.screens.music

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.hasAllFilesAccess
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.services.AudioMediaService
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.media.services.TransferEvent
import dev.anilbeesetti.nextplayer.core.media.services.TransferMode
import dev.anilbeesetti.nextplayer.core.media.services.TransferProgress
import dev.anilbeesetti.nextplayer.core.media.services.withPreservedExtension
import dev.anilbeesetti.nextplayer.core.media.sync.MediaSynchronizer
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.PlaylistSummary
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.AddToPlaylistState
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferFlowState
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class MusicViewModel(
    private val audioMediaService: AudioMediaService,
    private val preferencesRepository: PreferencesRepository,
    private val mediaOperationsService: MediaOperationsService,
    private val playlistRepository: PlaylistRepository,
    private val mediaRepository: MediaRepository,
    private val mediaSynchronizer: MediaSynchronizer,
    private val systemService: SystemService,
    private val context: Context,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class Output(
        val playAudios: (List<Uri>, Uri) -> Unit,
        val openSettings: () -> Unit,
        val openSearch: () -> Unit,
    )

    internal var output: Output = Output(
        playAudios = { _, _ -> },
        openSettings = {},
        openSearch = {},
    )

    private val folderPath = MutableStateFlow<String?>(null)

    // 记录进入过的目录，返回时回到上一个访问的目录而不是父目录
    private val folderHistory = mutableListOf<String?>()
    private val audiosInternal = MutableStateFlow<Result<List<AudioFile>>?>(null)
    private val refreshing = MutableStateFlow(value = false)

    // 历史里最近播放的音频，用于「继续播放」；历史被清空时自动变为 null
    private val recentlyPlayedAudio = MutableStateFlow<Video?>(null)

    // 完整播放历史，用于在当前目录内标记上次播放的音频
    private val playbackHistory = MutableStateFlow(value = emptyList<Video>())

    // uri -> 播放位置，用于在封面底部画进度条（样式与视频库一致）
    private val playbackPositions = MutableStateFlow<Map<String, Long>>(value = emptyMap())

    // 把播放位置贴到音频上：同一目录下每首歌各自带自己的进度
    private val audiosWithProgress = combine(audiosInternal, playbackPositions) { result, positions ->
        result?.map { audios ->
            audios.map { audio -> audio.copy(playbackPosition = positions[audio.uriString]) }
        }
    }

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = emptyList(),
        )

    val addToPlaylistState = MutableStateFlow(value = AddToPlaylistState())
    val transferFlowState = MutableStateFlow<TransferFlowState>(value = TransferFlowState.Idle)

    private var pendingSelection: Set<SelectionItem> = emptySet()
    private var transferJob: Job? = null

    val state: StateFlow<MusicUiState> = combine(
        folderPath,
        audiosWithProgress,
        preferencesRepository.applicationPreferences,
        refreshing,
        playbackHistory,
    ) { path, audiosResult, preferences, isRefreshing, history ->
        audiosResult.toUiState(
            path = path,
            preferences = preferences,
            refreshing = isRefreshing,
            playbackHistory = history,
        )
    }
        .flowOn(context = defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = MusicUiState(
                isLoading = true,
                hasAllFilesAccess = context.hasAllFilesAccess(),
                preferences = preferencesRepository.applicationPreferences.value
                    .toMusicPreferences(),
            ),
        )

    init {
        if (context.hasAllFilesAccess()) {
            refresh()
        }
        // 跟随 MediaStore 变化（含首页下拉触发的整卷扫描）自动更新音乐列表
        viewModelScope.launch(defaultDispatcher) {
            audioMediaService.observeAudios().collect { audios ->
                val path = folderPath.value
                val prefix = path?.trimEnd('/')?.plus(PATH_SEPARATOR)
                audiosInternal.value = Result.success(
                    audios.filter { audio ->
                        prefix == null ||
                            audio.path.startsWith(prefix = prefix)
                    },
                )
            }
        }
        viewModelScope.launch(defaultDispatcher) {
            mediaRepository.observePlaybackHistory().collect { history ->
                playbackHistory.value = history
                // 文件被删掉（应用内或外部）后直接跳过，继续找下一个最近播放且仍存在的音频
                recentlyPlayedAudio.value = history
                    .filter { it.isAudio && it.lastPlayedAt != null }
                    .filter { File(it.path).exists() }
                    .maxByOrNull { it.lastPlayedAt!!.time }
            }
        }
        // 播放位置变化（正在播放、拖动进度、换曲）时刷新封面进度条
        viewModelScope.launch(defaultDispatcher) {
            mediaRepository.observePlaybackPositions().collect { positions ->
                playbackPositions.value = positions
            }
        }
    }

    fun onAction(action: MusicAction) {
        when (action) {
            is MusicAction.OnFolderClick -> {
                if (action.folderPath != folderPath.value) {
                    folderHistory.add(folderPath.value)
                }
                folderPath.value = action.folderPath
                refresh(force = true)
            }

            is MusicAction.OnNavigateUpClick -> {
                if (folderPath.value == null) return
                folderPath.value = folderHistory.removeLastOrNull()
                refresh(force = true)
            }

            MusicAction.OnSearchClick -> output.openSearch()

            MusicAction.OnSettingsClick -> output.openSettings()

            is MusicAction.OnAudioClick -> {
                val playlist = state.value.audios.map { it.uriString.toUri() }
                if (playlist.isEmpty()) return
                output.playAudios(
                    playlist,
                    action.audio.uriString.toUri(),
                )
            }

            is MusicAction.OnPlayAllClick -> {
                // 有音频播放历史时优先「继续播放」最后一个音频，否则沿用原来的播放全部逻辑
                val recentAudio = recentlyPlayedAudio.value
                if (recentAudio != null && File(recentAudio.path).exists()) {
                    val uri = recentAudio.uriString.toUri()
                    output.playAudios(
                        listOf(uri),
                        uri,
                    )
                    return
                }
                val playlist = allAudiosUnderCurrentPath()
                    .sortedWith(state.value.preferences.musicSort().audioComparator())
                    .map { it.uriString.toUri() }
                playlist.firstOrNull()?.let { first ->
                    output.playAudios(
                        playlist,
                        first,
                    )
                }
            }

            is MusicAction.UpdatePreferences -> updatePreferences(action.preferences)

            is MusicAction.OnPermissionAccepted -> refresh()

            MusicAction.Refresh -> refreshWithMediaScan()

            is MusicAction.PlaySelected -> {
                val uris = action.items.toAudioUris()
                uris.firstOrNull()?.let { first ->
                    output.playAudios(
                        uris,
                        first,
                    )
                }
            }

            is MusicAction.ShareSelected -> {
                val uris = action.items.toAudioUris()
                if (uris.isEmpty()) return
                viewModelScope.launch { mediaOperationsService.shareMedia(uris) }
            }

            is MusicAction.CopySelected -> transferSelected(
                action.items,
                mode = TransferMode.COPY,
            )

            is MusicAction.MoveSelected -> transferSelected(
                action.items,
                mode = TransferMode.MOVE,
            )

            is MusicAction.RenameSelected -> renameAudio(
                uri = action.uri,
                to = action.to,
            )

            is MusicAction.RenameFolder -> renameFolder(
                oldPath = action.oldPath,
                to = action.to,
            )

            is MusicAction.DeleteSelected -> deleteSelected(items = action.items)

            is MusicAction.ShowAddToPlaylist -> {
                pendingSelection = action.items
                addToPlaylistState.value = AddToPlaylistState(
                    isVisible = true,
                    hasVideos = action.items.isNotEmpty(),
                )
            }

            is MusicAction.DismissAddToPlaylist -> {
                pendingSelection = emptySet()
                addToPlaylistState.value = AddToPlaylistState()
            }

            is MusicAction.AddSelectionToPlaylist -> addSelectionToPlaylist(
                playlistId = action.playlistId,
            )

            is MusicAction.CreatePlaylistWithSelection ->
                createPlaylistWithSelection(name = action.name)

            MusicAction.CancelTransfer -> {
                transferJob?.cancel()
                transferJob = null
                transferFlowState.value = TransferFlowState.Idle
            }
        }
    }

    private fun refresh(force: Boolean = false) {
        if (!context.hasAllFilesAccess()) {
            audiosInternal.value = Result.success(emptyList())
            return
        }
        // force 绕过节流，确保第三方改名/删除后能扫到并清理已不存在的文件；非 force 走节流避免频繁扫描
        audioMediaService.requestRefresh(folderPath = folderPath.value, force = force)
        viewModelScope.launch {
            audiosInternal.value = Result.success(
                audioMediaService.fetchAudios(folderPath = folderPath.value),
            )
        }
    }

    /** 页面从后台恢复时强制刷新当前文件夹，清掉第三方改名/删除后已不存在的文件。 */
    fun refreshOnResume() {
        if (context.hasAllFilesAccess()) refresh(force = true)
    }

    // 下拉刷新：做一次整卷系统扫描，视频和音频的 MediaStore 记录都会一起更新
    private fun refreshWithMediaScan() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            mediaSynchronizer.refresh()
            audioMediaService.requestRefresh(
                folderPath = folderPath.value,
                force = true,
            )
            refresh()
            refreshing.value = false
        }
    }

    private fun updatePreferences(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    musicViewMode = preferences.mediaViewMode,
                    musicLayoutMode = preferences.mediaLayoutMode,
                    musicSortBy = preferences.sortBy,
                    musicSortOrder = preferences.sortOrder,
                )
            }
        }
    }

    private fun transferSelected(items: Set<SelectionItem>, mode: TransferMode) {
        val uris = items.toAudioUris()
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
                    uris,
                    targetFolder,
                    mode,
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
                                // 移动后 uri/路径变了，把播放进度和历史记录迁到新位置
                                event.movedFiles.forEach { moved ->
                                    val newUri = audioMediaService
                                        .fetchAudios(folderPath = File(moved.destinationPath).parent)
                                        .firstOrNull { it.path == moved.destinationPath }
                                        ?.uriString
                                        ?: Uri.fromFile(File(moved.destinationPath)).toString()
                                    mediaRepository.movePlaybackState(
                                        fromUri = moved.sourceUri.toString(),
                                        toUri = newUri,
                                    )
                                }
                                transferFlowState.value = TransferFlowState.Idle
                                transferJob = null
                                audioMediaService.requestRefresh(
                                    folderPath = folderPath.value,
                                    force = true,
                                )
                                refresh()
                            }
                        }
                    }
            }
        }
    }

    private fun renameAudio(uri: Uri, to: String) {
        viewModelScope.launch {
            val renamed = mediaOperationsService.renameMedia(
                uri = uri,
                to = to,
            )
            // MediaStore 条目重命名后 uri 不变；file:// 的路径会变，进度和历史要跟过去
            if (renamed && uri.scheme == "file") {
                val oldPath = uri.path ?: return@launch
                // 服务层会补回后缀，这里用同样的最终文件名算新路径
                val newName = to.withPreservedExtension(File(oldPath).name)
                val newPath = File(File(oldPath).parentFile, newName).path
                val newUri = audioMediaService
                    .fetchAudios(folderPath = File(newPath).parent)
                    .firstOrNull { it.path == newPath }
                    ?.uriString
                    ?: Uri.fromFile(File(newPath)).toString()
                mediaRepository.movePlaybackState(
                    fromUri = uri.toString(),
                    toUri = newUri,
                )
            }
            audioMediaService.requestRefresh(
                folderPath = folderPath.value,
                force = true,
            )
            refresh()
        }
    }

    /** 重命名文件夹：磁盘 renameTo 后做一次整卷扫描 + 强制重扫当前目录，子文件索引切到新路径。 */
    private fun renameFolder(oldPath: String, to: String) {
        viewModelScope.launch {
            val renamed = mediaOperationsService.renameMedia(
                uri = Uri.fromFile(File(oldPath)),
                to = to,
            )
            if (renamed) refreshWithMediaScan()
        }
    }

    private fun deleteSelected(items: Set<SelectionItem>) {
        val uris = items.toAudioUris()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val deleted = mediaOperationsService.deleteMedia(
                uris = uris,
                permanently = true,
            )
            if (deleted) {
                // 文件删除成功后同步清掉它的播放历史，避免历史里残留无法播放的条目
                mediaRepository.removePlaybackHistory(uris = uris.map { uri -> uri.toString() })
            }
            refresh()
        }
    }

    private fun addSelectionToPlaylist(playlistId: Long) {
        val uris = pendingSelection.toAudioUris().map { it.toString() }
        if (uris.isEmpty()) return
        addToPlaylistState.value = addToPlaylistState.value.copy(isSaving = true)
        viewModelScope.launch {
            runCatching {
                playlistRepository.addVideos(
                    playlistId,
                    uris,
                )
            }
            pendingSelection = emptySet()
            addToPlaylistState.value = AddToPlaylistState()
        }
    }

    private fun createPlaylistWithSelection(name: String) {
        val uris = pendingSelection.toAudioUris().map { it.toString() }
        if (uris.isEmpty()) return
        addToPlaylistState.value = addToPlaylistState.value.copy(isSaving = true)
        viewModelScope.launch {
            runCatching {
                playlistRepository.create(
                    name = name,
                    videoUris = uris,
                )
            }
            pendingSelection = emptySet()
            addToPlaylistState.value = AddToPlaylistState()
        }
    }

    private fun allAudiosUnderCurrentPath(): List<AudioFile> {
        val excludeFolders = preferencesRepository.applicationPreferences.value.excludeAudioFolders
        return audiosInternal.value?.getOrElse { emptyList() }.orEmpty()
            .filterNot { it.isInExcludedFolder(excludeFolders) }
    }

    private fun Set<SelectionItem>.toAudioUris(): List<Uri> = toAudioFiles().map {
        it.uriString.toUri()
    }

    private fun Set<SelectionItem>.toAudioFiles(): List<AudioFile> {
        val all = allAudiosUnderCurrentPath()
        return flatMap { item ->
            when (item) {
                is SelectionItem.Video -> all.filter { it.uriString == item.id }

                is SelectionItem.Folder -> audiosUnder(
                    path = item.path,
                    audios = all,
                )
            }
        }.distinctBy { it.uriString }
    }

    private fun ApplicationPreferences.toMusicPreferences(): ApplicationPreferences = copy(
        mediaViewMode = musicViewMode,
        mediaLayoutMode = musicLayoutMode,
        sortBy = musicSortBy,
        sortOrder = musicSortOrder,
    )

    private fun ApplicationPreferences.musicSort(): Sort = Sort(
        by = musicSortBy,
        order = musicSortOrder,
    )

    private fun Result<List<AudioFile>>?.toUiState(
        path: String?,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
        playbackHistory: List<Video>,
    ): MusicUiState {
        val musicPreferences = preferences.toMusicPreferences()
        // 先剔除被排除目录下的音频，这样只含被排除内容的上层目录不会再出现在列表里
        val all = this?.getOrElse { emptyList() }.orEmpty()
            .filterNot { it.isInExcludedFolder(preferences.excludeAudioFolders) }
        val sort = musicPreferences.musicSort()
        val tree = buildTree(
            root = path,
            audios = all,
            sort = sort,
        )
        val folders: List<Folder>
        val audios: List<AudioFile>
        when (musicPreferences.mediaViewMode) {
            MediaViewMode.FOLDER_TREE -> {
                folders = tree.folders
                audios = tree.directAudios
            }

            MediaViewMode.FOLDERS -> {
                folders = if (path == null) {
                    flatFolders(
                        audios = all,
                        sort = sort,
                    )
                } else {
                    emptyList()
                }
                audios = if (path == null) emptyList() else tree.directAudios
            }

            MediaViewMode.VIDEOS -> {
                folders = emptyList()
                audios = (
                    if (path == null) {
                        all
                    } else {
                        audiosUnder(
                            path = path,
                            audios = all,
                        )
                    }
                    )
                    .sortedWith(sort.audioComparator())
            }
        }
        // 只标记当前目录里可见的音频，与视频库「每个文件夹中上次播放的媒体」一致
        val visibleAudioPaths = all.mapTo(destination = HashSet()) { audio -> audio.path }
        val recentlyPlayedAudio = playbackHistory
            .filter { history -> history.isAudio && history.lastPlayedAt != null }
            .filter { history -> history.path in visibleAudioPaths }
            .maxByOrNull { history -> history.lastPlayedAt!!.time }
        return MusicUiState(
            folderPath = path,
            folderName = path?.let { File(it).prettyName },
            folders = folders,
            audios = audios,
            preferences = musicPreferences,
            isLoading = this == null,
            hasAllFilesAccess = context.hasAllFilesAccess(),
            refreshing = refreshing,
            recentlyPlayedAudio = recentlyPlayedAudio,
        )
    }

    /** 树形展示：当前目录下的曲目 + 一层子目录（顶层只有多存储卷时才显示卷目录）。 */
    private fun buildTree(root: String?, audios: List<AudioFile>, sort: Sort): MusicTree {
        if (root == null) {
            val volumes = audios.mapNotNull { volumeRootOf(it.path) }.distinct()
            if (volumes.isEmpty()) {
                return MusicTree(
                    folders = emptyList(),
                    directAudios = emptyList(),
                )
            }
            if (volumes.size > 1) {
                val folders = volumes
                    .map { volume ->
                        summarize(
                            path = volume,
                            audios = audiosUnder(
                                path = volume,
                                audios = audios,
                            ),
                        )
                    }
                    .sortedWith(sort.folderComparator())
                return MusicTree(
                    folders = folders,
                    directAudios = emptyList(),
                )
            }
        }

        val rootPath = root ?: audios.mapNotNull { volumeRootOf(it.path) }.distinct().firstOrNull()
            ?: return MusicTree(
                folders = emptyList(),
                directAudios = emptyList(),
            )
        val prefix = rootPath.trimEnd('/') + PATH_SEPARATOR
        val directAudios = ArrayList<AudioFile>()
        val audiosByFolder = LinkedHashMap<String, MutableList<AudioFile>>()
        for (audio in audiosUnder(
            path = rootPath,
            audios = audios,
        )) {
            if (audio.parentPath == rootPath) {
                directAudios.add(element = audio)
                continue
            }
            val relative = audio.path.removePrefix(prefix = prefix)
            if (relative == audio.path) continue
            val childPath = prefix + relative.substringBefore(PATH_SEPARATOR)
            audiosByFolder.getOrPut(key = childPath) { ArrayList() }.add(element = audio)
        }
        val folders = audiosByFolder.asSequence()
            .map { (path, folderAudios) ->
                summarize(
                    path = path,
                    audios = folderAudios,
                )
            }
            .sortedWith(sort.folderComparator())
            .toList()
        return MusicTree(
            folders = folders,
            directAudios = directAudios.sortedWith(sort.audioComparator()),
        )
    }

    /** 「文件夹」模式顶层：所有含曲目的目录平铺展示。 */
    private fun flatFolders(audios: List<AudioFile>, sort: Sort): List<Folder> =
        audios.groupBy { it.parentPath }
            .map { (path, folderAudios) ->
                summarize(
                    path = path,
                    audios = folderAudios,
                )
            }
            .sortedWith(sort.folderComparator())

    private fun audiosUnder(path: String, audios: List<AudioFile>): List<AudioFile> {
        val prefix = path.trimEnd('/') + PATH_SEPARATOR
        return audios.filter { it.path.startsWith(prefix = prefix) }
    }

    private fun AudioFile.isInExcludedFolder(excludeFolders: List<String>): Boolean =
        excludeFolders.any { folder ->
            parentPath == folder ||
                parentPath.startsWith(prefix = folder.trimEnd('/') + PATH_SEPARATOR)
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
                childFolders.add(
                    audio.path.removePrefix(prefix = prefix).substringBefore(PATH_SEPARATOR),
                )
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

    private fun volumeRootOf(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" ->
                "/storage/emulated/${parts[2]}"

            parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"

            else -> null
        }
    }

    private companion object {
        const val PATH_SEPARATOR = "/"
    }
}

private data class MusicTree(val folders: List<Folder>, val directAudios: List<AudioFile>)

sealed interface MusicAction {
    data class OnFolderClick(val folderPath: String) : MusicAction
    data object OnNavigateUpClick : MusicAction
    data class OnAudioClick(val audio: AudioFile) : MusicAction
    data object OnPlayAllClick : MusicAction
    data class UpdatePreferences(val preferences: ApplicationPreferences) : MusicAction
    data object OnPermissionAccepted : MusicAction
    data object OnSearchClick : MusicAction
    data object OnSettingsClick : MusicAction
    data class PlaySelected(val items: Set<SelectionItem>) : MusicAction
    data class ShareSelected(val items: Set<SelectionItem>) : MusicAction
    data class CopySelected(val items: Set<SelectionItem>) : MusicAction
    data class MoveSelected(val items: Set<SelectionItem>) : MusicAction
    data class RenameSelected(val uri: Uri, val to: String) : MusicAction
    data class RenameFolder(val oldPath: String, val to: String) : MusicAction
    data class DeleteSelected(val items: Set<SelectionItem>) : MusicAction
    data class ShowAddToPlaylist(val items: Set<SelectionItem>) : MusicAction
    data object DismissAddToPlaylist : MusicAction
    data class AddSelectionToPlaylist(val playlistId: Long) : MusicAction
    data class CreatePlaylistWithSelection(val name: String) : MusicAction
    data object CancelTransfer : MusicAction
    data object Refresh : MusicAction
}
