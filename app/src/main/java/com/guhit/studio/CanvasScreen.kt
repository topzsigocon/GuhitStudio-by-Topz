package com.guhit.studio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.guhit.studio.designsystem.BottomSheetPanel
import com.guhit.studio.designsystem.BottomToolbar
import com.guhit.studio.designsystem.ColorPickerPanel
import com.guhit.studio.designsystem.GuhitColors
import com.guhit.studio.designsystem.ToolbarButton
import com.guhit.studio.designsystem.TopBar
import com.guhit.studio.designsystem.ValueSlider
import com.guhit.studio.engine.BrushMath
import com.guhit.studio.engine.BrushPresets
import com.guhit.studio.engine.CanvasSurfaceView
import com.guhit.studio.engine.CanvasTool
import com.guhit.studio.engine.ColorHistory
import com.guhit.studio.engine.FingerMode
import com.guhit.studio.engine.Layer
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private enum class Panel { None, Brushes, Color, Layers, Actions }

/** 1% steps; the top of the slider snaps to exactly 100% so the stroke is fully opaque. */
private fun snapOpacity(v: Float): Float {
    val r = (v * 100f).roundToInt() / 100f
    return if (r >= 0.97f) 1f else r.coerceAtLeast(0.01f)
}

@Composable
fun CanvasScreen(artwork: Artwork, onClose: () -> Unit) {
    EditorContent(artwork, onClose)
}

/**
 * Debounced + on-demand autosave. Layers are saved one at a time (read on the GL thread, written on
 * an IO thread) so memory stays at about one layer no matter how many layers exist.
 */
private class Saver(
    private val context: Context,
    private val art: Artwork,
    private val canvas: CanvasSurfaceView,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var dirty = false
    private var running = false
    private var rerun = false
    private val waiters = ArrayList<() -> Unit>()
    private val debounce = Runnable { request {} }

    fun markDirty() {
        dirty = true
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, AUTOSAVE_DELAY_MS)
    }

    fun cancelTimer() = main.removeCallbacks(debounce)

    fun shutdown() = io.shutdown() // lets queued writes finish

    /** Saves if there are unsaved changes, then calls [onDone] (also when nothing needed saving). */
    fun request(onDone: () -> Unit) {
        waiters.add(onDone)
        if (running) {
            rerun = true
            return
        }
        start()
    }

    private fun start() {
        if (!dirty) {
            finish()
            return
        }
        val layers = canvas.layers
        if (layers.isEmpty()) {
            finish()
            return
        }
        running = true
        dirty = false
        val version = canvas.structureVersion
        val active = canvas.activeLayerId
        io.execute {
            val session = try {
                ArtworkRepository.beginSave(context, art, layers, active)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (session == null) fail(retry = false) else step(session, layers, 0, version)
            }
        }
    }

    private fun step(session: ArtworkRepository.SaveSession, layers: List<Layer>, i: Int, version: Int) {
        if (canvas.structureVersion != version) { // layers were added/removed/moved mid-save
            io.execute { session.abort() }
            fail(retry = true)
            return
        }
        if (i >= layers.size) {
            canvas.exportFlat { flat ->
                io.execute {
                    val ok = try {
                        session.commit(flat)
                        true
                    } catch (e: Exception) {
                        session.abort()
                        false
                    }
                    main.post { if (ok) done() else fail(retry = false) }
                }
            }
            return
        }
        val layer = layers[i]
        canvas.readLayer(layer.id) { px ->
            if (px == null) {
                io.execute { session.abort() }
                fail(retry = false)
            } else {
                io.execute {
                    val ok = try {
                        session.writeLayer(layer, px)
                        true
                    } catch (e: Exception) {
                        session.abort()
                        false
                    }
                    main.post { if (ok) step(session, layers, i + 1, version) else fail(retry = false) }
                }
            }
        }
    }

    private fun done() {
        running = false
        if (rerun) {
            rerun = false
            start()
        } else {
            finish()
        }
    }

    private fun fail(retry: Boolean) {
        running = false
        dirty = true
        if (retry) {
            if (rerun) {
                rerun = false
                start()
            } else {
                main.postDelayed(debounce, 1000)
                finish()
            }
        } else {
            rerun = false
            finish()
        }
    }

    private fun finish() {
        val w = ArrayList(waiters)
        waiters.clear()
        w.forEach { it() }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 4000L
    }
}

@Composable
private fun EditorContent(artwork: Artwork, onClose: () -> Unit) {
    val context = LocalContext.current
    val canvas = remember { CanvasSurfaceView(context) }
    val saver = remember { Saver(context, artwork, canvas) }
    val io = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val sessionHolder = remember { arrayOfNulls<ArtworkRepository.LoadSession>(1) }
    var loading by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf<List<Layer>>(emptyList()) }
    var activeLayerId by remember { mutableIntStateOf(-1) }

    fun closeEditor() {
        if (closing) return
        closing = true
        saver.cancelTimer()
        saver.request { onClose() }
    }

    // Streams saved layers into the GPU one at a time (low memory).
    fun streamNext(session: ArtworkRepository.LoadSession) {
        io.execute {
            val next = try {
                session.next()
            } catch (e: Exception) {
                null
            }
            main.post {
                if (next == null) {
                    try { session.close() } catch (_: Exception) {}
                    sessionHolder[0] = null
                    canvas.finishLoading()
                    loading = false
                } else {
                    canvas.loadLayerPixels(next.first, next.second) { streamNext(session) }
                }
            }
        }
    }

    fun startLoad() {
        loading = true
        io.execute {
            val session = try {
                ArtworkRepository.openLoad(context, artwork)
            } catch (e: Exception) {
                null
            }
            main.post {
                sessionHolder[0] = session
                if (session == null) {
                    canvas.openDocument(artwork.width, artwork.height, listOf(Layer(1, "Layer 1")), 1)
                } else {
                    canvas.openDocument(session.width, session.height, session.layers, session.activeId)
                }
            }
        }
    }

    var panel by remember { mutableStateOf(Panel.None) }
    var barVisible by remember { mutableStateOf(true) }
    var sideVisible by remember { mutableStateOf(true) }

    var presetId by remember { mutableStateOf(BrushPresets.all.first().id) }
    var sizeValue by remember { mutableFloatStateOf(0.45f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var color by remember { mutableStateOf(Color.Black) }
    var erase by remember { mutableStateOf(false) }
    var fingerDraws by remember { mutableStateOf(true) }
    var showFps by remember { mutableStateOf(false) }
    var fps by remember { mutableFloatStateOf(0f) }
    var undoCount by remember { mutableIntStateOf(0) }
    var redoCount by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(CanvasTool.DRAW) }
    var prevTool by remember { mutableStateOf(CanvasTool.DRAW) } // tool to return to after the pipette
    var tolerance by remember { mutableFloatStateOf(0.1f) }
    var fillMerged by remember { mutableStateOf(true) }
    val prefs = remember { context.getSharedPreferences("guhit", Context.MODE_PRIVATE) }
    var recent by remember { mutableStateOf(ColorHistory.decode(prefs.getString("recent_colors", null))) }
    var thumbs by remember { mutableStateOf<Map<Int, ImageBitmap>>(emptyMap()) }
    val colorHolder = remember { arrayOf(Color.Black) }
    val panelHolder = remember { arrayOf(Panel.None) }

    fun refreshThumbs() {
        canvas.requestThumbnails(72) { w, h, map ->
            val out = HashMap<Int, ImageBitmap>()
            for ((id, bytes) in map) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                out[id] = bmp.asImageBitmap()
            }
            thumbs = out
        }
    }
    val thumbTask = remember { Runnable { refreshThumbs() } }
    fun scheduleThumbs() {
        main.removeCallbacks(thumbTask)
        main.postDelayed(thumbTask, 350)
    }

    val preset = BrushPresets.all.first { it.id == presetId }
    val sizePx = BrushMath.sizeFromSlider(sizeValue)

    // Read EVERY brush-related state here, in the screen's own scope, so any change (size, opacity,
    // color, eraser, preset, finger mode) recomposes this function and re-runs the SideEffect.
    // (Reading them only inside the panels would recompose just the panels and leave the engine stale.)
    val brushSettings = preset.toSettings(sizePx, opacity, color.toArgb(), erase)
    val fingerMode = if (fingerDraws) FingerMode.DRAW else FingerMode.NAVIGATE
    val toolNow = tool
    val toleranceNow = tolerance
    val mergedNow = fillMerged
    val colorNow = color
    val panelNow = panel
    SideEffect {
        canvas.brush = brushSettings
        canvas.fingerMode = fingerMode
        canvas.fpsEnabled = showFps
        canvas.tool = toolNow
        canvas.fillTolerance = toleranceNow
        canvas.fillSampleMerged = mergedNow
        colorHolder[0] = colorNow
        panelHolder[0] = panelNow
    }
    LaunchedEffect(panelNow, layers) { if (panelNow == Panel.Layers) scheduleThumbs() }

    // GL lifecycle + callbacks from the engine.
    DisposableEffect(canvas, saver) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    saver.request {}   // queue the snapshot before the GL thread pauses
                    canvas.onPause()
                }
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
        canvas.onFps = { fps = it }
        canvas.onContentChanged = {
            saver.markDirty()
            recent = ColorHistory.push(recent, colorHolder[0].toArgb())
            prefs.edit().putString("recent_colors", ColorHistory.encode(recent)).apply()
            if (panelHolder[0] == Panel.Layers) scheduleThumbs()
        }
        canvas.onColorSampled = { c, final ->
            color = Color(c)
            if (final) tool = prevTool
        }
        canvas.onLayersChanged = { l, a ->
            layers = l
            activeLayerId = a
        }
        canvas.onDocumentReady = {
            val session = sessionHolder[0]
            if (session == null) {
                canvas.finishLoading()
                loading = false
            } else {
                streamNext(session)
            }
        }
        canvas.onLayerLimit = { Toast.makeText(context, R.string.layer_limit, Toast.LENGTH_LONG).show() }
        canvas.onDrawBlocked = { Toast.makeText(context, R.string.draw_blocked, Toast.LENGTH_SHORT).show() }
        canvas.onCanvasError = {
            Toast.makeText(context, R.string.canvas_error, Toast.LENGTH_LONG).show()
            onClose()
        }
        // GPU memory was lost: reload the last saved version (drawing/saving stay blocked meanwhile).
        canvas.onContextRecreated = { startLoad() }
        startLoad()
        onDispose {
            lifecycle.removeObserver(observer)
            saver.cancelTimer()
            saver.shutdown()
            io.shutdown()
            canvas.onHistoryChanged = null
            canvas.onStylusDetected = null
            canvas.onContentChanged = null
            canvas.onColorSampled = null
            canvas.onFps = null
            canvas.onLayersChanged = null
            canvas.onDocumentReady = null
            canvas.onLayerLimit = null
            canvas.onDrawBlocked = null
            canvas.onCanvasError = null
            canvas.onContextRecreated = null
        }
    }

    // Export (system file picker, so no storage permission is needed).
    fun runExport(uri: Uri?, format: ExportFormat) {
        if (uri == null) return
        canvas.exportFlat { pixels ->
            if (pixels == null) {
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                return@exportFlat
            }
            Thread {
                val ok = ExportHelper.save(context, uri, pixels, artwork.width, artwork.height, format)
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

    BackHandler(enabled = !closing) {
        if (panel != Panel.None) panel = Panel.None else closeEditor()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { canvas }, modifier = Modifier.fillMaxSize())

        // Tap outside a sheet to close it.
        if (panel != Panel.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { panel = Panel.None } },
            )
        }

        // ------------------------------------------------ top: bar (back / undo / redo / ... / menu)
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = barVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                TopBar {
                    IconButton(onClick = ::closeEditor) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.gallery))
                    }
                    IconButton(onClick = canvas::undo, enabled = undoCount > 0) {
                        Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo_desc))
                    }
                    IconButton(onClick = canvas::redo, enabled = redoCount > 0) {
                        Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.redo_desc))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = canvas::resetView) {
                        Icon(painterResource(R.drawable.ic_fit), stringResource(R.string.reset_view))
                    }
                    IconButton(onClick = { toggle(Panel.Actions) }) {
                        Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.menu_desc))
                    }
                }
            }
            // Hide/show both bars to get the whole screen for drawing.
            Surface(
                onClick = { barVisible = !barVisible },
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                color = GuhitColors.Navy900.copy(alpha = 0.9f),
                contentColor = GuhitColors.Gold,
            ) {
                Text(
                    text = if (barVisible) "\u25B2" else "\u25BC",
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ------------------------------------------------ bottom: sheet, sliders, tool row
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = panel != Panel.None,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                BottomSheetPanel(scroll = panel != Panel.Layers) {
                    when (panel) {
                        Panel.Brushes -> BrushList(selectedId = presetId) {
                            presetId = it
                            panel = Panel.None
                        }
                        Panel.Color -> ColorPickerPanel(
                            color = color,
                            onColorChange = { color = it },
                            recent = recent.map { Color(it) },
                        )
                        Panel.Layers -> LayersPanel(
                            layers = layers,
                            activeId = activeLayerId,
                            thumbs = thumbs,
                            aspect = artwork.width.toFloat() / artwork.height,
                            maxLayers = canvas.maxLayers,
                            onSelect = canvas::selectLayer,
                            onAdd = { canvas.addLayer() },
                            onUpdate = canvas::updateLayer,
                            onDuplicate = { canvas.duplicateLayer(it) },
                            onDelete = { canvas.deleteLayer(it) },
                            onMergeDown = { canvas.mergeLayerDown(it) },
                            onMove = canvas::moveLayer,
                        )
                        Panel.Actions -> ActionsList(
                            fingerDraws = fingerDraws,
                            onFingerDraws = { fingerDraws = it },
                            showFps = showFps,
                            onShowFps = { showFps = it },
                            fillMerged = fillMerged,
                            onFillMerged = { fillMerged = it },
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

            AnimatedVisibility(
                visible = barVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(GuhitColors.Navy900.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (tool == CanvasTool.FILL) {
                            ValueSlider(
                                label = stringResource(R.string.label_tolerance),
                                valueText = "${(tolerance * 100).roundToInt()}%",
                                value = tolerance,
                                onValueChange = { tolerance = it },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            ValueSlider(
                                label = stringResource(R.string.label_size),
                                valueText = "${sizePx.roundToInt()} px",
                                value = sizeValue,
                                onValueChange = { sizeValue = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ValueSlider(
                            label = stringResource(R.string.label_opacity),
                            valueText = "${(opacity * 100).roundToInt()}%",
                            value = opacity,
                            onValueChange = { opacity = snapOpacity(it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BottomToolbar {
                        ToolbarButton(
                            label = stringResource(R.string.tool_brush),
                            selected = tool == CanvasTool.DRAW && !erase,
                            onClick = {
                                if (tool == CanvasTool.DRAW && !erase) {
                                    toggle(Panel.Brushes) // already the brush: open the brush list
                                } else {
                                    tool = CanvasTool.DRAW
                                    erase = false
                                    panel = Panel.None
                                }
                            },
                        ) { tint -> Icon(painterResource(R.drawable.ic_brush), null, tint = tint) }
                        ToolbarButton(
                            label = stringResource(R.string.tool_eraser),
                            selected = tool == CanvasTool.DRAW && erase,
                            onClick = {
                                tool = CanvasTool.DRAW
                                erase = true
                                panel = Panel.None
                            },
                        ) { tint -> Icon(painterResource(R.drawable.ic_eraser), null, tint = tint) }
                        ToolbarButton(
                            label = stringResource(R.string.tool_pipette),
                            selected = tool == CanvasTool.EYEDROPPER,
                            onClick = {
                                if (tool == CanvasTool.EYEDROPPER) {
                                    tool = prevTool
                                } else {
                                    prevTool = tool
                                    tool = CanvasTool.EYEDROPPER
                                }
                                panel = Panel.None
                            },
                        ) { tint -> Icon(painterResource(R.drawable.ic_pipette), null, tint = tint) }
                        ToolbarButton(
                            label = stringResource(R.string.tool_fill),
                            selected = tool == CanvasTool.FILL,
                            onClick = {
                                tool = CanvasTool.FILL
                                panel = Panel.None
                            },
                        ) { tint -> Icon(painterResource(R.drawable.ic_fill), null, tint = tint) }
                        ToolbarButton(
                            label = stringResource(R.string.layers_btn, layers.size),
                            selected = panel == Panel.Layers,
                            onClick = { toggle(Panel.Layers) },
                        ) { tint -> Icon(painterResource(R.drawable.ic_layers), null, tint = tint) }
                        ToolbarButton(
                            label = stringResource(R.string.tool_color),
                            selected = panel == Panel.Color,
                            onClick = { toggle(Panel.Color) },
                        ) { tint ->
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(2.dp, tint, CircleShape),
                            )
                        }
                    }
                }
            }
        }

        if (showFps) {
            Text(
                text = String.format(Locale.US, "%.0f FPS", fps),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (closing || loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC05070D))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(if (closing) R.string.saving else R.string.opening))
                }
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
    showFps: Boolean,
    onShowFps: (Boolean) -> Unit,
    fillMerged: Boolean,
    onFillMerged: (Boolean) -> Unit,
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.show_fps), fontWeight = FontWeight.Medium)
        Switch(checked = showFps, onCheckedChange = onShowFps)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.fill_merged), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.fill_merged_hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = fillMerged, onCheckedChange = onFillMerged)
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
