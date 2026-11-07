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
                reportHistory()
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

    fun resetView() {
        if (width == 0 || height == 0) return
        val fit = min(width, height) / CANVAS_SIZE * 0.9f
        setTransform(width / 2f, height / 2f, fit, 0f)
    }

    fun undo() = gl {
        NativeEngine.nativeUndo()
        reportHistory()
    }

    fun redo() = gl {
        NativeEngine.nativeRedo()
        reportHistory()
    }

    /** Premultiplied RGBA, canvas-top-first, CANVAS_SIZE^2 * 4 bytes. Callback runs on the UI thread. */
    fun exportPixels(onDone: (ByteArray?) -> Unit) = gl {
        val buf = ByteArray(NativeEngine.CANVAS_SIZE * NativeEngine.CANVAS_SIZE * 4)
        val ok = NativeEngine.nativeReadPixels(buf)
        ui.post { onDone(if (ok) buf else null) }
    }

    // ---------------------------------------------------------------- helpers

    private inline fun gl(crossinline block: () -> Unit) {
        queueEvent { block() }
        requestRender()
    }

    private fun reportHistory() {
        val u = NativeEngine.nativeUndoCount()
        val r = NativeEngine.nativeRedoCount()
        ui.post { onHistoryChanged?.invoke(u, r) }
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

    /** Screen px -> canvas px (0..CANVAS_SIZE, y down). Result in outX/outY. */
    private fun toCanvas(sx: Float, sy: Float) {
        val t = transform
        val dx = (sx - t.tx) / t.scale
        val dy = (sy - t.ty) / t.scale
        val c = cos(t.rotation)
        val s = sin(t.rotation)
        outX = c * dx + s * dy + CANVAS_SIZE / 2f
        outY = -s * dx + c * dy + CANVAS_SIZE / 2f
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
            reportHistory()
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
        const val CANVAS_SIZE = NativeEngine.CANVAS_SIZE.toFloat()
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 64f
        const val ARBITRATION_MS = 40L
        const val TAP_MS = 300L
        const val TAP_SLOP_PX = 30f
    }
}
