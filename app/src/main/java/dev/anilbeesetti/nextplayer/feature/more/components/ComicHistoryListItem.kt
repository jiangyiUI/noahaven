package dev.anilbeesetti.nextplayer.feature.more.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.more.screens.more.ComicHistoryEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.InfoChip
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.rememberComicCover

/** 「更多」页历史列表里的漫画阅读记录，样式与视频/音乐历史条目完全一致。 */
@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ComicHistoryListItem(
    entry: ComicHistoryEntry,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val comic = entry.comic
    val path = comic.path.substringBeforeLast(delimiter = "/")
    val showPath = preferences.showPathField && path.isNotBlank()
    val showSize =
        preferences.showSizeField && comic.size > 0 && comic.formattedFileSize.isNotBlank()
    val showPages = comic.imageCount > 0

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
            ComicHistoryThumbnail(
                comic = comic,
                showThumbnail = preferences.showThumbnailField,
                modifier = Modifier.width(
                    width = min(
                        150.dp,
                        b = LocalConfiguration.current.screenWidthDp.dp * 0.35f,
                    ),
                ),
            )
        },
        content = {
            Text(
                text = if (preferences.showExtensionField) {
                    comic.displayName
                } else {
                    comic.displayName.substringBeforeLast(
                        delimiter = '.',
                        missingDelimiterValue = comic.displayName,
                    )
                },
                maxLines = 2,
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (showPath || showSize || showPages) {
            {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showPath) {
                        Text(
                            text = path,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showSize || showPages) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (showSize) {
                                InfoChip(text = comic.formattedFileSize)
                            }
                            if (showPages) {
                                InfoChip(text = stringResource(R.string.comic_pages, comic.imageCount))
                            }
                        }
                    }
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ComicHistoryThumbnail(
    comic: ComicFile,
    showThumbnail: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .aspectRatio(ratio = 16f / 10f),
    ) {
        Icon(
            imageVector = NextIcons.Comic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceColorAtElevation(100.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(fraction = 0.5f),
        )
        if (showThumbnail) {
            rememberComicCover(comic)?.let { cover ->
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
