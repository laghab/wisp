package app.wisp.keyboard.gesture

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The gesture-typing gate (tasks 4.6): accuracy and speed thresholds on synthetic swipes decoded against the full
 * English dictionary. Thresholds and how they were chosen: docs/gesture-eval.md. Skipped when eval/words_en_US.tsv is missing
 * (create it with tools/export-wordlists.sh).
 */
class GestureGateTest {
    private val setup: Triple<GestureIndex, GestureGeometry, Boolean> by lazy {
        val file = GestureTestSupport.evalFile("words_en_US.tsv")
        if (file == null) return@lazy Triple(GestureIndex.open(GestureTestSupport.tempFile("none").also { GestureIndex.build(emptySequence(), IntArray(26) { 'a'.code + it }, 0, it) }), GestureGeometry(IntArray(0), FloatArray(0), FloatArray(0), 1f, 1f), false)
        val layout = GestureTestSupport.layout(); val alphabet = alphabetOf(layout)
        val f = GestureTestSupport.tempFile("gate"); GestureIndex.build(GestureTestSupport.readWords(file), alphabet, 0, f)
        Triple(GestureIndex.open(f), GestureGeometry.from(layout, alphabet), true)
    }

    private fun check(noise: Float, minTop1: Float, minTop3: Float) {
        assumeTrue("eval/words_en_US.tsv not found", setup.third)
        val r = GestureEval.run(setup.first, setup.second, noise, 2000, 42)
        println("GATE $r")
        assertTrue("noise $noise: top-1 ${r.top1} < $minTop1", r.top1 >= minTop1)
        assertTrue("noise $noise: top-3 ${r.top3} < $minTop3", r.top3 >= minTop3)
        assertTrue("noise $noise: decode p95 ${r.p95Us}us over 25ms on the desktop JVM", r.p95Us <= 25_000)
    }

    @Test fun carefulSwipes() = check(0.15f, minTop1 = 0.93f, minTop3 = 0.98f)
    @Test fun normalSwipes() = check(0.30f, minTop1 = 0.80f, minTop3 = 0.92f)
    @Test fun sloppySwipes() = check(0.45f, minTop1 = 0.50f, minTop3 = 0.65f)

    @Test fun longWordsDecodeWithinTheBudget() {
        assumeTrue("eval/words_en_US.tsv not found", setup.third)
        val (index, geo, _) = setup
        val long = (0 until index.count).filter { index.foldedLen(it) >= 12 && index.prob(it) >= 60 }.take(300)
        val dec = GestureDecoder(index, geo); val gen = SyntheticGestures(geo, 9); val out = GestureDecoder.Results(10)
        val times = LongArray(long.size)
        repeat(2) { for ((k, w) in long.withIndex()) {
            val t = gen.generate(IntArray(index.foldedLen(w)) { index.letter(w, it) }, 0.25f)
            val t0 = System.nanoTime(); dec.decode(t.xs, t.ys, t.n, out); times[k] = System.nanoTime() - t0
        } }
        times.sort()
        val p95 = times[(times.size * 95) / 100] / 1000
        println("GATE long words (12+ letters) n=${long.size} decode p95=${p95}us")
        assertTrue("long-word p95 ${p95}us over 150ms", p95 <= 150_000)
    }
}
