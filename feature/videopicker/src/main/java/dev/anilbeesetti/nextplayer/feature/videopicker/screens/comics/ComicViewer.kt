package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.media.index.ComicArchiveReader
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 漫画图片条目列表。图片顺序永远和 zip 内文件名排序一致，
 * 阅读方向只影响手势（往哪边滑是下一张），不影响图片顺序。
 */
@Serializable
data class ComicImageList(
    val zipPath: String,
    val entries: List<String>,
    val readingDirection: ComicReadingDirection,
) {
    val displayName: String get() = zipPath.substringAfterLast('/').substringBeforeLast('.')
}

@Composable
fun ComicViewer(
    images: ComicImageList,
    initialIndex: Int = 0,
    readingDirection: ComicReadingDirection,
    onProgressChanged: (pageIndex: Int, direction: ComicReadingDirection) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onDirectionChange: (ComicReadingDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, maxOf(images.entries.lastIndex, 0)),
        pageCount = { images.entries.size },
    )
    val scope = rememberCoroutineScope()
    // 逻辑上的当前页：翻页基准只认它。
    // 拖动时 Pager 可能被带着走过半页、currentPage / settledPage 自己会变，不能用它们算目标页。
    var currentIndex by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, maxOf(images.entries.lastIndex, 0)))
    }
    // 最近一次翻页是不是「往回翻」：往回翻到达的宽图要先显示另一半
    var arrivedBackward by remember { mutableStateOf(false) }
    // 当前翻页动画：新手势开始前先取消，否则动画会跟手指抢
    var pagerJob by remember { mutableStateOf<Job?>(null) }

    fun animateToPage(page: Int) {
        pagerJob?.cancel()
        pagerJob = scope.launch {
            pagerState.animateScrollToPage(page)
            // 兜底落位：dispatchRawDelta 拖过页面后滚动位置可能带残余偏移，
            // 光靠 animateScrollToPage 有时不会回到精确的整页（会残留下一张的一部分）
            pagerState.scrollToPage(page)
        }
    }

    LaunchedEffect(currentIndex, readingDirection) {
        onProgressChanged(currentIndex, readingDirection)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 横滑翻页由页面内部手势处理（需要和缩放共存），这里关掉 Pager 自带滑动。
            userScrollEnabled = false,
            // 布局方向跟随阅读方向：右→左模式下下一页在左侧，切页动画方向与手指滑动一致。
            reverseLayout = readingDirection == ComicReadingDirection.RIGHT_TO_LEFT,
            beyondViewportPageCount = 1,
            // 用条目名做 key：进度条跨页跳转时，旧页的 composition（含已解码的位图）
            // 不会因为复用 slot 被错当成新页显示，否则会出现上一张盖住当前页的残影。
            key = { page -> images.entries[page] },
        ) { page ->
            ComicPage(
                zipPath = images.zipPath,
                entryName = images.entries[page],
                readingDirection = readingDirection,
                isCurrentPage = page == currentIndex,
                arrivedBackward = arrivedBackward,
                onSwipe = { next ->
                    val target = (if (next) currentIndex + 1 else currentIndex - 1)
                        .coerceIn(0, images.entries.lastIndex)
                    if (target != currentIndex) {
                        currentIndex = target
                        arrivedBackward = !next
                        animateToPage(target)
                    }
                },
                // 内容已经到边界：多余的位移直接带着 Pager 走，页面跟着手指动
                onEdgeDrag = { overflow ->
                    // 翻页动画还没停就接着拖：先把逻辑页号对齐到 Pager 当前最近的页，
                    // 否则会以动画目标页为基准继续 ±1，导致多跳一张
                    if (pagerJob?.isActive == true) {
                        pagerJob?.cancel()
                        currentIndex = pagerState.currentPage
                    }
                    val delta = if (readingDirection == ComicReadingDirection.RIGHT_TO_LEFT) {
                        overflow
                    } else {
                        -overflow
                    }
                    pagerState.dispatchRawDelta(delta)
                },
                // 没到翻页阈值：弹回原页
                onEdgeDragSettle = {
                    animateToPage(currentIndex)
                },
            )
        }

        // 顶部栏：与图片查看器一致的贴顶 + 半透明背景
        // 左 0dp + IconButton 内部 12dp 让关闭图标视觉上落在 16dp，和底部页码左边缘对齐；
        // 右 16dp 让标题省略号不会越过底部「右」字。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = NextIcons.Close,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = images.displayName,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 底部栏：上方进度条，下方左页码、中间翻页方向开关、右侧回到开头
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            // 进度条方向跟随翻页方向开关
            ComicProgressBar(
                pageCount = images.entries.size,
                currentPage = currentIndex,
                readingDirection = readingDirection,
                onScrub = { index ->
                    val target = index.coerceIn(0, images.entries.lastIndex)
                    if (target != currentIndex) {
                        arrivedBackward = target < currentIndex
                        currentIndex = target
                        pagerJob?.cancel()
                        pagerJob = scope.launch { pagerState.scrollToPage(target) }
                    }
                },
                // 松手后再精确落位一次：拖动过程中可能连续跳页，最后补一次整页对齐，
                // 避免 Pager 停在两页之间导致上一页残留。
                onScrubFinished = {
                    pagerJob?.cancel()
                    pagerJob = scope.launch { pagerState.scrollToPage(currentIndex) }
                },
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${currentIndex + 1} / ${images.entries.size}",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                // 左 / 右 翻页方向开关：关=向左滑是下一张，开=向右滑是下一张
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "左",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = readingDirection == ComicReadingDirection.LEFT_TO_RIGHT,
                        onCheckedChange = { checked ->
                            val target = if (checked) {
                                ComicReadingDirection.LEFT_TO_RIGHT
                            } else {
                                ComicReadingDirection.RIGHT_TO_LEFT
                            }
                            if (target != readingDirection) onDirectionChange(target)
                        },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "右",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    text = "回到开头",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable {
                            currentIndex = 0
                            arrivedBackward = false
                            animateToPage(0)
                        },
                )
            }
        }
    }
}

/**
 * 漫画翻页进度条：样式取自播放器的 SimpleSlider（白色 50% 轨道 + 主题色已读部分 + 白色圆形滑块）。
 * 每页一个刻度，拖动时会在每张的节点吸附并给一次触感反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicProgressBar(
    pageCount: Int,
    currentPage: Int,
    readingDirection: ComicReadingDirection,
    onScrub: (pageIndex: Int) -> Unit,
    onScrubFinished: () -> Unit,
) {
    if (pageCount <= 1) return
    val hapticFeedback = LocalHapticFeedback.current
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val lastIndex = (pageCount - 1).toFloat()
    val fraction = (currentPage / lastIndex).coerceIn(0f, 1f)
    CompositionLocalProvider(
        // 右→左阅读时进度条整体镜像，从右往左推进
        LocalLayoutDirection provides if (readingDirection == ComicReadingDirection.RIGHT_TO_LEFT) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        },
    ) {
        Slider(
            value = currentPage.toFloat(),
            valueRange = 0f..lastIndex,
            // 每页一个刻度：拖动时逐页吸附，不会一路滑过去
            steps = pageCount - 2,
            onValueChange = { value ->
                val index = value.roundToInt()
                if (index != currentPage) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    currentOnScrub(index)
                }
            },
            onValueChangeFinished = { currentOnScrubFinished() },
            modifier = Modifier
                .fillMaxWidth()
                // 加大触摸区域：轨道还是 4dp，但整块可拖动的范围是 48dp 高
                .height(48.dp),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(size = 16.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape,
                        )
                        .background(MaterialTheme.colorScheme.onBackground),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = fraction)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            },
        )
    }
}

@Composable
private fun ComicPage(
    zipPath: String,
    entryName: String,
    readingDirection: ComicReadingDirection,
    isCurrentPage: Boolean,
    // 是否是从「下一张」往回翻到达本页：往回翻时先显示默认半边的另一边
    arrivedBackward: Boolean,
    onSwipe: (next: Boolean) -> Unit,
    // 内容已到边界后多出来的横向位移（像素），交给 Pager 让页面跟着手指动
    onEdgeDrag: (overflow: Float) -> Unit,
    // 松手时没达到翻页阈值：弹回原页
    onEdgeDragSettle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // 松手惯性滑动
    var inertiaJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(zipPath, entryName) {
        loading = true
        // 先清掉上一张的位图：进度条跨页跳转时 composition 可能被复用，
        // 不清空就会有一帧（或更久）继续画着旧页面。
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            ComicArchiveReader.decodeEntryBitmap(zipPath, entryName)
        }
        loading = false
    }

    if (loading) {
        Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    val bmp = bitmap
    if (bmp == null) {
        Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "无法读取图片",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }

    // 画面基准：宽度 = 屏幕宽度（FIT_WIDTH），所以 scale=1 就是「宽度适应屏幕」。
    // 只有横向跨页才算宽图：按宽度铺满后，图片高度还不到屏幕高度的一半，
    // 说明它是一张很扁的跨页图，这时默认放大到图片宽度的一半等于屏幕宽度（2 倍）。
    // 普通竖版页面按宽度铺满后能一屏看全，保持 1 倍。
    val defaultScale = remember(bmp, viewport) {
        if (viewport.width <= 0) {
            1f
        } else {
            val fittedHeight = bmp.height.toFloat() * viewport.width / bmp.width
            if (fittedHeight < viewport.height * WIDE_IMAGE_HEIGHT_FRACTION) 2f else 1f
        }
    }
    // 宽图默认只显示一半：判断先看左半还是右半。
    // 左→右阅读先看左半；右→左阅读先看右半。
    val defaultOffsetX = remember(defaultScale, viewport, readingDirection) {
        if (defaultScale > 1f) {
            when (readingDirection) {
                ComicReadingDirection.LEFT_TO_RIGHT -> viewport.width / 2f
                ComicReadingDirection.RIGHT_TO_LEFT -> viewport.width / -2f
            }
        } else {
            0f
        }
    }

    // 到达本页时要先显示哪一半：正着翻到本页用默认半边；
    // 往回翻到本页时，从哪边来就看哪边，也就是先显示默认半边的另一边。
    val arrivalOffsetX = when {
        defaultScale <= 1f -> 0f
        arrivedBackward -> -defaultOffsetX
        else -> defaultOffsetX
    }
    val latestArrivalOffsetX by rememberUpdatedState(arrivalOffsetX)

    // 翻页 / 切方向后回到默认大小与默认位置（上一张无论放大缩小过多少都重置）
    LaunchedEffect(isCurrentPage, defaultScale, viewport) {
        if (isCurrentPage) {
            scale = defaultScale
            offsetX = latestArrivalOffsetX
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 放大/平移后的图片严格限制在本页范围内，绝不画到相邻页面上
            .clipToBounds()
            .onSizeChanged { viewport = it }
            // 双击缩放：默认大小 -> 放大；已放大 -> 回到默认大小
            .pointerInput(entryName, defaultScale, arrivalOffsetX) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > defaultScale + 0.01f) {
                            scale = defaultScale
                            offsetX = arrivalOffsetX
                        } else {
                            scale = (defaultScale * DOUBLE_TAP_SCALE).coerceAtMost(MAX_SCALE)
                            offsetX = 0f
                        }
                    },
                )
            }
            .pointerInput(entryName, readingDirection, defaultScale, arrivalOffsetX) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 手指重新按下：停掉上一次的惯性滑动
                    inertiaJob?.cancel()
                    var multiTouch = false
                    // 超出边界的拖动量，用于松手判断翻页
                    var edgeDragX = 0f
                    val velocityTracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) multiTouch = true
                        event.changes.firstOrNull()?.let { change ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                        }

                        if (multiTouch) {
                            // 双指：只缩放；放大后可单指左右平移，但高度始终居中
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(defaultScale, MAX_SCALE)
                            if (newScale <= defaultScale + 0.01f) {
                                scale = defaultScale
                                offsetX = arrivalOffsetX
                            } else {
                                scale = newScale
                                val maxX = viewport.width * (newScale - 1f) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            }
                        } else {
                            // 单指：只能左右平移，上下不做任何位移；乘增益让画面比手指走得更快
                            val dx = event.calculatePan().x * PAN_GAIN
                            val maxX = viewport.width * (scale - 1f) / 2f
                            val targetX = offsetX + dx
                            val clampedX = targetX.coerceIn(-maxX, maxX)
                            offsetX = clampedX
                            // 越界部分交给 Pager：页面能立刻跟手地跟着拖，而不是松手才翻
                            val overflow = targetX - clampedX
                            if (overflow != 0f) {
                                edgeDragX += overflow
                                onEdgeDrag(overflow)
                            }
                        }

                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                        if (event.changes.none { it.pressed }) break
                    }

                    if (!multiTouch) {
                        // 只有真的拖到边界并继续拖了才算翻页手势；只是平移没越界不翻页。
                        // 越界后不管这次手势前面有没有平移过，够阈值就直接翻，避免松手回弹。
                        if (edgeDragX != 0f) {
                            val threshold = viewport.width * 0.08f
                            val flingThreshold = 800f
                            val velocityX = velocityTracker.calculateVelocity().x
                            // 速度和位移同向才算甩动，避免速度方向把页翻反
                            val goNext = when (readingDirection) {
                                ComicReadingDirection.LEFT_TO_RIGHT ->
                                    edgeDragX <= -threshold ||
                                        (edgeDragX < 0f && velocityX <= -flingThreshold)

                                ComicReadingDirection.RIGHT_TO_LEFT ->
                                    edgeDragX >= threshold ||
                                        (edgeDragX > 0f && velocityX >= flingThreshold)
                            }
                            val goPrev = when (readingDirection) {
                                ComicReadingDirection.LEFT_TO_RIGHT ->
                                    edgeDragX >= threshold ||
                                        (edgeDragX > 0f && velocityX >= flingThreshold)

                                ComicReadingDirection.RIGHT_TO_LEFT ->
                                    edgeDragX <= -threshold ||
                                        (edgeDragX < 0f && velocityX <= -flingThreshold)
                            }
                            when {
                                goNext -> onSwipe(true)
                                goPrev -> onSwipe(false)
                                // 被 Pager 带着走了一段但没到翻页条件：弹回原页
                                else -> onEdgeDragSettle()
                            }
                        } else {
                            // 没碰到边界：松手后按甩动速度滑一段惯性，滑到边界就停（不翻页）
                            val velocityX = velocityTracker.calculateVelocity().x * PAN_GAIN
                            val maxX = viewport.width * (scale - 1f) / 2f
                            if (maxX > 0f && abs(velocityX) > INERTIA_MIN_VELOCITY) {
                                inertiaJob?.cancel()
                                inertiaJob = scope.launch {
                                    var velocity = velocityX
                                    var lastFrameNanos = 0L
                                    var hitEdge = false
                                    while (!hitEdge && abs(velocity) > INERTIA_STOP_VELOCITY) {
                                        withFrameNanos { now ->
                                            if (lastFrameNanos != 0L) {
                                                val dt = ((now - lastFrameNanos) / 1e9f)
                                                    .coerceIn(0f, 0.05f)
                                                val targetX = offsetX + velocity * dt
                                                val clampedX = targetX.coerceIn(-maxX, maxX)
                                                if (clampedX != targetX) hitEdge = true
                                                offsetX = clampedX
                                                // 指数衰减：甩得越快滑得越远
                                                velocity *= exp(-INERTIA_DECAY * dt)
                                            }
                                            lastFrameNanos = now
                                        }
                                    }
                                }
                            }
                        }
                    } else if (edgeDragX != 0f) {
                        // 多指拖动中 Pager 也被带偏了：松手弹回
                        onEdgeDragSettle()
                    }
                }
            },
    ) {
        Image(
            // 宽度撑满屏幕、高度居中裁切：高图 = 宽度适应，宽图默认再放大 2 倍看一半
            bitmap = remember(bmp) { bmp.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = 0f
                },
        )
    }
}

/** 缩放上限 */
private const val MAX_SCALE = 5f

/**
 * 宽图判定阈值：按屏幕宽度铺满后，图片高度小于屏幕高度的这个比例才算「宽图」。
 * 取 0.5 的意义：放大 2 倍后高度最多正好铺满屏幕，纵向绝不会被裁掉。
 * 普通竖版页面铺满后约占屏幕高度的 60%~100%，横向跨页只有 25%~45%。
 */
private const val WIDE_IMAGE_HEIGHT_FRACTION = 0.5f

/**
 * 单指平移增益。1f = 画面位移严格等于手指位移（跟手）；
 * 越大画面越「跑」，但会脱离手指，反而显得不跟手。
 */
private const val PAN_GAIN = 1f

/** 松手惯性：起滑的最小速度（px/s） */
private const val INERTIA_MIN_VELOCITY = 200f

/** 松手惯性：速度低于此值就停（px/s） */
private const val INERTIA_STOP_VELOCITY = 40f

/** 松手惯性的指数衰减系数，越大停得越快 */
private const val INERTIA_DECAY = 4f

/** 双击放大后的倍数，与图片查看器保持一致 */
private const val DOUBLE_TAP_SCALE = 2.5f
