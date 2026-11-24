package dev.anilbeesetti.nextplayer.feature.books.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.model.ChapterNumberType
import dev.anilbeesetti.nextplayer.core.model.ChapterRule
import dev.anilbeesetti.nextplayer.feature.books.data.ChapterRuleDefaults
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/** 胶囊按钮 50% 圆角。 */
private val PillShape = RoundedCornerShape(50)

/**
 * 分章规则的新建 / 编辑表单。
 *
 * - 简单模式：前缀（可输入+下拉）/ 序号（下拉）/ 后缀（可输入+下拉），可启用分卷；
 * - 正则模式：章节正则（必填）+ 分卷正则（可选）；
 * - 两种模式都可当场用「示例章节名」测试命中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterRuleEditSheet(
    theme: ReaderTheme,
    rule: ChapterRule,
    defaultRuleId: String,
    onDismiss: () -> Unit,
    onSave: (ChapterRule, Boolean) -> Unit,
) {
    val isEditing = rule.id.isNotBlank()

    var name by remember { mutableStateOf(rule.name) }
    var isRegex by remember { mutableStateOf(rule.isRegexMode) }
    var prefix by remember { mutableStateOf(rule.prefix) }
    var numberType by remember { mutableStateOf(rule.parsedNumberType) }
    var suffix by remember { mutableStateOf(rule.suffix) }
    var useVolume by remember { mutableStateOf(rule.useVolume) }
    var volumePrefix by remember { mutableStateOf(rule.volumePrefix) }
    var volumeNumberType by remember { mutableStateOf(rule.parsedVolumeNumberType) }
    var volumeSuffix by remember { mutableStateOf(rule.volumeSuffix) }
    var chapterRegex by remember { mutableStateOf(rule.chapterRegex ?: "") }
    var volumeRegex by remember { mutableStateOf(rule.volumeRegex ?: "") }
    var setAsDefault by remember { mutableStateOf(rule.id == defaultRuleId) }

    var example by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testHit by remember { mutableStateOf(false) }

    val nameError = name.isBlank()
    val regexError = isRegex && chapterRegex.isBlank()
    val canSave = !nameError && !regexError

    fun previewRule(): ChapterRule = if (isRegex) {
        rule.copy(chapterRegex = chapterRegex, useVolume = false)
    } else {
        rule.copy(
            prefix = prefix,
            numberType = numberType.storage,
            suffix = suffix,
            chapterRegex = null,
            useVolume = false,
        )
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isEditing) "编辑规则" else "新增规则",
                    style = TextStyle(
                        color = theme.panelText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val saved = if (isRegex) {
                        rule.copy(
                            name = name.trim(),
                            isBuiltin = false,
                            chapterRegex = chapterRegex.trim(),
                            volumeRegex = volumeRegex.trim().ifEmpty { null },
                            useVolume = volumeRegex.isNotBlank(),
                        )
                    } else {
                        rule.copy(
                            name = name.trim(),
                            isBuiltin = false,
                            prefix = prefix.trim(),
                            numberType = numberType.storage,
                            suffix = suffix.trim(),
                            useVolume = useVolume,
                            volumePrefix = if (useVolume) volumePrefix.trim() else "",
                            volumeNumberType =
                                if (useVolume) volumeNumberType.storage else rule.volumeNumberType,
                            volumeSuffix = if (useVolume) volumeSuffix.trim() else "",
                            chapterRegex = null,
                            volumeRegex = null,
                        )
                    }
                    onSave(saved, setAsDefault)
                }, enabled = canSave) {
                    Text(
                        text = "保存",
                        style = TextStyle(
                            color = if (canSave) theme.accent else theme.panelSub,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }

            // 规则名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("规则名称") },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text("请输入规则名称", style = TextStyle(color = errorColor(theme), fontSize = 11.sp)) }
                } else {
                    null
                },
                colors = textFieldColors(theme),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 匹配模式切换（label 左，切换 右）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "匹配模式",
                    style = TextStyle(color = theme.panelText, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Spacer(modifier = Modifier.weight(1f))
                ModeToggle(theme = theme, isRegex = isRegex, onSelect = { isRegex = it })
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (isRegex) {
                OutlinedTextField(
                    value = chapterRegex,
                    onValueChange = { chapterRegex = it },
                    label = { Text("章节正则") },
                    isError = regexError,
                    supportingText = if (regexError) {
                        { Text("此字段不能为空", style = TextStyle(color = errorColor(theme), fontSize = 11.sp)) }
                    } else {
                        null
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = theme.panelText),
                    colors = textFieldColors(theme),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = volumeRegex,
                    onValueChange = { volumeRegex = it },
                    label = { Text("分卷正则（可选）") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = theme.panelText),
                    colors = textFieldColors(theme),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                DefaultSwitch(theme = theme, checked = setAsDefault, label = "设为默认规则", onCheckedChange = { setAsDefault = it })
            } else {
                // 前缀 + 序号 同一行（各占一半，避免文字竖排）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EditableDropdown(
                        value = prefix,
                        onValueChange = { prefix = it },
                        label = "前缀",
                        options = ChapterRuleDefaults.PREFIX_PRESETS,
                        theme = theme,
                        modifier = Modifier.weight(1f),
                    )
                    SelectDropdown(
                        value = numberType.displayName,
                        label = "序号",
                        options = ChapterRuleDefaults.NUMBER_TYPES.map { it.displayName },
                        onSelect = { i -> numberType = ChapterRuleDefaults.NUMBER_TYPES[i] },
                        theme = theme,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 后缀单独占满一行
                EditableDropdown(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = "后缀",
                    options = ChapterRuleDefaults.SUFFIX_PRESETS,
                    theme = theme,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                DefaultSwitch(theme = theme, checked = setAsDefault, label = "设为默认规则", onCheckedChange = { setAsDefault = it })
                DefaultSwitch(theme = theme, checked = useVolume, label = "启用分卷规则（可选）", onCheckedChange = { useVolume = it })

                if (useVolume) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = volumePrefix,
                            onValueChange = { volumePrefix = it },
                            label = { Text("分卷前缀") },
                            placeholder = { Text("如：第", style = TextStyle(color = theme.panelSub)) },
                            singleLine = true,
                            colors = textFieldColors(theme),
                            modifier = Modifier.weight(1f),
                        )
                        SelectDropdown(
                            value = volumeNumberType.displayName,
                            label = "分卷序号",
                            options = ChapterRuleDefaults.NUMBER_TYPES.map { it.displayName },
                            onSelect = { i -> volumeNumberType = ChapterRuleDefaults.NUMBER_TYPES[i] },
                            theme = theme,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = volumeSuffix,
                        onValueChange = { volumeSuffix = it },
                        label = { Text("分卷后缀") },
                        placeholder = { Text("如：卷、部", style = TextStyle(color = theme.panelSub)) },
                        singleLine = true,
                        colors = textFieldColors(theme),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 测试区：label + 测试按钮同一行，输入框单独一行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "示例章节名",
                    style = TextStyle(color = theme.panelText, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(modifier = Modifier.weight(1f))
                PillButton(
                    text = "测试",
                    onClick = {
                        val hit = previewRule().matchesLine(example)
                        testHit = hit
                        testResult = if (hit) "命中" else "未命中"
                    },
                    containerColor = theme.surfaceVariant,
                    contentColor = theme.accent,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = example,
                onValueChange = { example = it },
                placeholder = { Text("输入示例章节名测试是否命中", style = TextStyle(color = theme.panelSub)) },
                singleLine = true,
                colors = textFieldColors(theme),
                modifier = Modifier.fillMaxWidth(),
            )
            testResult?.let { result ->
                Text(
                    text = result,
                    style = TextStyle(
                        color = if (testHit) theme.accent else errorColor(theme),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeToggle(
    theme: ReaderTheme,
    isRegex: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(theme.surfaceVariant)
            .padding(4.dp),
    ) {
        ModeTab(text = "简单", selected = !isRegex, theme = theme, onClick = { onSelect(false) })
        ModeTab(text = "正则", selected = isRegex, theme = theme, onClick = { onSelect(true) })
    }
}

@Composable
private fun RowScope.ModeTab(
    text: String,
    selected: Boolean,
    theme: ReaderTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(PillShape)
            .background(if (selected) theme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) theme.panelText else theme.panelSub,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun DefaultSwitch(
    theme: ReaderTheme,
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(color = theme.panelText, fontSize = 14.sp))
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = theme.accent,
                checkedThumbColor = theme.onAccent,
            ),
        )
    }
}

/** 轻量胶囊按钮：文字 + 可选图标，圆角 50%，指定背景/文字色。 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    val bg = if (enabled) containerColor else containerColor.copy(alpha = 0.45f)
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = TextStyle(color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            colors = textFieldColors(theme),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        imageVector = NextIcons.ArrowDropDown,
                        contentDescription = "选择预设",
                        tint = theme.panelSub,
                    )
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = theme.surface,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = TextStyle(color = theme.panelText)) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectDropdown(
    value: String,
    label: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            colors = textFieldColors(theme),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        imageVector = NextIcons.ArrowDropDown,
                        contentDescription = "选择",
                        tint = theme.panelSub,
                    )
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = theme.surface,
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option, style = TextStyle(color = theme.panelText)) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** app 标准描边输入框配色：未聚焦 outline 边框、聚焦 accent 边框，无填充背景。 */
@Composable
private fun textFieldColors(theme: ReaderTheme) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = theme.panelText,
    unfocusedTextColor = theme.panelText,
    disabledTextColor = theme.panelSub,
    cursorColor = theme.accent,
    focusedBorderColor = theme.accent,
    unfocusedBorderColor = theme.outline,
    disabledBorderColor = theme.outline,
    focusedLabelColor = theme.accent,
    unfocusedLabelColor = theme.panelSub,
    focusedTrailingIconColor = theme.accent,
    unfocusedTrailingIconColor = theme.panelSub,
)

/** 错误提示红；夜间阅读主题没有 error 色，用偏红的强调色替代。 */
private fun errorColor(theme: ReaderTheme): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(0xFFE57373)
