package app.wisp.keyboard.kbd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTrackingTest {
    private val json = """{"name":"t","rows":[["a","s","d","f"],[{"k":"shift"},"z","x",{"k":"backspace"}]]}"""
    private val layout = CompiledLayout(LayoutParser.parse(json), 400f, 200f, 0f, 0f, 0f)

    private class Rec : KeyTouchTracker.Sink {
        var taps = 0; var presses = 0; var releases = 0
        var started = 0; var moved = 0; var ended = 0; var cancelled = 0
        var endedCount = 0; var endedFirstX = -1f; var endedLastX = -1f
        override fun pressed(key: Int) { presses++ }
        override fun released(key: Int) { releases++ }
        override fun tapped(key: Int, x: Float, y: Float) { taps++ }
        override fun gestureStarted() { started++ }
        override fun gestureMoved(count: Int) { moved++ }
        override fun gestureEnded(xs: FloatArray, ys: FloatArray, count: Int) { ended++; endedCount = count; endedFirstX = xs[0]; endedLastX = xs[count - 1] }
        override fun gestureCancelled() { cancelled++ }
    }

    private fun tracker(rec: Rec, slop: Float = 30f): KeyTouchTracker {
        val canSwipe = BooleanArray(layout.count) { layout.kinds[it] == KeyKind.LETTER }
        return KeyTouchTracker(KeyGrid(layout), rec, gestureStart = canSwipe, gestureSlopPx = slop)
    }

    @Test fun aSmallWobbleIsStillATap() {
        val r = Rec(); val t = tracker(r); val a = layout.indexOfCode('a'.code)
        val x = layout.centerX(a); val y = layout.centerY(a)
        t.onDown(0, x, y); t.onMove(0, x + 6f, y + 3f); t.onUp(0, x + 6f, y + 3f)
        assertEquals(1, r.taps); assertEquals(0, r.started); assertEquals(0, r.ended)
    }

    @Test fun aLongDragFromALetterIsASwipeAndNotATap() {
        val r = Rec(); val t = tracker(r); val a = layout.indexOfCode('a'.code)
        val x = layout.centerX(a); val y = layout.centerY(a)
        t.onDown(0, x, y)
        for (i in 1..10) t.onMove(0, x + i * 20f, y)
        assertTrue(t.gesturing)
        t.onUp(0, x + 220f, y)
        assertEquals(0, r.taps); assertEquals(1, r.started); assertEquals(1, r.ended)
        assertEquals(12, r.endedCount)                       // down + 10 moves + up
        assertEquals(x, r.endedFirstX, 0.01f); assertEquals(x + 220f, r.endedLastX, 0.01f)
        assertFalse(t.gesturing)
        assertEquals(r.presses, r.releases)                  // the key the swipe began on was released
    }

    @Test fun swipesOnlyStartOnLetterKeys() {
        val r = Rec(); val t = tracker(r); val s = layout.indexOfCode(KeyCodes.SHIFT)
        val x = layout.centerX(s); val y = layout.centerY(s)
        t.onDown(0, x, y); for (i in 1..10) t.onMove(0, x + i * 20f, y); t.onUp(0, x + 200f, y)
        assertEquals(0, r.started); assertEquals(0, r.ended)
    }

    @Test fun cancelEndsTheSwipeWithoutAResult() {
        val r = Rec(); val t = tracker(r); val a = layout.indexOfCode('a'.code)
        val x = layout.centerX(a); val y = layout.centerY(a)
        t.onDown(0, x, y); for (i in 1..5) t.onMove(0, x + i * 20f, y)
        t.cancelAll()
        assertEquals(1, r.cancelled); assertEquals(0, r.ended); assertFalse(t.gesturing)
    }

    @Test fun aSecondFingerDuringASwipeDoesNotBreakIt() {
        val r = Rec(); val t = tracker(r); val a = layout.indexOfCode('a'.code); val z = layout.indexOfCode('z'.code)
        val x = layout.centerX(a); val y = layout.centerY(a)
        t.onDown(0, x, y); for (i in 1..5) t.onMove(0, x + i * 20f, y)
        t.onDown(1, layout.centerX(z), layout.centerY(z)); t.onUp(1, layout.centerX(z), layout.centerY(z))
        for (i in 6..9) t.onMove(0, x + i * 20f, y)
        t.onUp(0, x + 180f, y)
        assertEquals(1, r.ended); assertEquals(1, r.taps)     // the second finger's tap still counts
    }

    @Test fun twoSwipesInARowEachProduceOneResult() {
        val r = Rec(); val t = tracker(r); val a = layout.indexOfCode('a'.code)
        val x = layout.centerX(a); val y = layout.centerY(a)
        repeat(2) { t.onDown(0, x, y); for (i in 1..6) t.onMove(0, x + i * 20f, y); t.onUp(0, x + 120f, y) }
        assertEquals(2, r.ended); assertEquals(0, r.taps)
    }

    @Test fun gesturesCanBeSwitchedOff() {
        val r = Rec(); val a = layout.indexOfCode('a'.code)
        val t = KeyTouchTracker(KeyGrid(layout), r, gestureStart = null)
        val x = layout.centerX(a); val y = layout.centerY(a)
        t.onDown(0, x, y); for (i in 1..6) t.onMove(0, x + i * 20f, y); t.onUp(0, x + 120f, y)
        assertEquals(0, r.started)
    }
}
