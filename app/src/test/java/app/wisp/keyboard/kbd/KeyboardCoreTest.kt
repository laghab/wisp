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
        CompiledLayout(LayoutParser.parse(json), w, h, 8f, 12f, 16f)

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

    @Test fun rowsFillWidthWithoutOverlap() {
        val l = compiled()
        for (i in 0 until l.count) {
            assertTrue(l.rects[i * 4] >= 8f - 0.01f)
            assertTrue(l.rects[i * 4 + 2] <= 1080f - 8f + 0.01f)
        }
    }

    @Test fun gridFindsKeysAndNearestInGaps() {
        val l = compiled()
        val g = KeyGrid(l)
        val q = l.indexOfCode('q'.code)
        assertEquals(q, g.keyAt(l.centerX(q), l.centerY(q)))
        // The shared edge of q and w belongs to one of them, never to nothing.
        val edge = l.rects[q * 4 + 2]
        val k = g.keyAt(edge, l.centerY(q))
        assertTrue(k == q || k == l.indexOfCode('w'.code))
        assertEquals(-1, g.keyAt(-1f, 10f))
        assertEquals(-1, g.keyAt(2000f, 10f))
    }

    private class Rec : KeyTouchTracker.Sink {
        var pressed = 0; var released = 0; var tapped = 0; var lastTap = -1
        override fun pressed(key: Int) { pressed++ }
        override fun released(key: Int) { released++ }
        override fun tapped(key: Int, x: Float, y: Float) { tapped++; lastTap = key }
    }

    @Test fun tapOnSameKeyCommits() {
        val l = compiled(); val rec = Rec(); val t = KeyTouchTracker(KeyGrid(l), rec)
        val a = l.indexOfCode('a'.code)
        t.onDown(0, l.centerX(a), l.centerY(a)); t.onUp(0, l.centerX(a), l.centerY(a))
        assertEquals(1, rec.tapped); assertEquals(a, rec.lastTap)
        assertEquals(rec.pressed, rec.released)
    }

    @Test fun slidingOffTheKeyDoesNotCommit() {
        val l = compiled(); val rec = Rec(); val t = KeyTouchTracker(KeyGrid(l), rec)
        val a = l.indexOfCode('a'.code); val s = l.indexOfCode('s'.code)
        t.onDown(0, l.centerX(a), l.centerY(a)); t.onMove(0, l.centerX(s), l.centerY(s)); t.onUp(0, l.centerX(s), l.centerY(s))
        assertEquals(0, rec.tapped)
        assertEquals(rec.pressed, rec.released)
    }

    @Test fun twoFingersTrackIndependently() {
        val l = compiled(); val rec = Rec(); val t = KeyTouchTracker(KeyGrid(l), rec)
        val a = l.indexOfCode('a'.code); val s = l.indexOfCode('s'.code)
        t.onDown(0, l.centerX(a), l.centerY(a)); t.onDown(1, l.centerX(s), l.centerY(s))
        t.onUp(0, l.centerX(a), l.centerY(a)); t.onUp(1, l.centerX(s), l.centerY(s))
        assertEquals(2, rec.tapped)
    }

    @Test fun cancelReleasesWithoutCommit() {
        val l = compiled(); val rec = Rec(); val t = KeyTouchTracker(KeyGrid(l), rec)
        val a = l.indexOfCode('a'.code)
        t.onDown(0, l.centerX(a), l.centerY(a)); t.cancelAll()
        assertEquals(0, rec.tapped); assertEquals(1, rec.released)
    }

    @Test fun touchHandlingDoesNotAllocate() {
        // java.lang.management is not on the Android compile classpath but exists on the test JVM.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val getBytes = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        fun bytes(): Long = getBytes.invoke(bean, id) as Long
        val l = compiled(); val rec = Rec(); val t = KeyTouchTracker(KeyGrid(l), rec)
        val a = l.indexOfCode('a'.code); val s = l.indexOfCode('s'.code)
        val ax = l.centerX(a); val ay = l.centerY(a); val sx = l.centerX(s); val sy = l.centerY(s)
        fun burst(n: Int) { repeat(n) {
            t.onDown(0, ax, ay); t.onMove(0, sx, sy); t.onMove(0, ax, ay); t.onUp(0, ax, ay)
        } }
        burst(20_000) // warm up the JIT
        val b0 = bytes(); val b1 = bytes()
        val baseline = b1 - b0
        val c0 = bytes(); burst(50_000); val c1 = bytes()
        val used = (c1 - c0) - baseline
        assertTrue("touch path allocated $used bytes over 50k gestures", used <= 512)
    }
}

class SpaceSlideTest {
    private val json = """{"name":"t","rows":[["a","b"],[{"k":"space","w":4}]]}"""

    private class Rec : KeyTouchTracker.Sink {
        var steps = 0; var tapped = 0; var pressed = 0; var released = 0
        override fun pressed(key: Int) { pressed++ }
        override fun released(key: Int) { released++ }
        override fun tapped(key: Int, x: Float, y: Float) { tapped++ }
        override fun slid(steps: Int) { this.steps += steps }
    }

    private fun setup(): Triple<CompiledLayout, Rec, KeyTouchTracker> {
        val l = CompiledLayout(LayoutParser.parse(json), 400f, 200f, 0f, 0f, 0f)
        val rec = Rec()
        return Triple(l, rec, KeyTouchTracker(KeyGrid(l), rec, l.indexOfCode(KeyCodes.SPACE), 20f, 10f))
    }

    @Test fun dragOnSpaceMovesCursorAndDoesNotType() {
        val (l, rec, t) = setup(); val sp = l.indexOfCode(KeyCodes.SPACE); val y = l.centerY(sp); val x0 = l.centerX(sp)
        t.onDown(0, x0, y)
        t.onMove(0, x0 + 15f, y)       // below the start threshold: nothing yet
        assertEquals(0, rec.steps)
        t.onMove(0, x0 + 40f, y)       // crosses threshold, then counts steps from there
        t.onMove(0, x0 + 70f, y)       // 30px further = 3 steps
        t.onUp(0, x0 + 70f, y)
        assertEquals(3, rec.steps)
        assertEquals(0, rec.tapped)
        assertEquals(rec.pressed, rec.released)
    }

    @Test fun dragLeftGivesNegativeSteps() {
        val (l, rec, t) = setup(); val sp = l.indexOfCode(KeyCodes.SPACE); val y = l.centerY(sp); val x0 = l.centerX(sp)
        t.onDown(0, x0, y); t.onMove(0, x0 - 40f, y); t.onMove(0, x0 - 60f, y); t.onUp(0, x0 - 60f, y)
        assertEquals(-2, rec.steps)
    }

    @Test fun smallMovementStillTaps() {
        val (l, rec, t) = setup(); val sp = l.indexOfCode(KeyCodes.SPACE); val y = l.centerY(sp); val x0 = l.centerX(sp)
        t.onDown(0, x0, y); t.onMove(0, x0 + 5f, y); t.onUp(0, x0 + 5f, y)
        assertEquals(1, rec.tapped); assertEquals(0, rec.steps)
    }
}
