package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.PendingComicFolder
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
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
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

    // 与图片库保持一致：底部栏和继续阅读按钮只在根目录显示
    BindTopLevelBottomBarVisible(
        visible = state.folderPath == null && viewerSession == null,
    )

    if (state.folderPath == null && viewerSession == null) {
        // 优先继续上次看的漫画，没有记录就打开当前目录第一本
        val pool = state.comics.ifEmpty { state.allComics }
        if (pool.isNotEmpty()) {
            BindTopLevelFab(
                key = TopLevelFabKey.IMAGES,
                icon = NextIcons.View,
                onClick = {
                    val lastViewedPath = state.preferences.lastViewedComicPath
                    val target = pool.firstOrNull { it.path == lastViewedPath } ?: pool.first()
                    openComic(target)
                },
            )
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = {
                    if (state.folderPath == null) {
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
                    if (state.folderName != null) {
                        FilledTonalIconButton(onClick = viewModel::goBack) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }
                    }
                },
                actions = {
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
                },
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
                                onFolderClick = viewModel::openFolder,
                                onComicClick = { openComic(it) },
                            )
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = viewerSession == null && state.folderPath != null) {
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
