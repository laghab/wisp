package app.wisp.keyboard.gesture

import app.wisp.keyboard.kbd.CompiledLayout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/** Scratch: does the geometric fit of the best word separate real words from names and slang that are not in the dictionary? */
class GestureTuneTest {
    @Test fun fit() {
        val file = GestureTestSupport.evalFile("words_en_US.tsv"); assumeTrue(file != null)
        val layout = GestureTestSupport.layout(); val alphabet = alphabetOf(layout)
        val f = GestureTestSupport.tempFile("fit"); GestureIndex.build(GestureTestSupport.readWords(file!!), alphabet, 0, f)
        val index = GestureIndex.open(f); val geo = GestureGeometry.from(layout, alphabet)
        val dec = GestureDecoder(index, geo); val out = GestureDecoder.Results(10)
        fun idx(c: Char) = alphabet.indexOf(c.code)
        fun fit(): Float = out.cost[0] + dec.priorWeight * out.prob(0) / 255f

        val names = listOf("raghav", "kubernetes", "anthropic", "sherpa", "whatsapp", "tiktok", "bitcoin", "waydroid", "zoho", "gmail", "pixel",
            "shruti", "ananya", "vikram", "priyanka", "kavita", "rohan", "aditya", "krishna", "bhavna", "srinivas", "harshit", "keyboardz", "qwertz", "xyzzy", "bluetooth", "youtube", "instagram")
        for (noise in listOf(0.15f, 0.30f)) {
            val gen = SyntheticGestures(geo, 21)
            val real = ArrayList<Float>(); val non = ArrayList<Float>()
            val pick = Random(3); val ids = (0 until index.count).filter { index.foldedLen(it) in 4..9 && index.prob(it) >= 60 }
            repeat(1500) {
                val w = ids[pick.nextInt(ids.size)]
                val t = gen.generate(IntArray(index.foldedLen(w)) { index.letter(w, it) }, noise); dec.decode(t.xs, t.ys, t.n, out)
                if (out.count > 0) real.add(fit())
            }
            repeat(40) { for (n in names) {
                val t = gen.generate(IntArray(n.length) { idx(n[it]) }, noise); dec.decode(t.xs, t.ys, t.n, out)
                non.add(if (out.count > 0) fit() else 9f)
            } }
            fun q(l: List<Float>, p: Double) = l.sorted()[((l.size - 1) * p).toInt()]
            println("FIT noise=%.2f real: p50=%.3f p90=%.3f p95=%.3f p99=%.3f | non-words: p5=%.3f p10=%.3f p25=%.3f p50=%.3f".format(
                noise, q(real, .5), q(real, .9), q(real, .95), q(real, .99), q(non, .05), q(non, .10), q(non, .25), q(non, .5)))
            for (th in listOf(0.10f, 0.15f, 0.20f, 0.25f, 0.30f)) println("FIT   threshold %.2f: %d%% of real words pass, %d%% of non-words rejected".format(
                th, 100 * real.count { it <= th } / real.size, 100 * non.count { it > th } / non.size))
        }
    }
}
