package app.wisp.keyboard.style

import org.junit.Assert.assertTrue
import org.junit.Test

/** The brand checklist's legibility rules (docs/brand.md), as tests, for both themes and every accent. */
class BrandTest {
    private fun ok(label: String, fg: Int, bg: Int, min: Double) {
        val c = Brand.contrast(fg, bg)
        assertTrue("$label contrast %.2f is below %.1f".format(c, min), c >= min)
    }

    @Test fun keyLabelsAreReadableInBothThemes() {
        for ((name, t) in listOf("dark" to Brand.DARK, "light" to Brand.LIGHT)) {
            ok("$name text on key", t.text, t.key, 7.0)
            ok("$name text on background", t.text, t.background, 7.0)
            ok("$name muted label on special key", t.textMuted, t.special, 4.5)
            ok("$name muted label on background", t.textMuted, t.background, 4.5)
            ok("$name settings secondary text", t.onSurfaceVariant, t.surfaceVariant, 4.5)
        }
    }

    @Test fun theEnterKeyLabelIsReadableOnEveryAccent() {
        for ((name, accent) in Brand.ACCENTS) ok("label on $name", Brand.onAccent(accent), accent, 4.5)
    }

    @Test fun theAccentStandsOutFromTheKeyboardSurface() {
        for ((name, accent) in Brand.ACCENTS) {
            ok("$name accent on dark background", Brand.accentOn(accent, Brand.DARK.background), Brand.DARK.background, 3.0)
            ok("$name accent on light background", Brand.accentOn(accent, Brand.LIGHT.background), Brand.LIGHT.background, 3.0)
            ok("label on $name accent in light theme", Brand.onAccent(Brand.accentOn(accent, Brand.LIGHT.background)), Brand.accentOn(accent, Brand.LIGHT.background), 4.5)
        }
    }
}
