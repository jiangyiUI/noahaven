package dev.anilbeesetti.nextplayer.feature.videopicker.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.extensions.hasAllFilesAccess
import dev.anilbeesetti.nextplayer.core.common.extensions.openAllFilesAccessSettings
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.PlaylistSummary
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.composables.AllFilesAccessView
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.MusicView
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.NoMusicFound
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.QuickSettingsDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.PendingMusicFolder
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.AddToPlaylistState
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.DeleteConfirmationDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.PlaylistTargetDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferFlowState
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferProgressDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.isFileSystemItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.rememberSelectionManager

@Composable
fun MusicScreen(viewModel: MusicViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val addToPlaylistState by viewModel.addToPlaylistState.collectAsStateWithLifecycle()
    val transferFlowState by viewModel.transferFlowState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasStorageAccess by remember { mutableStateOf(context.hasAllFilesAccess()) }
    var wasStorageAccessGranted by remember { mutableStateOf(hasStorageAccess) }

    LifecycleResumeEffect(key1 = Unit) {
        val granted = context.hasAllFilesAccess()
        hasStorageAccess = granted
        if (granted && !wasStorageAccessGranted) {
            viewModel.onAction(MusicAction.OnPermissionAccepted)
        }
        wasStorageAccessGranted = granted
        onPauseOrDispose {}
    }

    // 从搜索页点击文件夹返回时，直接进入该目录
    LifecycleResumeEffect(key1 = Unit) {
        PendingMusicFolder.consume()?.let { path ->
            viewModel.onAction(MusicAction.OnFolderClick(folderPath = path))
        }
        onPauseOrDispose {}
    }

    LaunchedEffect(hasStorageAccess) {
        if (!hasStorageAccess) context.openAllFilesAccessSettings()
    }

    MusicScreenContent(
        state = state,
        playlists = playlists,
        addToPlaylistState = addToPlaylistState,
        transferFlowState = transferFlowState,
        onAction = viewModel::onAction,
        hasStorageAccess = hasStorageAccess,
        onAllFilesAccessGranted = {
            hasStorageAccess = true
            wasStorageAccessGranted = true
            viewModel.onAction(MusicAction.OnPermissionAccepted)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicScreenContent(
    state: MusicUiState,
    playlists: List<PlaylistSummary> = emptyList(),
    addToPlaylistState: AddToPlaylistState = AddToPlaylistState(),
    transferFlowState: TransferFlowState = TransferFlowState.Idle,
    onAction: (MusicAction) -> Unit = {},
    hasStorageAccess: Boolean = true,
    onAllFilesAccessGranted: () -> Unit = {},
) {
    // 文件夹层和音频层各自持有滚动状态：进入目录从顶部开始，返回时文件夹列表仍停在原位置
    val folderGridState = rememberLazyGridState()
    val audioGridState = remember(state.folderPath) { LazyGridState() }
    val lazyGridState = if (state.folderPath == null) folderGridState else audioGridState
    val navigationBottomPadding = LocalNavigationBottomPadding.current
    val selectionManager = rememberSelectionManager()
    val contentFocusRequester = remember { FocusRequester() }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(value = false) }
    var renameAudioUri by rememberSaveable { mutableStateOf<String?>(value = null) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(value = false) }

    BindTopLevelBottomBarVisible(
        visible = state.folderPath == null && !selectionManager.isInSelectionMode,
    )

    if (state.folderPath == null) {
        BindTopLevelFab(
            key = TopLevelFabKey.MUSIC,
            icon = NextIcons.Play,
            onClick = { onAction(MusicAction.OnPlayAllClick) },
        )
    }

    val selectedItemsSize = selectionManager.selectionItems.size
    val totalItemsSize = state.folders.size + state.audios.size
    // 仅选中单个音频时支持重命名
    val singleSelectedAudio = selectionManager.selectionItems
        .singleOrNull()
        ?.takeIf { it is SelectionItem.Video }
        ?.let { item -> state.audios.firstOrNull { it.uriString == item.id } }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    val titleText = (state.folderName ?: stringResource(R.string.music))
                        .takeIf { !selectionManager.isInSelectionMode } ?: ""
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold.takeIf { state.folderName == null },
                    )
                },
                navigationIcon = {
                    if (selectionManager.isInSelectionMode) {
                        Row(
                            modifier = Modifier
                                .clip(shape = CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { selectionManager.exitSelectionMode() }
                                .padding(8.dp)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = NextIcons.Close,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                            Text(
                                text = stringResource(
                                    R.string.m_n_selected,
                                    selectedItemsSize,
                                    totalItemsSize,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else if (state.folderName != null) {
                        FilledTonalIconButton(
                            onClick = { onAction(MusicAction.OnNavigateUpClick) },
                        ) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                        }
                    }
                },
                actions = {
                    if (selectionManager.isInSelectionMode) {
                        FilledTonalIconButton(
                            onClick = {
                                if (selectedItemsSize != totalItemsSize) {
                                    val items = buildList {
                                        state.folders.forEach {
                                            add(
                                                SelectionItem.Folder(
                                                    name = it.name,
                                                    path = it.path,
                                                ),
                                            )
                                        }
                                        state.audios.forEach {
                                            add(
                                                SelectionItem.Video(
                                                    name = it.displayName,
                                                    uriString = it.uriString,
                                                    path = it.path,
                                                ),
                                            )
                                        }
                                    }
                                    selectionManager.selectAll(items)
                                } else {
                                    selectionManager.clearSelection()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (selectedItemsSize != totalItemsSize) {
                                    NextIcons.SelectAll
                                } else {
                                    NextIcons.DeselectAll
                                },
                                contentDescription = if (selectedItemsSize != totalItemsSize) {
                                    stringResource(R.string.select_all)
                                } else {
                                    stringResource(R.string.deselect_all)
                                },
                            )
                        }
                    } else {
                        IconButton(onClick = { onAction(MusicAction.OnSearchClick) }) {
                            Icon(
                                imageVector = NextIcons.Search,
                                contentDescription = stringResource(id = R.string.search),
                            )
                        }
                        IconButton(onClick = { showQuickSettingsDialog = true }) {
                            Icon(
                                imageVector = NextIcons.DashBoard,
                                contentDescription = stringResource(id = R.string.menu),
                            )
                        }
                        IconButton(onClick = { onAction(MusicAction.OnSettingsClick) }) {
                            Icon(
                                imageVector = NextIcons.Settings,
                                contentDescription = stringResource(id = R.string.settings),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            SelectionActionsSheet(
                show = selectionManager.isInSelectionMode &&
                    selectionManager.selectionItems.isNotEmpty(),
                contentFocusRequester = contentFocusRequester,
                showRenameAction = singleSelectedAudio != null,
                showInfoAction = false,
                showHideAction = false,
                showMoveAction = true,
                showDeleteAction = true,
                onPlayAction = {
                    onAction(MusicAction.PlaySelected(selectionManager.selectionItems))
                    selectionManager.exitSelectionMode()
                },
                onAddToPlaylistAction = {
                    onAction(MusicAction.ShowAddToPlaylist(selectionManager.selectionItems))
                },
                onRenameAction = {
                    renameAudioUri = singleSelectedAudio?.uriString
                },
                onShareAction = {
                    onAction(MusicAction.ShareSelected(selectionManager.selectionItems))
                },
                onCopyAction = {
                    onAction(MusicAction.CopySelected(selectionManager.selectionItems))
                    selectionManager.exitSelectionMode()
                },
                onMoveAction = {
                    onAction(MusicAction.MoveSelected(selectionManager.selectionItems))
                    selectionManager.exitSelectionMode()
                },
                onInfoAction = {},
                onHideAction = {},
                onDeleteAction = { showDeleteConfirmation = true },
            )
        },
        floatingActionButton = {
            if (state.folderPath != null && !selectionManager.isInSelectionMode) {
                FloatingActionButton(
                    onClick = { onAction(MusicAction.OnPlayAllClick) },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(
                        imageVector = NextIcons.Play,
                        contentDescription = stringResource(R.string.play_all),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues = scaffoldPadding.copy(bottom = 0.dp))
                .clip(
                    shape = RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                    ),
                )
                .background(color = MaterialTheme.colorScheme.background),
        ) {
            if (!hasStorageAccess) {
                AllFilesAccessView(onGranted = onAllFilesAccessGranted)
                return@Scaffold
            }
            if (state.isLoading) {
                CenterCircularProgressBar()
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { onAction(MusicAction.Refresh) },
                ) {
                    // 内容延伸到长按底栏后面，底栏顶部圆角才能透出来（与视频列表一致）
                    val contentPadding = PaddingValues(
                        bottom = scaffoldPadding.calculateBottomPadding() + navigationBottomPadding,
                    )
                    if (state.folders.isEmpty() && state.audios.isEmpty()) {
                        NoMusicFound(contentPadding = contentPadding)
                    } else {
                        val selectedIds = selectionManager.selectionItems.map { it.id }.toSet()
                        MusicView(
                            folders = state.folders,
                            audios = state.audios,
                            preferences = state.preferences,
                            recentlyPlayedAudio = state.recentlyPlayedAudio,
                            recentlyPlayedFolder = state.recentlyPlayedFolder,
                            contentPadding = contentPadding,
                            lazyGridState = lazyGridState,
                            selectedIds = selectedIds,
                            onFolderClick = { path ->
                                if (selectionManager.isInSelectionMode) {
                                    state.folders.find { it.path == path }?.let { folder ->
                                        selectionManager.toggleSelection(
                                            SelectionItem.Folder(
                                                name = folder.name,
                                                path = folder.path,
                                            ),
                                        )
                                    }
                                } else {
                                    onAction(MusicAction.OnFolderClick(folderPath = path))
                                }
                            },
                            onAudioClick = { audio ->
                                if (selectionManager.isInSelectionMode) {
                                    selectionManager.toggleSelection(audio.toSelectionItem())
                                } else {
                                    onAction(MusicAction.OnAudioClick(audio = audio))
                                }
                            },
                            onFolderLongClick = { folder ->
                                selectionManager.toggleSelection(
                                    SelectionItem.Folder(
                                        name = folder.name,
                                        path = folder.path,
                                    ),
                                )
                            },
                            onAudioLongClick = { audio ->
                                selectionManager.toggleSelection(audio.toSelectionItem())
                            },
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = selectionManager.isInSelectionMode) {
        selectionManager.exitSelectionMode()
    }

    BackHandler(enabled = !selectionManager.isInSelectionMode && state.folderPath != null) {
        onAction(MusicAction.OnNavigateUpClick)
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = state.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = { onAction(MusicAction.UpdatePreferences(preferences = it)) },
            showFieldSettings = false,
            mediaFilesLabel = stringResource(R.string.songs),
        )
    }

    (transferFlowState as? TransferFlowState.Processing)?.let { transfer ->
        TransferProgressDialog(
            mode = transfer.mode,
            progress = transfer.progress,
            onCancel = { onAction(MusicAction.CancelTransfer) },
        )
    }

    if (addToPlaylistState.isVisible) {
        PlaylistTargetDialog(
            playlists = playlists,
            state = addToPlaylistState,
            onDismissRequest = { onAction(MusicAction.DismissAddToPlaylist) },
            onPlaylistSelected = {
                onAction(MusicAction.AddSelectionToPlaylist(playlistId = it))
                selectionManager.exitSelectionMode()
            },
            onCreatePlaylist = {
                onAction(MusicAction.CreatePlaylistWithSelection(name = it))
                selectionManager.exitSelectionMode()
            },
        )
    }

    renameAudioUri?.let { uri ->
        state.audios.firstOrNull { it.uriString == uri }?.let { audio ->
            RenameDialog(
                name = audio.displayName,
                onDismiss = { renameAudioUri = null },
                onDone = {
                    onAction(
                        MusicAction.RenameSelected(
                            uri = uri.toUri(),
                            to = it,
                        ),
                    )
                    selectionManager.exitSelectionMode()
                    renameAudioUri = null
                },
            )
        }
    }

    if (showDeleteConfirmation) {
        DeleteConfirmationDialog(
            selectionItems = selectionManager.selectionItems,
            isAudio = true,
            onConfirm = {
                onAction(MusicAction.DeleteSelected(items = selectionManager.selectionItems))
                selectionManager.exitSelectionMode()
                showDeleteConfirmation = false
            },
            onCancel = { showDeleteConfirmation = false },
        )
    }
}

private fun AudioFile.toSelectionItem() = SelectionItem.Video(
    name = displayName,
    uriString = uriString,
    path = path,
)
