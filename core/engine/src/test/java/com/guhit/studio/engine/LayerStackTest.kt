package com.guhit.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStackTest {
    private fun stackOf(vararg ids: Int) = LayerStack(ids.map { Layer(it, "L$it") }, ids.last())

    @Test
    fun addInsertsAboveActiveAndSelectsIt() {
        val s = stackOf(1, 2, 3)
        s.select(2)
        val n = s.add()
        assertEquals(listOf(1, 2, n.id, 3), s.layers.map { it.id })
        assertEquals(n.id, s.activeId)
    }

    @Test
    fun removeKeepsAtLeastOneAndRepairsActive() {
        val s = stackOf(1, 2)
        s.select(2)
        assertTrue(s.remove(2))
        assertEquals(1, s.activeId)
        assertFalse(s.remove(1))
        assertEquals(1, s.layers.size)
    }

    @Test
    fun moveRespectsBounds() {
        val s = stackOf(1, 2, 3)
        assertNull(s.move(3, +1))
        assertNull(s.move(1, -1))
        assertEquals(1, s.move(1, +1))
        assertEquals(listOf(2, 1, 3), s.layers.map { it.id })
    }

    @Test
    fun mergeDownRemovesUpperAndSelectsLower() {
        val s = stackOf(1, 2, 3)
        val r = s.mergeDown(3)
        assertNotNull(r)
        assertEquals(3, r!!.first.id)
        assertEquals(2, r.second.id)
        assertEquals(listOf(1, 2), s.layers.map { it.id })
        assertEquals(2, s.activeId)
        assertNull(s.mergeDown(1))
    }

    @Test
    fun duplicateGoesAboveSourceWithFreshId() {
        val s = stackOf(1, 2)
        val d = s.duplicate(1)!!
        assertEquals(listOf(1, d.id, 2), s.layers.map { it.id })
        assertTrue(d.id > 2)
    }

    @Test
    fun blendModeIdsAreStable() {
        assertEquals(0, BlendMode.NORMAL.id)
        assertEquals(1, BlendMode.MULTIPLY.id)
        assertEquals(17, BlendMode.LUMINOSITY.id)
        assertEquals(BlendMode.NORMAL, BlendMode.fromId(999))
    }
}
