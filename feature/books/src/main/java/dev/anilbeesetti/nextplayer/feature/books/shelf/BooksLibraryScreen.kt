package dev.anilbeesetti.nextplayer.feature.books.shelf

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.BookLibraryTab
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import kotlinx.coroutines.launch

/** 横滑切换 tab 的最小距离，按屏幕宽度的比例计算，避免竖直滚动时误触。 */
private const val SwipeThresholdFraction = 0.15f

/** 与导航转场一致的缓动曲线，保证整个应用的滑动手感统一。 */
private val TabSwitchEasing = CubicBezierEasing(0.3f, 0.7f, 0.5f, 1f)
private const val ExitDurationMillis = 180
private const val EnterDurationMillis = 220

/**
 * 书籍页的 tab 宿主：在「书架」和「浏览」之间切换，tab 顺序可在顶部长按拖动调整。
 * 交互与动画完全复用图片/漫画库的 MediaLibraryScreen。
 */
@Composable
fun BooksLibraryScreen(
    browseViewModel: BookshelfViewModel,
    shelfViewModel: ShelfViewModel,
    tabsViewModel: BookTabsViewModel,
    defaultGroupName: String,
) {
    val context = LocalContext.current
    val tabOrder by tabsViewModel.tabOrder.collectAsStateWithLifecycle()
    val shelfState by shelfViewModel.state.collectAsStateWithLifecycle()
    // 存 tab 名字而不是枚举本身，rememberSaveable 才能正确保存
    var selectedTabName by rememberSaveable { mutableStateOf("") }
    val order = BookLibraryTab.sanitize(tabOrder)
    val selectedTab = BookLibraryTab.entries.firstOrNull { it.name == selectedTabName }
    val currentTab = selectedTab?.takeIf { it in order } ?: order.first()
    val currentIndex = order.indexOf(currentTab)

    // 分组 id → 层级路径（如「分组1/子分组1」，未分组/找不到用本地化名称）。
    val groupLabelFor: (Long) -> String = remember(shelfState.groups, defaultGroupName) {
        val groupById = shelfState.groups.associateBy { it.id }
        fun labelFor(groupId: Long): String {
            val group = groupById[groupId] ?: return defaultGroupName
            if (group.isUngrouped) return defaultGroupName
            val chain = mutableListOf<String>()
            var cur: BookShelfGroup? = group
            while (cur != null) {
                chain.add(cur.name)
                cur = cur.parentId?.let { groupById[it] }
            }
            return chain.asReversed().joinToString("/")
        }
        ::labelFor
    }

    // 已在书架的书（合并展示）：非空时弹出一个对话框列出每本书所在分组。
    var alreadyInShelf by remember { mutableStateOf<Map<String, Long>?>(null) }

    // 加入书架完成后提示数量；已在书架的书合并成一个对话框展示。
    LaunchedEffect(shelfViewModel, shelfState.groups, defaultGroupName) {
        shelfViewModel.events.collect { event ->
            when (event) {
                is ShelfEvent.Added -> Toast.makeText(
                    context,
                    context.getString(R.string.book_shelf_added) + " (${event.count})",
                    Toast.LENGTH_SHORT,
                ).show()
                is ShelfEvent.AlreadyInShelf -> alreadyInShelf = event.pathToGroup
            }
        }
    }

    // 切页时整页位移：先滑出旧页，再让新页从另一侧滑入。
    // 同一时刻只组合一个页面，避免两个 Scaffold 争抢 FAB 和底栏的绑定状态。
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var containerWidth by remember { mutableIntStateOf(0) }
    var enteringDirection by remember { mutableStateOf(0) }
    var isSwitching by remember { mutableStateOf(false) }

    fun switchTo(targetIndex: Int, direction: Int) {
        val targetTab = order.getOrNull(targetIndex)
        if (isSwitching || containerWidth == 0 || targetTab == null) return
        isSwitching = true
        scope.launch {
            offsetX.animateTo(
                targetValue = -direction * containerWidth.toFloat(),
                animationSpec = tween(ExitDurationMillis, easing = TabSwitchEasing),
            )
            enteringDirection = direction
            selectedTabName = targetTab.name
        }
    }

    // 新页面就位后从另一侧滑入，方向与刚才滑出的方向一致
    LaunchedEffect(currentTab) {
        val direction = enteringDirection
        if (direction == 0) return@LaunchedEffect
        enteringDirection = 0
        if (containerWidth == 0) {
            offsetX.snapTo(0f)
        } else {
            offsetX.snapTo(direction * containerWidth.toFloat())
            offsetX.animateTo(0f, tween(EnterDurationMillis, easing = TabSwitchEasing))
        }
        isSwitching = false
    }

    val tabBar: @Composable () -> Unit = {
        BookTabRow(
            tabs = order,
            selectedTab = currentTab,
            onTabSelected = { tab ->
                val targetIndex = order.indexOf(tab)
                if (targetIndex >= 0 && targetIndex != currentIndex) {
                    switchTo(targetIndex, if (targetIndex > currentIndex) 1 else -1)
                }
            },
            onMoveTab = tabsViewModel::moveTab,
        )
    }

    // 横滑手势在内容区生效；位移动画挂在白色卡片背景之后，卡片不动、只有内容滑动
    val contentModifier = Modifier
        .graphicsLayer { translationX = offsetX.value }
        .pointerInput(order, currentIndex, isSwitching) {
            val threshold = size.width * SwipeThresholdFraction
            var draggedDistance = 0f
            detectHorizontalDragGestures(
                onDragStart = { draggedDistance = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    draggedDistance += dragAmount
                },
                onDragEnd = {
                    // 左滑看后一个 tab，右滑看前一个 tab；顺序变化时方向自动跟着变
                    val direction = when {
                        draggedDistance <= -threshold && currentIndex < order.lastIndex -> 1
                        draggedDistance >= threshold && currentIndex > 0 -> -1
                        else -> 0
                    }
                    if (direction != 0) switchTo(currentIndex + direction, direction)
                    draggedDistance = 0f
                },
                onDragCancel = { draggedDistance = 0f },
            )
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerWidth = it.width },
    ) {
        when (currentTab) {
            BookLibraryTab.SHELF -> ShelfScreen(
                viewModel = shelfViewModel,
                tabBar = tabBar,
                contentModifier = contentModifier,
            )

            BookLibraryTab.BROWSE -> BookshelfScreen(
                viewModel = browseViewModel,
                tabBar = tabBar,
                contentModifier = contentModifier,
                shelfGroups = shelfState.groups,
                defaultGroupName = defaultGroupName,
                onAddToShelf = shelfViewModel::addToShelf,
            )
        }

        alreadyInShelf?.let { mapping ->
            AlreadyInShelfDialog(
                pathToGroup = mapping,
                groupLabelFor = groupLabelFor,
                onDismiss = { alreadyInShelf = null },
            )
        }
    }
}
