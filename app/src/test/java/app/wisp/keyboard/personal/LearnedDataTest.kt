package app.wisp.keyboard.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class LearnedDataTest {
    private val dir: File = Files.createTempDirectory("wisp-learned").toFile().also { it.deleteOnExit() }
    private val data = LearnedData(dir)

    private fun teach(tag: String, vararg words: String) {
        val d = PersonalDictionary()
        for (w in words) repeat(3) { d.learn(w, "hi", false, false) }
        PersonalStore(dir).file(tag).also { d.save(it) }
    }

    @Test fun listsWhatWasLearnedMostUsedFirst() {
        val d = PersonalDictionary(); repeat(5) { d.learn("raghav", null, false, false) }; repeat(2) { d.learn("wisp", null, false, false) }
        d.save(PersonalStore(dir).file("en_US"))
        assertEquals(listOf("raghav", "wisp"), data.words("en_US").map { it.word })
    }

    @Test fun anUnknownLanguageHasNothing() = assertTrue(data.words("xx").isEmpty())

    @Test fun forgettingAWordRemovesItAndItsPairs() {
        teach("en_US", "raghav", "wisp")
        assertTrue(data.forget("en_US", "raghav"))
        assertEquals(listOf("wisp"), data.words("en_US").map { it.word })
        assertFalse("forgetting it again finds nothing", data.forget("en_US", "raghav"))
        val d = PersonalDictionary().also { it.load(PersonalStore(dir).file("en_US")) }
        assertEquals(0, d.pairBonus("hi", "raghav"))
    }

    @Test fun exportWritesTheLearnedDataAndNothingElse() {
        teach("en_US", "raghav")
        File(dir, "gesture_style.txt").writeText("0.1 0.1 1.0 1.0 5.0")
        val out = ByteArrayOutputStream(); data.export("en_US", out)
        val text = out.toString()
        assertTrue(text.contains("raghav")); assertFalse(text.contains("0.1 0.1"))
    }

    @Test fun wipingEverythingLeavesNothing() {
        teach("en_US", "raghav"); teach("hi_Latn", "yaar"); File(dir, "gesture_style.txt").writeText("x")
        data.wipeEverything()
        assertTrue(data.isEmpty); assertTrue(data.words("en_US").isEmpty()); assertTrue(data.words("hi_Latn").isEmpty())
    }
}
