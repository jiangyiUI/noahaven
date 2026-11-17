package dev.anilbeesetti.nextplayer.feature.more.screens.more

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.feature.more.components.HistoryEmptyState
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.VideoListItem
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet

@Composable
fun MoreScreen(viewModel: MoreViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MoreScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun MoreScreenContent(state: MoreUiState, onAction: (MoreAction) -> Unit) {
    var showClearConfirmation by rememberSaveable { mutableStateOf(value = false) }

    val isInSelectionMode = state.selectedUris.isNotEmpty()
    val historySize = state.history.result.orEmpty().size
    val isAllSelected = historySize > 0 && state.selectedUris.size == historySize

    if (!isInSelectionMode) {
        BindTopLevelFab(
            TopLevelFabKey.MORE,
            NextIcons.DeleteSweep,
        ) { showClearConfirmation = true }
    }
    // 多选时隐藏底部导航栏，由长按底栏占据底部位置（与视频/音乐页一致）
    BindTopLevelBottomBarVisible(visible = !isInSelectionMode)

    BackHandler(enabled = isInSelectionMode) {
        onAction(MoreAction.ClearSelection)
    }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onAction(MoreAction.PlayUri(it.toString())) }
        }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.more)
                            .takeIf { !isInSelectionMode }
                            .orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (isInSelectionMode) {
                        Row(
                            modifier = Modifier
                                .clip(shape = CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { onAction(MoreAction.ClearSelection) }
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
                                    state.selectedUris.size,
                                    state.history.result.orEmpty().size,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                },
                actions = {
                    if (isInSelectionMode) {
                        FilledTonalIconButton(
                            onClick = {
                                if (isAllSelected) {
                                    onAction(MoreAction.ClearSelection)
                                } else {
                                    onAction(MoreAction.SelectAll)
                                }
                            },
                            modifier = Modifier.tvFocusRing(),
                        ) {
                            Icon(
                                imageVector = if (isAllSelected) {
                                    NextIcons.DeselectAll
                                } else {
                                    NextIcons.SelectAll
                                },
                                contentDescription = stringResource(
                                    id = if (isAllSelected) {
                                        R.string.deselect_all
                                    } else {
                                        R.string.select_all
                                    },
                                ),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { onAction(MoreAction.OpenSettings) },
                            modifier = Modifier.tvFocusRing(),
                        ) {
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
                show = isInSelectionMode,
                contentFocusRequester = remember { FocusRequester() },
                showRenameAction = false,
                showInfoAction = false,
                showHideAction = false,
                showMoveAction = false,
                showDeleteAction = true,
                showPlayAction = false,
                showAddToPlaylistAction = false,
                showShareAction = false,
                showCopyAction = false,
                onPlayAction = {},
                onAddToPlaylistAction = {},
                onRenameAction = {},
                onShareAction = {},
                onCopyAction = {},
                onMoveAction = {},
                onInfoAction = {},
                onHideAction = {},
                onDeleteAction = { onAction(MoreAction.DeleteSelected) },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding.copy(bottom = 0.dp))
                .clip(
                    shape = RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2,
                    ) {
                        FilledTonalButton(
                            modifier = Modifier.weight(weight = 1f).tvFocusRing(),
                            onClick = { onAction(MoreAction.OpenPlaylists) },
                        ) {
                            Icon(
                                imageVector = NextIcons.Playlist,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(text = stringResource(R.string.playlists))
                        }
                        FilledTonalButton(
                            modifier = Modifier.weight(weight = 1f).tvFocusRing(),
                            onClick = { onAction(MoreAction.OpenNetwork) },
                        ) {
                            Icon(
                                imageVector = NextIcons.Network,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(text = stringResource(R.string.network))
                        }
                        FilledTonalButton(
                            modifier = Modifier.weight(weight = 1f).tvFocusRing(),
                            onClick = { onAction(MoreAction.OpenVault) },
                        ) {
                            Icon(
                                imageVector = NextIcons.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(text = stringResource(R.string.vault))
                        }
                        FilledTonalButton(
                            modifier = Modifier.weight(weight = 1f).tvFocusRing(),
                            onClick = { filePicker.launch(input = "video/*") },
                        ) {
                            Icon(
                                imageVector = NextIcons.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(text = stringResource(R.string.pick_file))
                        }
                        if (MediaOperationsService.supportsTrash()) {
                            FilledTonalButton(
                                modifier = Modifier.weight(weight = 1f).tvFocusRing(),
                                onClick = { onAction(MoreAction.OpenTrash) },
                            ) {
                                Icon(
                                    imageVector = NextIcons.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(text = stringResource(R.string.trash))
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(weight = 1f))
                        }
                    }
                    Text(
                        text = stringResource(R.string.history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp),
                    )
                }

                HistoryContent(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
                    bottomPadding = scaffoldPadding.calculateBottomPadding(),
                )
            }
        }
    }

    if (showClearConfirmation) {
        NextDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(text = stringResource(R.string.clear_history)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(MoreAction.ClearHistory)
                        showClearConfirmation = false
                    },
                ) { Text(text = stringResource(R.string.clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
            content = { Text(text = stringResource(R.string.clear_history_confirmation)) },
        )
    }
}

@Composable
private fun HistoryContent(
    state: MoreUiState,
    onAction: (MoreAction) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    if (state.preferences.isHistoryPaused) {
        HistoryEmptyState(
            isHistoryOff = true,
            modifier = modifier.fillMaxSize().verticalScroll(state = rememberScrollState()),
        )
        return
    }

    when (val history = state.history) {
        is DataState.Loading -> Box(modifier = modifier) { CenterCircularProgressBar() }

        is DataState.Error -> Text(
            text = history.value.message.orEmpty(),
            modifier = modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )

        is DataState.Success -> if (history.value.isEmpty()) {
            HistoryEmptyState(
                isHistoryOff = false,
                modifier = modifier.fillMaxSize().verticalScroll(state = rememberScrollState()),
            )
        } else {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(
                    start = 8.dp,
                    top = 4.dp,
                    end = 8.dp,
                    bottom = bottomPadding + LocalNavigationBottomPadding.current,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = history.value,
                    key = { _, video -> video.uriString },
                ) { index, video ->
                    VideoListItem(
                        video = video,
                        isRecentlyPlayedVideo = false,
                        preferences = state.preferences,
                        isFirstItem = index == 0,
                        isLastItem = index == history.value.lastIndex,
                        selected = video.uriString in state.selectedUris,
                        onClick = {
                            if (state.selectedUris.isEmpty()) {
                                onAction(MoreAction.PlayVideo(video))
                            } else {
                                onAction(MoreAction.ToggleSelection(video.uriString))
                            }
                        },
                        onLongClick = {
                            onAction(MoreAction.ToggleSelection(video.uriString))
                        },
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun MoreScreenContentPreview() {
    MoreScreenContent(
        state = MoreUiState(
            history = DataState.Success(value = emptyList()),
        ),
        onAction = {},
    )
}
