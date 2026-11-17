package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

@Composable
fun ImageItem(
    image: ImageFile,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    layoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    when (layoutMode) {
        ImageLayoutMode.LIST -> ImageListItem(
            image = image,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )

        ImageLayoutMode.GRID -> ImageGridItem(
            image = image,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )

        // 九宫格只显示缩略图，不显示任何字段
        ImageLayoutMode.NINE_GRID -> ImageGridItem(
            image = image,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            showFields = false,
        )
    }
}

@Composable
private fun ImageThumbnail(
    image: ImageFile,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NextIcons.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.uriString)
                .crossfade(true)
                .build(),
            contentDescription = image.displayName,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImageListItem(
    image: ImageFile,
    preferences: ApplicationPreferences,
    modifier: Modifier,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val details = buildList {
        if (preferences.showExtensionField && image.extension.isNotEmpty()) {
            add(image.extension.uppercase())
        }
        if (preferences.showResolutionField && image.hasDimensions) {
            add("${image.width} × ${image.height}")
        }
        if (preferences.showSizeField) add(image.formattedFileSize)
    }
    NextSegmentedListItem(
        modifier = modifier,
        selected = selected,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = {
            if (preferences.showThumbnailField) {
                ImageThumbnail(
                    image = image,
                    modifier = Modifier.size(52.dp),
                )
            } else {
                Icon(
                    imageVector = NextIcons.Image,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = {
            Text(
                text = image.displayName,
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
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
private fun ImageGridItem(
    image: ImageFile,
    preferences: ApplicationPreferences,
    modifier: Modifier,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    showFields: Boolean = true,
) {
    NextSegmentedListItem(
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                if (!showFields || preferences.showThumbnailField) {
                    ImageThumbnail(
                        image = image,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = NextIcons.Image,
                        contentDescription = image.displayName,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showFields && preferences.showExtensionField && image.extension.isNotEmpty()) {
                    InfoChip(
                        text = image.extension.uppercase(),
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.BottomStart),
                        backgroundColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
                if (showFields && preferences.showResolutionField && image.hasDimensions) {
                    InfoChip(
                        text = "${image.width} × ${image.height}",
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.BottomEnd),
                        backgroundColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = NextIcons.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
