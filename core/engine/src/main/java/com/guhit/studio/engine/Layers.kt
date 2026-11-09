package com.guhit.studio.engine

/** Blend modes. `id` is shared with the GLSL shader: never renumber. */
enum class BlendMode(val id: Int, val label: String) {
    NORMAL(0, "Normal"),
    MULTIPLY(1, "Multiply"),
    DARKEN(2, "Darken"),
    COLOR_BURN(3, "Color Burn"),
    LINEAR_BURN(4, "Linear Burn"),
    SCREEN(5, "Screen"),
    LIGHTEN(6, "Lighten"),
    COLOR_DODGE(7, "Color Dodge"),
    ADD(8, "Add"),
    OVERLAY(9, "Overlay"),
    SOFT_LIGHT(10, "Soft Light"),
    HARD_LIGHT(11, "Hard Light"),
    DIFFERENCE(12, "Difference"),
    EXCLUSION(13, "Exclusion"),
    HUE(14, "Hue"),
    SATURATION(15, "Saturation"),
    COLOR(16, "Color"),
    LUMINOSITY(17, "Luminosity"),
    ;

    companion object {
        fun fromId(id: Int): BlendMode = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

data class Layer(
    val id: Int,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.NORMAL,
    val alphaLock: Boolean = false,
    val clip: Boolean = false,
    val locked: Boolean = false,
)

/**
 * Pure-Kotlin layer model (bottom -> top). The view owns one and mirrors every change to the GPU.
 * Always keeps at least one layer; the active layer is always a member of the stack.
 */
class LayerStack(initial: List<Layer> = emptyList(), active: Int = -1) {
    var layers: List<Layer> = initial
        private set
    var activeId: Int = active
        private set
    private var nextId: Int = (initial.maxOfOrNull { it.id } ?: 0) + 1

    init {
        if (layers.isNotEmpty() && layers.none { it.id == activeId }) activeId = layers.last().id
    }

    val active: Layer? get() = layers.firstOrNull { it.id == activeId }

    fun indexOf(id: Int): Int = layers.indexOfFirst { it.id == id }

    fun reset(newLayers: List<Layer>, newActive: Int) {
        layers = newLayers
        nextId = (newLayers.maxOfOrNull { it.id } ?: 0) + 1
        activeId = if (newLayers.any { it.id == newActive }) newActive else (newLayers.lastOrNull()?.id ?: -1)
    }

    /** New empty layer directly above the active one (or on top). It becomes active. */
    fun add(): Layer {
        val layer = Layer(id = nextId++, name = "Layer ${nextId - 1}")
        val at = if (activeId >= 0 && indexOf(activeId) >= 0) indexOf(activeId) + 1 else layers.size
        layers = layers.toMutableList().apply { add(at, layer) }
        activeId = layer.id
        return layer
    }

    /** Copy placed directly above the source; becomes active. */
    fun duplicate(id: Int): Layer? {
        val i = indexOf(id)
        if (i < 0) return null
        val src = layers[i]
        val copy = src.copy(id = nextId++, name = src.name + " (kopya)")
        layers = layers.toMutableList().apply { add(i + 1, copy) }
        activeId = copy.id
        return copy
    }

    /** False if the layer does not exist or is the last one. Active moves to a neighbour. */
    fun remove(id: Int): Boolean {
        val i = indexOf(id)
        if (i < 0 || layers.size <= 1) return false
        layers = layers.toMutableList().apply { removeAt(i) }
        if (activeId == id) activeId = layers[(i - 1).coerceAtLeast(0)].id
        return true
    }

    /** Moves by [delta] positions (+1 = up). Returns the new index, or null if it cannot move. */
    fun move(id: Int, delta: Int): Int? {
        val i = indexOf(id)
        if (i < 0) return null
        val to = i + delta
        if (to < 0 || to >= layers.size || to == i) return null
        val list = layers.toMutableList()
        val l = list.removeAt(i)
        list.add(to, l)
        layers = list
        return to
    }

    /** Removes [id] (the upper layer) into the layer below it. Returns (upper, lower) or null. */
    fun mergeDown(id: Int): Pair<Layer, Layer>? {
        val i = indexOf(id)
        if (i <= 0) return null
        val upper = layers[i]
        val lower = layers[i - 1]
        layers = layers.toMutableList().apply { removeAt(i) }
        activeId = lower.id
        return upper to lower
    }

    fun update(id: Int, transform: (Layer) -> Layer): Layer? {
        val i = indexOf(id)
        if (i < 0) return null
        val updated = transform(layers[i]).copy(id = id)
        layers = layers.toMutableList().apply { set(i, updated) }
        return updated
    }

    fun select(id: Int) {
        if (indexOf(id) >= 0) activeId = id
    }
}
