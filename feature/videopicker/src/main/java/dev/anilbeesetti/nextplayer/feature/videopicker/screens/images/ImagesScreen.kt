package dev.anilbeesetti.nextplayer.feature.videopicker.screens.images

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.composables.AllFilesAccessView
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ImageView
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.NoImagesFound
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.QuickSettingsDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.PendingImageFolder
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.SelectionActionsSheet
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferFlowState
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker.TransferProgressDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.state.SelectionItem
import dev.anilbeesetti.nextplayer.feature.videopicker.state.rememberSelectionManager
import java.text.DateFormat
import java.util.Date

@Composable
fun ImagesScreen(
    viewModel: ImagesViewModel,
    tabBar: @Composable () -> Unit = {},
    contentModifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val context = LocalContext.current
    var hasStorageAccess by remember { mutableStateOf(context.hasAllFilesAccess()) }
    var wasStorageAccessGranted by remember { mutableStateOf(hasStorageAccess) }

    LifecycleResumeEffect(Unit) {
        val granted = context.hasAllFilesAccess()
        hasStorageAccess = granted
        if (granted && !wasStorageAccessGranted) {
            viewModel.onAction(ImagesAction.OnPermissionAccepted)
        }
        wasStorageAccessGranted = granted
        onPauseOrDispose {}
    }

    // 从搜索页点击文件夹返回时，直接进入该目录
    LifecycleResumeEffect(Unit) {
        PendingImageFolder.consume()?.let { path ->
            viewModel.onAction(ImagesAction.OnFolderClick(folderPath = path))
        }
        onPauseOrDispose {}
    }

    LaunchedEffect(hasStorageAccess) {
        if (!hasStorageAccess) context.openAllFilesAccessSettings()
    }

    ImagesScreenContent(
        state = state,
        onAction = viewModel::onAction,
        tabBar = tabBar,
        contentModifier = contentModifier,
        hasStorageAccess = hasStorageAccess,
        onAllFilesAccessGranted = {
            hasStorageAccess = true
            wasStorageAccessGranted = true
            viewModel.onAction(ImagesAction.OnPermissionAccepted)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImagesScreenContent(
    state: ImagesUiState,
    onAction: (ImagesAction) -> Unit = {},
    tabBar: @Composable () -> Unit = {},
    contentModifier: Modifier = Modifier,
    hasStorageAccess: Boolean = true,
    onAllFilesAccessGranted: () -> Unit = {},
) {
    // 文件夹层和图片层各自持有滚动状态：进入文件夹从顶部开始，返回时文件夹列表仍停在原位置
    val folderGridState = rememberLazyGridState()
    val imageGridState = remember(state.folderPath) { LazyGridState() }
    val lazyGridState = if (state.folderPath == null) folderGridState else imageGridState
    val navigationBottomPadding = LocalNavigationBottomPadding.current
    val selectionManager = rememberSelectionManager()
    val contentFocusRequester = remember { FocusRequester() }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var renameImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var infoImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerImages by remember { mutableStateOf<List<ImageFile>>(emptyList()) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    BindTopLevelBottomBarVisible(
        visible = state.folderPath == null && !selectionManager.isInSelectionMode,
    )

    if (state.folderPath == null) {
        BindTopLevelFab(
            key = TopLevelFabKey.IMAGES,
            icon = NextIcons.View,
            onClick = {
                // 优先继续查看最后一次浏览的图片，没有记录时查看当前第一张
                val pool = state.images.ifEmpty { state.allImages }
                if (pool.isNotEmpty()) {
                    val lastViewedPath = state.preferences.lastViewedImagePath
                    val target = pool.firstOrNull { it.path == lastViewedPath } ?: pool.first()
                    // 只在目标图片所在文件夹内翻页
                    viewerImages = state.allImages
                        .filter { it.parentPath == target.parentPath }
                        .ifEmpty { listOf(target) }
                    viewerImagePath = target.path
                }
            },
        )
    }

    val selectedItemsSize = selectionManager.selectionItems.size
    val totalItemsSize = state.folders.size + state.images.size
    val singleSelectedImage = selectionManager.selectionItems
        .singleOrNull()
        ?.takeIf { it is SelectionItem.Video }
        ?.let { item -> state.images.firstOrNull { it.uriString == item.id } }
    // 菜单项按选择类型区分：文件夹只保留删除/复制/移动，图片额外提供重命名/分享/信息
    val hasImageSelection = selectionManager.selectionItems.any { it is SelectionItem.Video }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    if (state.folderPath == null && !selectionManager.isInSelectionMode) {
                        tabBar()
                    } else {
                        val titleText = (state.folderName ?: stringResource(R.string.images))
                            .takeIf { !selectionManager.isInSelectionMode }.orEmpty()
                        Text(
                            text = titleText,
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
                                .clip(CircleShape)
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
                        FilledTonalIconButton(
                            onClick = { onAction(ImagesAction.OnNavigateUpClick) },
                        ) {
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
                                    val items = buildList {
                                        state.folders.forEach {
                                            add(
                                                SelectionItem.Folder(
                                                    it.name,
                                                    it.path,
                                                ),
                                            )
                                        }
                                        state.images.forEach { add(it.toSelectionItem()) }
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
                                contentDescription = stringResource(
                                    if (selectedItemsSize != totalItemsSize) {
                                        R.string.select_all
                                    } else {
                                        R.string.deselect_all
                                    },
                                ),
                            )
                        }
                    } else {
                        IconButton(onClick = { onAction(ImagesAction.OnSearchClick) }) {
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
                        IconButton(onClick = { onAction(ImagesAction.OnSettingsClick) }) {
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
                showRenameAction = singleSelectedImage != null,
                showInfoAction = singleSelectedImage != null,
                showHideAction = false,
                showMoveAction = true,
                showDeleteAction = true,
                showPlayAction = false,
                showAddToPlaylistAction = false,
                showShareAction = hasImageSelection,
                showCopyAction = true,
                onPlayAction = {},
                onAddToPlaylistAction = {},
                onRenameAction = { renameImageUri = singleSelectedImage?.uriString },
                onShareAction = {
                    onAction(ImagesAction.ShareSelected(selectionManager.selectionItems))
                },
                onCopyAction = {
                    onAction(ImagesAction.CopySelected(selectionManager.selectionItems))
                    selectionManager.exitSelectionMode()
                },
                onMoveAction = {
                    onAction(ImagesAction.MoveSelected(selectionManager.selectionItems))
                    selectionManager.exitSelectionMode()
                },
                onInfoAction = {
                    infoImagePath = singleSelectedImage?.path
                    selectionManager.exitSelectionMode()
                },
                onHideAction = {},
                onDeleteAction = { showDeleteConfirmation = true },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding.copy(bottom = 0.dp))
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.background)
                // 放在 background 之后，白色卡片保持不动，只有里面的内容滑动
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
                    onRefresh = { onAction(ImagesAction.Refresh) },
                ) {
                    val contentPadding = PaddingValues(
                        bottom = scaffoldPadding.calculateBottomPadding() + navigationBottomPadding,
                    )
                    if (state.folders.isEmpty() && state.images.isEmpty()) {
                        NoImagesFound(
                            contentPadding = contentPadding,
                            showFolderSetup = state.preferences.imageFolders.isEmpty(),
                            onFolderSetupClick = { onAction(ImagesAction.OnAddFoldersClick) },
                        )
                    } else {
                        val selectedIds = selectionManager.selectionItems.map { it.id }.toSet()
                        ImageView(
                            folders = state.folders,
                            images = state.images,
                            preferences = state.preferences,
                            contentPadding = contentPadding,
                            lazyGridState = lazyGridState,
                            selectedIds = selectedIds,
                            onFolderClick = { path ->
                                if (selectionManager.isInSelectionMode) {
                                    state.folders.find { it.path == path }?.let { folder ->
                                        selectionManager.toggleSelection(
                                            SelectionItem.Folder(
                                                folder.name,
                                                folder.path,
                                            ),
                                        )
                                    }
                                } else {
                                    onAction(ImagesAction.OnFolderClick(path))
                                }
                            },
                            onImageClick = { image ->
                                if (selectionManager.isInSelectionMode) {
                                    selectionManager.toggleSelection(image.toSelectionItem())
                                } else {
                                    viewerImages = state.images.ifEmpty { state.allImages }
                                    viewerImagePath = image.path
                                }
                            },
                            onFolderLongClick = { folder ->
                                selectionManager.toggleSelection(
                                    SelectionItem.Folder(
                                        folder.name,
                                        folder.path,
                                    ),
                                )
                            },
                            onImageLongClick = { image ->
                                selectionManager.toggleSelection(image.toSelectionItem())
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
        onAction(ImagesAction.OnNavigateUpClick)
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = state.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = { onAction(ImagesAction.UpdatePreferences(it)) },
            showFieldSettings = true,
            mediaFilesLabel = stringResource(R.string.images),
            imageMode = true,
        )
    }

    (state.transferFlow as? TransferFlowState.Processing)?.let { transfer ->
        TransferProgressDialog(
            mode = transfer.mode,
            progress = transfer.progress,
            onCancel = { onAction(ImagesAction.CancelTransfer) },
        )
    }

    renameImageUri?.let { uri ->
        state.images.firstOrNull { it.uriString == uri }?.let { image ->
            RenameDialog(
                // 和视频/音乐一致：输入框只显示不带后缀的名字，后缀由重命名逻辑自动补回
                name = image.displayName,
                onDismiss = { renameImageUri = null },
                onDone = {
                    onAction(
                        ImagesAction.RenameSelected(
                            uri.toUri(),
                            it,
                        ),
                    )
                    selectionManager.exitSelectionMode()
                    renameImageUri = null
                },
            )
        }
    }

    infoImagePath?.let { path ->
        state.images.firstOrNull { it.path == path }?.let { image ->
            ImageInfoDialog(
                image = image,
                onDismiss = { infoImagePath = null },
            )
        }
    }

    viewerImagePath?.let { path ->
        ImageViewer(
            // 进程恢复后列表可能丢失，退回当前列表避免查看器为空
            images = viewerImages.ifEmpty { state.images.ifEmpty { state.allImages } },
            initialImagePath = path,
            onDismiss = {
                viewerImagePath = null
                viewerImages = emptyList()
            },
            onPageChanged = { onAction(ImagesAction.OnImageViewed(it)) },
        )
    }

    if (showDeleteConfirmation) {
        val count = selectionManager.selectionItems.size
        NextDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    stringResource(
                        R.string.delete_items,
                        count,
                    ),
                )
            },
            content = {
                Text(
                    stringResource(
                        if (count == 1) R.string.delete_item_info else R.string.delete_items_info,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(ImagesAction.DeleteSelected(selectionManager.selectionItems))
                        selectionManager.exitSelectionMode()
                        showDeleteConfirmation = false
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { CancelButton(onClick = { showDeleteConfirmation = false }) },
        )
    }
}

@Composable
private fun ImageInfoDialog(image: ImageFile, onDismiss: () -> Unit) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = image.nameWithExtension,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            HorizontalDivider()
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageInfoRow(
                    stringResource(R.string.file),
                    image.nameWithExtension,
                )
                ImageInfoRow(
                    stringResource(R.string.location),
                    image.parentPath,
                )
                ImageInfoRow(
                    stringResource(R.string.size),
                    image.formattedFileSize,
                )
                if (image.hasDimensions) {
                    ImageInfoRow(
                        stringResource(R.string.resolution),
                        "${image.width} × ${image.height}",
                    )
                }
                ImageInfoRow(
                    stringResource(R.string.date),
                    DateFormat.getDateTimeInstance().format(Date(image.dateModified * 1000)),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.okay)) }
        },
    )
}

@Composable
private fun ImageInfoRow(title: String, value: String) {
    Row {
        Text(
            text = "$title: ",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun ImageFile.toSelectionItem() = SelectionItem.Video(
    name = displayName,
    uriString = uriString,
    path = path,
)
