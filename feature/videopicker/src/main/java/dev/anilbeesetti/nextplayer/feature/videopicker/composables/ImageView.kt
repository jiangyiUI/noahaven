package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

@Composable
fun ImageView(
    folders: List<Folder>,
    images: List<ImageFile>,
    preferences: ApplicationPreferences,
    contentPadding: PaddingValues = PaddingValues(),
    lazyGridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    selectedIds: Set<String> = emptySet(),
    onFolderClick: (String) -> Unit,
    onImageClick: (ImageFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit = {},
    onImageLongClick: (ImageFile) -> Unit = {},
) {
    val isTv = LocalContext.current.isTelevision
    val layoutDirection = LocalLayoutDirection.current
    val folderMinWidth = if (isTv) 160.dp else 90.dp
    val imageMinWidth = if (isTv) 240.dp else 130.dp
    val showHeaders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE
    val imagesLabel = stringResource(R.string.images)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = 8.dp
        val itemSpacing = 2.dp
        val maxWidth = maxWidth - (horizontalPadding * 2) - itemSpacing
        val maxFolders = (maxWidth / folderMinWidth).toInt().coerceAtLeast(1)
        val maxImages = (maxWidth / imageMinWidth).toInt().coerceAtLeast(1)
        // 文件夹和图片分别按自己的布局计算列数
        val folderColumns = when (preferences.mediaLayoutMode) {
            MediaLayoutMode.LIST -> 1
            MediaLayoutMode.GRID -> maxFolders
        }
        val imageColumns = when (preferences.imageLayoutMode) {
            ImageLayoutMode.LIST -> 1
            ImageLayoutMode.GRID -> maxImages
            ImageLayoutMode.NINE_GRID -> 3
        }
        val folderSpanCount = if (folders.isEmpty()) 1 else folderColumns
        val imageSpanCount = if (images.isEmpty()) 1 else imageColumns
        val spans = lcm(folderSpanCount, imageSpanCount)
        val singleFolderSpan = spans / folderSpanCount
        val singleImageSpan = spans / imageSpanCount

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
                    isRecentlyPlayedFolder = false,
                    preferences = preferences,
                    isFirstItem = index == 0,
                    isLastItem = index == folders.lastIndex,
                    selected = folder.path in selectedIds,
                    onClick = { onFolderClick(folder.path) },
                    onLongClick = { onFolderLongClick(folder) },
                    mediaCountLabel = imagesLabel,
                )
            }
            if (showHeaders && folders.isNotEmpty() && images.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.size(8.dp))
                }
            }
            if (showHeaders && images.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListSectionTitle(text = imagesLabel + " (${images.size})")
                }
            }
            itemsIndexed(
                items = images,
                key = { _, image -> image.path },
                span = { _, _ -> GridItemSpan(singleImageSpan) },
            ) { index, image ->
                ImageItem(
                    image = image,
                    preferences = preferences,
                    layoutMode = preferences.imageLayoutMode,
                    isFirstItem = index == 0,
                    isLastItem = index == images.lastIndex,
                    selected = image.uriString in selectedIds,
                    onClick = { onImageClick(image) },
                    onLongClick = { onImageLongClick(image) },
                )
            }
        }
    }
}

@Composable
fun NoImagesFound(contentPadding: PaddingValues = PaddingValues()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = NextIcons.Image,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.no_images_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
