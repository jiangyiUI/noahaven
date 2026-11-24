package dev.anilbeesetti.nextplayer.settings.screens.reader

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.ui.R

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
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

/**
 * 监听指针位置，输出归一化坐标 (x,y ∈ [0,1])。down 与 move 都响应，
 * 点击和拖动都能选色。
 */
private fun Modifier.selectPosition(
    onPosition: (Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                if (change.pressed) {
                    val s = size
                    val x = (change.position.x / s.width).coerceIn(0f, 1f)
                    val y = (change.position.y / s.height).coerceIn(0f, 1f)
                    onPosition(Offset(x, y))
                    change.consume()
                }
            }
        }
    }
}

/**
 * HSV 颜色选择器：hex 输入 + SV 色板 + 色相滑块 + 透明度滑块。
 * 设置页版本：面板配色走 MaterialTheme（与设置页 UI 规范一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsColorPickerSheet(
    initialColor: Color,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val hsv = remember { FloatArray(3) }
    val initialArgb = initialColor.toArgb()
    AndroidColor.colorToHSV(initialArgb, hsv)
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf((initialArgb ushr 24) / 255f) }
    var hexText by remember { mutableStateOf("") }

    val currentColor = remember(hue, saturation, value, alpha) {
        Color(
            AndroidColor.HSVToColor(
                (alpha * 255).toInt().coerceIn(0, 255),
                floatArrayOf(hue, saturation, value),
            ),
        )
    }

    LaunchedEffect(currentColor) {
        hexText = "#" + currentColor.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()
    }

    fun applyHex(text: String) {
        val clean = text.removePrefix("#").trim()
        val parsed = when (clean.length) {
            6 -> clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> clean.toLongOrNull(16)?.toInt()
            else -> null
        } ?: return
        AndroidColor.colorToHSV(parsed, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = (parsed ushr 24) / 255f
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onConfirm(currentColor)
                    onDismiss()
                }) {
                    Text(
                        text = stringResource(R.string.reader_theme_save),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = hexText,
                onValueChange = {
                    hexText = it
                    applyHex(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                colors = settingsTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // SV 色板：横向 saturation 0→1，纵向 value 1→0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .selectPosition { pos ->
                        saturation = pos.x
                        value = 1f - pos.y
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    val pureHue = Color(AndroidColor.HSVToColor(255, floatArrayOf(hue, 1f, 1f)))
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(Color.White, pureHue)),
                        size = size,
                    )
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                        size = size,
                    )
                    val cx = saturation * size.width
                    val cy = (1f - value) * size.height
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 色相滑块
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .selectPosition { pos -> hue = pos.x * 360f },
            ) {
                val colors = (0..6).map {
                    Color(AndroidColor.HSVToColor(255, floatArrayOf(it * 60f, 1f, 1f)))
                }
                drawRect(brush = Brush.horizontalGradient(colors), size = size)
                val cx = (hue / 360f) * size.width
                drawLine(
                    color = Color.White,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 透明度滑块：棋盘格底 + 透明→纯色渐变
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .selectPosition { pos -> alpha = pos.x },
            ) {
                drawChecker(size)
                val solid = currentColor.copy(alpha = 1f)
                drawRect(
                    brush = Brush.horizontalGradient(listOf(solid.copy(alpha = 0f), solid)),
                    size = size,
                )
                val cx = alpha * size.width
                drawLine(
                    color = Color.White,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
    }
}

private fun DrawScope.drawChecker(size: Size) {
    val cell = 6.dp.toPx()
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            if ((r + c) % 2 == 0) {
                drawRect(
                    color = Color(0xFFCCCCCC),
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}
