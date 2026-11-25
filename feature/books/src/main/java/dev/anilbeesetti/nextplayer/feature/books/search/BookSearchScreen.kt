package dev.anilbeesetti.nextplayer.feature.books.search

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextOutlinedTextField
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.core.ui.extensions.plus
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfEntry
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import dev.anilbeesetti.nextplayer.feature.books.shelf.AddToShelfDialog
import dev.anilbeesetti.nextplayer.feature.books.shelf.AlreadyInShelfDialog
import dev.anilbeesetti.nextplayer.feature.books.shelf.BookLibraryView
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.FolderItem
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.DeleteConfirmationDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.rememberSelectionManager
import java.io.File
import kotlinx.coroutines.flow.SharedFlow

/** 书籍搜索页，结构与漫画搜索页一致。 */
@Composable
fun BookSearchScreen(
    viewModel: BookSearchViewModel,
    onOpenBook: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )

    BookSearchScreenContent(
        state = state,
        onAction = viewModel::onAction,
        onBookClick = { book ->
            // 打开阅读器时把当前查询写入搜索历史
            viewModel.onAction(BookSearchUiEvent.OnSearch(state.query))
            onOpenBook(book.path)
        },
        onAddToShelf = viewModel::addToShelf,
        onClearBooks = viewModel::removeBooksFromShelf,
        onMoveBooks = viewModel::moveBooksToGroup,
        onRenameBook = viewModel::renameBook,
        events = viewModel.events,
    )
}

@Composable
internal fun BookSearchScreenContent(
    state: BookSearchUiState,
    onAction: (BookSearchUiEvent) -> Unit,
    onBookClick: (BookFile) -> Unit,
    onAddToShelf: (groupId: Long?, newGroupName: String, paths: List<String>) -> Unit,
    onClearBooks: (paths: List<String>) -> Unit,
    onMoveBooks: (paths: List<String>, groupId: Long?, newGroupName: String) -> Unit,
    onRenameBook: (path: String, newName: String) -> Unit,
    events: SharedFlow<BookSearchEvent>,
) {
    val context = LocalContext.current
    val defaultGroupName = stringResource(R.string.book_shelf_ungrouped)
    val focusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val selectionManager = rememberSelectionManager()
    var showAddToShelfDialog by rememberSaveable { mutableStateOf(false) }
    var addToShelfPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    // 书架内搜索：多选底栏的「清除记录 / 移动到分组 / 重命名」弹窗状态。
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var booksToClear by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var booksToMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var showRenameBookDialog by rememberSaveable { mutableStateOf(false) }
    var renameTargetPath by remember { mutableStateOf<String?>(null) }

    // 分组 id → 层级路径（如「分组1/子分组1」，未分组/找不到用本地化名称）。
    val groupLabelFor: (Long) -> String = remember(state.shelfGroups, defaultGroupName) {
        val groupById = state.shelfGroups.associateBy { it.id }
        fun labelFor(groupId: Long): String {
            val group = groupById[groupId] ?: return defaultGroupName
            if (group.isUngrouped) return defaultGroupName
            val chain = mutableListOf<String>()
            var cur: BookShelfGroup? = group
            while (cur != null) {
                chain.add(cur.name)
                cur = cur.parentId?.let { groupById[it] }
            }
            return chain.asReversed().joinToString("/")
        }
        ::labelFor
    }

    // 书架内搜索：构建 path → 分组层级路径（如「分组1/子分组1」，未分组用本地化名称）。
    val pathToGroupLabel: Map<String, String> = remember(
        state.shelfGroups,
        state.shelfEntries,
        defaultGroupName,
    ) {
        state.shelfEntries.associate { entry -> entry.path to groupLabelFor(entry.groupId) }
    }

    // 书架内搜索：副信息行用「分组 · 格式 · 大小 · 进度」接管；浏览搜索保持原行为（显示路径）。
    val shelfSearchSubtitle: (BookFile) -> String? = if (state.shelfOnly) {
        { book ->
            val groupLabel = pathToGroupLabel[book.path] ?: defaultGroupName
            val format = book.format.name
            val size = book.formattedFileSize
            val progress = if (book.hasProgress) "${(book.percent * 100).toInt()}%" else null
            listOfNotNull(groupLabel, format, size, progress).joinToString(" · ")
        }
    } else {
        { null }
    }

    LaunchedEffect(key1 = Unit) { focusRequester.requestFocus() }

    // 已在书架的书（合并展示）：非空时弹出一个对话框列出每本书所在分组。
    var alreadyInShelf by remember { mutableStateOf<Map<String, Long>?>(null) }

    // 加入书架结果：成功提示「已加入书架」；已存在的书合并成一个对话框展示。
    LaunchedEffect(state.shelfGroups, defaultGroupName) {
        events.collect { event ->
            when (event) {
                is BookSearchEvent.Added -> Toast.makeText(
                    context,
                    context.getString(R.string.book_shelf_added),
                    Toast.LENGTH_SHORT,
                ).show()
                is BookSearchEvent.AlreadyInShelf -> alreadyInShelf = event.pathToGroup
            }
        }
    }

    val selectedItemsSize = selectionManager.selectionItems.size
    val totalBooksSize = state.searchResults.books.size
    val allSelected = totalBooksSize > 0 && selectedItemsSize >= totalBooksSize

    Scaffold(
        topBar = {
            if (selectionManager.isInSelectionMode) {
                // 选择模式：顶栏换成关闭 + 已选数量 + 全选，与浏览/书架列表的多选样式一致，
                // 同时避免选择过程中误改搜索词。
                NextTopAppBar(
                    title = { Text(text = "", maxLines = 1) },
                    navigationIcon = {
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
                                    totalBooksSize,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                    actions = {
                        FilledTonalIconButton(
                            onClick = {
                                if (allSelected) {
                                    selectionManager.clearSelection()
                                } else {
                                    selectionManager.selectAll(
                                        state.searchResults.books.map { it.toSearchSelectionItem() },
                                    )
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (allSelected) {
                                    NextIcons.DeselectAll
                                } else {
                                    NextIcons.SelectAll
                                },
                                contentDescription = if (allSelected) {
                                    stringResource(R.string.deselect_all)
                                } else {
                                    stringResource(R.string.select_all)
                                },
                            )
                        }
                    },
                )
            } else {
                NextTopAppBar(
                    title = {
                        NextOutlinedTextField(
                            value = state.query,
                            onValueChange = { onAction(BookSearchUiEvent.OnQueryChange(query = it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester = focusRequester)
                                .tvFocusRing(shape = CircleShape),
                            placeholder = {
                                Text(
                                    text = stringResource(
                                        if (state.shelfOnly) R.string.search_books
                                        else R.string.search_books_and_folders,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = {
                                        onAction(BookSearchUiEvent.OnQueryChange(query = ""))
                                    }) {
                                        Icon(
                                            imageVector = NextIcons.Close,
                                            contentDescription = stringResource(R.string.clear_history),
                                        )
                                    }
                                } else if (state.isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    onAction(BookSearchUiEvent.OnSearch(state.query))
                                    keyboardController?.hide()
                                },
                            ),
                            shape = CircleShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                errorBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                            ),
                        )
                    },
                    navigationIcon = {
                        FilledTonalIconButton(onClick = { onAction(BookSearchUiEvent.NavigateUp) }) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            val selectedPaths = selectionManager.selectionItems
                .mapNotNull { (it as? SelectionItem.Video)?.path }
                .distinct()
            when {
                state.shelfOnly -> {
                    // 书架内搜索：长按进入多选，底栏与书架列表一致——清除记录 / 重命名(仅单选) / 移动到分组。
                    val singleSelectedBook = selectionManager.selectionItems
                        .singleOrNull()
                        ?.let { it as? SelectionItem.Video }
                        ?.let { item -> state.searchResults.books.firstOrNull { it.path == item.path } }
                    SelectionActionsSheet(
                        show = selectionManager.isInSelectionMode && selectedPaths.isNotEmpty(),
                        contentFocusRequester = contentFocusRequester,
                        showRenameAction = singleSelectedBook != null,
                        showDeleteAction = true,
                        showMoveAction = true,
                        deleteLabelRes = R.string.book_shelf_clear,
                        showCopyAction = false,
                        showShareAction = false,
                        showAddToShelfAction = false,
                        showAddToPlaylistAction = false,
                        showPlayAction = false,
                        showReorderAction = false,
                        showInfoAction = false,
                        showHideAction = false,
                        onDeleteAction = {
                            booksToClear = selectedPaths
                            showDeleteConfirmation = true
                        },
                        onRenameAction = {
                            renameTargetPath = singleSelectedBook?.path
                            showRenameBookDialog = true
                        },
                        onMoveAction = {
                            booksToMove = selectedPaths
                            showMoveDialog = true
                        },
                        onCopyAction = {},
                        onShareAction = {},
                        onPlayAction = {},
                        onAddToPlaylistAction = {},
                        onInfoAction = {},
                        onHideAction = {},
                    )
                }
                else -> {
                    // 浏览搜索：只提供「加入书架」批量动作，复用浏览列表的底栏样式。
                    SelectionActionsSheet(
                        show = selectionManager.isInSelectionMode && selectedPaths.isNotEmpty(),
                        contentFocusRequester = contentFocusRequester,
                        showRenameAction = false,
                        showInfoAction = false,
                        showHideAction = false,
                        showPlayAction = false,
                        showAddToPlaylistAction = false,
                        showAddToShelfAction = true,
                        showShareAction = false,
                        showCopyAction = false,
                        showMoveAction = false,
                        showDeleteAction = false,
                        onPlayAction = {},
                        onAddToPlaylistAction = {},
                        onRenameAction = {},
                        onShareAction = {},
                        onCopyAction = {},
                        onMoveAction = {},
                        onInfoAction = {},
                        onHideAction = {},
                        onDeleteAction = {},
                        onAddToShelfAction = {
                            if (selectedPaths.isNotEmpty()) {
                                addToShelfPaths = selectedPaths
                                showAddToShelfDialog = true
                            }
                        },
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
                .padding(
                    start = scaffoldPadding.calculateStartPadding(LocalLayoutDirection.current),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(
                        shape = RoundedCornerShape(
                            topStart = 24.dp,
                            topEnd = 24.dp,
                        ),
                    )
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val updatedScaffoldPadding = scaffoldPadding.copy(top = 0.dp, start = 0.dp)
                if (state.query.isBlank()) {
                    SuggestionsContent(
                        shelfOnly = state.shelfOnly,
                        searchHistory = state.searchHistory,
                        popularFolders = state.popularFolders,
                        contentPadding = updatedScaffoldPadding,
                        onHistoryItemClick = {
                            onAction(BookSearchUiEvent.OnHistoryItemClick(query = it))
                        },
                        onRemoveHistoryItem = {
                            onAction(BookSearchUiEvent.OnRemoveHistoryItem(query = it))
                        },
                        onClearHistory = { onAction(BookSearchUiEvent.OnClearHistory) },
                        onFolderClick = { onAction(BookSearchUiEvent.OpenFolder(path = it)) },
                    )
                } else {
                    SearchResultsContent(
                        searchResults = state.searchResults,
                        preferences = state.preferences,
                        isSearching = state.isSearching,
                        contentPadding = updatedScaffoldPadding,
                        onFolderClick = { onAction(BookSearchUiEvent.OpenFolder(path = it)) },
                        selectedIds = selectionManager.selectionItems.map { it.id }.toSet(),
                        onBookClick = { book ->
                            if (selectionManager.isInSelectionMode) {
                                selectionManager.toggleSelection(book.toSearchSelectionItem())
                            } else {
                                onBookClick(book)
                            }
                        },
                        onBookLongClick = { book ->
                            // 长按进入多选：浏览搜索显示「加入书架」底栏，书架内搜索显示清除/重命名/移动底栏。
                            selectionManager.toggleSelection(book.toSearchSelectionItem())
                        },
                        showBookPath = !state.shelfOnly,
                        subtitleOverride = shelfSearchSubtitle,
                    )
                }
            }
        }
    }

    BackHandler(enabled = selectionManager.isInSelectionMode) {
        selectionManager.exitSelectionMode()
    }

    // 书架内搜索：清除选中书籍的书架记录（不删磁盘文件）
    if (showDeleteConfirmation) {
        DeleteConfirmationDialog(
            selectionItems = selectionManager.selectionItems,
            removalFromShelf = true,
            onConfirm = {
                onClearBooks(booksToClear)
                selectionManager.exitSelectionMode()
                showDeleteConfirmation = false
                booksToClear = emptyList()
            },
            onCancel = {
                showDeleteConfirmation = false
                booksToClear = emptyList()
            },
        )
    }

    // 书架内搜索：移动选中书籍到别的分组
    if (showMoveDialog) {
        AddToShelfDialog(
            groups = state.shelfGroups,
            ungroupedId = state.shelfGroups.firstOrNull { it.isUngrouped }?.id,
            ungroupedLabel = stringResource(R.string.book_shelf_root_directory),
            defaultGroupName = defaultGroupName,
            title = stringResource(R.string.book_shelf_move_to_group),
            onDismiss = {
                showMoveDialog = false
                booksToMove = emptyList()
            },
            onConfirm = { groupId, newGroupName ->
                onMoveBooks(booksToMove, groupId, newGroupName)
                showMoveDialog = false
                booksToMove = emptyList()
                selectionManager.exitSelectionMode()
            },
        )
    }

    // 书架内搜索：单选重命名（与书架列表一致，仅单选时点底栏「重命名」弹出）
    if (showRenameBookDialog) {
        renameTargetPath?.let { path ->
            val book = state.searchResults.books.firstOrNull { it.path == path }
            val initialName = book?.displayName
                ?.substringBeforeLast(delimiter = '.', missingDelimiterValue = book.displayName)
                ?: ""
            RenameDialog(
                name = initialName,
                onDismiss = {
                    showRenameBookDialog = false
                    renameTargetPath = null
                    selectionManager.exitSelectionMode()
                },
                onDone = { name ->
                    onRenameBook(path, name)
                    showRenameBookDialog = false
                    renameTargetPath = null
                    selectionManager.exitSelectionMode()
                },
            )
        }
    }

    if (showAddToShelfDialog) {
        AddToShelfDialog(
            groups = state.shelfGroups,
            ungroupedId = state.shelfGroups.firstOrNull { it.isUngrouped }?.id,
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

    alreadyInShelf?.let { mapping ->
        AlreadyInShelfDialog(
            pathToGroup = mapping,
            groupLabelFor = groupLabelFor,
            onDismiss = { alreadyInShelf = null },
        )
    }
}

@Composable
private fun SuggestionsContent(
    shelfOnly: Boolean,
    searchHistory: List<String>,
    popularFolders: List<Folder>,
    contentPadding: PaddingValues = PaddingValues(),
    onHistoryItemClick: (String) -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onFolderClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp) + contentPadding,
    ) {
        if (searchHistory.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ListSectionTitle(
                        text = stringResource(R.string.recent_searches),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 8.dp),
                    )
                    TextButton(onClick = onClearHistory) {
                        Text(text = stringResource(R.string.clear_history))
                    }
                }
            }

            items(items = searchHistory, key = { "history_$it" }) { query ->
                SearchHistoryItem(
                    query = query,
                    onClick = { onHistoryItemClick(query) },
                    onRemove = { onRemoveHistoryItem(query) },
                )
            }
        }

        if (popularFolders.isNotEmpty()) {
            item {
                ListSectionTitle(
                    text = stringResource(R.string.popular_folders),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = if (searchHistory.isNotEmpty()) 20.dp else 12.dp,
                        bottom = 8.dp,
                    ),
                )
            }

            itemsIndexed(
                items = popularFolders,
                key = { _, folder -> "popular_${folder.path}" },
            ) { index, folder ->
                FolderItem(
                    folder = folder,
                    isRecentlyPlayedFolder = false,
                    preferences = ApplicationPreferences(
                        mediaLayoutMode = MediaLayoutMode.LIST,
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    isFirstItem = index == 0,
                    isLastItem = index == popularFolders.lastIndex,
                    onClick = { onFolderClick(folder.path) },
                    mediaCountLabel = stringResource(R.string.books),
                )
            }
        }

        if (searchHistory.isEmpty() && (shelfOnly || popularFolders.isEmpty())) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = NextIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                if (shelfOnly) R.string.search_books
                                else R.string.search_books_and_folders,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchHistoryItem(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    NextSegmentedListItem(
        modifier = Modifier.padding(horizontal = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = NextIcons.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = NextIcons.Close,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = {
            Text(
                text = query,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun SearchResultsContent(
    searchResults: BookSearchResults,
    preferences: ApplicationPreferences,
    isSearching: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    onFolderClick: (String) -> Unit,
    selectedIds: Set<String> = emptySet(),
    onBookClick: (BookFile) -> Unit,
    onBookLongClick: (BookFile) -> Unit = {},
    showBookPath: Boolean = false,
    subtitleOverride: (BookFile) -> String? = { null },
) {
    AnimatedVisibility(
        visible = isSearching,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 100.dp),
            contentAlignment = Alignment.TopCenter,
        ) { CircularProgressIndicator() }
    }

    AnimatedVisibility(
        visible = !isSearching,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        if (searchResults.isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 100.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = NextIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.no_results_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            BookLibraryView(
                folders = searchResults.folders,
                books = searchResults.books,
                preferences = preferences.copy(mediaViewMode = MediaViewMode.FOLDER_TREE),
                contentPadding = contentPadding,
                selectedIds = selectedIds,
                onFolderClick = onFolderClick,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
                showBookPath = showBookPath,
                subtitleOverride = subtitleOverride,
            )
        }
    }
}

/** 把书籍转换成选管理器可识别的条目（id 用文件 Uri，与书架列表保持一致）。 */
private fun BookFile.toSearchSelectionItem(): SelectionItem.Video = SelectionItem.Video(
    name = displayName,
    uriString = Uri.fromFile(File(path)).toString(),
    path = path,
)
