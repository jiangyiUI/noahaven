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
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

@Composable
fun MusicView(
    folders: List<Folder>,
    audios: List<AudioFile>,
    preferences: ApplicationPreferences,
    contentPadding: PaddingValues = PaddingValues(),
    lazyGridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    selectedIds: Set<String> = emptySet(),
    onFolderClick: (String) -> Unit,
    onAudioClick: (AudioFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit = {},
    onAudioLongClick: (AudioFile) -> Unit = {},
) {
    val context = LocalContext.current
    val isTv = context.isTelevision
    val layoutDirection = LocalLayoutDirection.current
    val folderMinWidth = if (isTv) 160.dp else 90.dp
    val audioMinWidth = if (isTv) 240.dp else 130.dp
    val showHeaders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE
    val songsLabel = stringResource(id = R.string.songs)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = 8.dp
        val itemSpacing = 2.dp
        val maxWidth = this.maxWidth - (horizontalPadding * 2) - itemSpacing
        val maxFolders = (maxWidth / folderMinWidth).toInt().coerceAtLeast(minimumValue = 1)
        val maxAudios = (maxWidth / audioMinWidth).toInt().coerceAtLeast(minimumValue = 1)
        val spans = when (preferences.mediaLayoutMode) {
            MediaLayoutMode.LIST -> 1
            MediaLayoutMode.GRID -> lcm(a = maxFolders, b = maxAudios)
        }
        val singleFolderSpan = when (preferences.mediaLayoutMode) {
            MediaLayoutMode.LIST -> 1
            MediaLayoutMode.GRID -> spans / maxFolders
        }
        val singleAudioSpan = when (preferences.mediaLayoutMode) {
            MediaLayoutMode.LIST -> 1
            MediaLayoutMode.GRID -> spans / maxAudios
        }

        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = lazyGridState,
            columns = GridCells.Fixed(count = spans),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + horizontalPadding,
                end = contentPadding.calculateEndPadding(layoutDirection) + horizontalPadding,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(space = itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(space = itemSpacing),
        ) {
            if (showHeaders && folders.isNotEmpty()) {
                item(span = { GridItemSpan(currentLineSpan = maxLineSpan) }) {
                    ListSectionTitle(
                        text = stringResource(id = R.string.folders) + " (${folders.size})",
                    )
                }
            }
            itemsIndexed(
                items = folders,
                key = { _, folder -> folder.path },
                span = { _, _ -> GridItemSpan(currentLineSpan = singleFolderSpan) },
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
                    mediaCountLabel = songsLabel,
                )
            }

            if (showHeaders && folders.isNotEmpty() && audios.isNotEmpty()) {
                item(span = { GridItemSpan(currentLineSpan = maxLineSpan) }) {
                    Spacer(modifier = Modifier.size(size = 8.dp))
                }
            }

            if (showHeaders && audios.isNotEmpty()) {
                item(span = { GridItemSpan(currentLineSpan = maxLineSpan) }) {
                    ListSectionTitle(
                        text = stringResource(id = R.string.songs) + " (${audios.size})",
                    )
                }
            }

            itemsIndexed(
                items = audios,
                key = { _, audio -> audio.uriString },
                span = { _, _ -> GridItemSpan(currentLineSpan = singleAudioSpan) },
            ) { index, audio ->
                MusicItem(
                    audio = audio,
                    preferences = preferences,
                    isFirstItem = index == 0,
                    isLastItem = index == audios.lastIndex,
                    selected = audio.uriString in selectedIds,
                    onClick = { onAudioClick(audio) },
                    onLongClick = { onAudioLongClick(audio) },
                )
            }
        }
    }
}

@Composable
fun NoMusicFound(contentPadding: PaddingValues = PaddingValues()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues = contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = NextIcons.Audio,
            contentDescription = null,
            modifier = Modifier.size(size = 64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.no_music_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
