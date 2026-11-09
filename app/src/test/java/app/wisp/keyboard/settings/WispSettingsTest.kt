package app.wisp.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WispSettingsTest {
    private val file: File = File(Files.createTempDirectory("wisp-settings").toFile().also { it.deleteOnExit() }, "s.properties")

    @Test fun missingKeysGiveTheDefault() {
        val s = WispSettings(file)
        assertEquals(7, s.getInt("n", 7)); assertTrue(s.getBoolean("b", true)); assertNull(s.getString("x")); assertEquals(5L, s.getLong("l", 5L)); assertTrue(s.getStringSet("set").isEmpty())
    }

    @Test fun valuesRoundTrip() {
        val s = WispSettings(file)
        s.putInt("n", 3); s.putBoolean("b", false); s.putString("name", "Wisp"); s.putLong("l", 9_000_000_000L); s.putStringSet("apps", setOf("a.b", "c.d"))
        assertEquals(3, s.getInt("n", 0)); assertFalse(s.getBoolean("b", true)); assertEquals("Wisp", s.getString("name")); assertEquals(9_000_000_000L, s.getLong("l", 0)); assertEquals(setOf("a.b", "c.d"), s.getStringSet("apps"))
    }

    @Test fun anotherProcessSeesTheChange() {
        val a = WispSettings(file); val b = WispSettings(file)      // two "processes"
        a.putInt("voice_idle_minutes", 5)
        assertEquals(5, b.getInt("voice_idle_minutes", 3))
        b.putInt("voice_idle_minutes", 1)
        assertEquals(1, a.getInt("voice_idle_minutes", 3))
    }

    @Test fun aWriteKeepsTheOtherKeys() {
        val a = WispSettings(file); val b = WispSettings(file)
        a.putString("x", "1"); b.putString("y", "2")
        assertEquals("1", a.getString("x")); assertEquals("2", a.getString("y"))
    }

    @Test fun removeDeletesTheKey() { val s = WispSettings(file); s.putString("k", "v"); s.remove("k"); assertNull(s.getString("k")) }

    @Test fun aDamagedFileReadsAsEmpty() { file.writeBytes(byteArrayOf(0x5c, 0x75, 0x00, 0x7f)); assertEquals(4, WispSettings(file).getInt("n", 4)) }

    @Test fun unparsableNumbersGiveTheDefault() { val s = WispSettings(file); s.putString("n", "abc"); assertEquals(2, s.getInt("n", 2)) }
}
