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
    val category: String = "Basic",
    val premium: Boolean = false,
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
    // Original GuhitStudio presets inspired by common digital-painting workflows.
    // No proprietary brush assets or presets are copied from other applications.
    val all: List<BrushPreset> = listOf(
        BrushPreset("ink", "Studio G-Pen", "Malinis na anime line art na may pressure taper", .98f,.035f,1f,1f,0f,.55f,"Ink"),
        BrushPreset("detail", "Technical Liner", "Pantay at matalas na technical outline", 1f,.025f,1f,.05f,0f,.7f,"Ink"),
        BrushPreset("mapping", "Mapping Pen", "Manipis na flexible nib para sa manga", .96f,.04f,1f,.85f,0f,.48f,"Ink"),
        BrushPreset("brush_pen", "Real Brush Pen", "Calligraphy-like thick and thin strokes", .82f,.07f,.95f,.9f,.05f,.35f,"Ink",true),
        BrushPreset("comic", "Comic Inker", "Bold comic contours", 1f,.045f,1f,.65f,0f,.42f,"Ink",true),
        BrushPreset("pencil", "HB Graphite", "Natural sketch pencil", .48f,.09f,.58f,.8f,.65f,.25f,"Pencil"),
        BrushPreset("2b", "Soft 2B Pencil", "Malambot at madilim na graphite", .32f,.12f,.7f,.9f,.7f,.2f,"Pencil"),
        BrushPreset("mechanical", "Mechanical Pencil", "Consistent precision sketch", .9f,.035f,.82f,.1f,0f,.65f,"Pencil"),
        BrushPreset("charcoal", "Charcoal", "Grainy expressive charcoal shading", .2f,.18f,.65f,.7f,.65f,.18f,"Pencil",true),
        BrushPreset("chalk", "Soft Chalk", "Dry chalk texture", .28f,.22f,.62f,.4f,.55f,.2f,"Texture",true),
        BrushPreset("marker", "Alcohol Marker", "Layerable marker with broad nib feel", .72f,.045f,.72f,.12f,.15f,.4f,"Marker"),
        BrushPreset("chisel", "Chisel Marker", "Broad angled marker effect", .85f,.055f,.85f,.08f,0f,.35f,"Marker",true),
        BrushPreset("fineliner", "Fineliner", "Even opaque writing and details", .98f,.03f,1f,.05f,0f,.6f,"Ink"),
        BrushPreset("soft", "Soft Round", "Smooth air-soft shading", .12f,.1f,.7f,.45f,.7f,.3f,"Paint"),
        BrushPreset("air", "Airbrush Pro", "Soft spray-like gradients", 0f,.09f,.24f,.15f,.95f,.25f,"Paint",true),
        BrushPreset("hard_round", "Hard Round", "Opaque clean painting", .98f,.045f,1f,.5f,.1f,.25f,"Paint"),
        BrushPreset("watercolor", "Watercolor Wash", "Translucent flowing wash", .18f,.14f,.38f,.55f,.85f,.18f,"Wet Media",true),
        BrushPreset("watercolor_detail", "Watercolor Detail", "Controlled watercolor edges", .55f,.08f,.58f,.7f,.45f,.35f,"Wet Media",true),
        BrushPreset("oil", "Oil Paint", "Dense painterly opaque strokes", .72f,.16f,.82f,.55f,.15f,.2f,"Wet Media",true),
        BrushPreset("acrylic", "Acrylic", "Bold opaque paint", .88f,.1f,.9f,.45f,.1f,.25f,"Wet Media"),
        BrushPreset("gouache", "Gouache", "Matte opaque illustration paint", .82f,.12f,.82f,.5f,.15f,.25f,"Wet Media",true),
        BrushPreset("dry", "Dry Brush", "Broken bristled paint texture", .42f,.24f,.7f,.55f,.4f,.15f,"Texture",true),
        BrushPreset("flat", "Flat Brush", "Broad flat-color strokes", .9f,.08f,.9f,.25f,0f,.3f,"Paint"),
        BrushPreset("round_brush", "Round Bristle", "Textured rounded painterly stroke", .62f,.17f,.78f,.5f,.2f,.2f,"Texture",true),
        BrushPreset("spray", "Particle Spray", "Scattered spray-like marks", .05f,.32f,.48f,.1f,.8f,.15f,"Effects",true),
        BrushPreset("cloud", "Cloud Texture", "Soft atmospheric texture", .08f,.2f,.3f,.2f,.8f,.2f,"Effects",true),
        BrushPreset("leaf", "Foliage Dab", "Organic foliage-like dab strokes", .35f,.28f,.72f,.3f,.3f,.15f,"Texture",true),
        BrushPreset("glow", "Neon Glow", "Soft luminous line base", .15f,.07f,.72f,.55f,.3f,.4f,"Effects",true),
        BrushPreset("pixel", "Pixel Hard", "Crisp hard-edged pixel-like marks", 1f,.025f,1f,0f,0f,.8f,"Pixel"),
        BrushPreset("smudge", "Blender Soft", "Low-flow blending-style brush", .1f,.08f,.22f,.35f,.8f,.3f,"Blender",true),
        BrushPreset("eraser_soft", "Soft Eraser", "Feathered erasing", .12f,.1f,.8f,.35f,.4f,.3f,"Eraser"),
        BrushPreset("eraser_hard", "Hard Eraser", "Crisp precise erasing", 1f,.04f,1f,.1f,0f,.55f,"Eraser"),
    )
}

object BrushMath {
    const val MIN_SIZE = 1f
    const val MAX_SIZE = 300f

    /** Slider 0..1 -> brush diameter in canvas px (exponential, fine control at small sizes). */
    fun sizeFromSlider(v: Float): Float = MIN_SIZE * (MAX_SIZE / MIN_SIZE).pow(v.coerceIn(0f, 1f))
}
