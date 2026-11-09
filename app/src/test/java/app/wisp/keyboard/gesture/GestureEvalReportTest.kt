package app.wisp.keyboard.gesture

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prints the evaluation table against the full exported dictionary (run `tools/e2e/selftest.sh` export, then pull to eval/). Skipped when the file is absent. */
class GestureEvalReportTest {
    @Test fun report() {
        val file = GestureTestSupport.evalFile("words_en_US.tsv")
        assumeTrue("eval/words_en_US.tsv not found", file != null)
        val layout = GestureTestSupport.layout()
        val alphabet = alphabetOf(layout)
        val idxFile = GestureTestSupport.tempFile("en")
        val t0 = System.nanoTime()
        GestureIndex.build(GestureTestSupport.readWords(file!!), alphabet, 0, idxFile)
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        val index = GestureIndex.open(idxFile)
        val geo = GestureGeometry.from(layout, alphabet)
        println("GESTURE index: ${index.count} words, ${idxFile.length() / 1024} KB, built in ${buildMs} ms")
        for (noise in listOf(0.15f, 0.30f, 0.45f)) {
            val r = GestureEval.run(index, geo, noise, 2000, 42)
            println("GESTURE $r")
            if (noise == 0.30f) println("GESTURE misses: ${r.misses.take(8)}")
        }
    }
}
