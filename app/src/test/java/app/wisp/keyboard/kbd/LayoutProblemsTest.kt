package app.wisp.keyboard.kbd

import org.junit.Assert.*
import org.junit.Test

class LayoutProblemsTest {
    @Test fun goodLayoutHasNoProblem() {
        assertNull(LayoutProblems.check("""{"rows":[["a","b"],["c"]]}"""))
    }

    @Test fun badKeyIsReportedWithItsLine() {
        val text = "{\n  \"rows\": [\n    [\"a\", \"b\"],\n    [\"c\", {\"k\": \"nope\"}]\n  ]\n}"
        val msg = LayoutProblems.check(text)!!
        assertTrue(msg, msg.startsWith("Line 4:"))
        assertTrue(msg, msg.contains("unknown key"))
    }

    @Test fun brokenJsonIsReportedWithItsLine() {
        val msg = LayoutProblems.check("{\n  \"rows\": [\n    [\"a\" \"b\"]\n  ]\n}")!!
        assertTrue(msg, msg.startsWith("Line "))
    }

    @Test fun missingRowsIsExplained() {
        assertTrue(LayoutProblems.check("""{"name":"x"}""")!!.contains("rows"))
    }

    @Test fun multiCharacterKeyIsRefused() {
        assertNotNull(LayoutProblems.check("""{"rows":[["ab"]]}"""))
    }
}
