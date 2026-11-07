package com.guhit.studio.engine

/** JNI bridge to the C++ canvas engine. All calls must happen on the GL thread. */
object NativeEngine {
    const val CANVAS_SIZE = 2048

    init {
        System.loadLibrary("guhit_engine")
    }

    external fun nativeInit()
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw(tx: Float, ty: Float, scale: Float, rotation: Float)

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
