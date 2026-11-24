package dev.anilbeesetti.nextplayer.feature.books.shelf

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.anilbeesetti.nextplayer.core.model.BookLibraryTab
import dev.anilbeesetti.nextplayer.core.ui.R
import kotlin.math.abs

/** tab 之间的间距，与媒体库 tab 保持一致。 */
private val TabSpacing = 16.dp

/**
 * 书籍页顶栏上的 tab：书架 / 浏览。
 * 视觉与媒体库 LibraryTabRow 一致，长按 tab 后左右拖动可调整顺序。
 */
@Composable
fun BookTabRow(
    tabs: List<BookLibraryTab>,
    selectedTab: BookLibraryTab,
    onTabSelected: (BookLibraryTab) -> Unit,
    onMoveTab: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 记录每个 tab 的宽度，用于拖动时判断何时与相邻 tab 交换位置
    val tabWidths = remember { mutableMapOf<BookLibraryTab, Float>() }
    var draggingTab by remember { mutableStateOf<BookLibraryTab?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 拖动过程中顺序会变化，事件回调里要读到最新的列表
    val currentTabs by rememberUpdatedState(tabs)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TabSpacing),
    ) {
        tabs.forEach { tab ->
            // 用 key 保证拖动交换顺序时每个 tab 的 Composition 身份不变
            key(tab) {
                val selected = tab == selectedTab
                val dragging = tab == draggingTab
                Text(
                    text = stringResource(tab.labelRes()),
                    maxLines = 1,
                    style = if (selected) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (dragging) dragOffset else 0f
                            alpha = if (dragging) 0.8f else 1f
                        }
                        .onGloballyPositioned { coordinates ->
                            tabWidths[tab] = coordinates.size.width.toFloat()
                        }
                        .pointerInput(tab) {
                            val spacingPx = TabSpacing.toPx()
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingTab = tab
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.x
                                    val tabs = currentTabs
                                    val currentIndex = tabs.indexOf(tab)
                                    if (currentIndex == -1) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    val neighborIndex = when {
                                        dragOffset > 0f && currentIndex < tabs.lastIndex ->
                                            currentIndex + 1
                                        dragOffset < 0f && currentIndex > 0 -> currentIndex - 1
                                        else -> -1
                                    }
                                    if (neighborIndex == -1) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    val step = (tabWidths[tabs[neighborIndex]] ?: 0f) + spacingPx
                                    if (abs(dragOffset) >= step / 2f) {
                                        onMoveTab(currentIndex, neighborIndex)
                                        dragOffset -= if (dragOffset > 0f) step else -step
                                    }
                                },
                                onDragEnd = {
                                    draggingTab = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingTab = null
                                    dragOffset = 0f
                                },
                            )
                        }
                        .pointerInput(tab) {
                            detectTapGestures(
                                onTap = { onTabSelected(tab) },
                            )
                        },
                )
            }
        }
    }
}

private fun BookLibraryTab.labelRes(): Int = when (this) {
    BookLibraryTab.SHELF -> R.string.book_tab_shelf
    BookLibraryTab.BROWSE -> R.string.book_tab_browse
}
