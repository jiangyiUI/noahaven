package dev.anilbeesetti.nextplayer.feature.books.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.model.Bookmark
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.books.model.BookContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    theme: ReaderTheme,
    content: BookContent,
    currentChapter: Int,
    bookmarks: List<Bookmark>,
    onSelectChapter: (Int) -> Unit,
    onOpenBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onRechapter: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        contentColor = theme.panelText,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = theme.panelSub) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderSheetTab(
                        label = stringResource(R.string.reader_toc),
                        selected = tab == 0,
                        theme = theme,
                        onClick = { tab = 0 },
                    )
                    ReaderSheetTab(
                        label = stringResource(R.string.reader_bookmarks),
                        selected = tab == 1,
                        theme = theme,
                        onClick = { tab = 1 },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(theme.accent.copy(alpha = 0.12f))
                        .clickable(onClick = onRechapter)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = NextIcons.Update,
                        contentDescription = null,
                        tint = theme.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "重新分章",
                        style = TextStyle(
                            color = theme.accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            when (tab) {
                0 -> ReaderChapterList(
                    theme = theme,
                    content = content,
                    currentChapter = currentChapter,
                    onSelect = onSelectChapter,
                )
                else -> ReaderBookmarkList(
                    theme = theme,
                    bookmarks = bookmarks,
                    onOpen = onOpenBookmark,
                    onDelete = onDeleteBookmark,
                )
            }
        }
    }
}

@Composable
private fun ReaderSheetTab(
    label: String,
    selected: Boolean,
    theme: ReaderTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 照抄书籍页顶部 BookTabRow：纯文字 tab，无胶囊背景、无点击涟漪；
    // 选中 = 粗体深色（panelText），未选中 = 中灰（panelSub）。tab 间距由父 Row 的 spacedBy 控制。
    Text(
        text = label,
        maxLines = 1,
        style = TextStyle(
            color = if (selected) theme.panelText else theme.panelSub,
            fontSize = if (selected) 18.sp else 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        ),
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun ReaderChapterList(
    theme: ReaderTheme,
    content: BookContent,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(content.chapters) { index, chapter ->
            val selected = index == currentChapter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) theme.accent.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = TextStyle(
                        color = if (selected) theme.accent else theme.panelSub,
                        fontSize = 13.sp,
                    ),
                    modifier = Modifier.width(36.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    val chapterTitle = if (chapter.title.isBlank()) {
                        stringResource(R.string.reader_untitled_chapter)
                    } else {
                        chapter.title
                    }
                    Text(
                        text = chapterTitle,
                        style = TextStyle(
                            color = if (selected) theme.accent else theme.panelText,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val percent = if (content.totalChars > 0) {
                        chapter.startChar * 100 / content.totalChars
                    } else {
                        0
                    }
                    Text(
                        text = "$percent%",
                        style = TextStyle(color = theme.panelSub, fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBookmarkList(
    theme: ReaderTheme,
    bookmarks: List<Bookmark>,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.reader_bookmarks_empty),
                style = TextStyle(color = theme.panelSub, fontSize = 14.sp),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(bookmarks) { index, bookmark ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .clickable { onOpen(bookmark) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = TextStyle(color = theme.panelSub, fontSize = 13.sp),
                    modifier = Modifier.width(36.dp),
                )
                val chapterTitle = bookmark.chapterTitle.ifBlank {
                    stringResource(R.string.reader_untitled_chapter)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookmark.previewText,
                        style = TextStyle(
                            color = theme.panelText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$chapterTitle · ${(bookmark.percent * 100).toInt()}%",
                        style = TextStyle(color = theme.panelSub, fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(bookmark.id) }) {
                    Icon(
                        imageVector = NextIcons.Delete,
                        contentDescription = stringResource(R.string.reader_bookmark_delete),
                        tint = theme.panelSub,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    theme: ReaderTheme,
    settings: ReaderSettings,
    customFonts: List<ReaderCustomFont>,
    onSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onImportFont: (Uri) -> Unit,
    onDeleteFont: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportFont(uri)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        contentColor = theme.panelText,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = theme.panelSub) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 64.dp),
        ) {
            // 字号
            SectionLabel(text = stringResource(R.string.reader_font_size), theme = theme)
            Spacer(modifier = Modifier.height(8.dp))
            var fontSize by remember(settings.fontSizeSp) {
                mutableFloatStateOf(settings.fontSizeSp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "A",
                    style = TextStyle(color = theme.panelSub, fontSize = 13.sp),
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = {
                        onSettingsChange { current -> current.copy(fontSizeSp = fontSize) }
                    },
                    valueRange = settings.fontSizeMin..settings.fontSizeMax,
                    steps = settings.fontSizeSteps,
                    colors = readerSliderColors(theme),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                Text(
                    text = "A",
                    style = TextStyle(color = theme.panelSub, fontSize = 22.sp),
                )
            }

            // 行间距
            SectionLabel(
                text = stringResource(R.string.reader_line_spacing),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var lineSpacing by remember(settings.lineSpacing) {
                mutableFloatStateOf(settings.lineSpacing)
            }
            Slider(
                value = lineSpacing,
                onValueChange = { lineSpacing = it },
                onValueChangeFinished = {
                    onSettingsChange { current -> current.copy(lineSpacing = lineSpacing) }
                },
                valueRange = settings.lineSpacingMin..settings.lineSpacingMax,
                steps = settings.lineSpacingSteps,
                colors = readerSliderColors(theme),
                modifier = Modifier.fillMaxWidth(),
            )

            // 段间距
            SectionLabel(
                text = stringResource(R.string.reader_paragraph_spacing),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var paragraphSpacing by remember(settings.paragraphSpacing) {
                mutableFloatStateOf(settings.paragraphSpacing)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = paragraphSpacing,
                    onValueChange = { paragraphSpacing = it },
                    onValueChangeFinished = {
                        onSettingsChange { current ->
                            current.copy(paragraphSpacing = paragraphSpacing)
                        }
                    },
                    valueRange = settings.paragraphSpacingMin..settings.paragraphSpacingMax,
                    steps = settings.paragraphSpacingSteps,
                    colors = readerSliderColors(theme),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (paragraphSpacing <= 0.06f) {
                        stringResource(R.string.reader_spacing_none)
                    } else {
                        "${(paragraphSpacing * 100).toInt()}%"
                    },
                    style = TextStyle(color = theme.panelSub, fontSize = 11.sp),
                    modifier = Modifier.width(44.dp),
                )
            }

            // 左右边距
            SectionLabel(
                text = stringResource(R.string.reader_horizontal_padding),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var horizontalPadding by remember(settings.horizontalPaddingDp) {
                mutableFloatStateOf(settings.horizontalPaddingDp)
            }
            Slider(
                value = horizontalPadding,
                onValueChange = { horizontalPadding = it },
                onValueChangeFinished = {
                    onSettingsChange { current ->
                        current.copy(horizontalPaddingDp = horizontalPadding)
                    }
                },
                valueRange = settings.horizontalPaddingMin..settings.horizontalPaddingMax,
                steps = settings.horizontalPaddingSteps,
                colors = readerSliderColors(theme),
                modifier = Modifier.fillMaxWidth(),
            )

            // 亮度
            SectionLabel(
                text = stringResource(R.string.reader_brightness),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var brightness by remember(settings.brightness) {
                mutableFloatStateOf(settings.brightness)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    onValueChangeFinished = {
                        onSettingsChange { current -> current.copy(brightness = brightness) }
                    },
                    valueRange = 0f..1f,
                    steps = settings.brightnessSteps,
                    colors = readerSliderColors(theme),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (brightness <= 0.02f) {
                        stringResource(R.string.reader_brightness_follow)
                    } else {
                        "${(brightness * 100).toInt()}%"
                    },
                    style = TextStyle(color = theme.panelSub, fontSize = 11.sp),
                    modifier = Modifier.width(44.dp),
                )
            }

            // 进度条作用范围
            SectionLabel(
                text = stringResource(R.string.reader_progress_scope),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReaderProgressScope.entries.forEach { item ->
                    OptionChip(
                        text = stringResource(item.labelRes),
                        selected = settings.progressScope == item,
                        theme = theme,
                        onClick = {
                            onSettingsChange { current -> current.copy(progressScope = item) }
                        },
                    )
                }
            }

            // 浅色主题
            SectionLabel(
                text = stringResource(R.string.reader_theme_light),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReaderTheme.entries.filterNot { it.isDark }.forEach { item ->
                    ThemeSwatch(
                        theme = item,
                        selected = item == settings.lightTheme,
                        onSelect = {
                            onSettingsChange { current -> current.copy(lightTheme = item) }
                        },
                    )
                }
            }

            // 深色主题
            SectionLabel(
                text = stringResource(R.string.reader_theme_dark),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReaderTheme.entries.filter { it.isDark }.forEach { item ->
                    ThemeSwatch(
                        theme = item,
                        selected = item == settings.darkTheme,
                        onSelect = {
                            onSettingsChange { current -> current.copy(darkTheme = item) }
                        },
                    )
                }
            }

            // 翻页方式
            SectionLabel(
                text = stringResource(R.string.reader_page_turn),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ReaderPageTurn.entries.forEach { item ->
                    OptionChip(
                        text = stringResource(item.labelRes),
                        selected = settings.pageTurn == item,
                        theme = theme,
                        onClick = {
                            onSettingsChange { current -> current.copy(pageTurn = item) }
                        },
                    )
                }
            }

            // 字体：默认 + 导入的字体 + 添加按钮
            SectionLabel(
                text = stringResource(R.string.reader_font_family),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptionChip(
                    text = stringResource(R.string.reader_font_default),
                    selected = settings.customFontPath == null,
                    theme = theme,
                    onClick = {
                        onSettingsChange { current -> current.copy(customFontPath = null) }
                    },
                )
                customFonts.forEach { font ->
                    FontChip(
                        text = font.displayName,
                        selected = settings.customFontPath == font.path,
                        theme = theme,
                        onClick = {
                            onSettingsChange { current ->
                                current.copy(customFontPath = font.path)
                            }
                        },
                        onLongClick = { onDeleteFont(font.path) },
                    )
                }
                AddFontChip(
                    theme = theme,
                    onClick = { fontPicker.launch(arrayOf("*/*")) },
                )
            }

            // 字重：1–9 对应 100–900
            SectionLabel(
                text = stringResource(R.string.reader_font_weight),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var fontWeight by remember(settings.fontWeight) {
                mutableFloatStateOf(settings.fontWeight.toFloat())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "A",
                    style = TextStyle(
                        color = theme.panelSub,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Thin,
                    ),
                )
                Slider(
                    value = fontWeight,
                    onValueChange = { fontWeight = it },
                    onValueChangeFinished = {
                        onSettingsChange { current ->
                            current.copy(fontWeight = fontWeight.toInt())
                        }
                    },
                    valueRange = settings.fontWeightMin..settings.fontWeightMax,
                    steps = settings.fontWeightSteps,
                    colors = readerSliderColors(theme),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                Text(
                    text = "A",
                    style = TextStyle(
                        color = theme.panelSub,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                    ),
                )
            }

            // 开关组
            SectionLabel(
                text = stringResource(R.string.reader_appearance),
                theme = theme,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            SwitchRow(
                label = stringResource(R.string.reader_first_line_indent),
                checked = settings.firstLineIndent,
                theme = theme,
                onCheckedChange = { checked ->
                    onSettingsChange { current -> current.copy(firstLineIndent = checked) }
                },
            )
            SwitchRow(
                label = stringResource(R.string.reader_show_chapter_title),
                checked = settings.showChapterTitle,
                theme = theme,
                onCheckedChange = { checked ->
                    onSettingsChange { current -> current.copy(showChapterTitle = checked) }
                },
            )
            SwitchRow(
                label = stringResource(R.string.reader_show_time_battery),
                checked = settings.showTimeBattery,
                theme = theme,
                onCheckedChange = { checked ->
                    onSettingsChange { current -> current.copy(showTimeBattery = checked) }
                },
            )
        }
    }
}

@Composable
private fun readerSliderColors(theme: ReaderTheme) = SliderDefaults.colors(
    thumbColor = theme.accent,
    activeTrackColor = theme.accent,
    inactiveTrackColor = theme.surfaceVariant,
    activeTickColor = theme.onAccent,
    inactiveTickColor = theme.accent,
)

@Composable
private fun SectionLabel(
    text: String,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = TextStyle(
            color = theme.panelSub,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    theme: ReaderTheme,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = theme.panelText, fontSize = 16.sp),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = theme.onAccent,
                checkedTrackColor = theme.accent,
                checkedBorderColor = theme.accent,
                checkedIconColor = theme.accent,
                uncheckedThumbColor = theme.panelSub,
                uncheckedTrackColor = theme.surfaceVariant,
                uncheckedBorderColor = theme.outline,
                uncheckedIconColor = theme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun ThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onSelect)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                // 主题预览色块：故意显示阅读页背景/正文色，不走面板色。
                .background(color = theme.background),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(color = theme.text.copy(alpha = 0.85f), shape = CircleShape),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(13.dp))
                        .background(color = theme.accent.copy(alpha = 0.20f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = stringResource(theme.labelRes),
            style = TextStyle(
                color = if (selected) theme.accent else theme.panelSub,
                fontSize = 10.5.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    theme: ReaderTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) theme.accentContainer else theme.surfaceVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) theme.onAccentContainer else theme.panelSub,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

/** 已导入的字体：单击选用，长按删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontChip(
    text: String,
    selected: Boolean,
    theme: ReaderTheme,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) theme.accentContainer else theme.surfaceVariant,
                shape = CircleShape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) theme.onAccentContainer else theme.panelSub,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

/** 添加字体：圆形加号，点击打开系统文件选择器。 */
@Composable
private fun AddFontChip(
    theme: ReaderTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(color = theme.surfaceVariant, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NextIcons.Add,
            contentDescription = stringResource(R.string.reader_font_add),
            tint = theme.panelSub,
            modifier = Modifier.size(18.dp),
        )
    }
}
