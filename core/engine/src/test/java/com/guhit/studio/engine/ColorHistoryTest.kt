package com.guhit.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorHistoryTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun newestFirstAndNoDuplicates() {
        var h = emptyList<Int>()
        h = ColorHistory.push(h, red)
        h = ColorHistory.push(h, green)
        h = ColorHistory.push(h, red)
        assertEquals(listOf(red, green), h)
    }

    @Test
    fun limitedToMax() {
        var h = emptyList<Int>()
        for (i in 0 until 30) h = ColorHistory.push(h, 0xFF000000.toInt() or i)
        assertEquals(ColorHistory.MAX, h.size)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val h = listOf(red, green, blue)
        assertEquals(h, ColorHistory.decode(ColorHistory.encode(h)))
        assertEquals(emptyList<Int>(), ColorHistory.decode(null))
    }
}
