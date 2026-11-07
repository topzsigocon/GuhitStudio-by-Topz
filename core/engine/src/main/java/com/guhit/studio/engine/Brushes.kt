package com.guhit.studio.engine

import kotlin.math.pow

/** Everything the engine needs for one stroke. Colors are ARGB ints. */
data class BrushSettings(
    val sizePx: Float = 12f,
    val hardness: Float = 0.9f,
    val spacing: Float = 0.06f,
    val flow: Float = 1f,
    val sizePressure: Float = 1f,
    val flowPressure: Float = 0f,
    val color: Int = 0xFF000000.toInt(),
    val opacity: Float = 1f,
    val erase: Boolean = false,
    val streamline: Float = 0.3f,
)

data class BrushPreset(
    val id: String,
    val name: String,
    val description: String,
    val hardness: Float,
    val spacing: Float,
    val flow: Float,
    val sizePressure: Float,
    val flowPressure: Float,
    val streamline: Float,
) {
    fun toSettings(sizePx: Float, opacity: Float, color: Int, erase: Boolean) = BrushSettings(
        sizePx = sizePx,
        hardness = hardness,
        spacing = spacing,
        flow = flow,
        sizePressure = sizePressure,
        flowPressure = flowPressure,
        color = color,
        opacity = opacity,
        erase = erase,
        streamline = streamline,
    )
}

object BrushPresets {
    val all: List<BrushPreset> = listOf(
        BrushPreset("ink", "Panulat", "Matigas at malinis; nagbabago ang kapal ayon sa diin", 0.95f, 0.05f, 1f, 0.9f, 0f, 0.35f),
        BrushPreset("detail", "Pinong Linya", "Manipis na linya para sa detalye at outline", 0.98f, 0.03f, 1f, 1f, 0f, 0.5f),
        BrushPreset("marker", "Marker", "Pantay na kapal, halos solid", 0.8f, 0.04f, 0.85f, 0.15f, 0f, 0.4f),
        BrushPreset("soft", "Malambot na Bilog", "Malabong gilid para sa pagpipinta at shading", 0.15f, 0.08f, 0.5f, 0.5f, 0.5f, 0.25f),
        BrushPreset("air", "Airbrush", "Mahinang pag-spray na dumadami habang inuulit", 0f, 0.05f, 0.12f, 0f, 0.9f, 0.2f),
    )
}

object BrushMath {
    const val MIN_SIZE = 1f
    const val MAX_SIZE = 300f

    /** Slider 0..1 -> brush diameter in canvas px (exponential, fine control at small sizes). */
    fun sizeFromSlider(v: Float): Float = MIN_SIZE * (MAX_SIZE / MIN_SIZE).pow(v.coerceIn(0f, 1f))
}
