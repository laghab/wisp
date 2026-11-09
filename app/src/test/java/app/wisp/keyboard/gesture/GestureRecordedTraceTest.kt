package app.wisp.keyboard.gesture

import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.LayoutGuard
import app.wisp.keyboard.kbd.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GestureRecordedTraceTest {
    @Test fun aTraceSurvivesTheFileFormat() {
        val t = RecordedTrace("hello", "en_US", 950, 400, 1.3f, floatArrayOf(10.5f, 20f, 31.25f), floatArrayOf(5f, 6.5f, 7f))
        val back = RecordedTrace.parse(t.toJson())
        assertEquals("hello", back.word); assertEquals(950, back.width); assertEquals(3, back.count)
        assertEquals(31.3f, back.xs[2], 0.06f)   // coordinates are rounded to a tenth of a pixel
    }

    /** Replays traces recorded on the phone with TraceRecorderActivity (pull files/traces_en_US.jsonl to eval/). */
    @Test fun replayRecordedTraces() {
        val traceFile = GestureTestSupport.evalFile("traces_en_US.jsonl"); val words = GestureTestSupport.evalFile("words_en_US.tsv")
        assumeTrue("no recorded traces", traceFile != null && words != null)
        val spec = LayoutGuard.ensureSwitchKey(LayoutParser.parse(File(listOf("src/main/res/raw/layout_qwerty.json", "app/src/main/res/raw/layout_qwerty.json").first { File(it).isFile }).readText()))
        val first = RecordedTrace.parse(traceFile!!.readLines().first())
        val d = first.density
        val layout = CompiledLayout(spec, first.width.toFloat(), first.height.toFloat(), 3 * d, 6 * d, 8 * d)
        val alphabet = alphabetOf(layout)
        val f = GestureTestSupport.tempFile("rec"); GestureIndex.build(GestureTestSupport.readWords(words!!), alphabet, 0, f)
        val index = GestureIndex.open(f); val dec = GestureDecoder(index, GestureGeometry.from(layout, alphabet)); val out = GestureDecoder.Results(10)
        var n = 0; var top1 = 0; var top3 = 0
        for (line in traceFile.readLines().filter { it.isNotBlank() }) {
            val t = RecordedTrace.parse(line)
            dec.decode(t.xs, t.ys, t.count, out)
            val rank = (0 until out.count).firstOrNull { out.word(it).equals(t.word, true) } ?: Int.MAX_VALUE
            n++; if (rank == 0) top1++; if (rank < 3) top3++
        }
        println("RECORDED traces=$n top1=${100 * top1 / n}% top3=${100 * top3 / n}%")
        assertTrue(n > 0)
    }
}
