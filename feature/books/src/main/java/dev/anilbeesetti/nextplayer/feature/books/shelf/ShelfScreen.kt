package dev.anilbeesetti.nextplayer.feature.books.shelf

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.DoneButton
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.composables.rememberPersistentLazyGridState
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.QuickSettingsDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.TextInputDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.DeleteConfirmationDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.rememberSelectionManager
import java.io.File

/** 底部分组弹窗要执行的动作（书架已去重，同本书只一条记录，故仅保留移动）。 */
private enum class GroupPickerMode { MOVE }

private data class GroupPickerRequest(
    val books: List<ShelfBook>,
    val mode: GroupPickerMode,
)

/** 分组子树移动：要搬的源分组。 */
private enum class GroupTargetMode { MOVE }

private data class GroupTargetRequest(
    val group: BookShelfGroup,
    val mode: GroupTargetMode,
)

/**
 * 「书架」tab：按分组展示收藏的书籍，布局/字段开关与「浏览」共用同一份偏好。
 * 长按分组管理分组，长按书籍进入多选模式——选完用 SelectionActionsSheet 批量操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    viewModel: ShelfViewModel,
    tabBar: @Composable () -> Unit = {},
    contentModifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    // 屏幕启动时确保「未分组」存在（老用户升级会把第一个顶级分组提升为未分组）
    LaunchedEffect(Unit) { viewModel.ensureUngrouped() }
    // 状态栏 / 进入分组各记一套滚动位置，切换不串
    val shelfGridState = rememberPersistentLazyGridState(key = "${state.selectedStatus}_${state.navigatedGroupId}")
    val navigationBottomPadding = LocalNavigationBottomPadding.current
    val selectionManager = rememberSelectionManager()
    val contentFocusRequester = remember { FocusRequester() }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var groupMenuTarget by remember { mutableStateOf<BookShelfGroup?>(null) }
    var renameGroupTarget by remember { mutableStateOf<BookShelfGroup?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<BookShelfGroup?>(null) }
    var reorderGroupTarget by remember { mutableStateOf<BookShelfGroup?>(null) }
    var groupPickerRequest by remember { mutableStateOf<GroupPickerRequest?>(null) }
    var showNewGroupDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameBookDialog by remember { mutableStateOf(false) }
    var subGroupParent by remember { mutableStateOf<BookShelfGroup?>(null) }
    var groupTargetRequest by remember { mutableStateOf<GroupTargetRequest?>(null) }

    val groupMenuOpen = groupMenuTarget != null
    val selectMode = selectionManager.isInSelectionMode

    // 分组长按底栏 + 多选底栏 + 进入分组（与浏览 tab 进文件夹一致）都要隐藏顶级底栏
    BindTopLevelBottomBarVisible(visible = state.navigatedGroupId == null && !groupMenuOpen && !selectMode)

    // 与「浏览」tab 同一个 FAB 槽位：点击续看书架上最近读过的一本。
    // 无条件注册（仅跟随底栏可见性），避免书架为空时残留浏览页的旧 FAB 导致点击串页；
    // 空书架下由 ViewModel 的空池判断兜底为无操作。
    if (state.navigatedGroupId == null && !groupMenuOpen && !selectMode) {
        BindTopLevelFab(
            key = TopLevelFabKey.BOOKS,
            icon = NextIcons.Books,
            onClick = viewModel::continueLastViewedBook,
        )
    }

    // 多选时顶栏操作
    val selectedItemsSize = selectionManager.selectionItems.size
    val totalItemsSize = state.books.size
    val singleSelectedBook = selectionManager.selectionItems
        .singleOrNull()
        ?.let { it as? SelectionItem.Video }
        ?.let { item -> state.books.firstOrNull { it.book.path == item.path } }

    // 选中的书架书籍：用于底栏「标记已读/未读」与「移动」的目标计算
    val selectedShelfBooks = selectionManager.selectionItems.toShelfBooks(state.books)
    val allSelectedFinished = selectedShelfBooks.isNotEmpty() &&
        selectedShelfBooks.all { it.book.finished }
    val toggleFinishedLabelRes =
        if (allSelectedFinished) R.string.book_shelf_mark_unread else R.string.book_shelf_mark_read

    // BackHandler 优先级：多选退出 > 分组菜单关闭 > 分组返回
    BackHandler(enabled = selectMode) {
        selectionManager.exitSelectionMode()
    }
    BackHandler(enabled = groupMenuOpen) {
        groupMenuTarget = null
    }
    val canGoBack = state.navigatedGroupId != null
    BackHandler(enabled = canGoBack && !selectMode && !groupMenuOpen) {
        viewModel.onBack()
    }

    // 是否进入了某个分组（非首页）：顶栏显示 ←+分组名、内容走图1样式、状态栏隐藏
    val currentGroup = state.groups.firstOrNull { it.id == state.navigatedGroupId }
    val isInGroup = currentGroup != null
    val isHome = state.navigatedGroupId == null

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    when {
                        selectMode -> Text(text = "", maxLines = 1)
                        isInGroup -> Text(
                            text = currentGroup!!.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        else -> tabBar()
                    }
                },
                navigationIcon = {
                    when {
                        selectMode -> {
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
                        }
                        isInGroup -> {
                            FilledTonalIconButton(onClick = { viewModel.onBack() }) {
                                Icon(
                                    imageVector = NextIcons.ArrowBack,
                                    contentDescription = stringResource(R.string.navigate_up),
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (selectMode) {
                        FilledTonalIconButton(
                            onClick = {
                                if (selectedItemsSize != totalItemsSize) {
                                    selectionManager.selectAll(
                                        state.books.map { it.book.toSelectionItem() },
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
            when {
                selectMode -> {
                    // 多选底栏：清除/标记已读未读/移动，只有单选时才能重命名（已去重，无复制）
                    SelectionActionsSheet(
                        show = selectedItemsSize > 0,
                        contentFocusRequester = contentFocusRequester,
                        showRenameAction = singleSelectedBook != null,
                        showDeleteAction = true,
                        showMoveAction = true,
                        showToggleFinishedAction = selectedShelfBooks.isNotEmpty(),
                        toggleFinishedLabelRes = toggleFinishedLabelRes,
                        deleteLabelRes = R.string.book_shelf_clear,
                        showCopyAction = false,
                        showShareAction = false,
                        showAddToShelfAction = false,
                        showAddToPlaylistAction = false,
                        showPlayAction = false,
                        showReorderAction = false,
                        showInfoAction = false,
                        showHideAction = false,
                        onDeleteAction = { showDeleteConfirmation = true },
                        onRenameAction = { showRenameBookDialog = true },
                        onToggleFinishedAction = {
                            if (selectedShelfBooks.isNotEmpty()) {
                                viewModel.setBooksFinished(
                                    paths = selectedShelfBooks.map { it.book.path },
                                    finished = !allSelectedFinished,
                                )
                            }
                        },
                        onMoveAction = {
                            if (selectedShelfBooks.isNotEmpty()) {
                                groupPickerRequest =
                                    GroupPickerRequest(selectedShelfBooks, GroupPickerMode.MOVE)
                            }
                        },
                        onCopyAction = {},
                        onShareAction = { /* 书架记录只是路径，不直接分享 */ },
                        onPlayAction = {},
                        onAddToPlaylistAction = {},
                        onInfoAction = {},
                        onHideAction = {},
                    )
                }

                groupMenuOpen -> {
                    // 分组长按底栏：未分组不可删除/重命名/排序/建子分组/移动（已去重，无复制）
                    val target = groupMenuTarget
                    val isUngrouped = target?.isUngrouped == true
                    SelectionActionsSheet(
                        show = true,
                        contentFocusRequester = contentFocusRequester,
                        showDeleteAction = !isUngrouped,
                        showRenameAction = !isUngrouped,
                        showReorderAction = !isUngrouped,
                        showAddSubGroupAction = !isUngrouped,
                        showMoveGroupAction = !isUngrouped,
                        showCopyGroupAction = false,
                        showCopyAction = false,
                        showMoveAction = false,
                        showPlayAction = false,
                        showAddToPlaylistAction = false,
                        showAddToShelfAction = false,
                        showShareAction = false,
                        showInfoAction = false,
                        showHideAction = false,
                        onDeleteAction = {
                            target?.let { deleteGroupTarget = it }
                            groupMenuTarget = null
                        },
                        onRenameAction = {
                            target?.let { renameGroupTarget = it }
                            groupMenuTarget = null
                        },
                        onReorderAction = {
                            target?.let { reorderGroupTarget = it }
                            groupMenuTarget = null
                        },
                        onAddSubGroupAction = {
                            target?.let { subGroupParent = it }
                            groupMenuTarget = null
                        },
                        onMoveGroupAction = {
                            target?.let {
                                groupTargetRequest = GroupTargetRequest(it, GroupTargetMode.MOVE)
                            }
                            groupMenuTarget = null
                        },
                        onCopyGroupAction = {},
                        onCopyAction = {},
                        onMoveAction = {},
                        onPlayAction = {},
                        onAddToPlaylistAction = {},
                        onShareAction = {},
                        onInfoAction = {},
                        onHideAction = {},
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Column(
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
            // 状态栏只在书架首页显示；进入分组后整页走图1样式，状态栏隐藏
            if (isHome) {
                ShelfStatusBar(
                    statusCounts = state.statusCounts,
                    selected = state.selectedStatus,
                    onSelect = { status ->
                        selectionManager.exitSelectionMode()
                        groupMenuTarget = null
                        viewModel.onStatusSelected(status)
                    },
                    onCreateGroup = {
                        selectionManager.exitSelectionMode()
                        groupMenuTarget = null
                        showNewGroupDialog = true
                    },
                )
            }
            val contentPadding = PaddingValues(bottom = navigationBottomPadding)
            // 可见分组文件夹：首页=顶级（排除未分组）；进入分组=该分组的子分组
            val visibleGroups = if (isHome) {
                state.groups.filter { it.parentId == null && !it.isUngrouped }
            } else {
                state.groups.filter { it.parentId == state.navigatedGroupId }
            }
            val folderList = visibleGroups.map { group ->
                group.toFolder(
                    groupCounts = state.groupCounts,
                    groupSizes = state.groupSizes,
                    childCount = state.groups.count { it.parentId == group.id },
                )
            }
            val folderPathToId = visibleGroups.associate { "shelf_group_${it.id}" to it.id }
            val displayBooks = state.books
            val bookFiles = displayBooks.map { it.book }
            val selectedIds = selectionManager.selectionItems.map { it.id }.toSet()
            val isStatusFilter = isHome && state.selectedStatus != ShelfStatus.ALL
            if (state.isLoading) {
                CenterCircularProgressBar()
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                ) {
                    when {
                        // 态C：未读/阅读中/读完，只展示过滤后的书籍
                        isStatusFilter -> {
                            if (bookFiles.isEmpty()) {
                                ShelfEmptyView(
                                    text = stringResource(statusEmptyRes(state.selectedStatus)),
                                    contentPadding = contentPadding,
                                )
                            } else {
                                BookShelfGrid(
                                    books = bookFiles,
                                    preferences = state.preferences,
                                    lastViewedPath = state.lastViewedBookPath,
                                    contentPadding = contentPadding,
                                    state = shelfGridState,
                                    selectedIds = selectedIds,
                                    onBookClick = { book ->
                                        val shelfBook = displayBooks.firstOrNull { it.book.path == book.path }
                                        if (selectMode) {
                                            shelfBook?.let {
                                                selectionManager.toggleSelection(it.book.toSelectionItem())
                                            }
                                        } else {
                                            viewModel.output.openBook(book.path)
                                        }
                                    },
                                    onBookLongClick = { book ->
                                        val shelfBook = displayBooks.firstOrNull { it.book.path == book.path }
                                        if (shelfBook != null) {
                                            selectionManager.toggleSelection(shelfBook.book.toSelectionItem())
                                        }
                                    },
                                )
                            }
                        }
                        // 态A 首页 / 态B 进入分组：分组文件夹 + 书籍（图1样式）
                        else -> {
                            if (folderList.isEmpty() && bookFiles.isEmpty()) {
                                ShelfEmptyView(
                                    text = if (isHome) {
                                        stringResource(R.string.book_shelf_empty)
                                    } else {
                                        stringResource(R.string.book_shelf_empty_group)
                                    },
                                    contentPadding = contentPadding,
                                )
                            } else {
                                BookLibraryView(
                                    folders = folderList,
                                    books = bookFiles,
                                    preferences = state.preferences,
                                    lastViewedPath = state.lastViewedBookPath,
                                    contentPadding = contentPadding,
                                    lazyGridState = shelfGridState,
                                    selectedIds = selectedIds,
                                    showFolderSectionHeader = false,
                                    showBookSectionHeader = true,
                                    onFolderClick = { path ->
                                        selectionManager.exitSelectionMode()
                                        groupMenuTarget = null
                                        folderPathToId[path]?.let(viewModel::onGroupClick)
                                    },
                                    onFolderLongClick = { folder ->
                                        folderPathToId[folder.path]?.let { id ->
                                            state.groups.firstOrNull { it.id == id }
                                                ?.let { groupMenuTarget = it }
                                        }
                                    },
                                    onBookClick = { book ->
                                        val shelfBook = displayBooks.firstOrNull { it.book.path == book.path }
                                        if (selectMode) {
                                            shelfBook?.let {
                                                selectionManager.toggleSelection(it.book.toSelectionItem())
                                            }
                                        } else {
                                            viewModel.output.openBook(book.path)
                                        }
                                    },
                                    onBookLongClick = { book ->
                                        val shelfBook = displayBooks.firstOrNull { it.book.path == book.path }
                                        if (shelfBook != null) {
                                            selectionManager.toggleSelection(shelfBook.book.toSelectionItem())
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 多选删除确认
    if (showDeleteConfirmation) {
        val booksToRemove = selectionManager.selectionItems.toShelfBooks(state.books)
        DeleteConfirmationDialog(
            selectionItems = selectionManager.selectionItems,
            comicMode = true,
            removalFromShelf = true,
            onConfirm = {
                viewModel.removeBooksFromShelf(booksToRemove)
                selectionManager.exitSelectionMode()
                showDeleteConfirmation = false
            },
            onCancel = { showDeleteConfirmation = false },
        )
    }

    // 单选重命名：仅在用户点击底栏「重命名」按钮后弹出，避免长按选中即弹窗
    if (showRenameBookDialog) {
        singleSelectedBook?.let { target ->
            RenameDialog(
                name = target.book.displayName.substringBeforeLast(
                    delimiter = '.',
                    missingDelimiterValue = target.book.displayName,
                ),
                onDismiss = {
                    showRenameBookDialog = false
                    selectionManager.exitSelectionMode()
                },
                onDone = { name ->
                    viewModel.renameBook(target.book.path, name)
                    showRenameBookDialog = false
                    selectionManager.exitSelectionMode()
                },
            )
        }
    }

    // 分组移动（书架已去重，无复制）
    groupPickerRequest?.let { request ->
        AddToShelfDialog(
            groups = state.groups,
            ungroupedId = state.ungroupedId,
            ungroupedLabel = stringResource(R.string.book_shelf_root_directory),
            defaultGroupName = viewModel.output.defaultGroupName,
            title = stringResource(R.string.book_shelf_move_to_group),
            onDismiss = { groupPickerRequest = null },
            onConfirm = { groupId, newGroupName ->
                viewModel.moveBooksToGroup(
                    books = request.books,
                    groupId = groupId,
                    newGroupName = newGroupName,
                )
                groupPickerRequest = null
                selectionManager.exitSelectionMode()
            },
        )
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = state.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = viewModel::updatePreferences,
            showFieldSettings = true,
            mediaFilesLabel = stringResource(R.string.books),
            imageMode = true,
            shelfMode = true,
        )
    }

    // 新建分组
    if (showNewGroupDialog) {
        TextInputDialog(
            title = stringResource(R.string.book_shelf_new_group),
            stateKey = "new_group",
            onDismiss = { showNewGroupDialog = false },
            onDone = { name ->
                viewModel.createGroup(name)
                // 非全部状态下新建分组后回到「全部」让新分组可见；取消则留在当前状态
                viewModel.onStatusSelected(ShelfStatus.ALL)
                showNewGroupDialog = false
            },
        )
    }

    // 分组长按的单条对话框（保持原有逻辑）
    renameGroupTarget?.let { group ->
        TextInputDialog(
            title = stringResource(R.string.book_shelf_rename_group),
            initialValue = group.name,
            stateKey = group.id,
            onDismiss = { renameGroupTarget = null },
            onDone = { name ->
                viewModel.renameGroup(group.id, name)
                renameGroupTarget = null
            },
        )
    }

    deleteGroupTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text(text = stringResource(R.string.book_shelf_delete_group)) },
            text = {
                val (groupCount, bookCount) = viewModel.deleteImpact(group.id)
                Text(
                    text = stringResource(
                        R.string.book_shelf_delete_group_recursive_message,
                        groupCount,
                        bookCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroup(group.id)
                        deleteGroupTarget = null
                    },
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroupTarget = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    reorderGroupTarget?.let { group ->
        GroupOrderDialog(
            group = group,
            groups = state.groups
                .filter { it.parentId == group.parentId && !it.isUngrouped }
                .sortedBy { it.sortOrder },
            onDismiss = { reorderGroupTarget = null },
            onConfirm = { index ->
                viewModel.moveGroupTo(group.id, index)
                reorderGroupTarget = null
            },
        )
    }

    // 长按分组 → 添加子分组
    subGroupParent?.let { parent ->
        TextInputDialog(
            title = stringResource(R.string.book_shelf_add_subgroup),
            stateKey = "sub_group_${parent.id}",
            onDismiss = { subGroupParent = null },
            onDone = { name ->
                viewModel.createSubGroup(parent.id, name)
                subGroupParent = null
            },
        )
    }

    // 移动分组子树 → 选目标父分组（含「根目录」，排除自身/子孙）
    groupTargetRequest?.let { request ->
        GroupTargetPickerDialog(
            groups = state.groups,
            ungroupedId = state.ungroupedId,
            sourceGroupId = request.group.id,
            title = stringResource(R.string.book_shelf_move_group_to),
            onDismiss = { groupTargetRequest = null },
            onConfirm = { targetParentId ->
                viewModel.moveGroupSubtreeTo(
                    groupId = request.group.id,
                    targetParentId = targetParentId,
                )
                groupTargetRequest = null
            },
        )
    }
}

/** 把 SelectionItem 集合转换成当前可见的 ShelfBook 列表。 */
private fun Set<SelectionItem>.toShelfBooks(all: List<ShelfBook>): List<ShelfBook> {
    val paths = filterIsInstance<SelectionItem.Video>().map { it.path }.toSet()
    return all.filter { it.book.path in paths }
}

/** BookFile 转 SelectionItem.Video，与浏览页保持同一套构造方式。 */
private fun dev.anilbeesetti.nextplayer.core.model.BookFile.toSelectionItem() =
    SelectionItem.Video(
        name = displayName,
        uriString = Uri.fromFile(File(path)).toString(),
        path = path,
    )

/** 状态栏标签文案资源。 */
private fun statusLabelRes(status: ShelfStatus): Int = when (status) {
    ShelfStatus.ALL -> R.string.book_shelf_status_all
    ShelfStatus.UNREAD -> R.string.book_shelf_status_unread
    ShelfStatus.READING -> R.string.book_shelf_status_reading
    ShelfStatus.FINISHED -> R.string.book_shelf_status_finished
}

/** 态C（非全部）空状态文案资源。 */
private fun statusEmptyRes(status: ShelfStatus): Int = when (status) {
    ShelfStatus.UNREAD -> R.string.book_shelf_empty_unread
    ShelfStatus.READING -> R.string.book_shelf_empty_reading
    ShelfStatus.FINISHED -> R.string.book_shelf_empty_finished
    else -> R.string.book_shelf_empty
}

/** 书架分组映射成浏览页同款 Folder 卡片（path 用合成值作唯一 key）。 */
private fun BookShelfGroup.toFolder(
    groupCounts: Map<Long, Int>,
    groupSizes: Map<Long, Long>,
    childCount: Int,
): Folder = Folder(
    name = name,
    path = "shelf_group_$id",
    dateModified = 0L,
    videosCount = groupCounts[id] ?: 0,
    foldersCount = childCount,
    totalSize = groupSizes[id] ?: 0L,
)

/**
 * 状态栏：[全部 N][未读 N][阅读中 N][读完 N] + [＋添加分组]，仅在书架首页显示，固定不可编辑。
 */
@Composable
private fun ShelfStatusBar(
    statusCounts: Map<ShelfStatus, Int>,
    selected: ShelfStatus,
    onSelect: (ShelfStatus) -> Unit,
    onCreateGroup: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                items = listOf(ShelfStatus.ALL, ShelfStatus.UNREAD, ShelfStatus.READING, ShelfStatus.FINISHED),
                key = { it.name },
            ) { status ->
                ShelfChip(
                    selected = selected == status,
                    label = stringResource(
                        R.string.book_shelf_group_count,
                        stringResource(statusLabelRes(status)),
                        statusCounts[status] ?: 0,
                    ),
                    onClick = { onSelect(status) },
                )
            }
        }
        CreateGroupChip(
            modifier = Modifier.padding(end = 16.dp),
            onClick = onCreateGroup,
        )
    }
}

/** 空状态：居中图标 + 文案。 */
@Composable
private fun ShelfEmptyView(
    text: String,
    contentPadding: PaddingValues,
) {
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
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
        )
    }
}

/**
 * Material You 的填充胶囊：选中用 secondaryContainer，未选中用 surfaceContainerHigh，
 * 都是实心填充、无描边、全圆角。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .height(36.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** 「新建分组」入口：实心圆形加号，与分组胶囊同一视觉语言。 */
@Composable
private fun CreateGroupChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick)
                .size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NextIcons.Add,
                contentDescription = stringResource(R.string.book_shelf_new_group),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 调整分组位置：选中要放到这一行的第几个。 */
@Composable
private fun GroupOrderDialog(
    group: BookShelfGroup,
    groups: List<BookShelfGroup>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedIndex by rememberSaveable(group.id) {
        mutableStateOf(groups.indexOfFirst { it.id == group.id }.coerceAtLeast(0))
    }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.book_shelf_sort_group)) },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(state = rememberScrollState()),
            ) {
                groups.forEachIndexed { index, _ ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == selectedIndex,
                                role = Role.RadioButton,
                                onClick = { selectedIndex = index },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = null,
                        )
                        Text(
                            text = stringResource(R.string.book_shelf_group_position, index + 1),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            DoneButton(onClick = { onConfirm(selectedIndex) })
        },
        dismissButton = {
            CancelButton(onClick = onDismiss)
        },
    )
}

/**
 * 移动/复制分组子树时选目标父分组：顶部是「顶级」，下面按层级缩进列出各分组；
 * 自身与自身子孙不可选（避免循环），「未分组」不出现在列表里（不允许有子分组）。
 */
@Composable
private fun GroupTargetPickerDialog(
    groups: List<BookShelfGroup>,
    ungroupedId: Long?,
    sourceGroupId: Long,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (targetParentId: Long?) -> Unit,
) {
    // 自身 + 子孙不能作为目标（避免循环）
    val blocked = subtreeGroupIds(groups, rootId = sourceGroupId, includeSelf = true)
    // 「顶级」用一个不可能与真实 id 冲突的哨兵值表示
    val topLevelSentinel = Long.MIN_VALUE
    var selectedId by rememberSaveable(sourceGroupId) { mutableStateOf(topLevelSentinel) }
    val ordered = remember(groups, ungroupedId) { groupsInDisplayOrder(groups, ungroupedId) }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(state = rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedId == topLevelSentinel,
                            role = Role.RadioButton,
                            onClick = { selectedId = topLevelSentinel },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedId == topLevelSentinel, onClick = null)
                    Text(
                        text = stringResource(R.string.book_shelf_top_level),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                ordered.forEach { (group, depth) ->
                    if (group.id in blocked) return@forEach
                    val selected = selectedId == group.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { selectedId = group.id },
                            )
                            .padding(
                                start = (8 + depth * 16).dp,
                                top = 8.dp,
                                bottom = 8.dp,
                                end = 8.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = group.name,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            DoneButton(
                onClick = {
                    onConfirm(if (selectedId == topLevelSentinel) null else selectedId)
                },
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}

/** 按层级顺序（父在前、子缩进）列出非「未分组」的分组，返回分组与深度。 */
internal fun groupsInDisplayOrder(
    groups: List<BookShelfGroup>,
    ungroupedId: Long?,
): List<Pair<BookShelfGroup, Int>> {
    val byParent = groups.filter { it.id != ungroupedId }.groupBy { it.parentId }
    val result = mutableListOf<Pair<BookShelfGroup, Int>>()
    fun walk(parentId: Long?, depth: Int) {
        for (child in byParent[parentId].orEmpty()) {
            result += child to depth
            walk(child.id, depth + 1)
        }
    }
    walk(null, 0)
    return result
}
