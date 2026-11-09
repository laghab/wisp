package app.wisp.keyboard.kbd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryOptionsTest {
    private val spec = LayoutParser.parse("""{"rows":[["q","w","e","r","t","y","u","i","o","p"],[{"k":"symbols"},{"k":"space","w":6},{"k":"enter"}]]}""")

    @Test fun splitLeavesAGapDownTheMiddleOfLetterRowsOnly() {
        val plain = CompiledLayout(spec, 1000f, 200f, 0f, 0f, 0f)
        val split = CompiledLayout(spec, 1000f, 200f, 0f, 0f, 0f, 0f, 100f)
        // key 4 (t) ends the left half; key 5 (y) starts the right half
        val gap = split.rects[5 * 4] - split.rects[4 * 4 + 2]
        assertEquals(100f, gap, 0.01f)
        assertEquals(0f, plain.rects[5 * 4] - plain.rects[4 * 4 + 2], 0.01f)
        assertEquals(1000f, split.rects[9 * 4 + 2], 0.01f)          // the row still ends at the edge
        // the space-bar row is not split
        val bottom = 10
        assertEquals(0f, split.rects[(bottom + 1) * 4] - split.rects[bottom * 4 + 2], 0.01f)
    }

    @Test fun oneHandedPaddingKeepsKeysOnOneSide() {
        val l = CompiledLayout(spec, 1000f, 200f, 0f, 0f, 0f, 200f)
        assertEquals(0f, l.rects[0], 0.01f)
        assertEquals(800f, l.rects[9 * 4 + 2], 0.01f)
    }

    @Test fun numberRowAddsDigitsOnceAndKeepsTheSwitchKeyGuarantee() {
        val with = LayoutGuard.withNumberRow(spec)
        assertEquals(spec.rows.size + 1, with.rows.size)
        assertEquals("1234567890", with.rows.first().joinToString("") { it.label })
        assertTrue(LayoutGuard.withNumberRow(with) === with)
        assertTrue(LayoutGuard.ensureSwitchKey(with).rows.any { r -> r.any { it.code == KeyCodes.SWITCH } })
    }
}
