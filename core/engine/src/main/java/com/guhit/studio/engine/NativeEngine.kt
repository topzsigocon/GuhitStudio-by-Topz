package com.guhit.studio.engine

/** JNI bridge to the C++ canvas engine. All calls must happen on the GL thread. */
object NativeEngine {
    init {
        System.loadLibrary("guhit_engine")
    }

    external fun nativeInit()
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw(tx: Float, ty: Float, scale: Float, rotation: Float)

    /** Allocates a blank canvas. Returns false if the size is unsupported by this GPU. */
    external fun nativeNewCanvas(width: Int, height: Int): Boolean

    /** Premultiplied RGBA, row 0 = top, width*height*4 bytes. */
    external fun nativeLoadPixels(pixels: ByteArray): Boolean

    external fun nativeSetBrush(
        size: Float, hardness: Float, spacing: Float, flow: Float,
        sizePressure: Float, flowPressure: Float,
        r: Float, g: Float, b: Float, opacity: Float, erase: Boolean,
    )

    external fun nativeStrokeBegin()
    external fun nativeStrokeAdd(x: Float, y: Float, pressure: Float)
    external fun nativeStrokeEnd()
    external fun nativeStrokeCancel()

    external fun nativeUndo(): Boolean
    external fun nativeRedo(): Boolean
    external fun nativeUndoCount(): Int
    external fun nativeRedoCount(): Int

    external fun nativeReadPixels(out: ByteArray): Boolean
}
