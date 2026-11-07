package com.guhit.studio.engine

/** JNI bridge to the C++ canvas engine. All calls must happen on the GL thread. */
object NativeEngine {
    init {
        System.loadLibrary("guhit_engine")
    }

    external fun nativeInit()
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw(tx: Float, ty: Float, scale: Float, rotation: Float)
}
