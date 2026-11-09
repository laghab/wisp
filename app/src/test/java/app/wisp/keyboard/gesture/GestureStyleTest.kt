package app.wisp.keyboard.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

class GestureStyleTest {
    private val layout = GestureTestSupport.layout()
    private val alphabet = alphabetOf(layout)
    private val geo = GestureGeometry.from(layout, alphabet)
    private val words = listOf("hello", "world", "thanks", "keyboard", "please", "people", "should", "because", "morning", "friend", "school", "coffee", "window", "garden", "message", "phone")

    private fun letters(w: String) = IntArray(w.length) { alphabet.indexOf(w[it].code) }

    private fun train(style: GestureStyle, n: Int, bx: Float, by: Float, scale: Float, noise: Float = 0.25f, weight: Float = 3f, seed: Long = 5) {
        val gen = SyntheticGestures(geo, seed).also { it.habitBiasX = bx; it.habitBiasY = by; it.habitScale = scale }
        val rnd = Random(seed)
        repeat(n) { val w = words[rnd.nextInt(words.size)]; val t = gen.generate(letters(w), noise); style.observe(t.xs, t.ys, t.n, letters(w), geo, weight) }
    }

    @Test fun learnsAConsistentOffset() {
        val s = GestureStyle(); train(s, 60, 0.25f, -0.18f, 1f)
        assertEquals(0.25f, s.biasX, 0.07f); assertEquals(-0.18f, s.biasY, 0.07f)
    }

    @Test fun anUnbiasedUserStaysNearlyNeutral() {
        val s = GestureStyle(); train(s, 80, 0f, 0f, 1f)
        assertEquals(0f, s.biasX, 0.06f); assertEquals(0f, s.biasY, 0.06f)
        assertTrue("a noisy swipe is naturally a little longer than its ideal path: ${s.lengthRatio}", s.lengthRatio in 0.95f..1.15f)
    }

    @Test fun learnsHowBigTheSwipesAre() {
        val normal = GestureStyle(); train(normal, 80, 0f, 0f, 1f)
        val small = GestureStyle(); train(small, 80, 0f, 0f, 0.9f)
        assertTrue("normal ${normal.lengthRatio}, small ${small.lengthRatio}", small.lengthRatio < normal.lengthRatio - 0.06f)
    }

    @Test fun everythingIsClamped() {
        val s = GestureStyle(); train(s, 200, 0.9f, -0.9f, 0.5f, weight = 5f)
        assertTrue(s.biasX <= GestureStyle.MAX_BIAS + 1e-4f && s.biasY >= -GestureStyle.MAX_BIAS - 1e-4f)
        assertTrue(s.lengthRatio >= GestureStyle.MIN_RATIO - 1e-4f)
    }

    @Test fun earlyObservationsMoveItOnlyALittle() {
        val s = GestureStyle(); train(s, 1, 0.3f, 0.3f, 1f, weight = 1f)
        assertTrue("moved ${s.biasX}", Math.abs(s.biasX) < 0.05f)
    }

    @Test fun theWrongWordTeachesNothing() {
        val s = GestureStyle(); val gen = SyntheticGestures(geo, 1)
        val t = gen.generate(letters("hello"), 0.2f)
        s.observe(t.xs, t.ys, t.n, letters("keyboard"), geo, 3f)     // the swipe is not for that word: lengths do not fit
        assertTrue(s.isNeutral)
    }

    @Test fun resetGoesBackToNeutral() { val s = GestureStyle(); train(s, 30, 0.2f, 0.2f, 1f); s.reset(); assertTrue(s.isNeutral); assertEquals(0f, s.biasX, 0f) }

    @Test fun survivesTheFile() {
        val s = GestureStyle(); train(s, 40, 0.2f, -0.1f, 1f)
        val f = File.createTempFile("wisp-style", ".txt").also { it.deleteOnExit() }
        s.save(f); val back = GestureStyle(); back.load(f)
        assertEquals(s.biasX, back.biasX, 1e-5f); assertEquals(s.lengthRatio, back.lengthRatio, 1e-5f); assertEquals(s.evidence, back.evidence, 1e-3f)
    }

    // ---- with the full dictionary: does it help, and does it do no harm?

    private fun fullIndex(): GestureIndex? {
        val file = GestureTestSupport.evalFile("words_en_US.tsv") ?: return null
        val f = GestureTestSupport.tempFile("style"); GestureIndex.build(GestureTestSupport.readWords(file), alphabet, 0, f)
        return GestureIndex.open(f)
    }

    @Test fun trainingHelpsAUserWithAConsistentHabit() {
        val index = fullIndex(); assumeTrue("needs eval/words_en_US.tsv", index != null)
        val habit = { g: SyntheticGestures -> g.habitBiasX = 0.30f; g.habitBiasY = -0.25f; g.habitScale = 0.95f }
        val style = GestureStyle(); train(style, 150, 0.30f, -0.25f, 0.95f, noise = 0.25f)
        val before = GestureEval.run(index!!, geo, 0.25f, 1500, 77, habit)
        val after = GestureEval.run(index, geo, 0.25f, 1500, 77, habit) { it.style = style }
        println("STYLE biased user: before top1=%.1f top3=%.1f  after top1=%.1f top3=%.1f (learned bias %.2f,%.2f ratio %.2f)".format(before.top1 * 100, before.top3 * 100, after.top1 * 100, after.top3 * 100, style.biasX, style.biasY, style.lengthRatio))
        assertTrue("top-1 should improve by at least 3 points", after.top1 >= before.top1 + 0.03f)
        assertTrue(after.top3 >= before.top3)
    }

    @Test fun trainingDoesNoHarmToAnUnbiasedUser() {
        val index = fullIndex(); assumeTrue("needs eval/words_en_US.tsv", index != null)
        val style = GestureStyle(); train(style, 150, 0f, 0f, 1f, noise = 0.25f)
        val before = GestureEval.run(index!!, geo, 0.25f, 1500, 88)
        val after = GestureEval.run(index, geo, 0.25f, 1500, 88, setup = { it.style = style })
        println("STYLE unbiased user: before top1=%.1f top3=%.1f  after top1=%.1f top3=%.1f".format(before.top1 * 100, before.top3 * 100, after.top1 * 100, after.top3 * 100))
        assertTrue("must not lose more than one point", after.top1 >= before.top1 - 0.01f && after.top3 >= before.top3 - 0.01f)
    }
}
