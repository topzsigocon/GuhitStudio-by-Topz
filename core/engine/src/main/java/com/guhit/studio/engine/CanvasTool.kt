package com.guhit.studio.engine

/** What a touch on the canvas does. (The eraser is a DRAW tool with the brush's erase flag.) */
enum class CanvasTool { DRAW, EYEDROPPER, FILL }

/** Recently used colors: newest first, no duplicates, limited size. Pure functions (unit tested). */
object ColorHistory {
    const val MAX = 12

    fun push(list: List<Int>, color: Int, max: Int = MAX): List<Int> {
        val rgb = color or (0xFF shl 24)
        return (listOf(rgb) + list.filter { (it or (0xFF shl 24)) != rgb }).take(max)
    }

    fun encode(list: List<Int>): String = list.joinToString(",")

    fun decode(text: String?): List<Int> =
        text?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.take(MAX) ?: emptyList()
}
