package dev.anilbeesetti.nextplayer.feature.videopicker.screens.images

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import kotlinx.coroutines.launch

@Composable
fun ImageViewer(
    images: List<ImageFile>,
    initialImagePath: String,
    onDismiss: () -> Unit,
    onPageChanged: (String) -> Unit = {},
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val initialPage = images.indexOfFirst { it.path == initialImagePath }
        .coerceAtLeast(0)
        .coerceAtMost(images.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialPage) { images.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
        images.getOrNull(pagerState.currentPage)?.let { image -> onPageChanged(image.path) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 翻页完全交给页面内的手势处理，避免缩放和 Pager 拖动抢事件。
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
            ) { page ->
                val image = images[page]
                val isCurrentPage = page == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(image.path) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        }
                        .pointerInput(image.path) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val velocityTracker = VelocityTracker()
                                velocityTracker.addPosition(
                                    firstDown.uptimeMillis,
                                    firstDown.position,
                                )
                                var multiTouch = false
                                var dragDistanceX = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedPointers = event.changes.count { it.pressed }
                                    event.changes.firstOrNull { it.pressed }?.let { change ->
                                        velocityTracker.addPosition(
                                            change.uptimeMillis,
                                            change.position,
                                        )
                                    }
                                    if (pressedPointers >= 2) multiTouch = true
                                    if (pressedPointers >= 2 || scale > 1f) {
                                        val newScale = (scale * event.calculateZoom())
                                            .coerceIn(
                                                1f,
                                                5f,
                                            )
                                        val newOffset = if (newScale == 1f) {
                                            Offset.Zero
                                        } else {
                                            val maxX = size.width * (newScale - 1f) / 2f
                                            val maxY = size.height * (newScale - 1f) / 2f
                                            val pan = event.calculatePan()
                                            Offset(
                                                x = (offset.x + pan.x).coerceIn(
                                                    -maxX,
                                                    maxX,
                                                ),
                                                y = (offset.y + pan.y).coerceIn(
                                                    -maxY,
                                                    maxY,
                                                ),
                                            )
                                        }
                                        scale = newScale
                                        offset = newOffset
                                    } else {
                                        dragDistanceX += event.calculatePan().x
                                    }
                                    // 始终消费移动，避免 Pager 或外层控件介入。
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!multiTouch && scale == 1f) {
                                    // 距离或速度任一达标就翻页，快速轻扫也能生效
                                    val velocityX = velocityTracker.calculateVelocity().x
                                    val distanceThreshold = size.width * 0.12f
                                    val velocityThreshold = size.width * 0.8f
                                    val targetPage = when {
                                        dragDistanceX <= -distanceThreshold ||
                                            velocityX <= -velocityThreshold ->
                                            (pagerState.currentPage + 1).coerceAtMost(
                                                images.lastIndex,
                                            )

                                        dragDistanceX >= distanceThreshold ||
                                            velocityX >= velocityThreshold ->
                                            (pagerState.currentPage - 1).coerceAtLeast(0)

                                        else -> null
                                    }
                                    if (targetPage != null &&
                                        targetPage != pagerState.currentPage
                                    ) {
                                        scope.launch {
                                            pagerState.animateScrollToPage(page = targetPage)
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(image.uriString)
                            .crossfade(true)
                            .build(),
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 只缩放当前页，否则预加载的相邻页也会被放大并溢入视野
                                scaleX = if (isCurrentPage) scale else 1f
                                scaleY = if (isCurrentPage) scale else 1f
                                translationX = if (isCurrentPage) offset.x else 0f
                                translationY = if (isCurrentPage) offset.y else 0f
                            },
                    )
                }
            }

            if (controlsVisible) {
                val currentImage = images[pagerState.currentPage.coerceAtMost(images.lastIndex)]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .statusBarsPadding()
                        // 左 0dp + IconButton 内部 12dp 让关闭图标落在约 16dp，和底部页码对齐；
                        // 右 16dp 让标题省略号不越过底部内容的右边缘。
                        .padding(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = NextIcons.Close,
                                contentDescription = stringResource(R.string.navigate_up),
                                tint = Color.White,
                            )
                        }
                        Text(
                            text = currentImage.nameWithExtension,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            }
        }
    }
}
