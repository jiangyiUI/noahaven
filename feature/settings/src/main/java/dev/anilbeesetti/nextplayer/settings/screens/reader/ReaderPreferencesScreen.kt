package dev.anilbeesetti.nextplayer.settings.screens.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSlider
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSwitch
import dev.anilbeesetti.nextplayer.core.ui.components.RadioTextButton
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderPageTurn
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderProgressScope
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderTheme
import dev.anilbeesetti.nextplayer.settings.composables.OptionsDialog

@Composable
fun ReaderPreferencesScreen(viewModel: ReaderPreferencesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onAction(ReaderPreferencesUiEvent.ImportFont(uri))
        }
    }

    ReaderPreferencesScreenContent(
        state = state,
        onAction = viewModel::onAction,
        onPickFont = { fontPicker.launch(arrayOf("*/*")) },
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun ReaderPreferencesScreenContent(
    state: ReaderPreferencesUiState,
    onAction: (ReaderPreferencesUiEvent) -> Unit,
    onPickFont: () -> Unit,
) {
    val settings = state.settings

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.reader_settings),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { onAction(ReaderPreferencesUiEvent.NavigateUp) },
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(paddingValues = innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            // 排版：滑块组
            var fontSize by remember(settings.fontSizeSp) {
                mutableFloatStateOf(settings.fontSizeSp)
            }
            PreferenceSlider(
                title = stringResource(R.string.reader_font_size),
                description = fontSize.toInt().toString(),
                icon = NextIcons.FontSize,
                value = fontSize,
                valueRange = settings.fontSizeMin..settings.fontSizeMax,
                steps = settings.fontSizeSteps,
                onValueChange = { fontSize = it },
                onValueChangeFinished = {
                    onAction(ReaderPreferencesUiEvent.UpdateFontSize(fontSize))
                },
                isFirstItem = true,
            )

            var lineSpacing by remember(settings.lineSpacing) {
                mutableFloatStateOf(settings.lineSpacing)
            }
            PreferenceSlider(
                title = stringResource(R.string.reader_line_spacing),
                description = "%.1f".format(lineSpacing),
                icon = NextIcons.LineSpacing,
                value = lineSpacing,
                valueRange = settings.lineSpacingMin..settings.lineSpacingMax,
                steps = settings.lineSpacingSteps,
                onValueChange = { lineSpacing = it },
                onValueChangeFinished = {
                    onAction(ReaderPreferencesUiEvent.UpdateLineSpacing(lineSpacing))
                },
            )

            var paragraphSpacing by remember(settings.paragraphSpacing) {
                mutableFloatStateOf(settings.paragraphSpacing)
            }
            PreferenceSlider(
                title = stringResource(R.string.reader_paragraph_spacing),
                description = if (paragraphSpacing <= 0.06f) {
                    stringResource(R.string.reader_spacing_none)
                } else {
                    "${(paragraphSpacing * 100).toInt()}%"
                },
                icon = NextIcons.ParagraphSpacing,
                value = paragraphSpacing,
                valueRange = settings.paragraphSpacingMin..settings.paragraphSpacingMax,
                steps = settings.paragraphSpacingSteps,
                onValueChange = { paragraphSpacing = it },
                onValueChangeFinished = {
                    onAction(
                        ReaderPreferencesUiEvent.UpdateParagraphSpacing(paragraphSpacing),
                    )
                },
            )

            var horizontalPadding by remember(settings.horizontalPaddingDp) {
                mutableFloatStateOf(settings.horizontalPaddingDp)
            }
            PreferenceSlider(
                title = stringResource(R.string.reader_horizontal_padding),
                description = horizontalPadding.toInt().toString(),
                icon = NextIcons.Padding,
                value = horizontalPadding,
                valueRange = settings.horizontalPaddingMin..settings.horizontalPaddingMax,
                steps = settings.horizontalPaddingSteps,
                onValueChange = { horizontalPadding = it },
                onValueChangeFinished = {
                    onAction(
                        ReaderPreferencesUiEvent.UpdateHorizontalPadding(horizontalPadding),
                    )
                },
            )

            var brightness by remember(settings.brightness) {
                mutableFloatStateOf(settings.brightness)
            }
            PreferenceSlider(
                title = stringResource(R.string.reader_brightness),
                description = if (brightness <= 0.02f) {
                    stringResource(R.string.reader_brightness_follow)
                } else {
                    "${(brightness * 100).toInt()}%"
                },
                icon = NextIcons.Brightness,
                value = brightness,
                valueRange = 0f..1f,
                steps = settings.brightnessSteps,
                onValueChange = { brightness = it },
                onValueChangeFinished = {
                    onAction(ReaderPreferencesUiEvent.UpdateBrightness(brightness))
                },
            )

            var fontWeight by remember(settings.fontWeight) {
                mutableFloatStateOf(settings.fontWeight.toFloat())
            }
            PreferenceSlider(
                title = stringResource(R.string.reader_font_weight),
                description = fontWeight.toInt().toString(),
                icon = NextIcons.Bold,
                value = fontWeight,
                valueRange = settings.fontWeightMin..settings.fontWeightMax,
                steps = settings.fontWeightSteps,
                onValueChange = { fontWeight = it },
                onValueChangeFinished = {
                    onAction(
                        ReaderPreferencesUiEvent.UpdateFontWeight(fontWeight.toInt()),
                    )
                },
                isLastItem = true,
            )

            Spacer(modifier = Modifier.padding(top = 8.dp))

            // 选项：进度范围 / 主题 / 翻页 / 字体
            ClickablePreferenceItem(
                title = stringResource(R.string.reader_progress_scope),
                description = stringResource(settings.progressScope.labelRes),
                icon = NextIcons.Progress,
                onClick = {
                    onAction(
                        ReaderPreferencesUiEvent.ShowDialog(
                            ReaderPreferenceDialog.ProgressScope,
                        ),
                    )
                },
                isFirstItem = true,
            )
            ClickablePreferenceItem(
                title = stringResource(R.string.reader_theme_light),
                description = stringResource(settings.lightTheme.labelRes),
                icon = NextIcons.Appearance,
                onClick = {
                    onAction(
                        ReaderPreferencesUiEvent.ShowDialog(ReaderPreferenceDialog.LightTheme),
                    )
                },
            )
            ClickablePreferenceItem(
                title = stringResource(R.string.reader_theme_dark),
                description = stringResource(settings.darkTheme.labelRes),
                icon = NextIcons.Contrast,
                onClick = {
                    onAction(
                        ReaderPreferencesUiEvent.ShowDialog(ReaderPreferenceDialog.DarkTheme),
                    )
                },
            )
            ClickablePreferenceItem(
                title = stringResource(R.string.reader_page_turn),
                description = stringResource(settings.pageTurn.labelRes),
                icon = NextIcons.SwipeHorizontal,
                onClick = {
                    onAction(
                        ReaderPreferencesUiEvent.ShowDialog(ReaderPreferenceDialog.PageTurn),
                    )
                },
            )
            val currentFontName = settings.customFontPath?.let { path ->
                state.customFonts.firstOrNull { it.path == path }?.displayName
            } ?: stringResource(R.string.reader_font_default)
            ClickablePreferenceItem(
                title = stringResource(R.string.reader_font_family),
                description = currentFontName,
                icon = NextIcons.Font,
                onClick = {
                    onAction(
                        ReaderPreferencesUiEvent.ShowDialog(
                            ReaderPreferenceDialog.FontFamily,
                        ),
                    )
                },
                isLastItem = true,
            )

            Spacer(modifier = Modifier.padding(top = 8.dp))

            // 开关组
            PreferenceSwitch(
                title = stringResource(R.string.reader_first_line_indent),
                isChecked = settings.firstLineIndent,
                onClick = { onAction(ReaderPreferencesUiEvent.ToggleFirstLineIndent) },
                isFirstItem = true,
            )
            PreferenceSwitch(
                title = stringResource(R.string.reader_show_chapter_title),
                isChecked = settings.showChapterTitle,
                onClick = { onAction(ReaderPreferencesUiEvent.ToggleShowChapterTitle) },
            )
            PreferenceSwitch(
                title = stringResource(R.string.reader_show_time_battery),
                isChecked = settings.showTimeBattery,
                onClick = { onAction(ReaderPreferencesUiEvent.ToggleShowTimeBattery) },
                isLastItem = true,
            )
        }

        state.showDialog?.let { dialog ->
            when (dialog) {
                ReaderPreferenceDialog.ProgressScope -> {
                    OptionsDialog(
                        text = stringResource(R.string.reader_progress_scope),
                        onDismissClick = {
                            onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                        },
                    ) {
                        items(ReaderProgressScope.entries) { item ->
                            RadioTextButton(
                                text = stringResource(item.labelRes),
                                selected = item == settings.progressScope,
                                onClick = {
                                    onAction(
                                        ReaderPreferencesUiEvent.SetProgressScope(item),
                                    )
                                    onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                ReaderPreferenceDialog.LightTheme -> {
                    OptionsDialog(
                        text = stringResource(R.string.reader_theme_light),
                        onDismissClick = {
                            onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                        },
                    ) {
                        items(ReaderTheme.entries.filterNot { it.isDark }) { theme ->
                            RadioTextButton(
                                text = stringResource(theme.labelRes),
                                selected = theme == settings.lightTheme,
                                onClick = {
                                    onAction(
                                        ReaderPreferencesUiEvent.SetLightTheme(theme),
                                    )
                                    onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                ReaderPreferenceDialog.DarkTheme -> {
                    OptionsDialog(
                        text = stringResource(R.string.reader_theme_dark),
                        onDismissClick = {
                            onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                        },
                    ) {
                        items(ReaderTheme.entries.filter { it.isDark }) { theme ->
                            RadioTextButton(
                                text = stringResource(theme.labelRes),
                                selected = theme == settings.darkTheme,
                                onClick = {
                                    onAction(
                                        ReaderPreferencesUiEvent.SetDarkTheme(theme),
                                    )
                                    onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                ReaderPreferenceDialog.PageTurn -> {
                    OptionsDialog(
                        text = stringResource(R.string.reader_page_turn),
                        onDismissClick = {
                            onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                        },
                    ) {
                        items(ReaderPageTurn.entries) { item ->
                            RadioTextButton(
                                text = stringResource(item.labelRes),
                                selected = item == settings.pageTurn,
                                onClick = {
                                    onAction(
                                        ReaderPreferencesUiEvent.SetPageTurn(item),
                                    )
                                    onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                ReaderPreferenceDialog.FontFamily -> {
                    OptionsDialog(
                        text = stringResource(R.string.reader_font_family),
                        onDismissClick = {
                            onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                        },
                    ) {
                        item {
                            RadioTextButton(
                                text = stringResource(R.string.reader_font_default),
                                selected = settings.customFontPath == null,
                                onClick = {
                                    onAction(
                                        ReaderPreferencesUiEvent.SetFont(null),
                                    )
                                    onAction(ReaderPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                        items(state.customFonts) { font ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioTextButton(
                                    text = font.displayName,
                                    selected = settings.customFontPath == font.path,
                                    onClick = {
                                        onAction(
                                            ReaderPreferencesUiEvent.SetFont(font.path),
                                        )
                                        onAction(
                                            ReaderPreferencesUiEvent.ShowDialog(null),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        onAction(
                                            ReaderPreferencesUiEvent.DeleteFont(font.path),
                                        )
                                    },
                                ) {
                                    Icon(
                                        imageVector = NextIcons.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                    )
                                }
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAction(
                                            ReaderPreferencesUiEvent.ShowDialog(null),
                                        )
                                        onPickFont()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = NextIcons.Add,
                                    contentDescription = null,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = stringResource(R.string.reader_font_add))
                            }
                        }
                    }
                }
            }
        }
    }
}
