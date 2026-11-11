package app.wisp.keyboard.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class TrainingStatsTest {
    @Test fun hardWordsComeUpMoreOften() {
        val s = TrainingStats()
        repeat(10) { s.record("hard", false); s.record("easy", true) }
        val words = listOf("hard", "easy") + (1..40).map { "w$it" }
        var hard = 0; var easy = 0; val rnd = Random(1)
        repeat(400) { val p = s.pick(words, 10, rnd); if ("hard" in p) hard++; if ("easy" in p) easy++ }
        assertTrue("hard=$hard easy=$easy", hard > easy * 2)
    }

    @Test fun aPickHasNoRepeatsAndTheRightSize() {
        val s = TrainingStats(); val p = s.pick((1..100).map { "w$it" }, 20, Random(3))
        assertEquals(20, p.size); assertEquals(20, p.toSet().size)
    }

    @Test fun fewCandidatesAreAllUsed() = assertEquals(3, TrainingStats().pick(listOf("a", "b", "c"), 20, Random(1)).size)

    @Test fun survivesTheFile() {
        val s = TrainingStats(); s.record("hello", true); s.record("hello", false)
        val f = File.createTempFile("wisp-train", ".tsv").also { it.deleteOnExit() }; s.save(f)
        val b = TrainingStats(); b.load(f)
        assertEquals(2, b.totalAttempts); assertEquals(s.weight("hello"), b.weight("hello"), 1e-9)
    }
}
