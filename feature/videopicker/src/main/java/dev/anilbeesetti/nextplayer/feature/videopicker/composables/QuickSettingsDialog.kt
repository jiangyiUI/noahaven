package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.Sort
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.DoneButton
import dev.anilbeesetti.nextplayer.core.ui.components.MediaLayoutAndSortSettings
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.extensions.name

@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun QuickSettingsDialog(
    applicationPreferences: ApplicationPreferences,
    onDismiss: () -> Unit,
    updatePreferences: (ApplicationPreferences) -> Unit,
    showFieldSettings: Boolean = true,
    mediaFilesLabel: String? = null,
    imageMode: Boolean = false,
    comicMode: Boolean = false,
    /** 书架专用模式：
     *  - imageLayoutMode 可选 LIST + NINE_GRID（九宫格，不带 GRID）
     *  - 字段只显示 extension / size / thumbnail / playedProgress
     *  - 不走 comicMode 的过滤逻辑
     */
    shelfMode: Boolean = false,
    /** 书籍浏览 tab：不显示「路径」字段开关（书籍条目用不到）。 */
    bookMode: Boolean = false,
) {
    var preferences by remember { mutableStateOf(value = applicationPreferences) }

    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.quick_settings)) },
        content = {
            HorizontalDivider()
            Column(
                modifier = Modifier.verticalScroll(state = rememberScrollState()),
            ) {
                if (!shelfMode) {
                    DialogSectionTitle(text = stringResource(R.string.media_view_mode))
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MediaViewMode.entries.forEachIndexed { index, viewMode ->
                            SegmentedButton(
                                selected = preferences.mediaViewMode == viewMode,
                                onClick = { preferences = preferences.copy(mediaViewMode = viewMode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = MediaViewMode.entries.size,
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContentColor = MaterialTheme.colorScheme.primary,
                                    activeBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(
                                    text = if (viewMode == MediaViewMode.VIDEOS &&
                                        mediaFilesLabel != null
                                    ) {
                                        mediaFilesLabel
                                    } else {
                                        viewMode.name()
                                    },
                                )
                            }
                        }
                    }
                }
                if (imageMode) {
                    // shelfMode（书架 tab）没有文件夹概念，跳过文件夹布局
                    if (!shelfMode) {
                        DialogSectionTitle(text = stringResource(R.string.folder_layout))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            MediaLayoutMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = preferences.imageFolderLayoutMode == mode,
                                    onClick = {
                                        preferences =
                                            preferences.copy(imageFolderLayoutMode = mode)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = MediaLayoutMode.entries.size,
                                    ),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContentColor = MaterialTheme.colorScheme.primary,
                                        activeBorderColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(
                                            id = if (mode == MediaLayoutMode.LIST) {
                                                R.string.list
                                            } else {
                                                R.string.grid
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    DialogSectionTitle(
                        text = stringResource(
                            when {
                                shelfMode -> R.string.book_layout
                                comicMode -> R.string.comic_layout
                                else -> R.string.image_layout
                            },
                        ),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val layoutModes = when {
                            shelfMode -> listOf(ImageLayoutMode.LIST, ImageLayoutMode.NINE_GRID)
                            comicMode -> listOf(ImageLayoutMode.LIST, ImageLayoutMode.GRID)
                            else -> ImageLayoutMode.entries
                        }
                        layoutModes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = preferences.imageLayoutMode == mode,
                                onClick = {
                                    preferences = preferences.copy(imageLayoutMode = mode)
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = layoutModes.size,
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContentColor = MaterialTheme.colorScheme.primary,
                                    activeBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(
                                    text = stringResource(
                                        id = when (mode) {
                                            ImageLayoutMode.LIST -> R.string.list
                                            ImageLayoutMode.GRID -> R.string.grid
                                            ImageLayoutMode.NINE_GRID -> R.string.nine_grid
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                MediaLayoutAndSortSettings(
                    layoutMode = preferences.mediaLayoutMode,
                    sort = Sort(
                        preferences.sortBy,
                        preferences.sortOrder,
                    ),
                    onLayoutModeChange = { preferences = preferences.copy(mediaLayoutMode = it) },
                    showLayout = !imageMode,
                    dateLabel = if (shelfMode) R.string.date_modified else R.string.date,
                    onSortChange = {
                        preferences = preferences.copy(
                            sortBy = it.by,
                            sortOrder = it.order,
                        )
                    },
                    sortOptions = when {
                        shelfMode -> listOf(
                            Sort.By.TITLE,
                            Sort.By.SHELF_ADDED,
                            Sort.By.DATE,
                            Sort.By.LAST_VIEWED,
                        )
                        imageMode -> listOf(Sort.By.TITLE, Sort.By.DATE, Sort.By.SIZE)
                        else -> listOf(
                            Sort.By.TITLE,
                            Sort.By.LENGTH,
                            Sort.By.DATE,
                            Sort.By.SIZE,
                            Sort.By.PATH,
                        )
                    },
                )
                if (showFieldSettings) {
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                    DialogSectionTitle(text = stringResource(R.string.fields))
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.Top),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // shelfMode 时只保留 extension/size/thumbnail/playedProgress
                        val showDurationFields = !imageMode && !shelfMode
                        if (showDurationFields) {
                            FieldChip(
                                label = stringResource(id = R.string.duration),
                                selected = preferences.showDurationField,
                                onClick = {
                                    preferences = preferences.copy(
                                        showDurationField = !preferences.showDurationField,
                                    )
                                },
                            )
                            FieldChip(
                                label = stringResource(id = R.string.folder_duration),
                                selected = preferences.showFolderDurationField,
                                onClick = {
                                    val showFolderDurationField =
                                        preferences.showFolderDurationField
                                    preferences = preferences.copy(
                                        showFolderDurationField = !showFolderDurationField,
                                    )
                                },
                            )
                        }
                        FieldChip(
                            label = stringResource(id = R.string.extension),
                            selected = preferences.showExtensionField,
                            onClick = {
                                preferences = preferences.copy(
                                    showExtensionField = !preferences.showExtensionField,
                                )
                            },
                        )
                        val showPathField = !comicMode && !shelfMode && !bookMode
                        if (showPathField) {
                            FieldChip(
                                label = stringResource(id = R.string.path),
                                selected = preferences.showPathField,
                                onClick = {
                                    preferences =
                                        preferences.copy(showPathField = !preferences.showPathField)
                                },
                            )
                        }
                        val showPlayedProgress = !imageMode || shelfMode
                        if (showPlayedProgress) {
                            FieldChip(
                                label = stringResource(
                                    id = if (shelfMode) R.string.reading_progress else R.string.played_progress,
                                ),
                                selected = preferences.showPlayedProgress,
                                onClick = {
                                    preferences = preferences.copy(
                                        showPlayedProgress = !preferences.showPlayedProgress,
                                    )
                                },
                            )
                        }
                        // 图片库显示分辨率；漫画/书架/书籍浏览 tab 不显示
                        val showResolutionField = !comicMode && !shelfMode && !bookMode
                        if (showResolutionField) {
                            FieldChip(
                                label = stringResource(id = R.string.resolution),
                                selected = preferences.showResolutionField,
                                onClick = {
                                    preferences = preferences.copy(
                                        showResolutionField = !preferences.showResolutionField,
                                    )
                                },
                            )
                        }
                        FieldChip(
                            label = stringResource(id = R.string.size),
                            selected = preferences.showSizeField,
                            onClick = {
                                preferences =
                                    preferences.copy(showSizeField = !preferences.showSizeField)
                            },
                        )
                        FieldChip(
                            label = stringResource(id = R.string.thumbnail),
                            selected = preferences.showThumbnailField,
                            onClick = {
                                preferences = preferences.copy(
                                    showThumbnailField = !preferences.showThumbnailField,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            DoneButton(
                onClick = {
                    updatePreferences(preferences)
                    onDismiss()
                },
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}

@Composable
fun FieldChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedIcon: ImageVector = NextIcons.CheckBox,
    unselectedIcon: ImageVector = NextIcons.CheckBoxOutline,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = "",
                modifier = Modifier.size(FilterChipDefaults.IconSize),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            selectedBorderWidth = 1.dp,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier,
    )
}

@Composable
private fun DialogSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            top = 16.dp,
            bottom = 8.dp,
        ),
    )
}

@Preview
@Composable
fun QuickSettingsPreview() {
    Surface {
        QuickSettingsDialog(
            applicationPreferences = ApplicationPreferences(),
            onDismiss = {},
            updatePreferences = {},
        )
    }
}
