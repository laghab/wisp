package app.wisp.keyboard.kbd

/**
 * Constant-time "which key is under this point" lookup. Each grid cell stores the key that contains its
 * centre, or the nearest key when the centre falls in a gap, so a touch between keys still picks a key.
 * Points outside the keyboard return -1.
 */
class KeyGrid(private val layout: CompiledLayout, private val cell: Float = 6f) {
    private val cols = (layout.width / cell).toInt() + 1
    private val rows = (layout.height / cell).toInt() + 1
    private val map = IntArray(cols * rows)

    init {
        val r = layout.rects
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val px = (cx + 0.5f) * cell
            val py = (cy + 0.5f) * cell
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in 0 until layout.count) {
                val o = i * 4
                val dx = if (px < r[o]) r[o] - px else if (px > r[o + 2]) px - r[o + 2] else 0f
                val dy = if (py < r[o + 1]) r[o + 1] - py else if (py > r[o + 3]) py - r[o + 3] else 0f
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = i }
                if (d == 0f) break
            }
            map[cy * cols + cx] = best
        }
    }

    fun keyAt(x: Float, y: Float): Int {
        if (x < 0f || y < 0f || x >= layout.width || y >= layout.height) return -1
        return map[(y / cell).toInt() * cols + (x / cell).toInt()]
    }
}
