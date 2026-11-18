package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.extensions.hasAllFilesAccess
import dev.anilbeesetti.nextplayer.core.common.extensions.openAllFilesAccessSettings
import dev.anilbeesetti.nextplayer.core.media.index.ComicArchiveReader
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.composables.AllFilesAccessView
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.QuickSettingsDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.PendingComicFolder
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.DeleteConfirmationDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferFlowState
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferProgressDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.rememberSelectionManager
import dev.anilbeesetti.nextplayer.feature.videopicker.state.toSelectedFolder
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ComicsScreen(
    viewModel: ComicsViewModel,
    tabBar: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val context = LocalContext.current
    var hasStorageAccess by remember { mutableStateOf(context.hasAllFilesAccess()) }

    LifecycleResumeEffect(Unit) {
        val granted = context.hasAllFilesAccess()
        if (granted && !hasStorageAccess) viewModel.refresh()
        hasStorageAccess = granted
        onPauseOrDispose {}
    }

    // 从搜索页点击文件夹返回时，直接进入该文件夹
    LifecycleResumeEffect(Unit) {
        PendingComicFolder.consume()?.let(viewModel::openFolder)
        onPauseOrDispose {}
    }

    LaunchedEffect(hasStorageAccess) {
        if (!hasStorageAccess) context.openAllFilesAccessSettings()
    }

    ComicsScreenContent(
        state = state,
        viewModel = viewModel,
        tabBar = tabBar,
        hasStorageAccess = hasStorageAccess,
        onAllFilesAccessGranted = {
            hasStorageAccess = true
            viewModel.refresh()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComicsScreenContent(
    state: ComicsUiState,
    viewModel: ComicsViewModel,
    tabBar: @Composable () -> Unit = {},
    hasStorageAccess: Boolean = true,
    onAllFilesAccessGranted: () -> Unit = {},
) {
    // 文件夹层和漫画层各自持有滚动状态，与图片库一致
    val folderGridState = rememberLazyGridState()
    val comicGridState = remember(state.folderPath) { LazyGridState() }
    val lazyGridState = if (state.folderPath == null) folderGridState else comicGridState
    val navigationBottomPadding = LocalNavigationBottomPadding.current
    val scope = rememberCoroutineScope()
    val selectionManager = rememberSelectionManager()
    val contentFocusRequester = remember { FocusRequester() }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var renameComicPath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerSession by remember { mutableStateOf<ViewerSession?>(null) }
    var viewerPage by remember { mutableIntStateOf(0) }

    fun openComic(comic: ComicFile) {
        if (viewerSession != null) return
        scope.launch {
            val entries = ComicArchiveReader.listImageEntryNames(comic.path)
            if (entries.isEmpty()) return@launch
            val progress = viewModel.loadProgress(comic.path)
            viewerPage = progress.lastPage.coerceIn(0, entries.lastIndex)
            viewerSession = ViewerSession(
                comicPath = comic.path,
                entries = entries,
                initialIndex = viewerPage,
                direction = progress.readingDirection,
            )
            viewModel.recordViewedComic(comic.path)
        }
    }

    // 与图片库保持一致：底部栏和继续阅读按钮只在根目录、非选择模式显示
    BindTopLevelBottomBarVisible(
        visible = state.folderPath == null && viewerSession == null &&
            !selectionManager.isInSelectionMode,
    )

    if (state.folderPath == null && viewerSession == null &&
        !selectionManager.isInSelectionMode
    ) {
        // 优先继续上次看的漫画，没有记录就打开当前目录第一本
        val pool = state.comics.ifEmpty { state.allComics }
        if (pool.isNotEmpty()) {
            BindTopLevelFab(
                key = TopLevelFabKey.IMAGES,
                icon = NextIcons.View,
                onClick = {
                    // 上次看的没了（改名/移动/删除）就顺延到最近读过的下一本，最后才退回第一本
                    fun existing(path: String?): ComicFile? = pool.firstOrNull {
                        it.path == path && File(it.path).exists()
                    }
                    val target = existing(state.preferences.lastViewedComicPath)
                        ?: existing(state.recentComicPath)
                        ?: pool.first()
                    openComic(target)
                },
            )
        }
    }

    val selectedItemsSize = selectionManager.selectionItems.size
    val totalItemsSize = state.folders.size + state.comics.size
    // 只有单选一本漫画时才允许重命名；分享对任意数量的漫画都可用
    val singleSelectedComic = selectionManager.selectionItems
        .singleOrNull()
        ?.let { it as? SelectionItem.Video }
        ?.let { item -> state.allComics.firstOrNull { it.path == item.path } }
    val hasSelectedComic = selectionManager.selectionItems.any { it is SelectionItem.Video }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    if (selectionManager.isInSelectionMode) {
                        // 选择模式下标题让位给「已选数量」
                        Text(text = "", maxLines = 1)
                    } else if (state.folderPath == null) {
                        tabBar()
                    } else {
                        Text(
                            text = state.folderName ?: stringResource(R.string.comics),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold.takeIf { state.folderName == null },
                        )
                    }
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
                                contentDescription = stringResource(R.string.navigate_up),
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
                        FilledTonalIconButton(onClick = viewModel::goBack) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }
                    }
                },
                actions = {
                    if (selectionManager.isInSelectionMode) {
                        FilledTonalIconButton(
                            onClick = {
                                if (selectedItemsSize != totalItemsSize) {
                                    selectionManager.selectAll(
                                        buildList {
                                            state.folders.forEach { add(it.toSelectedFolder()) }
                                            state.comics.forEach { add(it.toSelectionItem()) }
                                        },
                                    )
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
                        IconButton(onClick = viewModel::openSearch) {
                            Icon(
                                imageVector = NextIcons.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                        IconButton(onClick = { showQuickSettingsDialog = true }) {
                            Icon(
                                imageVector = NextIcons.DashBoard,
                                contentDescription = stringResource(R.string.menu),
                            )
                        }
                        IconButton(onClick = viewModel::openSettings) {
                            Icon(
                                imageVector = NextIcons.Settings,
                                contentDescription = stringResource(R.string.settings),
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
                showRenameAction = singleSelectedComic != null,
                showInfoAction = false,
                showHideAction = false,
                showPlayAction = false,
                showAddToPlaylistAction = false,
                showShareAction = hasSelectedComic,
                showCopyAction = true,
                showMoveAction = true,
                onPlayAction = {},
                onAddToPlaylistAction = {},
                onRenameAction = { renameComicPath = singleSelectedComic?.path },
                onShareAction = {
                    viewModel.onAction(
                        ComicsAction.ShareSelected(selectionManager.selectionItems),
                    )
                },
                onCopyAction = {
                    viewModel.onAction(
                        ComicsAction.CopySelected(selectionManager.selectionItems),
                    )
                    selectionManager.exitSelectionMode()
                },
                onMoveAction = {
                    viewModel.onAction(
                        ComicsAction.MoveSelected(selectionManager.selectionItems),
                    )
                    selectionManager.exitSelectionMode()
                },
                onInfoAction = {},
                onHideAction = {},
                onDeleteAction = { showDeleteConfirmation = true },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = scaffoldPadding.calculateTopPadding())
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.background),
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
                    onRefresh = { viewModel.refresh(force = true) },
                ) {
                    val contentPadding = PaddingValues(
                        bottom = scaffoldPadding.calculateBottomPadding() + navigationBottomPadding,
                    )
                    when {
                        state.comicFolders.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = NextIcons.Comic,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.comic_folders_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                TextButton(onClick = viewModel::openSettings) {
                                    Text(text = stringResource(R.string.comic_folders_setup))
                                }
                            }
                        }

                        state.folders.isEmpty() && state.comics.isEmpty() -> {
                            NoComicsFound(contentPadding = contentPadding)
                        }

                        else -> {
                            ComicLibraryView(
                                folders = state.folders,
                                comics = state.comics,
                                preferences = state.preferences,
                                lastViewedPath = state.preferences.lastViewedComicPath,
                                contentPadding = contentPadding,
                                lazyGridState = lazyGridState,
                                selectedIds = selectionManager.selectionItems
                                    .map { it.id }
                                    .toSet(),
                                onFolderClick = { path ->
                                    if (selectionManager.isInSelectionMode) {
                                        state.folders.find { it.path == path }?.let { folder ->
                                            selectionManager.toggleSelection(
                                                folder.toSelectedFolder(),
                                            )
                                        }
                                    } else {
                                        viewModel.openFolder(path)
                                    }
                                },
                                onComicClick = { comic ->
                                    if (selectionManager.isInSelectionMode) {
                                        selectionManager.toggleSelection(
                                            comic.toSelectionItem(),
                                        )
                                    } else {
                                        openComic(comic)
                                    }
                                },
                                onFolderLongClick = { folder ->
                                    selectionManager.toggleSelection(folder.toSelectedFolder())
                                },
                                onComicLongClick = { comic ->
                                    selectionManager.toggleSelection(comic.toSelectionItem())
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 选择模式下返回键先退出选择，而不是切换目录
    BackHandler(enabled = selectionManager.isInSelectionMode) {
        selectionManager.exitSelectionMode()
    }

    BackHandler(
        enabled = !selectionManager.isInSelectionMode &&
            viewerSession == null &&
            state.folderPath != null,
    ) {
        viewModel.goBack()
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = state.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = viewModel::updatePreferences,
            showFieldSettings = true,
            mediaFilesLabel = stringResource(R.string.comics),
            imageMode = true,
            // 漫画布局只有列表/网格，没有九宫格；也没有路径和分辨率字段
            comicMode = true,
        )
    }

    if (showDeleteConfirmation) {
        DeleteConfirmationDialog(
            selectionItems = selectionManager.selectionItems,
            comicMode = true,
            onConfirm = {
                viewModel.onAction(
                    ComicsAction.DeleteSelected(selectionManager.selectionItems),
                )
                selectionManager.exitSelectionMode()
                showDeleteConfirmation = false
            },
            onCancel = { showDeleteConfirmation = false },
        )
    }

    renameComicPath?.let { path ->
        state.allComics.firstOrNull { it.path == path }?.let { comic ->
            RenameDialog(
                // 输入框只放不带后缀的名字（和视频/音乐/图片一致），后缀由重命名逻辑自动补回
                name = comic.displayName.substringBeforeLast(
                    delimiter = '.',
                    missingDelimiterValue = comic.displayName,
                ),
                onDismiss = { renameComicPath = null },
                onDone = {
                    viewModel.onAction(
                        ComicsAction.RenameSelected(
                            item = comic.toSelectionItem(),
                            to = it,
                        ),
                    )
                    selectionManager.exitSelectionMode()
                    renameComicPath = null
                },
            )
        }
    }

    (state.transferFlow as? TransferFlowState.Processing)?.let { transfer ->
        TransferProgressDialog(
            mode = transfer.mode,
            progress = transfer.progress,
            onCancel = { viewModel.onAction(ComicsAction.CancelTransfer) },
        )
    }

    viewerSession?.let { session ->
        BackHandler(enabled = true) { viewerSession = null }
        ComicViewer(
            images = ComicImageList(
                zipPath = session.comicPath,
                entries = session.entries,
                readingDirection = session.direction,
            ),
            initialIndex = session.initialIndex,
            readingDirection = session.direction,
            onProgressChanged = { page, direction ->
                viewerPage = page
                viewModel.saveProgress(session.comicPath, page, direction)
            },
            onDirectionChange = { direction ->
                viewerSession = session.copy(direction = direction)
                viewModel.saveProgress(session.comicPath, viewerPage, direction)
            },
            onBack = { viewerSession = null },
        )
    }
}

private data class ViewerSession(
    val comicPath: String,
    val entries: List<String>,
    val initialIndex: Int,
    val direction: ComicReadingDirection,
)

/** 漫画压缩包在选择模式里按视频项处理，id 用 file:// uri。 */
private fun ComicFile.toSelectionItem() = SelectionItem.Video(
    name = displayName,
    uriString = Uri.fromFile(File(path)).toString(),
    path = path,
)
