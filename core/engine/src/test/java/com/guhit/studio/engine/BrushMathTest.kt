package com.guhit.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushMathTest {
    @Test
    fun sliderEndpointsMapToMinAndMax() {
        assertEquals(BrushMath.MIN_SIZE, BrushMath.sizeFromSlider(0f), 0.001f)
        assertEquals(BrushMath.MAX_SIZE, BrushMath.sizeFromSlider(1f), 0.01f)
    }

    @Test
    fun sliderIsMonotonicAndClamped() {
        assertTrue(BrushMath.sizeFromSlider(0.3f) < BrushMath.sizeFromSlider(0.6f))
        assertEquals(BrushMath.MAX_SIZE, BrushMath.sizeFromSlider(5f), 0.01f)
    }

    @Test
    fun presetsHaveUniqueIds() {
        val ids = BrushPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
