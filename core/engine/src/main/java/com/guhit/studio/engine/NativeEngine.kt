package com.guhit.studio.engine

/** JNI bridge to the C++ canvas engine. All calls must happen on the GL thread. */
object NativeEngine {
    init {
        System.loadLibrary("guhit_engine")
    }

    external fun nativeInit()
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw(tx: Float, ty: Float, scale: Float, rotation: Float)

    /** Allocates an empty canvas with no layers. False if the size is unsupported by this GPU. */
    external fun nativeNewCanvas(width: Int, height: Int): Boolean
    external fun nativeMaxLayers(): Int

    external fun nativeAddLayer(id: Int, index: Int, force: Boolean): Boolean
    external fun nativeDuplicateLayer(srcId: Int, newId: Int, index: Int): Boolean
    external fun nativeRemoveLayer(id: Int)
    external fun nativeMoveLayer(id: Int, index: Int)
    external fun nativeSetLayerProps(
        id: Int, visible: Boolean, opacity: Float, blend: Int, alphaLock: Boolean, clip: Boolean,
    )
    external fun nativeSetActive(id: Int)
    external fun nativeMergeDown(upperId: Int, lowerId: Int): Boolean

    /** Premultiplied RGBA, row 0 = top, width*height*4 bytes. Follow with nativeRecompositeAll. */
    external fun nativeLoadLayerPixels(id: Int, pixels: ByteArray): Boolean
    external fun nativeRecompositeAll()
    external fun nativeReadLayerPixels(id: Int, out: ByteArray): Boolean
    external fun nativeReadFlat(out: ByteArray): Boolean

    external fun nativeSetBrush(
        size: Float, hardness: Float, spacing: Float, flow: Float,
        sizePressure: Float, flowPressure: Float,
        r: Float, g: Float, b: Float, opacity: Float, erase: Boolean,
    )

    external fun nativeStrokeBegin()
    external fun nativeStrokeAdd(x: Float, y: Float, pressure: Float)

    /** Draws the stamps queued by nativeStrokeAdd (call once per touch event, not per point). */
    external fun nativeStrokeFlush()
    external fun nativeStrokeEnd()
    external fun nativeStrokeCancel()

    external fun nativeUndo(): Boolean
    external fun nativeRedo(): Boolean
    external fun nativeUndoCount(): Int
    external fun nativeRedoCount(): Int
}
