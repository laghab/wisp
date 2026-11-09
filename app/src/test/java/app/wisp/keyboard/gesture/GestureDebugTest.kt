package app.wisp.keyboard.gesture

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Scratch: explains individual failures. Prints to the test report. */
class GestureDebugTest {
    @Test fun explain() {
        val file = GestureTestSupport.evalFile("words_en_US.tsv"); assumeTrue(file != null)
        val layout = GestureTestSupport.layout(); val alphabet = alphabetOf(layout)
        val f = GestureTestSupport.tempFile("dbg"); GestureIndex.build(GestureTestSupport.readWords(file!!), alphabet, 0, f)
        val index = GestureIndex.open(f); val geo = GestureGeometry.from(layout, alphabet)
        val dec = GestureDecoder(index, geo); val gen = SyntheticGestures(geo, 7)
        val out = GestureDecoder.Results(10)
        fun find(w: String) = (0 until index.count).first { index.original(it) == w }
        for (w in listOf("and", "names", "musical", "senior", "world", "hello")) {
            val id = find(w); val letters = IntArray(index.foldedLen(id)) { index.letter(id, it) }
            var wins = 0; val sb = StringBuilder()
            repeat(20) {
                val t = gen.generate(letters, 0.30f); dec.decode(t.xs, t.ys, t.n, out)
                val rank = (0 until out.count).firstOrNull { out.word(it) == w } ?: -1
                if (rank == 0) wins++ else if (sb.length < 300) sb.append("rank=$rank top=[${(0 until minOf(3, out.count)).joinToString { out.word(it) + ":" + "%.2f".format(out.cost[it]) + "/p" + out.prob(it) }}] ")
            }
            println("DBG $w (prob ${index.prob(id)}) top1 in $wins/20; misses: $sb")
        }
    }
}
