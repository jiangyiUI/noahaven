package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.media.index.ComicArchiveReader
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.FolderItem
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.InfoChip
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.lcm
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ComicLibraryView(
    folders: List<Folder>,
    comics: List<ComicFile>,
    preferences: ApplicationPreferences,
    lastViewedPath: String? = null,
    contentPadding: PaddingValues = PaddingValues(),
    lazyGridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    onFolderClick: (String) -> Unit,
    onComicClick: (ComicFile) -> Unit,
) {
    val isTv = LocalContext.current.isTelevision
    val layoutDirection = LocalLayoutDirection.current
    val folderMinWidth = if (isTv) 160.dp else 90.dp
    val comicMinWidth = if (isTv) 240.dp else 130.dp
    val showHeaders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE
    val comicsLabel = stringResource(R.string.comics)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = 8.dp
        val itemSpacing = 2.dp
        val maxWidth = maxWidth - (horizontalPadding * 2) - itemSpacing
        val maxFolders = (maxWidth / folderMinWidth).toInt().coerceAtLeast(1)
        val maxComics = (maxWidth / comicMinWidth).toInt().coerceAtLeast(1)
        val folderColumns = when (preferences.mediaLayoutMode) {
            MediaLayoutMode.LIST -> 1
            MediaLayoutMode.GRID -> maxFolders
        }
        val comicColumns = when (preferences.imageLayoutMode) {
            ImageLayoutMode.LIST -> 1
            ImageLayoutMode.GRID -> maxComics
            ImageLayoutMode.NINE_GRID -> 3
        }
        val folderSpanCount = if (folders.isEmpty()) 1 else folderColumns
        val comicSpanCount = if (comics.isEmpty()) 1 else comicColumns
        val spans = lcm(folderSpanCount, comicSpanCount)
        val singleFolderSpan = spans / folderSpanCount
        val singleComicSpan = spans / comicSpanCount

        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = lazyGridState,
            columns = GridCells.Fixed(spans),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + horizontalPadding,
                end = contentPadding.calculateEndPadding(layoutDirection) + horizontalPadding,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (showHeaders && folders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListSectionTitle(text = stringResource(R.string.folders) + " (${folders.size})")
                }
            }
            itemsIndexed(
                items = folders,
                key = { _, folder -> folder.path },
                span = { _, _ -> GridItemSpan(singleFolderSpan) },
            ) { index, folder ->
                FolderItem(
                    folder = folder,
                    // 最近查看的漫画所在文件夹高亮，与视频库一致
                    isRecentlyPlayedFolder = lastViewedPath != null &&
                        lastViewedPath.startsWith(folder.path.trimEnd('/') + "/"),
                    preferences = preferences,
                    isFirstItem = index == 0,
                    isLastItem = index == folders.lastIndex,
                    onClick = { onFolderClick(folder.path) },
                    mediaCountLabel = comicsLabel,
                )
            }
            if (showHeaders && folders.isNotEmpty() && comics.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.size(8.dp))
                }
            }
            if (showHeaders && comics.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListSectionTitle(text = comicsLabel + " (${comics.size})")
                }
            }
            itemsIndexed(
                items = comics,
                key = { _, comic -> comic.path },
                span = { _, _ -> GridItemSpan(singleComicSpan) },
            ) { index, comic ->
                ComicItem(
                    comic = comic,
                    preferences = preferences,
                    layoutMode = preferences.imageLayoutMode,
                    isLastViewed = comic.path == lastViewedPath,
                    isFirstItem = index == 0,
                    isLastItem = index == comics.lastIndex,
                    onClick = { onComicClick(comic) },
                )
            }
        }
    }
}

/** 搜索结果用：单列自适应网格，不显示文件夹。 */
@Composable
fun ComicGrid(
    comics: List<ComicFile>,
    preferences: ApplicationPreferences = ApplicationPreferences(),
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    onComicClick: (ComicFile) -> Unit,
) {
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Adaptive(minSize = 130.dp),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = comics, key = { it.path }) { comic ->
            ComicItem(
                comic = comic,
                preferences = preferences,
                layoutMode = ImageLayoutMode.GRID,
                onClick = { onComicClick(comic) },
            )
        }
    }
}

@Composable
fun ComicItem(
    comic: ComicFile,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    layoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
    isLastViewed: Boolean = false,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    onClick: () -> Unit = {},
) {
    when (layoutMode) {
        ImageLayoutMode.LIST -> ComicListItem(
            comic = comic,
            preferences = preferences,
            modifier = modifier,
            isLastViewed = isLastViewed,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            onClick = onClick,
        )

        ImageLayoutMode.GRID -> ComicGridItem(
            comic = comic,
            preferences = preferences,
            modifier = modifier,
            isLastViewed = isLastViewed,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            onClick = onClick,
        )

        // 九宫格只显示封面
        ImageLayoutMode.NINE_GRID -> ComicGridItem(
            comic = comic,
            preferences = preferences,
            modifier = modifier,
            isLastViewed = isLastViewed,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            onClick = onClick,
            showFields = false,
        )
    }
}

@Composable
private fun ComicThumbnail(
    comic: ComicFile,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val cover = rememberComicCover(comic)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NextIcons.Comic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        cover?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = comic.displayName,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ComicListItem(
    comic: ComicFile,
    preferences: ApplicationPreferences,
    modifier: Modifier,
    isLastViewed: Boolean,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    onClick: () -> Unit,
) {
    val highlight = isLastViewed && preferences.markLastPlayedMedia
    val details = comicDetails(comic, preferences)
    NextSegmentedListItem(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            contentColor = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                ListItemDefaults.segmentedColors().contentColor
            },
            supportingContentColor = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                ListItemDefaults.colors().supportingContentColor
            },
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        leadingContent = {
            if (preferences.showThumbnailField) {
                ComicThumbnail(
                    comic = comic,
                    modifier = Modifier.size(52.dp),
                )
            } else {
                Icon(
                    imageVector = NextIcons.Comic,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = {
            Text(
                text = comic.displayName,
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (details.isNotEmpty()) {
            {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ComicGridItem(
    comic: ComicFile,
    preferences: ApplicationPreferences,
    modifier: Modifier,
    isLastViewed: Boolean,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    onClick: () -> Unit,
    showFields: Boolean = true,
) {
    NextSegmentedListItem(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                ) {
                    if (!showFields || preferences.showThumbnailField) {
                        ComicThumbnail(
                            comic = comic,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = NextIcons.Comic,
                            contentDescription = comic.displayName,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showFields && preferences.showExtensionField) {
                        InfoChip(
                            text = comic.extension.uppercase(),
                            modifier = Modifier
                                .padding(5.dp)
                                .align(Alignment.BottomStart),
                            backgroundColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    if (showFields && comic.imageCount > 0) {
                        InfoChip(
                            text = stringResource(R.string.comic_pages, comic.imageCount),
                            modifier = Modifier
                                .padding(5.dp)
                                .align(Alignment.BottomEnd),
                            backgroundColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                }
                // 文件名不带后缀，最多两行，样式与视频网格一致
                Text(
                    text = comic.displayName.substringBeforeLast(
                        delimiter = '.',
                        missingDelimiterValue = comic.displayName,
                    ),
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = if (isLastViewed && preferences.markLastPlayedMedia) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ListItemDefaults.colors().contentColor
                    },
                )
            }
        },
    )
}

/** 列表模式下显示在名称下方的详细信息，字段开关跟随快捷设置。 */
@Composable
private fun comicDetails(comic: ComicFile, preferences: ApplicationPreferences): List<String> = buildList {
    if (preferences.showExtensionField) add(comic.extension.uppercase())
    if (preferences.showSizeField) add(comic.formattedFileSize)
    if (comic.imageCount > 0) add(stringResource(R.string.comic_pages, comic.imageCount))
}

/** 封面缓存优先；缓存缺失时直接从压缩包解第一张。 */
@Composable
fun rememberComicCover(comic: ComicFile): ImageBitmap? {
    var cover by remember(comic.path, comic.coverCachePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(comic.path, comic.coverCachePath) {
        cover = withContext(Dispatchers.IO) {
            val cached = comic.coverCachePath?.let(::File)?.takeIf { it.exists() }
            val bitmap: Bitmap? = if (cached != null) {
                BitmapFactory.decodeFile(cached.absolutePath)
            } else {
                ComicArchiveReader.decodeFirstEntryBitmap(comic.path)
            }
            bitmap?.asImageBitmap()
        }
    }
    return cover
}

private val ComicFile.extension: String
    get() = displayName.substringAfterLast('.', missingDelimiterValue = "")

@Composable
fun NoComicsFound(contentPadding: PaddingValues = PaddingValues()) {
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
            text = stringResource(R.string.no_comics_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
