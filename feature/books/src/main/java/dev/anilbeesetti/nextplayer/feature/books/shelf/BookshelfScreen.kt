package dev.anilbeesetti.nextplayer.feature.books.shelf

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
import dev.anilbeesetti.nextplayer.core.ui.composables.rememberPersistentLazyGridState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.extensions.hasAllFilesAccess
import dev.anilbeesetti.nextplayer.core.common.extensions.openAllFilesAccessSettings
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.composables.AllFilesAccessView
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import dev.anilbeesetti.nextplayer.feature.books.navigation.PendingBookFolder
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.QuickSettingsDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.DeleteConfirmationDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferFlowState
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferProgressDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.rememberSelectionManager
import dev.anilbeesetti.nextplayer.feature.videopicker.state.toSelectedFolder
import java.io.File

/**
 * 书架页：完全照漫画库的交互重写——白名单文件夹树、下拉刷新、多选删除/重命名/分享/复制/移动，
 * 搜索和快捷设置复用媒体库组件。点书直接进入阅读页。
 */
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    tabBar: @Composable () -> Unit = {},
    contentModifier: Modifier = Modifier,
    shelfGroups: List<BookShelfGroup> = emptyList(),
    defaultGroupName: String = "",
    onAddToShelf: (groupId: Long?, newGroupName: String, paths: List<String>) -> Unit =
        { _, _, _ -> },
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
        PendingBookFolder.consume()?.let(viewModel::openFolder)
        onPauseOrDispose {}
    }

    LaunchedEffect(hasStorageAccess) {
        if (!hasStorageAccess) context.openAllFilesAccessSettings()
    }

    BookshelfScreenContent(
        state = state,
        viewModel = viewModel,
        tabBar = tabBar,
        contentModifier = contentModifier,
        hasStorageAccess = hasStorageAccess,
        shelfGroups = shelfGroups,
        defaultGroupName = defaultGroupName,
        onAddToShelf = onAddToShelf,
        onAllFilesAccessGranted = {
            hasStorageAccess = true
            viewModel.refresh()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfScreenContent(
    state: BookshelfUiState,
    viewModel: BookshelfViewModel,
    tabBar: @Composable () -> Unit = {},
    contentModifier: Modifier = Modifier,
    hasStorageAccess: Boolean = true,
    shelfGroups: List<BookShelfGroup> = emptyList(),
    defaultGroupName: String = "",
    onAddToShelf: (groupId: Long?, newGroupName: String, paths: List<String>) -> Unit =
        { _, _, _ -> },
    onAllFilesAccessGranted: () -> Unit = {},
) {
    // 文件夹层和书籍层各自持有滚动状态——都用 rememberSaveable 持久化，
    // push/pop 阅读页时整个 Screen 被 dispose 也能恢复回来。
    val folderGridState = rememberPersistentLazyGridState(key = "browse_root")
    val bookGridState = rememberPersistentLazyGridState(key = state.folderPath)
    val lazyGridState = if (state.folderPath == null) folderGridState else bookGridState
    val navigationBottomPadding = LocalNavigationBottomPadding.current
    val selectionManager = rememberSelectionManager()
    val contentFocusRequester = remember { FocusRequester() }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var renameBookPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddToShelfDialog by rememberSaveable { mutableStateOf(false) }
    var addToShelfPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    BindTopLevelBottomBarVisible(
        visible = state.folderPath == null && !selectionManager.isInSelectionMode,
    )

    if (state.folderPath == null && !selectionManager.isInSelectionMode) {
        // 优先继续上次看的书，没有记录就打开当前目录第一本
        val pool = state.books.ifEmpty { state.allBooks }
        if (pool.isNotEmpty()) {
            BindTopLevelFab(
                key = TopLevelFabKey.BOOKS,
                icon = NextIcons.Books,
                onClick = {
                    // 上次看的没了（改名/移动/删除）就顺延到最近读过的下一本，最后才退回第一本
                    fun existing(path: String?): BookFile? = pool.firstOrNull {
                        it.path == path && File(it.path).exists()
                    }
                    val target = existing(state.preferences.lastViewedBookPath)
                        ?: existing(state.recentBookPath)
                        ?: pool.first()
                    viewModel.output.openBook(target.path)
                },
            )
        }
    }

    val selectedItemsSize = selectionManager.selectionItems.size
    val totalItemsSize = state.folders.size + state.books.size
    // 只有单选一本书时才允许重命名；分享对任意数量都可用
    val singleSelectedBook = selectionManager.selectionItems
        .singleOrNull()
        ?.let { it as? SelectionItem.Video }
        ?.let { item -> state.allBooks.firstOrNull { it.path == item.path } }
    val hasSelectedBook = selectionManager.selectionItems.any { it is SelectionItem.Video }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    if (selectionManager.isInSelectionMode) {
                        Text(text = "", maxLines = 1)
                    } else if (state.folderPath == null) {
                        tabBar()
                    } else {
                        Text(
                            text = state.folderName ?: stringResource(R.string.books),
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
                                            state.books.forEach { add(it.toSelectionItem()) }
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
                showRenameAction = singleSelectedBook != null,
                showInfoAction = false,
                showHideAction = false,
                showPlayAction = false,
                showAddToPlaylistAction = false,
                showAddToShelfAction = true,
                showShareAction = hasSelectedBook,
                showCopyAction = true,
                showMoveAction = true,
                onPlayAction = {},
                onAddToPlaylistAction = {},
                onAddToShelfAction = {
                    val paths = selectionManager.selectionItems.toShelfPaths(state.allBooks)
                    if (paths.isNotEmpty()) {
                        addToShelfPaths = paths
                        showAddToShelfDialog = true
                    }
                },
                onRenameAction = { renameBookPath = singleSelectedBook?.path },
                onShareAction = {
                    viewModel.onAction(
                        BookshelfAction.ShareSelected(selectionManager.selectionItems),
                    )
                },
                onCopyAction = {
                    viewModel.onAction(
                        BookshelfAction.CopySelected(selectionManager.selectionItems),
                    )
                    selectionManager.exitSelectionMode()
                },
                onMoveAction = {
                    viewModel.onAction(
                        BookshelfAction.MoveSelected(selectionManager.selectionItems),
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
                .background(MaterialTheme.colorScheme.background)
                .then(contentModifier),
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
                        state.bookFolders.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = NextIcons.Books,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.book_folders_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                TextButton(onClick = viewModel::openBookFolders) {
                                    Text(text = stringResource(R.string.book_folders_setup))
                                }
                            }
                        }

                        state.folders.isEmpty() && state.books.isEmpty() -> {
                            NoBooksFound(contentPadding = contentPadding)
                        }

                        else -> {
                            BookLibraryView(
                                folders = state.folders,
                                books = state.books,
                                preferences = state.preferences,
                                lastViewedPath = state.preferences.lastViewedBookPath,
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
                                onBookClick = { book ->
                                    if (selectionManager.isInSelectionMode) {
                                        selectionManager.toggleSelection(
                                            book.toSelectionItem(),
                                        )
                                    } else {
                                        viewModel.output.openBook(book.path)
                                    }
                                },
                                onFolderLongClick = { folder ->
                                    selectionManager.toggleSelection(folder.toSelectedFolder())
                                },
                                onBookLongClick = { book ->
                                    selectionManager.toggleSelection(book.toSelectionItem())
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
        enabled = !selectionManager.isInSelectionMode && state.folderPath != null,
    ) {
        viewModel.goBack()
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = state.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = viewModel::updatePreferences,
            showFieldSettings = true,
            mediaFilesLabel = stringResource(R.string.books),
            imageMode = true,
            bookMode = true,
        )
    }

    if (showDeleteConfirmation) {
        DeleteConfirmationDialog(
            selectionItems = selectionManager.selectionItems,
            comicMode = true,
            onConfirm = {
                viewModel.onAction(
                    BookshelfAction.DeleteSelected(selectionManager.selectionItems),
                )
                selectionManager.exitSelectionMode()
                showDeleteConfirmation = false
            },
            onCancel = { showDeleteConfirmation = false },
        )
    }

    renameBookPath?.let { path ->
        state.allBooks.firstOrNull { it.path == path }?.let { book ->
            RenameDialog(
                // 输入框只放不带后缀的名字，后缀由重命名逻辑自动补回
                name = book.displayName.substringBeforeLast(
                    delimiter = '.',
                    missingDelimiterValue = book.displayName,
                ),
                onDismiss = { renameBookPath = null },
                onDone = {
                    viewModel.onAction(
                        BookshelfAction.RenameSelected(
                            item = book.toSelectionItem(),
                            to = it,
                        ),
                    )
                    selectionManager.exitSelectionMode()
                    renameBookPath = null
                },
            )
        }
    }

    if (showAddToShelfDialog) {
        AddToShelfDialog(
            groups = shelfGroups,
            ungroupedId = shelfGroups.firstOrNull { it.isUngrouped }?.id,
            ungroupedLabel = defaultGroupName,
            defaultGroupName = defaultGroupName,
            onDismiss = {
                showAddToShelfDialog = false
                addToShelfPaths = emptyList()
            },
            onConfirm = { groupId, newGroupName ->
                onAddToShelf(groupId, newGroupName, addToShelfPaths)
                showAddToShelfDialog = false
                addToShelfPaths = emptyList()
                selectionManager.exitSelectionMode()
            },
        )
    }

    (state.transferFlow as? TransferFlowState.Processing)?.let { transfer ->
        TransferProgressDialog(
            mode = transfer.mode,
            progress = transfer.progress,
            onCancel = { viewModel.onAction(BookshelfAction.CancelTransfer) },
        )
    }
}

/** 选中项换算成要加入书架的路径：文件夹展开成其下所有已索引的书。 */
private fun Set<SelectionItem>.toShelfPaths(allBooks: List<BookFile>): List<String> = flatMap { item ->
    when (item) {
        is SelectionItem.Video -> listOf(item.path)

        is SelectionItem.Folder -> {
            val prefix = item.path.trimEnd('/') + "/"
            allBooks.filter { it.path.startsWith(prefix) }.map { it.path }
        }
    }
}.distinct()

/** 书籍在选项里按视频项处理，id 用 file:// uri，与 SelectionManager 一致。 */
private fun BookFile.toSelectionItem() = SelectionItem.Video(
    name = displayName,
    uriString = Uri.fromFile(File(path)).toString(),
    path = path,
)
