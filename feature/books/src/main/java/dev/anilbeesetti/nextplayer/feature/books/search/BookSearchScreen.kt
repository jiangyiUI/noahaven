package dev.anilbeesetti.nextplayer.feature.books.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import dev.anilbeesetti.nextplayer.feature.books.shelf.BookLibraryView
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.FolderItem

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
    )
}

@Composable
internal fun BookSearchScreenContent(
    state: BookSearchUiState,
    onAction: (BookSearchUiEvent) -> Unit,
    onBookClick: (BookFile) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(key1 = Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
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
                        onBookClick = onBookClick,
                    )
                }
            }
        }
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
    onBookClick: (BookFile) -> Unit,
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
                onFolderClick = onFolderClick,
                onBookClick = onBookClick,
            )
        }
    }
}
