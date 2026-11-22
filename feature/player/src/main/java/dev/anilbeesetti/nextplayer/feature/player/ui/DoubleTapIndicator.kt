package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.DoubleTapGesture
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.state.TapGestureState
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DoubleTapIndicator(modifier: Modifier = Modifier, tapGestureState: TapGestureState) {
    SeekSideIndicator(
        modifier = modifier,
        seekMillis = tapGestureState.seekMillis,
        fraction = when (tapGestureState.doubleTapGesture) {
            DoubleTapGesture.PLAY_PAUSE -> 0f
            DoubleTapGesture.FAST_FORWARD_AND_REWIND -> 0.5f
            DoubleTapGesture.BOTH -> 0.35f
            DoubleTapGesture.NONE -> 0f
        },
        interactionSource = tapGestureState.interactionSource,
    )
}

/**
 * 左右两侧的“快进/快退 N 秒”提示。双击和键盘方向键共用这一套视觉，
 * seekMillis > 0 时贴右边、< 0 时贴左边，累计后自动消失（由调用方清零）。
 */
@Composable
internal fun SeekSideIndicator(
    seekMillis: Long,
    modifier: Modifier = Modifier,
    fraction: Float = 0.35f,
    interactionSource: MutableInteractionSource? = null,
) {
    if (seekMillis == 0L) return
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (seekMillis > 0) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = fraction)
                .clip(
                    shape = if (seekMillis > 0) {
                        RightSideOvalShape()
                    } else {
                        LeftSideOvalShape()
                    },
                )
                .background(Color.White.copy(alpha = 0.2f))
                .then(
                    if (interactionSource != null) {
                        Modifier.indication(
                            interactionSource,
                            indication = ripple(),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DoubleTapSeekTriangles(isForward = seekMillis > 0)
                Text(
                    text = "${seekMillis.milliseconds.inWholeSeconds} seconds",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DoubleTapSeekTriangles(isForward: Boolean, modifier: Modifier = Modifier) {
    val animationDuration = 750L

    val alpha1 = remember { Animatable(initialValue = 0f) }
    val alpha2 = remember { Animatable(initialValue = 0f) }
    val alpha3 = remember { Animatable(initialValue = 0f) }

    LaunchedEffect(key1 = animationDuration) {
        while (true) {
            alpha1.animateTo(
                targetValue = 1f,
                animationSpec = tween((animationDuration / 5).toInt()),
            )
            alpha2.animateTo(
                targetValue = 1f,
                animationSpec = tween((animationDuration / 5).toInt()),
            )
            alpha3.animateTo(
                targetValue = 1f,
                animationSpec = tween((animationDuration / 5).toInt()),
            )
            alpha1.animateTo(
                targetValue = 0f,
                animationSpec = tween((animationDuration / 5).toInt()),
            )
            alpha2.animateTo(
                targetValue = 0f,
                animationSpec = tween((animationDuration / 5).toInt()),
            )
            alpha3.animateTo(
                targetValue = 0f,
                animationSpec = tween((animationDuration / 5).toInt()),
            )
        }
    }

    val rotation = if (isForward) 0f else 180f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.rotate(degrees = rotation),
    ) {
        DoubleTapArrow(alpha1.value)
        DoubleTapArrow(alpha2.value)
        DoubleTapArrow(alpha3.value)
    }
}

private class RightSideOvalShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply {
            moveTo(
                size.width,
                size.height,
            )
            lineTo(
                size.width,
                y = 0f,
            )
            lineTo(
                x = size.width * 0.1f,
                y = 0f,
            )
            cubicTo(
                x1 = size.width * 0.1f,
                y1 = 0f,
                x2 = -size.width * 0.1f,
                y2 = size.height / 2,
                x3 = size.width * 0.1f,
                size.height,
            )
            close()
        }
        return Outline.Generic(path = path)
    }
}

private class LeftSideOvalShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply {
            moveTo(
                x = 0f,
                y = 0f,
            )
            lineTo(
                x = size.width * 0.9f,
                y = 0f,
            )
            cubicTo(
                x1 = size.width * 0.9f,
                y1 = 0f,
                x2 = size.width * 1.1f,
                y2 = size.height / 2,
                x3 = size.width * 0.9f,
                size.height,
            )
            lineTo(
                x = 0f,
                size.height,
            )
            close()
        }
        return Outline.Generic(path = path)
    }
}

@Composable
private fun DoubleTapArrow(alpha: Float) {
    Icon(
        painter = painterResource(R.drawable.ic_play),
        contentDescription = null,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer { this.alpha = alpha },
        tint = Color.White,
    )
}

@Preview
@Composable
private fun OvalShapePreview() {
    NextPlayerTheme {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(weight = 1f)
                    .clip(shape = LeftSideOvalShape())
                    .background(Color.Blue),
            ) {}
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(weight = 1f)
                    .clip(shape = RightSideOvalShape())
                    .background(Color.Blue),
            ) {}
        }
    }
}
