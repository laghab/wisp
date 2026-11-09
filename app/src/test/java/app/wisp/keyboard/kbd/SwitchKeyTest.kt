package app.wisp.keyboard.kbd

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchKeyTest {
    private fun hasSwitch(spec: LayoutSpec) = spec.rows.any { r -> r.any { !it.spacer && it.code == KeyCodes.SWITCH } }
    private fun switchCount(spec: LayoutSpec) = spec.rows.sumOf { r -> r.count { !it.spacer && it.code == KeyCodes.SWITCH } }

    @Test fun layoutWithoutSwitchKeyGetsOne() {
        val spec = LayoutParser.parse("""{"rows":[["a","b"],[{"k":"symbols"},{"k":"space","w":5},{"k":"enter"}]]}""")
        assertTrue(!hasSwitch(spec))
        val guarded = LayoutGuard.ensureSwitchKey(spec)
        assertEquals(1, switchCount(guarded))
        // the new key sits on the bottom row and the space bar gave up one unit of width
        val bottom = guarded.rows.last()
        assertEquals(4f, bottom.first { it.code == KeyCodes.SPACE }.weight, 0.001f)
    }

    @Test fun layoutThatHasOneIsUnchanged() {
        val spec = LayoutParser.parse("""{"rows":[["a"],[{"k":"switch"},{"k":"space","w":5}]]}""")
        assertTrue(LayoutGuard.ensureSwitchKey(spec) === spec)
    }

    @Test fun singleKeyRowsStillGetOne() {
        val spec = LayoutParser.parse("""{"rows":[["a"]]}""")
        assertEquals(1, switchCount(LayoutGuard.ensureSwitchKey(spec)))
    }

    /** Every layout shipped in res/raw must come out of the guard with exactly one switch key. */
    @Test fun everyShippedLayoutHasTheSwitchKey() {
        val dir = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.startsWith("layout_") && f.name.endsWith(".json") }!!
        assertTrue("no layouts found", files.isNotEmpty())
        for (f in files) {
            val guarded = LayoutGuard.ensureSwitchKey(LayoutParser.parse(f.readText()))
            assertEquals("${f.name} must have exactly one switch key", 1, switchCount(guarded))
        }
    }
}
