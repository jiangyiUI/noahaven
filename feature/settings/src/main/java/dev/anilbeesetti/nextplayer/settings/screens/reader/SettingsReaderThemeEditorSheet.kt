package dev.anilbeesetti.nextplayer.settings.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderTheme
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@Composable
private fun settingsEditorTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    disabledBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun settingsEditorSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

private enum class ColorField(val labelRes: Int) {
    Background(R.string.reader_theme_color_background),
    Text(R.string.reader_theme_color_text),
    SecondaryText(R.string.reader_theme_color_secondary_text),
    Accent(R.string.reader_theme_color_accent),
    Surface(R.string.reader_theme_color_surface),
}

private fun Color.toHex(): String =
    "#" + toArgb().toUInt().toString(16).padStart(8, '0').uppercase()

private fun String.parseColor(): Color? {
    val clean = removePrefix("#").trim()
    return when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
        8 -> clean.toLongOrNull(16)?.let { Color(it.toInt()) }
        else -> null
    }
}

/**
 * 自定义主题的新建 / 编辑表单（设置页版本）。
 *
 * 面板配色走 MaterialTheme，与设置页 UI 规范一致；功能与阅读器版保持一致。
 *
 * @param isDark 归属：浅色组传 false，深色组传 true（编辑时取被编辑主题的 isDark）。
 * @param initial 编辑时传入原主题，新建传 null。
 * @param pendingImagePath 调用方选图并导入完成后传回的新路径，编辑器据此更新背景图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsReaderThemeEditorSheet(
    isDark: Boolean,
    initial: ReaderTheme.Custom?,
    pendingImagePath: String?,
    onPickImage: () -> Unit,
    onImageConsumed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (ReaderTheme.Custom) -> Unit,
    onDelete: (String) -> Unit,
) {
    val isEditing = initial != null
    val baseStorageKey = initial?.storageKey ?: "custom_" + UUID.randomUUID().toString().take(8)
    val seed = initial ?: run {
        val b = if (isDark) ReaderTheme.INK_GREEN else ReaderTheme.PAPER
        ReaderTheme.Custom(
            storageKey = baseStorageKey,
            name = "",
            isDark = isDark,
            background = b.background,
            text = b.text,
            secondaryText = b.secondaryText,
            accent = b.accent,
            surface = b.surface,
        )
    }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var background by remember { mutableStateOf(seed.background) }
    var text by remember { mutableStateOf(seed.text) }
    var secondaryText by remember { mutableStateOf(seed.secondaryText) }
    var accent by remember { mutableStateOf(seed.accent) }
    var surface by remember { mutableStateOf(seed.surface) }
    var imagePath by remember { mutableStateOf(initial?.backgroundImagePath) }
    var maskAlpha by remember { mutableStateOf(initial?.maskAlpha ?: 0.3f) }
    var pickingField by remember { mutableStateOf<ColorField?>(null) }

    LaunchedEffect(pendingImagePath) {
        if (pendingImagePath != null) {
            imagePath = pendingImagePath
            onImageConsumed()
        }
    }

    val previewTheme = remember(
        background, text, secondaryText, accent, surface, imagePath, maskAlpha, isDark,
    ) {
        ReaderTheme.Custom(
            storageKey = baseStorageKey,
            name = name.ifBlank { " " },
            isDark = isDark,
            background = background,
            text = text,
            secondaryText = secondaryText,
            accent = accent,
            surface = surface,
            backgroundImagePath = imagePath,
            maskAlpha = maskAlpha,
        )
    }

    fun buildTheme() = ReaderTheme.Custom(
        storageKey = baseStorageKey,
        name = name.trim(),
        isDark = isDark,
        background = background,
        text = text,
        secondaryText = secondaryText,
        accent = accent,
        surface = surface,
        backgroundImagePath = imagePath,
        maskAlpha = maskAlpha,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(
                    if (isEditing) R.string.reader_theme_edit else R.string.reader_theme_add,
                ),
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 实时预览
            ThemePreview(
                theme = previewTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.reader_theme_name)) },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                colors = settingsEditorTextFieldColors(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 5 个核心色
            ColorField.entries.forEach { field ->
                val color = when (field) {
                    ColorField.Background -> background
                    ColorField.Text -> text
                    ColorField.SecondaryText -> secondaryText
                    ColorField.Accent -> accent
                    ColorField.Surface -> surface
                }
                ColorRow(
                    label = stringResource(field.labelRes),
                    color = color,
                    onPick = { pickingField = field },
                    onColorChange = { c ->
                        when (field) {
                            ColorField.Background -> background = c
                            ColorField.Text -> text = c
                            ColorField.SecondaryText -> secondaryText = c
                            ColorField.Accent -> accent = c
                            ColorField.Surface -> surface = c
                        }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 背景图
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_theme_background_image),
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                )
                Spacer(modifier = Modifier.weight(1f))
                val path = imagePath
                if (path != null && File(path).exists()) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = {
                        // 清除背景图，同时删除文件
                        runCatching { File(path).delete() }
                        imagePath = null
                        maskAlpha = 0.3f
                    }) {
                        Text(
                            text = stringResource(R.string.reader_theme_clear_image),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    TextButton(onClick = onPickImage) {
                        Text(
                            text = stringResource(R.string.reader_theme_pick_image),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 遮罩透明度（仅选了背景图时显示）
            if (imagePath != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.reader_theme_mask_alpha),
                        style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${(maskAlpha * 100).roundToInt()}%",
                        style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
                    )
                }
                Slider(
                    value = maskAlpha,
                    onValueChange = { maskAlpha = it },
                    valueRange = 0f..1f,
                    colors = settingsEditorSliderColors(),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isEditing) {
                    TextButton(onClick = {
                        onDelete(baseStorageKey)
                        onDismiss()
                    }) {
                        Text(
                            text = stringResource(R.string.reader_theme_delete),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(
                    onClick = {
                        onSave(buildTheme())
                        onDismiss()
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text(
                        text = stringResource(R.string.reader_theme_save),
                        color = if (name.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }

    pickingField?.let { field ->
        SettingsColorPickerSheet(
            initialColor = when (field) {
                ColorField.Background -> background
                ColorField.Text -> text
                ColorField.SecondaryText -> secondaryText
                ColorField.Accent -> accent
                ColorField.Surface -> surface
            },
            title = stringResource(field.labelRes),
            onDismiss = { pickingField = null },
            onConfirm = { c ->
                when (field) {
                    ColorField.Background -> background = c
                    ColorField.Text -> text = c
                    ColorField.SecondaryText -> secondaryText = c
                    ColorField.Accent -> accent = c
                    ColorField.Surface -> surface = c
                }
                pickingField = null
            },
        )
    }
}

@Composable
private fun ColorRow(
    label: String,
    color: Color,
    onPick: () -> Unit,
    onColorChange: (Color) -> Unit,
) {
    var hexText by remember(color) { mutableStateOf(color.toHex()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            modifier = Modifier.width(72.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable(onClick = onPick),
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedTextField(
            value = hexText,
            onValueChange = {
                hexText = it
                it.parseColor()?.let(onColorChange)
            },
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
            colors = settingsEditorTextFieldColors(),
            modifier = Modifier.width(112.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

/** 阅读区实时预览：背景（图 + 遮罩）+ 正文 + 次文 + 强调色条。 */
@Composable
private fun ThemePreview(theme: ReaderTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(theme.background),
    ) {
        theme.backgroundImagePath?.takeIf { File(it).exists() }?.let { path ->
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(theme.background.copy(alpha = theme.maskAlpha)),
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "示例正文文字内容，预览阅读效果",
                color = theme.text,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "次要文字 / 页码 12 / 120",
                color = theme.secondaryText,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(theme.accent),
            )
        }
    }
}
