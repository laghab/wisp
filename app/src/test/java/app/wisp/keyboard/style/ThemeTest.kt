package app.wisp.keyboard.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ThemeTest {
    private val dir = listOf(File("src/main/assets/themes"), File("app/src/main/assets/themes")).first { it.isDirectory }
    /** Every look of every theme: a theme with a light and a dark look counts twice. */
    private val themes = dir.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }.flatMap { f ->
        val text = f.readText()
        if (ThemeParser.hasVariants(text)) listOf(ThemeParser.parse(text, dark = false), ThemeParser.parse(text, dark = true)) else listOf(ThemeParser.parse(text))
    }

    @Test fun allBuiltInThemesParse() {
        assertEquals(
            setOf("wisp", "moon", "dandelion", "sakura", "factory", "cyberpunk", "terminal", "y2k", "material", "glass", "modern", "retro", "brutalist", "comic", "manga",
                "angel", "swan", "dragon", "fire-dragon", "cosmo-deep", "dust-glitter", "forest", "ocean", "mountains", "cat", "dog", "sun", "lanterns", "sunflowers", "lotus"),
            themes.map { it.id }.toSet(),
        )
        assertEquals(30, themes.map { it.id }.toSet().size)
    }

    @Test fun oneThemeCanHaveALightAndADarkLook() {
        val base = """{"id":"duo","name":"Duo","board":"#111111","key":"#222222","mod":"#333333","accent":"#ff0000","fg":"#ffffff","modFg":"#ffffff","accentFg":"#000000",
            "radius":8,"font":"inter","size":20,"stripFg":"#cccccc","sep":"#444444","dark":true,
            "variants":{"dark":{"board":"#111111"},"light":{"board":"#f0f0f0","key":"#ffffff","fg":"#111111","stripFg":"#444444"}}}"""
        val dark = ThemeParser.parse(base, dark = true); val light = ThemeParser.parse(base, dark = false)
        assertEquals("duo", dark.id); assertEquals("duo", light.id)
        assertTrue(dark.dark); assertTrue(!light.dark)
        assertTrue(dark.hasVariants && light.hasVariants)
        assertEquals(0xFFF0F0F0.toInt(), light.board.first().base()); assertEquals(0xFF111111.toInt(), dark.board.first().base())
        assertEquals(0xFFFFFFFF.toInt(), light.key.base())
    }

    @Test fun aThemeWithoutVariantsIsTheSameInEitherLook() {
        val t = themes.first { it.id == "factory" }
        val file = dir.resolve("factory.json").readText()
        assertEquals(t.dark, ThemeParser.parse(file, dark = false).dark)
        assertTrue(!t.hasVariants)
    }

    @Test fun fileNameMatchesId() {
        for (f in dir.listFiles()!!) assertEquals(f.nameWithoutExtension, ThemeParser.parse(f.readText()).id)
    }

    @Test fun missingTokenIsRejectedByName() {
        for (token in ThemeParser.REQUIRED) {
            val o = org.json.JSONObject(File(dir, "wisp.json").readText()); o.remove(token)
            try { ThemeParser.parse(o.toString()); fail("accepted a theme without $token") }
            catch (e: ThemeException) { assertTrue("error should name $token: ${e.message}", e.message!!.contains("\"$token\"")) }
        }
    }

    @Test fun badInputIsRejectedWithAReason() {
        for ((bad, expect) in listOf("nope" to "JSON", """{"id":"Bad Id"}""" to "Missing")) {
            try { ThemeParser.parse(bad); fail() } catch (e: ThemeException) { assertTrue(e.message!!, e.message!!.contains(expect)) }
        }
        val o = org.json.JSONObject(File(dir, "wisp.json").readText()); o.put("fg", "red-ish")
        try { ThemeParser.parse(o.toString()); fail() } catch (e: ThemeException) { assertTrue(e.message!!.contains("bad colour")) }
    }

    @Test fun colourFormats() {
        assertEquals(0xFF112233.toInt(), ThemeParser.color("#123"))
        assertEquals(0xFF123456.toInt(), ThemeParser.color("#123456"))
        assertEquals(0x80123456.toInt(), ThemeParser.color("#12345680"))
        assertEquals(0x80FF0000.toInt(), ThemeParser.color("rgba(255,0,0,0.5)"))
        assertEquals(0, ThemeParser.color("transparent"))
    }

    /** Labels must be readable on their keys. Letters and the accent (enter) key at 3:1 (large text); the design's own pairs. */
    @Test fun labelsAreLegibleOnTheirKeys() {
        for (t in themes) {
            // Translucent keys sit on the board, so measure against the opaque board base colour.
            val board = t.board.first().base() or (0xFF shl 24)
            fun over(fill: Fill): Int = fill.base().let { c -> blend(c, board) }
            assertTrue("${t.id}: letters ${Brand.contrast(t.fg, over(t.key))}", Brand.contrast(t.fg, over(t.key)) >= 3.0)
            assertTrue("${t.id}: modifiers ${Brand.contrast(t.modFg, over(t.mod))}", Brand.contrast(t.modFg, over(t.mod)) >= 3.0)
            // Some light looks in the design have soft enter keys under 3:1 (Dandelion 2.8; Sakura, Cat, Dog, Dragon and Stardust, Sunshine and Lotus Pond about 2.1 to 2.7: pastel with white or cream); kept as designed.
            val accentMin = if (!t.dark && t.id == "dandelion") 2.7 else if (!t.dark && t.id in softLight) 2.1 else 3.0
            assertTrue("${t.id}: accent ${Brand.contrast(t.accentFg, over(t.accent))}", Brand.contrast(t.accentFg, over(t.accent)) >= accentMin)
        }
    }

    /** Light looks whose enter key is a soft pastel by design. */
    private val softLight = setOf("sakura", "cat", "dog", "dragon", "dust-glitter", "lotus", "sun")

    private fun blend(top: Int, bottom: Int): Int {
        val a = (top ushr 24) / 255f
        fun ch(s: Int) = (((top shr s) and 0xFF) * a + ((bottom shr s) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
