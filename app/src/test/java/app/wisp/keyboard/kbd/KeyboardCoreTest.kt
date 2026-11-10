package app.wisp.keyboard.kbd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyboardCoreTest {

    private val json = """
        {"name":"t","rows":[
          ["q","w","e","r","t","y","u","i","o","p"],
          [{"pad":0.5},"a","s","d","f","g","h","j","k","l",{"pad":0.5}],
          [{"k":"shift","w":1.5},"z","x","c","v","b","n","m",{"k":"backspace","w":1.5}],
          [{"k":"symbols","w":1.5},{"k":"comma"},{"k":"space","w":5},{"k":"period"},{"k":"enter","w":1.5}]
        ]}
    """.trimIndent()

    private fun compiled(w: Float = 1080f, h: Float = 600f) =
        TestGeometry.compile(LayoutParser.parse(json), w, h, 8f, 12f, 16f)

    @Test fun parsesQwertyShape() {
        val l = compiled()
        assertEquals(10 + 9 + 9 + 5, l.count)
        assertEquals('q'.code, l.codes[0])
        assertEquals("Q", l.labelsUpper[0])
        assertTrue(l.codes.contains(KeyCodes.SPACE))
    }

    @Test fun rejectsBadLayouts() {
        for (bad in listOf("nope", """{"rows":[]}""", """{"rows":[["ab"]]}""", """{"rows":[[{"k":"zzz"}]]}""", """{"rows":[[{"pad":1}]]}""")) {
            try { LayoutParser.parse(bad); fail("should reject: $bad") } catch (e: LayoutException) { /* expected */ }
        }
    }
}
