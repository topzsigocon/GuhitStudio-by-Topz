package com.guhit.studio.engine

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * GL canvas with two-finger pan / pinch-zoom / rotate (pivot stays under the fingers).
 * One-finger drawing arrives in M1.
 */
class CanvasSurfaceView(context: Context) : GLSurfaceView(context) {

    private class Transform(val tx: Float, val ty: Float, val scale: Float, val rotation: Float)

    @Volatile
    private var transform = Transform(0f, 0f, 1f, 0f)
    private var hasTransform = false

    private var tracking = false
    private var prevMx = 0f
    private var prevMy = 0f
    private var prevDist = 1f
    private var prevAngle = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = NativeEngine.nativeInit()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) =
                NativeEngine.nativeResize(width, height)

            override fun onDrawFrame(gl: GL10?) {
                val t = transform
                NativeEngine.nativeDraw(t.tx, t.ty, t.scale, t.rotation)
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Fit the whole canvas on screen, centered, unrotated. */
    fun resetView() {
        if (width == 0 || height == 0) return
        val fit = min(width, height) / CANVAS_SIZE * 0.9f
        setTransform(width / 2f, height / 2f, fit, 0f)
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) handleTwoFingers(e)
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
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
        // Canvas point that was under the previous midpoint
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
        const val CANVAS_SIZE = 2048f
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 64f
    }
}
