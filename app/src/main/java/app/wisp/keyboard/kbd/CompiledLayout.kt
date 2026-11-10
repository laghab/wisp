package app.wisp.keyboard.kbd

/**
 * A layout with geometry applied for one view size, flattened into primitive arrays so drawing never
 * allocates. The geometry itself comes from the Rust core: [rects] are the rectangles to draw.
 */
class CompiledLayout(val spec: LayoutSpec, val width: Float, val height: Float, val rects: FloatArray) {
    val count: Int
    val codes: IntArray
    val kinds: IntArray
    val labels: Array<String>
    val labelsUpper: Array<String>
    /** Extra characters shown when the key is held, or null. */
    val popups: Array<String?>

    init {
        val keys = spec.rows.flatten().filter { !it.spacer }
        count = keys.size
        require(rects.size >= count * 4) { "rects hold ${rects.size / 4} keys, layout has $count" }
        codes = IntArray(count) { keys[it].code }
        kinds = IntArray(count) { keys[it].kind }
        labels = Array(count) { keys[it].label }
        labelsUpper = Array(count) { keys[it].labelUpper }
        popups = Array(count) { keys[it].popups }
    }

    fun centerX(i: Int) = (rects[i * 4] + rects[i * 4 + 2]) / 2f
    fun centerY(i: Int) = (rects[i * 4 + 1] + rects[i * 4 + 3]) / 2f
    fun indexOfCode(code: Int): Int { for (i in 0 until count) if (codes[i] == code) return i; return -1 }
}

/** A [LayoutSpec] as the flat arrays the Rust layout takes. */
class LayoutSlots(spec: LayoutSpec, glide: (KeySpec) -> Boolean) {
    val weights: FloatArray
    /** Per slot: 1 = spacer, 2 = space bar. */
    val flags: IntArray
    val rowLens: IntArray = IntArray(spec.rows.size) { spec.rows[it].size }
    /** Per key: kind (0 plain, 1 backspace, 2 space, 3 shift), 4 = glide may start, bits 8+ = long-press alternates. */
    val keyInfo: IntArray

    init {
        val slots = spec.rows.flatten()
        weights = FloatArray(slots.size) { slots[it].weight }
        flags = IntArray(slots.size) { (if (slots[it].spacer) 1 else 0) or (if (slots[it].code == KeyCodes.SPACE && !slots[it].spacer) 2 else 0) }
        keyInfo = slots.filter { !it.spacer }.map { k ->
            val kind = when (k.code) { KeyCodes.BACKSPACE -> 1; KeyCodes.SPACE -> 2; KeyCodes.SHIFT -> 3; else -> 0 }
            // The switch-keyboard key has a long-press too: it opens the keyboard picker.
            val pops = if (k.code == KeyCodes.SWITCH) 1 else k.popups?.let { it.codePointCount(0, it.length) } ?: 0
            kind or (if (glide(k)) 4 else 0) or (minOf(pops, 255) shl 8)
        }.toIntArray()
    }
}
