package app.wisp.keyboard.kbd

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDocTest {
    private fun docs(): File = listOf(File("../docs/layouts.md"), File("docs/layouts.md")).first { it.isFile }

    @Test fun theSampleInTheDocLoads() {
        val md = docs().readText()
        val sample = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(md)!!.groupValues[1]
        val spec = LayoutGuard.ensureSwitchKey(LayoutParser.parse(sample))
        assertEquals("example", spec.name)
        assertEquals(4, spec.rows.size)
        assertTrue(spec.rows.flatten().any { it.code == KeyCodes.SWITCH })
    }
}
