package com.guhit.studio.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val Swatches = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFF6D4C41, 0xFF9E9E9E,
).map { Color(it) }

private fun hsvOf(c: Color): FloatArray {
    val a = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), a)
    return a
}

/** Saturation/value square + hue bar + quick swatches. */
@Composable
fun ColorPickerPanel(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    recent: List<Color> = emptyList(),
) {
    val init = remember { hsvOf(color) }
    var hue by remember { mutableFloatStateOf(init[0]) }
    var sat by remember { mutableFloatStateOf(init[1]) }
    var value by remember { mutableFloatStateOf(init[2]) }
    val onChange by rememberUpdatedState(onColorChange)

    fun emit() = onChange(Color.hsv(hue.coerceIn(0f, 359.99f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Saturation / value square
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val c = awaitPointerEvent().changes.first()
                            if (c.pressed) {
                                sat = (c.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (c.position.y / size.height).coerceIn(0f, 1f)
                                emit()
                                c.consume()
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue.coerceIn(0f, 359.99f), 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = Offset(sat * size.width, (1f - value) * size.height),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Hue bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val c = awaitPointerEvent().changes.first()
                            if (c.pressed) {
                                hue = (c.position.x / size.width).coerceIn(0f, 1f) * 359.99f
                                emit()
                                c.consume()
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                drawRect(Brush.horizontalGradient(List(7) { Color.hsv(it * 60f, 1f, 1f) }))
                drawCircle(
                    color = Color.White,
                    radius = 10.dp.toPx(),
                    center = Offset(hue / 359.99f * size.width, size.height / 2f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Current color + hex
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            )
            Text(
                text = "#%06X".format(color.toArgb() and 0xFFFFFF),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Recently used colors
        if (recent.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Mga huling kulay", style = MaterialTheme.typography.labelMedium)
                recent.chunked(6).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { rc ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(rc)
                                    .border(1.dp, GuhitColors.Gold.copy(alpha = 0.5f), CircleShape)
                                    .clickable {
                                        val a = hsvOf(rc)
                                        hue = a[0]; sat = a[1]; value = a[2]
                                        onChange(rc)
                                    },
                            )
                        }
                    }
                }
            }
        }

        // Swatches
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatches.chunked(6).forEach { rowColors ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowColors.forEach { sw ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(sw)
                                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                .clickable {
                                    val a = hsvOf(sw)
                                    hue = a[0]; sat = a[1]; value = a[2]
                                    onChange(sw)
                                },
                        )
                    }
                }
            }
        }
    }
}
