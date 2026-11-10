package app.wisp.keyboard.style

/**
 * Wisp's colours and type in one place: the keyboard, the voice ribbon and the settings app all draw from these values, so the
 * look cannot drift apart. Plain integers (ARGB) so they can be checked without Android; see docs/brand.md.
 *
 * The motif is the ribbon: one soft, flowing line (the swipe trail, the voice level, the icon) in the accent colour on deep plum.
 */
object Brand {
    /** The colours of one theme. */
    class Tones(
        val background: Int, val key: Int, val keyPressed: Int, val special: Int, val text: Int, val textMuted: Int,
        /** Settings-app surfaces. */
        val surface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    )

    const val CORAL = 0xFFFF7A66.toInt()
    const val PLUM = 0xFF2A2230.toInt()
    const val INK = 0xFF241A1E.toInt()
    /** The "recording" dot in the voice panel. */
    const val RECORDING = 0xFFE5484D.toInt()

    val DARK = Tones(
        background = PLUM, key = 0xFF3B3144.toInt(), keyPressed = 0xFF54475F.toInt(), special = 0xFF332A3B.toInt(),
        text = 0xFFF3ECE6.toInt(), textMuted = 0xFFB8ABBE.toInt(),
        surface = 0xFF352B3D.toInt(), surfaceVariant = 0xFF433849.toInt(), onSurfaceVariant = 0xFFCDBFD0.toInt(),
    )
    val LIGHT = Tones(
        background = 0xFFEFE7EC.toInt(), key = 0xFFFFFFFF.toInt(), keyPressed = 0xFFDCCFD8.toInt(), special = 0xFFE1D6DE.toInt(),
        text = INK, textMuted = 0xFF6B5A63.toInt(),
        surface = 0xFFFFFFFF.toInt(), surfaceVariant = 0xFFF5E4E0.toInt(), onSurfaceVariant = 0xFF55454A.toInt(),
    )

    /** Accent colours the user can pick (plus "dynamic" on Android 12+). */
    val ACCENTS = linkedMapOf("coral" to CORAL, "rose" to 0xFFE85D8A.toInt(), "violet" to 0xFF9B7BFF.toInt(), "blue" to 0xFF4C9BFF.toInt(), "teal" to 0xFF2DBFA8.toInt(), "green" to 0xFF6CC24A.toInt(), "amber" to 0xFFFFB02E.toInt())

    /** The accent as it is drawn on [background]: darkened step by step until it reads (3:1) as text or a thin line and its label reads on it (4.5:1). */
    fun accentOn(accent: Int, background: Int): Int {
        var a = accent
        var n = 0
        while ((contrast(a, background) < 3.0 || contrast(onAccent(a), a) < 4.5) && n++ < 14) a = mix(a, INK, 0.08f)
        return a
    }

    fun mixColours(a: Int, b: Int, t: Float): Int = mix(a, b, t)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Dark or light text for use on top of [accent]. */
    fun onAccent(accent: Int): Int = if (contrast(accent, INK) >= contrast(accent, 0xFFFFFFFF.toInt())) INK else 0xFFFFFFFF.toInt()

    // Type: one family (the system's), a few weights. Sizes in sp for the settings app; the keys draw their own sizes in dp.
    const val KEY_LETTER_SP = 22f
    const val KEY_LABEL_SP = 15f
    const val BAR_TEXT_SP = 17f

    // ---- WCAG contrast, used by the brand checks
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }
    fun contrast(a: Int, b: Int): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }
}
