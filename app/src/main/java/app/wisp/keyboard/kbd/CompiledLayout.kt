package app.wisp.keyboard.kbd

/**
 * A layout with geometry applied for one view size, flattened into primitive arrays so drawing and hit
 * testing never allocate. Rectangles are the full touch areas (gaps included); the view insets them
 * when drawing.
 */
class CompiledLayout(
    val spec: LayoutSpec,
    val width: Float,
    val height: Float,
    padX: Float,
    padTop: Float,
    padBottom: Float,
    padRight: Float = padX,
    /** Extra empty space down the middle of every row of letters (split keyboard). */
    splitGap: Float = 0f,
) {
    val count: Int
    val rects: FloatArray      // left, top, right, bottom per key
    val codes: IntArray
    val kinds: IntArray
    val labels: Array<String>
    val labelsUpper: Array<String>
    /** Extra characters shown when the key is held, or null. */
    val popups: Array<String?>

    init {
        var n = 0
        for (row in spec.rows) for (k in row) if (!k.spacer) n++
        count = n
        rects = FloatArray(n * 4)
        codes = IntArray(n)
        kinds = IntArray(n)
        val l = arrayOfNulls<String>(n)
        val u = arrayOfNulls<String>(n)
        val pops = arrayOfNulls<String>(n)

        val rowH = (height - padTop - padBottom) / spec.rows.size
        var idx = 0
        for ((r, row) in spec.rows.withIndex()) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val splitHere = splitGap > 0f && row.none { it.code == KeyCodes.SPACE } && total >= 8f
            val unit = (width - padX - padRight - (if (splitHere) splitGap else 0f)) / total
            var x = padX
            var seen = 0f
            var gapDone = false
            val top = padTop + r * rowH
            for (k in row) {
                if (splitHere && !gapDone && seen >= total / 2f - 0.01f) { x += splitGap; gapDone = true }
                seen += k.weight
                val w = k.weight * unit
                if (!k.spacer) {
                    val o = idx * 4
                    rects[o] = x; rects[o + 1] = top; rects[o + 2] = x + w; rects[o + 3] = top + rowH
                    codes[idx] = k.code; kinds[idx] = k.kind
                    l[idx] = k.label; u[idx] = k.labelUpper; pops[idx] = k.popups
                    idx++
                }
                x += w
            }
        }
        @Suppress("UNCHECKED_CAST")
        labels = l as Array<String>
        @Suppress("UNCHECKED_CAST")
        labelsUpper = u as Array<String>
        popups = pops
    }

    fun centerX(i: Int) = (rects[i * 4] + rects[i * 4 + 2]) / 2f
    fun centerY(i: Int) = (rects[i * 4 + 1] + rects[i * 4 + 3]) / 2f
    fun indexOfCode(code: Int): Int { for (i in 0 until count) if (codes[i] == code) return i; return -1 }
}
