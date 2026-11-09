package com.guhit.studio.engine

import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Who is allowed to draw with a finger. A stylus always draws. */
enum class FingerMode { DRAW, NAVIGATE }

/**
 * GL canvas.
 *  - Finger (or stylus) draws with the current [brush]; finger pressure is simulated from speed.
 *  - Two fingers: pan / pinch-zoom / rotate. Two-finger tap = undo, three-finger tap = redo.
 *  - When a stylus is first seen, fingers switch to navigation (palm friendly).
 *  - Layers: the view owns a [LayerStack] and mirrors every change to the GPU.
 *  - Open a document with [openDocument] -> [loadLayerPixels] (per layer) -> [finishLoading].
 */
class CanvasSurfaceView(context: Context) : GLSurfaceView(context) {

    // ---- public state ----
    @Volatile
    var brush: BrushSettings = BrushSettings()

    @Volatile
    var fingerMode: FingerMode = FingerMode.DRAW

    var autoSwitchOnStylus = true
    var onHistoryChanged: ((undo: Int, redo: Int) -> Unit)? = null
    var onStylusDetected: (() -> Unit)? = null

    /** Fired (UI thread) after a stroke is committed or after undo/redo: the artwork changed. */
    var onContentChanged: (() -> Unit)? = null

    /** The GPU canvas could not be created (size not supported by this device). */
    var onCanvasError: (() -> Unit)? = null

    /** The GL context was lost and recreated: the app must reload the artwork from disk. */
    var onContextRecreated: (() -> Unit)? = null

    // ---- document state ----
    private class DocState(val w: Int, val h: Int, val layers: List<Layer>, val activeId: Int)

    // ---- layers (UI thread owns the model; the GL thread mirrors it) ----
    private val stack = LayerStack()
    val layers: List<Layer> get() = stack.layers
    val activeLayerId: Int get() = stack.activeId

    /** How many layers this canvas size allows on this device (set when the canvas is created). */
    @Volatile
    var maxLayers: Int = 1
        private set

    /** Bumped on add/remove/move/merge/open. Savers use it to detect structural changes mid-save. */
    @Volatile
    var structureVersion: Int = 0
        private set

    var onLayersChanged: ((layers: List<Layer>, activeId: Int) -> Unit)? = null

    /** The GPU canvas and (empty) layers exist: the app can now stream saved pixels in. */
    var onDocumentReady: (() -> Unit)? = null

    /** User touched the canvas while the active layer is locked or hidden. */
    var onDrawBlocked: (() -> Unit)? = null

    /** Tried to add a layer beyond [maxLayers]. */
    var onLayerLimit: (() -> Unit)? = null
    private var blockedTouch = false

    private val lock = Any()
    private var pendingDoc: DocState? = null
    private var glReady = false
    private var everReady = false

    @Volatile
    private var contentValid = false

    @Volatile
    private var canvasW = 2048

    @Volatile
    private var canvasH = 2048

    // ---- view transform (screen = t + scale * R(rotation) * canvasPoint, origin = canvas center) ----
    private class Transform(val tx: Float, val ty: Float, val scale: Float, val rotation: Float)

    @Volatile
    private var transform = Transform(0f, 0f, 1f, 0f)
    private var hasTransform = false

    // ---- two-finger navigation state ----
    private var tracking = false
    private var prevMx = 0f
    private var prevMy = 0f
    private var prevDist = 1f
    private var prevAngle = 0f

    // ---- touch state machine ----
    private enum class Mode { IDLE, PENDING, DRAWING, NAVIGATING }

    private class RawPt(val x: Float, val y: Float, val pressure: Float, val time: Long)

    private var mode = Mode.IDLE
    private var strokePointerId = -1
    private var strokeIsFinger = true
    private var strokeErase = false
    private var stylusSeen = false
    private var gestureStart = 0L
    private var maxPointers = 0
    private var travel = 0f
    private val downX = FloatArray(4)
    private val downY = FloatArray(4)
    private val pending = ArrayList<RawPt>()
    private val flush = Runnable { if (mode == Mode.PENDING) beginStroke() }
    private val ui = Handler(Looper.getMainLooper())

    // ---- stroke filter state ----
    private var strokeBrush = BrushSettings()
    private var fx = 0f
    private var fy = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastTime = 0L
    private var pEma = 1f
    private var lastPressure = 1f
    private var pointCount = 0
    private var outX = 0f
    private var outY = 0f

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                NativeEngine.nativeInit()
                var recreated = false
                synchronized(lock) {
                    recreated = everReady
                    everReady = true
                    glReady = true
                }
                if (recreated) {
                    // Pixels in GPU memory are gone. Block drawing/saving until the app reloads them.
                    contentValid = false
                    NativeEngine.nativeNewCanvas(canvasW, canvasH)
                    ui.post { onContextRecreated?.invoke() }
                }
                applyPendingDoc()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) =
                NativeEngine.nativeResize(width, height)

            override fun onDrawFrame(gl: GL10?) {
                val t = transform
                NativeEngine.nativeDraw(t.tx, t.ty, t.scale, t.rotation)
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ---------------------------------------------------------------- public API

    /**
     * Creates the canvas (w x h) with the given EMPTY layers. Drawing stays blocked until
     * [finishLoading]. [onDocumentReady] fires when the GPU side is ready for [loadLayerPixels].
     */
    fun openDocument(w: Int, h: Int, layers: List<Layer>, activeId: Int) {
        canvasW = w
        canvasH = h
        if (width > 0 && height > 0) resetView() else hasTransform = false
        contentValid = false
        stack.reset(layers, activeId)
        structureVersion++
        notifyLayers()
        synchronized(lock) {
            pendingDoc = DocState(w, h, layers, stack.activeId)
            if (glReady) queueEvent { applyPendingDoc() }
        }
        requestRender()
    }

    /** Uploads saved pixels (premultiplied RGBA, canvas-size) into layer [id]. */
    fun loadLayerPixels(id: Int, pixels: ByteArray, onDone: () -> Unit) = gl {
        NativeEngine.nativeLoadLayerPixels(id, pixels)
        ui.post { onDone() }
    }

    /** Call after all layer pixels were loaded (or immediately for a blank document). */
    fun finishLoading() = gl {
        NativeEngine.nativeRecompositeAll()
        contentValid = true
        reportHistory(false)
    }

    fun resetView() {
        if (width == 0 || height == 0) return
        hasTransform = true
        val fit = min(width / canvasW.toFloat(), height / canvasH.toFloat()) * 0.9f
        setTransform(width / 2f, height / 2f, fit, 0f)
    }

    fun undo() = gl {
        val changed = NativeEngine.nativeUndo()
        reportHistory(changed)
    }

    fun redo() = gl {
        val changed = NativeEngine.nativeRedo()
        reportHistory(changed)
    }

    /**
     * The flattened picture (all visible layers), premultiplied RGBA, canvas-top-first,
     * width*height*4 bytes. Callback on the UI thread; null if the content is not valid
     * (e.g. while loading or reloading after a GL context loss).
     */
    fun exportFlat(onDone: (ByteArray?) -> Unit) = gl {
        if (!contentValid) {
            ui.post { onDone(null) }
        } else {
            val buf = ByteArray(canvasW * canvasH * 4)
            val ok = NativeEngine.nativeReadFlat(buf)
            ui.post { onDone(if (ok) buf else null) }
        }
    }

    /** One layer's pixels (premultiplied RGBA). Null if invalid or the layer no longer exists. */
    fun readLayer(id: Int, onDone: (ByteArray?) -> Unit) = gl {
        if (!contentValid) {
            ui.post { onDone(null) }
        } else {
            val buf = ByteArray(canvasW * canvasH * 4)
            val ok = NativeEngine.nativeReadLayerPixels(id, buf)
            ui.post { onDone(if (ok) buf else null) }
        }
    }

    // ---- layer operations (UI thread) ----

    fun addLayer(): Boolean {
        if (!contentValid) return false
        if (stack.layers.size >= maxLayers) {
            onLayerLimit?.invoke()
            return false
        }
        val l = stack.add()
        val idx = stack.indexOf(l.id)
        structureChanged()
        gl {
            NativeEngine.nativeAddLayer(l.id, idx, false)
            NativeEngine.nativeSetActive(l.id)
        }
        return true
    }

    fun duplicateLayer(id: Int): Boolean {
        if (!contentValid) return false
        if (stack.layers.size >= maxLayers) {
            onLayerLimit?.invoke()
            return false
        }
        val d = stack.duplicate(id) ?: return false
        val idx = stack.indexOf(d.id)
        structureChanged()
        gl {
            NativeEngine.nativeDuplicateLayer(id, d.id, idx)
            NativeEngine.nativeSetActive(d.id)
        }
        return true
    }

    fun deleteLayer(id: Int): Boolean {
        if (!contentValid || !stack.remove(id)) return false
        val newActive = stack.activeId
        structureChanged()
        gl {
            NativeEngine.nativeRemoveLayer(id)
            NativeEngine.nativeSetActive(newActive)
            reportHistory(false)
        }
        return true
    }

    /** delta +1 = up one position, -1 = down. */
    fun moveLayer(id: Int, delta: Int) {
        if (!contentValid) return
        val idx = stack.move(id, delta) ?: return
        structureChanged()
        gl { NativeEngine.nativeMoveLayer(id, idx) }
    }

    /** Merges the layer into the one below it. Not undoable. */
    fun mergeLayerDown(id: Int): Boolean {
        if (!contentValid) return false
        val (upper, lower) = stack.mergeDown(id) ?: return false
        structureChanged()
        gl {
            NativeEngine.nativeMergeDown(upper.id, lower.id)
            NativeEngine.nativeSetActive(lower.id)
            reportHistory(false)
        }
        return true
    }

    fun selectLayer(id: Int) {
        if (stack.indexOf(id) < 0) return
        stack.select(id)
        notifyLayers()
        gl { NativeEngine.nativeSetActive(id) }
    }

    /** Change opacity, blend, visibility, locks, name... */
    fun updateLayer(id: Int, transform: (Layer) -> Layer) {
        val l = stack.update(id, transform) ?: return
        notifyLayers()
        onContentChanged?.invoke()
        gl {
            NativeEngine.nativeSetLayerProps(l.id, l.visible, l.opacity, l.blend.id, l.alphaLock, l.clip)
        }
    }

    private fun structureChanged() {
        structureVersion++
        notifyLayers()
        onContentChanged?.invoke()
    }

    private fun notifyLayers() {
        onLayersChanged?.invoke(stack.layers, stack.activeId)
    }

    // ---------------------------------------------------------------- helpers

    private inline fun gl(crossinline block: () -> Unit) {
        queueEvent { block() }
        requestRender()
    }

    /** GL thread: apply a queued document (new canvas + empty layers). */
    private fun applyPendingDoc() {
        val d = synchronized(lock) {
            val x = pendingDoc
            pendingDoc = null
            x
        } ?: return
        var ok = NativeEngine.nativeNewCanvas(d.w, d.h)
        if (ok) {
            for ((i, l) in d.layers.withIndex()) {
                if (!NativeEngine.nativeAddLayer(l.id, i, true)) ok = false
                NativeEngine.nativeSetLayerProps(l.id, l.visible, l.opacity, l.blend.id, l.alphaLock, l.clip)
            }
            NativeEngine.nativeSetActive(d.activeId)
            maxLayers = NativeEngine.nativeMaxLayers()
        }
        contentValid = false // becomes true in finishLoading()
        ui.post { if (ok) onDocumentReady?.invoke() else onCanvasError?.invoke() }
        reportHistory(false)
    }

    /** GL thread. */
    private fun reportHistory(changed: Boolean) {
        val u = NativeEngine.nativeUndoCount()
        val r = NativeEngine.nativeRedoCount()
        ui.post {
            onHistoryChanged?.invoke(u, r)
            if (changed) onContentChanged?.invoke()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasTransform) {
            hasTransform = true
            resetView()
        }
    }

    private fun setTransform(tx: Float, ty: Float, scale: Float, rotation: Float) {
        transform = Transform(tx, ty, scale, rotation)
        requestRender()
    }

    /** Screen px -> canvas px (0..width, 0..height, y down). Result in outX/outY. */
    private fun toCanvas(sx: Float, sy: Float) {
        val t = transform
        val dx = (sx - t.tx) / t.scale
        val dy = (sy - t.ty) / t.scale
        val c = cos(t.rotation)
        val s = sin(t.rotation)
        outX = c * dx + s * dy + canvasW / 2f
        outY = -s * dx + c * dy + canvasH / 2f
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onExtraDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e, cancel = false)
            MotionEvent.ACTION_CANCEL -> onUp(e, cancel = true)
        }
        return true
    }

    private fun recordDown(e: MotionEvent) {
        for (i in 0 until min(e.pointerCount, 4)) {
            downX[i] = e.getX(i)
            downY[i] = e.getY(i)
        }
    }

    private fun onFirstDown(e: MotionEvent) {
        if (!contentValid) {
            mode = Mode.IDLE
            return
        }
        val activeLayer = stack.active
        blockedTouch = activeLayer == null || activeLayer.locked || !activeLayer.visible
        if (blockedTouch) {
            // Two-finger navigation and tap gestures still work; a lone tap reports the block.
            ui.removeCallbacks(flush)
            pending.clear()
            tracking = false
            maxPointers = 1
            travel = 0f
            gestureStart = e.eventTime
            recordDown(e)
            mode = Mode.NAVIGATING
            return
        }
        ui.removeCallbacks(flush)
        pending.clear()
        tracking = false
        maxPointers = 1
        travel = 0f
        gestureStart = e.eventTime
        recordDown(e)

        val tool = e.getToolType(0)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (isStylus && !stylusSeen) {
            stylusSeen = true
            if (autoSwitchOnStylus) {
                fingerMode = FingerMode.NAVIGATE
                onStylusDetected?.invoke()
            }
        }
        val isMouse = tool == MotionEvent.TOOL_TYPE_MOUSE
        val canDraw = isStylus || isMouse || fingerMode == FingerMode.DRAW
        if (canDraw) {
            strokePointerId = e.getPointerId(0)
            strokeIsFinger = !isStylus && !isMouse
            strokeErase = tool == MotionEvent.TOOL_TYPE_ERASER
            mode = Mode.PENDING
            pending.add(RawPt(e.getX(0), e.getY(0), e.getPressure(0), e.eventTime))
            if (strokeIsFinger) ui.postDelayed(flush, ARBITRATION_MS) else beginStroke()
        } else {
            mode = Mode.NAVIGATING
        }
    }

    private fun onExtraDown(e: MotionEvent) {
        maxPointers = max(maxPointers, e.pointerCount)
        recordDown(e)
        tracking = false
        when (mode) {
            Mode.PENDING -> {
                ui.removeCallbacks(flush)
                pending.clear()
                mode = Mode.NAVIGATING
            }
            Mode.DRAWING -> if (strokeIsFinger) {
                cancelStroke()
                mode = Mode.NAVIGATING
            }
            else -> Unit
        }
    }

    private fun onMove(e: MotionEvent) {
        for (i in 0 until min(e.pointerCount, 4)) {
            travel = max(travel, hypot(e.getX(i) - downX[i], e.getY(i) - downY[i]))
        }
        when (mode) {
            Mode.PENDING, Mode.DRAWING -> {
                val idx = e.findPointerIndex(strokePointerId)
                if (idx < 0) return
                for (h in 0 until e.historySize) {
                    addRaw(
                        RawPt(
                            e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                            e.getHistoricalPressure(idx, h), e.getHistoricalEventTime(h),
                        ),
                    )
                }
                addRaw(RawPt(e.getX(idx), e.getY(idx), e.getPressure(idx), e.eventTime))
            }
            Mode.NAVIGATING -> when {
                e.pointerCount >= 2 -> handleTwoFingers(e)
                fingerMode == FingerMode.NAVIGATE && maxPointers == 1 -> panSingle(e)
            }
            Mode.IDLE -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        val id = e.getPointerId(e.actionIndex)
        if ((mode == Mode.DRAWING || mode == Mode.PENDING) && id == strokePointerId) {
            onUp(e, cancel = false)
            return
        }
        tracking = false
    }

    private fun onUp(e: MotionEvent, cancel: Boolean) {
        ui.removeCallbacks(flush)
        when (mode) {
            Mode.PENDING -> {
                if (cancel) pending.clear() else {
                    beginStroke()
                    finishStroke()
                }
            }
            Mode.DRAWING -> if (cancel) cancelStroke() else finishStroke()
            Mode.NAVIGATING -> {
                val quick = e.eventTime - gestureStart < TAP_MS && travel < TAP_SLOP_PX
                if (!cancel && e.actionMasked == MotionEvent.ACTION_UP && quick) {
                    when (maxPointers) {
                        1 -> if (blockedTouch) onDrawBlocked?.invoke()
                        2 -> undo()
                        3 -> redo()
                    }
                }
            }
            Mode.IDLE -> Unit
        }
        mode = Mode.IDLE
        tracking = false
        strokePointerId = -1
    }

    // ---------------------------------------------------------------- drawing

    private fun addRaw(p: RawPt) {
        if (mode == Mode.PENDING) pending.add(p) else feed(p)
    }

    private fun beginStroke() {
        if (pending.isEmpty()) return
        mode = Mode.DRAWING
        // Finger pressure is only a simulation (speed), so it may thin the line but must never
        // fade the paint: flow stays constant for finger strokes.
        val b = brush.copy(
            erase = brush.erase || strokeErase,
            flowPressure = if (strokeIsFinger) 0f else brush.flowPressure,
        )
        strokeBrush = b
        val first = pending[0]
        fx = first.x
        fy = first.y
        lastRawX = first.x
        lastRawY = first.y
        lastTime = first.time
        pEma = if (strokeIsFinger) 0.6f else first.pressure.coerceIn(0f, 1f)
        lastPressure = pEma
        pointCount = 0
        gl {
            NativeEngine.nativeSetBrush(
                b.sizePx, b.hardness, b.spacing, b.flow, b.sizePressure, b.flowPressure,
                Color.red(b.color) / 255f, Color.green(b.color) / 255f, Color.blue(b.color) / 255f,
                b.opacity, b.erase,
            )
            NativeEngine.nativeStrokeBegin()
        }
        val list = ArrayList(pending)
        pending.clear()
        for (p in list) feed(p)
    }

    private fun feed(p: RawPt) {
        val k = (1f - strokeBrush.streamline * 0.9f).coerceIn(0.1f, 1f)
        fx += (p.x - fx) * k
        fy += (p.y - fy) * k

        val dt = max(1L, p.time - lastTime).toFloat()
        val v = hypot(p.x - lastRawX, p.y - lastRawY) / dt // px per ms
        lastRawX = p.x
        lastRawY = p.y
        lastTime = p.time

        val target = if (strokeIsFinger) {
            1f - 0.75f * (v / 3f).coerceIn(0f, 1f) // faster = thinner
        } else {
            p.pressure.coerceIn(0f, 1f)
        }
        pEma += (target - pEma) * (if (strokeIsFinger) 0.2f else 0.5f)
        pointCount++
        val ramp = if (strokeIsFinger) min(1f, 0.35f + 0.13f * pointCount) else 1f
        val pressure = (pEma * ramp).coerceIn(0.02f, 1f)
        lastPressure = pressure

        toCanvas(fx, fy)
        val cx = outX
        val cy = outY
        gl { NativeEngine.nativeStrokeAdd(cx, cy, pressure) }
    }

    private fun finishStroke() {
        // Close the gap left by the smoothing filter.
        toCanvas(lastRawX, lastRawY)
        val cx = outX
        val cy = outY
        val pressure = lastPressure
        gl {
            NativeEngine.nativeStrokeAdd(cx, cy, pressure)
            NativeEngine.nativeStrokeEnd()
            reportHistory(true)
        }
    }

    private fun cancelStroke() = gl { NativeEngine.nativeStrokeCancel() }

    // ---------------------------------------------------------------- navigation

    private fun panSingle(e: MotionEvent) {
        val x = e.getX(0)
        val y = e.getY(0)
        if (!tracking) {
            tracking = true
            prevMx = x
            prevMy = y
            return
        }
        val t = transform
        setTransform(t.tx + x - prevMx, t.ty + y - prevMy, t.scale, t.rotation)
        prevMx = x
        prevMy = y
    }

    private fun handleTwoFingers(e: MotionEvent) {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val dist = hypot(x1 - x0, y1 - y0)
        val angle = atan2(y1 - y0, x1 - x0)

        if (!tracking || prevDist < 1f) {
            tracking = true
            prevMx = mx; prevMy = my; prevDist = dist; prevAngle = angle
            return
        }

        val cur = transform
        val dx = (prevMx - cur.tx) / cur.scale
        val dy = (prevMy - cur.ty) / cur.scale
        val cr = cos(cur.rotation)
        val sr = sin(cur.rotation)
        val cx = cr * dx + sr * dy
        val cy = -sr * dx + cr * dy

        val newScale = (cur.scale * dist / prevDist).coerceIn(MIN_SCALE, MAX_SCALE)
        var dAngle = angle - prevAngle
        while (dAngle > PI) dAngle -= (2 * PI).toFloat()
        while (dAngle < -PI) dAngle += (2 * PI).toFloat()
        val newRot = cur.rotation + dAngle
        val nc = cos(newRot)
        val ns = sin(newRot)

        setTransform(
            mx - newScale * (nc * cx - ns * cy),
            my - newScale * (ns * cx + nc * cy),
            newScale,
            newRot,
        )
        prevMx = mx; prevMy = my; prevDist = dist; prevAngle = angle
    }

    private companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 64f
        const val ARBITRATION_MS = 40L
        const val TAP_MS = 300L
        const val TAP_SLOP_PX = 30f
    }
}
