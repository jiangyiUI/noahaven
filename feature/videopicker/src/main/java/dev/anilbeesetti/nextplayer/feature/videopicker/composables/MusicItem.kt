package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.media.services.AudioArtwork
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/**
 * 音频文件的封面：优先使用文件内嵌封面，没有时显示缺省图。
 * 不联网获取封面。
 *
 * 播放进度条与视频库样式完全一致：贴底 4dp，底色 secondaryContainer，进度色 primary。
 */
@Composable
fun AudioCover(
    audio: AudioFile,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    playedPercentage: Float? = null,
    showProgress: Boolean = false,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = audio.path,
    ) {
        value = AudioArtwork.load(
            context = context,
            pathOrUri = audio.path,
        )
    }

    val shape = RoundedCornerShape(size = 8.dp)
    Box(
        modifier = modifier
            .clip(shape = shape)
            .background(color = MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val cover = bitmap
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = NextIcons.Audio,
                contentDescription = contentDescription,
                modifier = Modifier.size(size = 28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showProgress && playedPercentage != null) {
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = playedPercentage)
                        .fillMaxHeight()
                        .clip(shape = CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
fun MusicItem(
    audio: AudioFile,
    preferences: ApplicationPreferences,
    isRecentlyPlayed: Boolean = false,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    when (preferences.mediaLayoutMode) {
        MediaLayoutMode.LIST -> MusicListItem(
            audio = audio,
            isRecentlyPlayed = isRecentlyPlayed,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )

        MediaLayoutMode.GRID -> MusicGridItem(
            audio = audio,
            isRecentlyPlayed = isRecentlyPlayed,
            preferences = preferences,
            modifier = modifier,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MusicListItem(
    audio: AudioFile,
    isRecentlyPlayed: Boolean,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val metadata = listOf(
        audio.artist,
        audio.album,
    )
        .filter { it.isNotBlank() }
        .joinToString(separator = " · ")
    val details = buildList {
        if (preferences.showDurationField) add(audio.formattedDuration)
        if (preferences.showSizeField) add(audio.formattedFileSize)
    }.filter { it.isNotEmpty() }

    // 和视频列表一致：平时无背景，只有按下/选中时才出现底色
    NextSegmentedListItem(
        modifier = modifier,
        selected = selected,
        contentPadding = PaddingValues(all = 8.dp),
        colors = ListItemDefaults.segmentedColors(
            contentColor = if (isRecentlyPlayed && preferences.markLastPlayedMedia) {
                MaterialTheme.colorScheme.primary
            } else {
                ListItemDefaults.segmentedColors().contentColor
            },
            supportingContentColor = if (isRecentlyPlayed && preferences.markLastPlayedMedia) {
                MaterialTheme.colorScheme.primary
            } else {
                ListItemDefaults.colors().supportingContentColor
            },
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = {
            AudioCover(
                audio = audio,
                modifier = Modifier.size(size = 52.dp),
                contentDescription = audio.displayName,
                playedPercentage = audio.playedPercentage,
                showProgress = preferences.showPlayedProgress,
            )
        },
        content = {
            // 和视频列表一样用 titleMedium，文件名允许换行完整显示
            Text(
                text = audio.displayName,
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
            )
        },
        supportingContent = if (metadata.isNotEmpty() || details.isNotEmpty()) {
            {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(space = 4.dp),
                ) {
                    if (metadata.isNotEmpty()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(separator = " · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MusicGridItem(
    audio: AudioFile,
    isRecentlyPlayed: Boolean,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    NextSegmentedListItem(
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        contentPadding = PaddingValues(all = 8.dp),
        colors = ListItemDefaults.segmentedColors(
            contentColor = if (isRecentlyPlayed && preferences.markLastPlayedMedia) {
                MaterialTheme.colorScheme.primary
            } else {
                ListItemDefaults.segmentedColors().contentColor
            },
            supportingContentColor = if (isRecentlyPlayed && preferences.markLastPlayedMedia) {
                MaterialTheme.colorScheme.primary
            } else {
                ListItemDefaults.colors().supportingContentColor
            },
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(space = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 方形封面，时长放在右下角，和视频网格保持一致
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape = MaterialTheme.shapes.small)
                        .background(color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        .aspectRatio(ratio = 1f),
                ) {
                    AudioCover(
                        audio = audio,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = audio.displayName,
                        playedPercentage = audio.playedPercentage,
                        showProgress = preferences.showPlayedProgress,
                    )
                    if (preferences.showDurationField && audio.formattedDuration.isNotBlank()) {
                        InfoChip(
                            text = audio.formattedDuration,
                            modifier = Modifier
                                .padding(all = 5.dp)
                                .align(alignment = Alignment.BottomEnd),
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
                                .align(alignment = Alignment.TopEnd)
                                .padding(all = 5.dp)
                                .size(size = 20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 标题固定两行高度，单行/双行等高，和视频网格一致
                val titleText = audio.displayName
                val titleStyle = MaterialTheme.typography.titleMedium.copy(
                    lineBreak = LineBreak.Simple,
                )
                val titleHeight = with(LocalDensity.current) { (titleStyle.lineHeight * 2).toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height = titleHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = titleText,
                        style = titleStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = if (isRecentlyPlayed && preferences.markLastPlayedMedia) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            ListItemDefaults.colors().contentColor
                        },
                    )
                }
                Text(
                    text = listOf(
                        audio.artist,
                        audio.album,
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(separator = " · ")
                        .ifEmpty { "\u200B" },
                    minLines = 1,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}
