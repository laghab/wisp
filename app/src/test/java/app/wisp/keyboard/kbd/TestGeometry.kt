package app.wisp.keyboard.kbd

/** Plain even-weight key geometry for tests that need key positions on the JVM. The shipped geometry is Rust (`core/wisp-core/src/layout.rs`). */
object TestGeometry {
    fun compile(spec: LayoutSpec, width: Float, height: Float, padX: Float, padTop: Float, padBottom: Float): CompiledLayout {
        val keys = spec.rows.flatten().count { !it.spacer }
        val rects = FloatArray(keys * 4)
        val rowH = (height - padTop - padBottom) / spec.rows.size
        var idx = 0
        for ((r, row) in spec.rows.withIndex()) {
            val unit = (width - 2 * padX) / row.sumOf { it.weight.toDouble() }.toFloat()
            var x = padX
            for (k in row) {
                val w = k.weight * unit
                if (!k.spacer) {
                    val o = idx++ * 4
                    rects[o] = x; rects[o + 1] = padTop + r * rowH; rects[o + 2] = x + w; rects[o + 3] = padTop + (r + 1) * rowH
                }
                x += w
            }
        }
        return CompiledLayout(spec, width, height, rects)
    }
}
