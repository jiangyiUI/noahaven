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

/** 底部分组弹窗要执行的移动/复制动作。 */
private enum class GroupPickerMode { MOVE, COPY }

private data class GroupPickerRequest(
    val books: List<ShelfBook>,
    val mode: GroupPickerMode,
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
    // 每个分组一套滚动位置，push/pop 阅读页也能记住
    val shelfGridState = rememberPersistentLazyGridState(key = state.selectedGroupId)
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

    val groupMenuOpen = groupMenuTarget != null
    val selectMode = selectionManager.isInSelectionMode

    // 分组长按底栏 + 多选底栏都要隐藏顶部级底栏
    BindTopLevelBottomBarVisible(visible = !groupMenuOpen && !selectMode)

    // 与「浏览」tab 同一个 FAB 槽位：点击续看书架上最近读过的一本。
    // 无条件注册（仅跟随底栏可见性），避免书架为空时残留浏览页的旧 FAB 导致点击串页；
    // 空书架下由 ViewModel 的空池判断兜底为无操作。
    if (!groupMenuOpen && !selectMode) {
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

    // BackHandler 优先级：多选退出 > 分组菜单关闭 > 分组返回
    BackHandler(enabled = selectMode) {
        selectionManager.exitSelectionMode()
    }
    BackHandler(enabled = groupMenuOpen) {
        groupMenuTarget = null
    }
    BackHandler(enabled = state.selectedGroupId != null && !selectMode && !groupMenuOpen) {
        viewModel.selectGroup(null)
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    if (selectMode) {
                        Text(text = "", maxLines = 1)
                    } else {
                        tabBar()
                    }
                },
                navigationIcon = {
                    if (selectMode) {
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
                    // 多选底栏：删除/移动/复制，只有单选时才能重命名
                    SelectionActionsSheet(
                        show = selectedItemsSize > 0,
                        contentFocusRequester = contentFocusRequester,
                        showRenameAction = singleSelectedBook != null,
                        showDeleteAction = true,
                        showMoveAction = true,
                        showCopyAction = true,
                        showShareAction = false,
                        showAddToShelfAction = false,
                        showAddToPlaylistAction = false,
                        showPlayAction = false,
                        showReorderAction = false,
                        showInfoAction = false,
                        showHideAction = false,
                        onDeleteAction = { showDeleteConfirmation = true },
                        onRenameAction = { showRenameBookDialog = true },
                        onMoveAction = {
                            val books = selectionManager.selectionItems.toShelfBooks(state.books)
                            if (books.isNotEmpty()) {
                                groupPickerRequest = GroupPickerRequest(books, GroupPickerMode.MOVE)
                            }
                        },
                        onCopyAction = {
                            val books = selectionManager.selectionItems.toShelfBooks(state.books)
                            if (books.isNotEmpty()) {
                                groupPickerRequest = GroupPickerRequest(books, GroupPickerMode.COPY)
                            }
                        },
                        onShareAction = { /* 书架记录只是路径，不直接分享 */ },
                        onPlayAction = {},
                        onAddToPlaylistAction = {},
                        onInfoAction = {},
                        onHideAction = {},
                    )
                }

                groupMenuOpen -> {
                    // 分组长按底栏：保持原有单选逻辑
                    SelectionActionsSheet(
                        show = true,
                        contentFocusRequester = contentFocusRequester,
                        showDeleteAction = true,
                        showRenameAction = true,
                        showReorderAction = true,
                        showCopyAction = false,
                        showMoveAction = false,
                        showPlayAction = false,
                        showAddToPlaylistAction = false,
                        showAddToShelfAction = false,
                        showShareAction = false,
                        showInfoAction = false,
                        showHideAction = false,
                        onDeleteAction = {
                            groupMenuTarget?.let { deleteGroupTarget = it }
                            groupMenuTarget = null
                        },
                        onRenameAction = {
                            groupMenuTarget?.let { renameGroupTarget = it }
                            groupMenuTarget = null
                        },
                        onReorderAction = {
                            groupMenuTarget?.let { reorderGroupTarget = it }
                            groupMenuTarget = null
                        },
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
            ShelfGroupChips(
                groups = state.groups,
                selectedGroupId = state.selectedGroupId,
                allCount = state.allCount,
                groupCounts = state.groupCounts,
                onSelectGroup = { groupId ->
                    selectionManager.exitSelectionMode()
                    groupMenuTarget = null
                    viewModel.selectGroup(groupId)
                },
                onGroupLongClick = { group ->
                    selectionManager.exitSelectionMode()
                    groupMenuTarget = group
                },
                onCreateGroup = {
                    selectionManager.exitSelectionMode()
                    groupMenuTarget = null
                    showNewGroupDialog = true
                },
            )
            val contentPadding = PaddingValues(bottom = navigationBottomPadding)
            if (state.isLoading) {
                CenterCircularProgressBar()
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                ) {
                    when {
                        state.books.isEmpty() -> {
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
                                    text = stringResource(R.string.book_shelf_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        end = 24.dp,
                                        top = 12.dp,
                                    ),
                                )
                            }
                        }

                        else -> {
                            BookShelfGrid(
                                books = state.books.map { it.book },
                                preferences = state.preferences,
                                lastViewedPath = state.lastViewedBookPath,
                                contentPadding = contentPadding,
                                state = shelfGridState,
                                selectedIds = selectionManager.selectionItems
                                    .map { it.id }
                                    .toSet(),
                                onBookClick = { book ->
                                    val shelfBook = state.books.firstOrNull { it.book.path == book.path }
                                    if (selectMode) {
                                        shelfBook?.let {
                                            selectionManager.toggleSelection(it.book.toSelectionItem())
                                        }
                                    } else {
                                        viewModel.output.openBook(book.path)
                                    }
                                },
                                onBookLongClick = { book ->
                                    val shelfBook = state.books.firstOrNull { it.book.path == book.path }
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

    // 多选删除确认
    if (showDeleteConfirmation) {
        val booksToRemove = selectionManager.selectionItems.toShelfBooks(state.books)
        DeleteConfirmationDialog(
            selectionItems = selectionManager.selectionItems,
            comicMode = true,
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

    // 分组移动/复制
    groupPickerRequest?.let { request ->
        AddToShelfDialog(
            groups = state.groups,
            defaultGroupName = viewModel.output.defaultGroupName,
            title = stringResource(
                when (request.mode) {
                    GroupPickerMode.MOVE -> R.string.book_shelf_move_to_group
                    GroupPickerMode.COPY -> R.string.book_shelf_copy_to_group
                },
            ),
            onDismiss = { groupPickerRequest = null },
            onConfirm = { groupId, newGroupName ->
                when (request.mode) {
                    GroupPickerMode.MOVE -> viewModel.moveBooksToGroup(
                        books = request.books,
                        groupId = groupId,
                        newGroupName = newGroupName,
                    )

                    GroupPickerMode.COPY -> viewModel.copyBooksToGroup(
                        books = request.books,
                        groupId = groupId,
                        newGroupName = newGroupName,
                    )
                }
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
            text = { Text(text = stringResource(R.string.book_shelf_delete_group_message)) },
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
            groups = state.groups,
            onDismiss = { reorderGroupTarget = null },
            onConfirm = { index ->
                viewModel.moveGroupTo(group.id, index)
                reorderGroupTarget = null
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

/** 分组选择：全部 + 各分组（带数量），末尾是「新建分组」加号，加号固定在右侧不随滚动。 */
@Composable
private fun ShelfGroupChips(
    groups: List<BookShelfGroup>,
    selectedGroupId: Long?,
    allCount: Int,
    groupCounts: Map<Long, Int>,
    onSelectGroup: (Long?) -> Unit,
    onGroupLongClick: (BookShelfGroup) -> Unit,
    onCreateGroup: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 上方 20dp，与下方（12dp + 书籍网格顶部 8dp）保持一致
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
            item(key = "all") {
                ShelfChip(
                    selected = selectedGroupId == null,
                    label = stringResource(
                        R.string.book_shelf_group_count,
                        stringResource(R.string.book_shelf_group_all),
                        allCount,
                    ),
                    onClick = { onSelectGroup(null) },
                )
            }
            items(items = groups, key = { it.id }) { group ->
                ShelfChip(
                    selected = selectedGroupId == group.id,
                    label = stringResource(
                        R.string.book_shelf_group_count,
                        group.name,
                        groupCounts[group.id] ?: 0,
                    ),
                    onClick = { onSelectGroup(group.id) },
                    onLongClick = { onGroupLongClick(group) },
                )
            }
        }
        CreateGroupChip(
            modifier = Modifier.padding(end = 16.dp),
            onClick = onCreateGroup,
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
