package com.guhit.studio.engine

import kotlin.math.pow

/** Parameters consumed by the real-time GPU stroke pipeline. Colors are ARGB ints. */
data class BrushSettings(
    val sizePx: Float = 12f, val hardness: Float = 0.9f, val spacing: Float = 0.06f,
    val flow: Float = 1f, val sizePressure: Float = 1f, val flowPressure: Float = 0f,
    val color: Int = 0xFF000000.toInt(), val opacity: Float = 1f, val erase: Boolean = false,
    val streamline: Float = 0.3f,
)

data class BrushPreset(
    val id: String, val name: String, val description: String, val hardness: Float,
    val spacing: Float, val flow: Float, val sizePressure: Float, val flowPressure: Float,
    val streamline: Float, val category: String = "Basic",
) {
    fun toSettings(sizePx: Float, opacity: Float, color: Int, erase: Boolean) = BrushSettings(
        sizePx, hardness, spacing, flow, sizePressure, flowPressure, color, opacity, erase, streamline
    )
}

/**
 * Original Guhit Studio presets inspired by broad digital-painting brush families.
 * These are native, editable presets—not copied proprietary brush files or branded premium assets.
 */
object BrushPresets {
    private fun b(id:String,n:String,d:String,h:Float,s:Float,f:Float,sp:Float,fp:Float,st:Float,c:String="Basic") =
        BrushPreset(id,n,d,h,s,f,sp,fp,st,c)
    val all: List<BrushPreset> = listOf(
        b("ink","Studio Inker","Malinis na tapered ink line",.98f,.035f,1f,.95f,0f,.55f,"Ink"),
        b("gpen","G-Pen","Matapang na comic outline",1f,.04f,1f,1f,0f,.4f,"Ink"),
        b("mapping","Mapping Pen","Napakapinong technical line",1f,.025f,1f,.8f,0f,.65f,"Ink"),
        b("saji","Saji Pen","Bahagyang variable na calligraphy",.92f,.045f,1f,1f,.05f,.3f,"Ink"),
        b("fountain","Fountain Pen","Eleganteng pressure lettering",.9f,.04f,.95f,1f,.1f,.35f,"Ink"),
        b("comic","Comic Pen","Solid comic contour",1f,.055f,1f,.8f,0f,.5f,"Ink"),
        b("pencil","HB Pencil","Tradisyonal na graphite sketch",.42f,.11f,.48f,.65f,.7f,.25f,"Pencil"),
        b("2b","2B Pencil","Mas madilim na malambot na lapis",.3f,.09f,.62f,.8f,.65f,.2f,"Pencil"),
        b("mechanical","Mechanical Pencil","Pantay na precision sketch",.9f,.035f,.8f,.25f,.1f,.6f,"Pencil"),
        b("charcoal","Charcoal","Grainy charcoal-style shading",.18f,.16f,.58f,.55f,.8f,.15f,"Pencil"),
        b("chalk","Soft Chalk","Chalk texture effect via soft low-flow dabs",.22f,.2f,.52f,.4f,.65f,.2f,"Dry"),
        b("crayon","Crayon","Opaque na waxy stroke",.48f,.13f,.82f,.45f,.25f,.2f,"Dry"),
        b("marker","Alcohol Marker","Broad translucent marker",.72f,.035f,.58f,.18f,.2f,.4f,"Marker"),
        b("felt","Felt Tip","Pantay at malinaw na felt tip",.9f,.04f,.9f,.12f,0f,.35f,"Marker"),
        b("brushpen","Brush Pen","Expressive pressure brush lettering",.68f,.045f,1f,1f,.05f,.45f,"Ink"),
        b("watercolor","Watercolor Wash","Transparent layered wash",.08f,.18f,.28f,.45f,.8f,.18f,"Wet"),
        b("watercolor_detail","Watercolor Detail","Mas kontroladong watercolor line",.35f,.07f,.55f,.7f,.45f,.35f,"Wet"),
        b("oil","Oil Paint","Dense painterly opaque stroke",.62f,.12f,.92f,.55f,.15f,.2f,"Paint"),
        b("acrylic","Acrylic","Solid paint coverage",.86f,.075f,.95f,.35f,.05f,.25f,"Paint"),
        b("flat","Flat Brush","Malapad na flat-style stroke",.82f,.08f,.9f,.7f,.05f,.18f,"Paint"),
        b("round","Round Paint","General purpose round painter",.7f,.07f,.88f,.65f,.12f,.25f,"Paint"),
        b("soft","Soft Airbrush","Smooth soft shading",.05f,.12f,.48f,.25f,.9f,.2f,"Airbrush"),
        b("hardair","Hard Airbrush","Defined airbrush edge",.48f,.09f,.35f,.15f,.8f,.2f,"Airbrush"),
        b("spray","Fine Spray","Fine low-flow spray effect",0f,.07f,.2f,.05f,1f,.15f,"Airbrush"),
        b("glow","Glow Brush","Soft luminous buildup",.02f,.1f,.34f,.15f,.85f,.2f,"Airbrush"),
        b("blur","Soft Blend","Soft low-flow blending stroke",.12f,.08f,.22f,.15f,.65f,.25f,"Blend"),
        b("smudge","Smudge","Low-flow smudge-like paint buildup",.25f,.06f,.3f,.25f,.7f,.18f,"Blend"),
        b("pixel","Pixel Brush","Crisp hard-edge pixel marks",1f,.25f,1f,0f,0f,0f,"Special"),
        b("line","Ruler Line","Stable straight technical strokes",1f,.025f,1f,0f,0f,1f,"Special"),
        b("soft_eraser","Soft Eraser","Feathered erasing",.12f,.1f,1f,.45f,.5f,.25f,"Eraser"),
        b("hard_eraser","Hard Eraser","Crisp full-strength erasing",1f,.04f,1f,.2f,0f,.3f,"Eraser"),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

object BrushMath {
    const val MIN_SIZE = 1f
    const val MAX_SIZE = 300f
    fun sizeFromSlider(v: Float): Float = MIN_SIZE * (MAX_SIZE / MIN_SIZE).pow(v.coerceIn(0f, 1f))
}
