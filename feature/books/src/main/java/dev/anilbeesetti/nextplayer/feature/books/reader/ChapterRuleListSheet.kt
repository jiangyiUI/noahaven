package dev.anilbeesetti.nextplayer.feature.books.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.model.ChapterRule
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/** 胶囊形状：所有操作按钮统一 50% 圆角。 */
private val PillShape = RoundedCornerShape(50)

/**
 * 「重新分章」规则列表：
 * - 打开即对当前书用全部规则试分章，每条显示分出的章数；
 * - 顶部「自动检测」= 章数最多的规则，点击直接用它重分；
 * - 单击某条规则 → 用它单独重新分章；
 * - 长按 → 进入多选，选中 2 条以上可「合并重分」（结果按行去重）；
 * - 自建规则可编辑/删除；任意规则可设为默认（新书默认用它分章）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChapterRuleListSheet(
    theme: ReaderTheme,
    rules: List<ChapterRule>,
    counts: Map<String, Int>,
    defaultRuleId: String,
    onDismiss: () -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (ChapterRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onRechapter: (List<String>, (Boolean) -> Unit) -> Unit,
) {
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var working by remember { mutableStateOf(false) }

    val countsReady = counts.isNotEmpty()
    val bestRule = if (countsReady) rules.maxByOrNull { counts[it.id] ?: 0 } else null
    val bestCount = bestRule?.let { counts[it.id] ?: 0 } ?: 0
    val showAuto = !selectMode && bestRule != null && bestCount > 0

    fun runRechapter(ids: List<String>) {
        if (ids.isEmpty() || working) return
        working = true
        onRechapter(ids) { working = false }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectMode) {
                    Text(
                        text = "已选 ${selectedIds.size} 项",
                        style = TextStyle(
                            color = theme.panelText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        selectMode = false
                        selectedIds = emptySet()
                    }) {
                        Text(
                            text = "取消",
                            style = TextStyle(color = theme.panelSub, fontSize = 14.sp),
                        )
                    }
                } else {
                    Text(
                        text = "选择章节规则",
                        style = TextStyle(
                            color = theme.panelText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    RuleActionButton(
                        icon = NextIcons.Add,
                        label = "新建",
                        tint = theme.accent,
                        enabled = true,
                        onClick = onNewRule,
                    )
                }
            }

            val subtitle = when {
                !countsReady -> "正在用全部规则预览当前书的章数…"
                selectMode -> "选中两条及以上可合并分章（相同行只保留靠前规则的标题）。"
                else -> "选择规则立即预览章节列表；单击重分，长按多选合并。"
            }
            Text(
                text = subtitle,
                style = TextStyle(color = theme.panelSub, fontSize = 12.sp),
                modifier = Modifier.padding(bottom = 10.dp),
            )

            if (showAuto && bestRule != null) {
                AutoDetectCard(
                    theme = theme,
                    rule = bestRule,
                    count = bestCount,
                    onClick = { runRechapter(listOf(bestRule.id)) },
                )
                Spacer(modifier = Modifier.heightIn(min = 6.dp))
            }

            if (selectMode) {
                Button(
                    onClick = { runRechapter(selectedIds.toList()) },
                    enabled = selectedIds.size >= 2 && !working,
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.accent,
                        contentColor = theme.onAccent,
                        disabledContainerColor = theme.surfaceVariant,
                        disabledContentColor = theme.panelSub,
                    ),
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = theme.onAccent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("合并选中并重新分章")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(
                        theme = theme,
                        rule = rule,
                        count = counts[rule.id],
                        isDefault = rule.id == defaultRuleId,
                        selected = rule.id in selectedIds,
                        selectMode = selectMode,
                        working = working,
                        onClick = {
                            if (selectMode) {
                                selectedIds = selectedIds.toggle(rule.id)
                            } else {
                                runRechapter(listOf(rule.id))
                            }
                        },
                        onLongClick = {
                            selectMode = true
                            selectedIds = setOf(rule.id)
                        },
                        onEdit = { onEditRule(rule) },
                        onDelete = { onDeleteRule(rule.id) },
                        onSetDefault = { onSetDefault(rule.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuleRow(
    theme: ReaderTheme,
    rule: ChapterRule,
    count: Int?,
    isDefault: Boolean,
    selected: Boolean,
    selectMode: Boolean,
    working: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val description = rule.example?.takeIf { it.isNotBlank() }?.replace("\n", "\\n")
        ?: buildString {
            append(if (rule.isRegexMode) "正则" else "简单 · ${rule.parsedNumberType.displayName}")
            if (rule.useVolume) append(" · 分卷")
            if (rule.isBuiltin) append(" · 内置")
        }
    // 与「自动检测」卡片统一：圆角 12dp、选中态 accent 10% 背景、内边距 14/12。
    val rowBg = if (selectMode && selected) theme.accent.copy(alpha = 0.10f) else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .combinedClickable(
                enabled = !working,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // 第一行：选择点 + 标题/描述 + 章数徽章（徽章靠最右）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                SelectionDot(selected = selected, theme = theme)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name.ifBlank { "(未命名规则)" },
                        style = TextStyle(
                            color = theme.panelText,
                            fontSize = 15.sp,
                            fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isDefault) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.accent.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "默认",
                                style = TextStyle(color = theme.accent, fontSize = 10.sp),
                            )
                        }
                    }
                }
                // 标题与描述之间拉开间距。
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = description,
                    style = TextStyle(color = theme.panelSub, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            CountBadge(count = count, theme = theme)
        }
        // 第二行：操作按钮另起一行，每个图标配中文，靠左对齐，间距加大。
        if (!selectMode) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
            ) {
                RuleActionButton(
                    icon = if (isDefault) NextIcons.Star else NextIcons.StarBorder,
                    label = if (isDefault) "已默认" else "设默认",
                    tint = if (isDefault) theme.panelSub else theme.accent,
                    enabled = !isDefault && !working,
                    onClick = onSetDefault,
                )
                if (!rule.isBuiltin) {
                    RuleActionButton(
                        icon = NextIcons.Edit,
                        label = "编辑",
                        tint = theme.accent,
                        enabled = !working,
                        onClick = onEdit,
                    )
                    RuleActionButton(
                        icon = NextIcons.Delete,
                        label = "删除",
                        tint = theme.accent,
                        enabled = !working,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 可点且有效果：背景 16% + 实色文字；不可点或无效果：背景 5% + 文字淡化到 45%，整体呈浅色。
    val bg = if (enabled) tint.copy(alpha = 0.16f) else tint.copy(alpha = 0.05f)
    val contentTint = if (enabled) tint else tint.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentTint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = TextStyle(color = contentTint, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun CountBadge(count: Int?, theme: ReaderTheme) {
    val loading = count == null
    val tint = if (loading || count == 0) theme.panelSub else theme.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(theme.surfaceVariant)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                color = theme.panelSub,
                strokeWidth = 2.dp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$count",
                    style = TextStyle(color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "章",
                    style = TextStyle(color = theme.panelSub, fontSize = 10.sp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AutoDetectCard(
    theme: ReaderTheme,
    rule: ChapterRule,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.accent.copy(alpha = 0.10f))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "自动检测",
                style = TextStyle(
                    color = theme.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = "自动选择：${rule.name}",
                style = TextStyle(color = theme.panelSub, fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        CountBadge(count = count, theme = theme)
    }
}

@Composable
private fun SelectionDot(selected: Boolean, theme: ReaderTheme) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) theme.accent else theme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = NextIcons.Check,
                contentDescription = null,
                tint = theme.onAccent,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private fun <T> Set<T>.toggle(element: T): Set<T> =
    if (element in this) this - element else this + element
