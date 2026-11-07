package com.guhit.studio

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.guhit.studio.designsystem.ColorPickerPanel
import com.guhit.studio.designsystem.FloatingBar
import com.guhit.studio.designsystem.PopupPanel
import com.guhit.studio.designsystem.VerticalSlider
import com.guhit.studio.engine.BrushMath
import com.guhit.studio.engine.BrushPresets
import com.guhit.studio.engine.CanvasSurfaceView
import com.guhit.studio.engine.FingerMode
import java.util.Locale
import kotlin.math.roundToInt

private enum class Panel { None, Brushes, Color, Actions }

/** 1% steps; the top of the slider snaps to exactly 100% so the stroke is fully opaque. */
private fun snapOpacity(v: Float): Float {
    val r = (v * 100f).roundToInt() / 100f
    return if (r >= 0.97f) 1f else r.coerceAtLeast(0.01f)
}

@Composable
fun CanvasScreen() {
    val context = LocalContext.current
    val canvas = remember { CanvasSurfaceView(context) }

    var panel by remember { mutableStateOf(Panel.None) }
    var barVisible by remember { mutableStateOf(true) }
    var sideVisible by remember { mutableStateOf(true) }

    var presetId by remember { mutableStateOf(BrushPresets.all.first().id) }
    var sizeValue by remember { mutableFloatStateOf(0.45f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var color by remember { mutableStateOf(Color.Black) }
    var erase by remember { mutableStateOf(false) }
    var fingerDraws by remember { mutableStateOf(true) }
    var undoCount by remember { mutableIntStateOf(0) }
    var redoCount by remember { mutableIntStateOf(0) }

    val preset = BrushPresets.all.first { it.id == presetId }
    val sizePx = BrushMath.sizeFromSlider(sizeValue)

    // Push UI state into the engine view.
    SideEffect {
        canvas.brush = preset.toSettings(sizePx, opacity, color.toArgb(), erase)
        canvas.fingerMode = if (fingerDraws) FingerMode.DRAW else FingerMode.NAVIGATE
    }

    // GL lifecycle + callbacks from the engine.
    DisposableEffect(canvas) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> canvas.onPause()
                Lifecycle.Event.ON_RESUME -> canvas.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        canvas.onHistoryChanged = { u, r ->
            undoCount = u
            redoCount = r
        }
        canvas.onStylusDetected = {
            fingerDraws = false
            Toast.makeText(context, R.string.stylus_detected, Toast.LENGTH_LONG).show()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            canvas.onHistoryChanged = null
            canvas.onStylusDetected = null
        }
    }

    // Export (system file picker, so no storage permission is needed).
    fun runExport(uri: Uri?, format: ExportFormat) {
        if (uri == null) return
        canvas.exportPixels { pixels ->
            if (pixels == null) {
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                return@exportPixels
            }
            Thread {
                val ok = ExportHelper.save(context, uri, pixels, format)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        if (ok) R.string.export_saved else R.string.export_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.start()
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        runExport(it, ExportFormat.PNG_TRANSPARENT)
    }
    val pngWhiteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        runExport(it, ExportFormat.PNG_WHITE)
    }
    val jpegLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) {
        runExport(it, ExportFormat.JPEG)
    }
    fun stamp() = "Guhit-" + System.currentTimeMillis()

    fun toggle(p: Panel) {
        panel = if (panel == p) Panel.None else p
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { canvas }, modifier = Modifier.fillMaxSize())

        // Tap outside a pop-up to close it.
        if (panel != Panel.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { panel = Panel.None } },
            )
        }

        // ------------------------------------------------ top: collapsible tool bar + pop-ups
        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = barVisible) {
                FloatingBar {
                    TextButton(onClick = { toggle(Panel.Brushes) }) { Text(preset.name) }
                    TextButton(
                        onClick = { erase = !erase },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (erase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.tool_eraser),
                            fontWeight = if (erase) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    Box(
                        Modifier
                            .padding(horizontal = 8.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                            .clickable { toggle(Panel.Color) },
                    )
                    TextButton(onClick = { toggle(Panel.Actions) }) { Text(stringResource(R.string.menu_more)) }
                }
            }

            AnimatedVisibility(visible = panel != Panel.None) {
                PopupPanel {
                    when (panel) {
                        Panel.Brushes -> BrushList(selectedId = presetId) {
                            presetId = it
                            panel = Panel.None
                        }
                        Panel.Color -> ColorPickerPanel(color = color, onColorChange = { color = it })
                        Panel.Actions -> ActionsList(
                            fingerDraws = fingerDraws,
                            onFingerDraws = { fingerDraws = it },
                            onResetView = {
                                canvas.resetView()
                                panel = Panel.None
                            },
                            onExportPng = { panel = Panel.None; pngLauncher.launch(stamp() + ".png") },
                            onExportPngWhite = { panel = Panel.None; pngWhiteLauncher.launch(stamp() + ".png") },
                            onExportJpeg = { panel = Panel.None; jpegLauncher.launch(stamp() + ".jpg") },
                        )
                        Panel.None -> Unit
                    }
                }
            }

            Surface(
                onClick = { barVisible = !barVisible },
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = if (barVisible) "\u25B2" else "\u25BC",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ------------------------------------------------ left: collapsible sliders + undo/redo
        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(visible = sideVisible) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    String.format(Locale.US, "%.0f", sizePx),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                VerticalSlider(value = sizeValue, onValueChange = { sizeValue = it })
                                Text(stringResource(R.string.label_size), style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    String.format(Locale.US, "%.0f%%", opacity * 100f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                VerticalSlider(value = opacity, onValueChange = { opacity = snapOpacity(it) })
                                Text(stringResource(R.string.label_opacity), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row {
                            TextButton(onClick = canvas::undo, enabled = undoCount > 0) {
                                Text(stringResource(R.string.undo), style = MaterialTheme.typography.titleLarge)
                            }
                            TextButton(onClick = canvas::redo, enabled = redoCount > 0) {
                                Text(stringResource(R.string.redo), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            Surface(
                onClick = { sideVisible = !sideVisible },
                shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ) {
                Text(
                    text = if (sideVisible) "\u25C0" else "\u25B6",
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 18.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun BrushList(selectedId: String, onSelect: (String) -> Unit) {
    BrushPresets.all.forEach { p ->
        val selected = p.id == selectedId
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                .clickable { onSelect(p.id) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(p.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            Text(p.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActionsList(
    fingerDraws: Boolean,
    onFingerDraws: (Boolean) -> Unit,
    onResetView: () -> Unit,
    onExportPng: () -> Unit,
    onExportPngWhite: () -> Unit,
    onExportJpeg: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.finger_draws), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.finger_draws_hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = fingerDraws, onCheckedChange = onFingerDraws)
    }
    ActionRow(stringResource(R.string.reset_view), onResetView)
    ActionRow(stringResource(R.string.export_png), onExportPng)
    ActionRow(stringResource(R.string.export_png_white), onExportPngWhite)
    ActionRow(stringResource(R.string.export_jpeg), onExportJpeg)
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}
