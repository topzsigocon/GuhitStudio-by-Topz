package com.guhit.studio.engine

object CanvasLimits {
    const val MIN_SIDE = 64
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 16_777_216L // 4096 x 4096

    fun isValid(width: Int?, height: Int?): Boolean {
        if (width == null || height == null) return false
        if (width < MIN_SIDE || height < MIN_SIDE) return false
        if (width > MAX_SIDE || height > MAX_SIDE) return false
        return width.toLong() * height <= MAX_PIXELS
    }
}
