package dev.anilbeesetti.nextplayer.feature.videopicker.screens.images

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.MediaLibraryTab
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.LibraryTabRow
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicsScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics.ComicsViewModel
import kotlinx.coroutines.launch

/** 横滑切换 tab 的最小距离，按屏幕宽度的比例计算，避免竖直滚动时误触。 */
private const val SwipeThresholdFraction = 0.15f

/** 与导航转场一致的缓动曲线，保证整个应用的滑动手感统一。 */
private val TabSwitchEasing = CubicBezierEasing(0.3f, 0.7f, 0.5f, 1f)
private const val ExitDurationMillis = 180
private const val EnterDurationMillis = 220

/**
 * 「图片」页的 tab 宿主：在图片库和漫画库之间切换，tab 顺序可在顶部长按拖动调整。
 * 左右横滑时按当前 tab 顺序切换，滑动方向与 tab 排列一致，切换带左右滑动过渡。
 */
@Composable
fun MediaLibraryScreen(
    imagesViewModel: ImagesViewModel,
    comicsViewModel: ComicsViewModel,
    tabsViewModel: LibraryTabsViewModel,
) {
    val tabOrder by tabsViewModel.tabOrder.collectAsStateWithLifecycle()
    // 存 tab 名字而不是枚举本身，rememberSaveable 才能正确保存
    var selectedTabName by rememberSaveable { mutableStateOf("") }
    val order = MediaLibraryTab.sanitize(tabOrder)
    val selectedTab = MediaLibraryTab.entries.firstOrNull { it.name == selectedTabName }
    val currentTab = selectedTab?.takeIf { it in order } ?: order.first()
    val currentIndex = order.indexOf(currentTab)

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
        LibraryTabRow(
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
            MediaLibraryTab.IMAGES -> ImagesScreen(
                viewModel = imagesViewModel,
                tabBar = tabBar,
                contentModifier = contentModifier,
            )

            MediaLibraryTab.COMICS -> ComicsScreen(
                viewModel = comicsViewModel,
                tabBar = tabBar,
                contentModifier = contentModifier,
            )
        }
    }
}
