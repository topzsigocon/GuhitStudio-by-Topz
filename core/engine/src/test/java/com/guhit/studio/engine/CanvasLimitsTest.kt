package com.guhit.studio.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLimitsTest {
    @Test
    fun acceptsCommonSizes() {
        assertTrue(CanvasLimits.isValid(2048, 2048))
        assertTrue(CanvasLimits.isValid(3840, 2160))
        assertTrue(CanvasLimits.isValid(4096, 4096))
    }

    @Test
    fun rejectsOutOfRange() {
        assertFalse(CanvasLimits.isValid(null, 1000))
        assertFalse(CanvasLimits.isValid(32, 1000))
        assertFalse(CanvasLimits.isValid(5000, 100))
    }
}
