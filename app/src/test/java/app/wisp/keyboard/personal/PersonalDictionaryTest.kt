package app.wisp.keyboard.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalDictionaryTest {
    private var now = 1_700_000_000_000L
    private val day = 86_400_000L
    private fun dict(maxWords: Int = 4000, maxPairs: Int = 8000) = PersonalDictionary(maxWords, maxPairs) { now }

    @Test fun aWordTheMainDictionaryKnowsOnlyGetsABoostThatGrowsWithUse() {
        val d = dict()
        assertEquals(0, d.bonus("hello"))
        d.learn("hello", null, inMain = true, strong = false)
        val one = d.bonus("hello")
        repeat(5) { d.learn("hello", null, true, false) }
        assertTrue(one > 0 && d.bonus("hello") > one)
        assertFalse(d.isKnown("hello"))                      // not a personal word: the main dictionary has it
    }

    @Test fun anUnknownWordIsLearnedOnlyAfterRepeatedUse() {
        val d = dict()
        d.learn("Raghav", null, inMain = false, strong = false)
        assertFalse("one use is not enough (could be a typo)", d.isKnown("raghav"))
        d.learn("Raghav", null, false, false)
        assertTrue(d.isKnown("raghav"))
        assertEquals(listOf("Raghav"), d.wordsWithPrefix("rag", 5))           // keeps the capital letter of a name
        assertTrue(d.wordsWithPrefix("raghav", 5).isEmpty())                   // the full word is not suggested for itself
    }

    @Test fun aDeliberateChoiceCountsDoubleSoOneRepeatIsEnough() {
        val d = dict()
        d.learn("wisp", null, false, strong = true)
        assertTrue(d.isKnown("wisp"))
    }

    @Test fun pairsRememberWhatFollowsWhat() {
        val d = dict()
        repeat(3) { d.learn("morning", "good", true, false) }
        assertTrue(d.pairBonus("good", "morning") > 0)
        assertEquals(0, d.pairBonus("bad", "morning"))
        assertEquals(0, d.pairBonus(null, "morning"))
    }

    @Test fun rubbishIsNotLearned() {
        val d = dict()
        for (w in listOf("a", "x1y", "12345", "supercalifragilisticexpialidocious", "hello!", "")) d.learn(w, null, false, true)
        assertEquals(0, d.wordCount)
    }

    @Test fun whenFullTheLeastUsedLongestUnusedAreForgottenFirst() {
        val d = dict(maxWords = 10)
        for (i in 0 until 10) { val w = "word" + "abcdefghij"[i]; repeat(i + 1) { d.learn(w, null, true, false) }; now += day }
        assertEquals(10, d.wordCount)
        d.learn("newword", null, true, false)     // 11th: over the limit, trims to 9
        val left = d.entries().map { it.word }.toSet()
        assertTrue("newword" in left)
        assertTrue("the most used word survives", "wordj" in left)
        assertFalse("the least used word is gone", "worda" in left)
        assertTrue(d.wordCount <= 10)
    }

    @Test fun oldHabitsFadeSoRecentOnesOutrankThem() {
        val d = dict()
        repeat(6) { d.learn("oldword", null, true, false) }
        val fresh = d.bonus("oldword")
        now += 365 * day
        assertTrue(d.bonus("oldword") < fresh / 2)
    }

    @Test fun removeForgetsAWordAndItsPairs() {
        val d = dict()
        repeat(3) { d.learn("morning", "good", true, false) }
        d.remove("morning")
        assertEquals(0, d.bonus("morning")); assertEquals(0, d.pairBonus("good", "morning")); assertEquals(0, d.pairCount)
    }

    @Test fun clearForgetsEverything() {
        val d = dict(); repeat(3) { d.learn("hello", "say", true, false) }; d.clear()
        assertEquals(0, d.wordCount); assertEquals(0, d.pairCount)
    }

    @Test fun savingAndLoadingKeepsEverything() {
        val d = dict(); repeat(3) { d.learn("Raghav", "hi", false, false) }; d.learn("hello", "say", true, true)
        val f = File.createTempFile("wisp-personal", ".tsv").also { it.deleteOnExit() }
        d.save(f)
        val back = dict(); back.load(f)
        assertTrue(back.isKnown("raghav")); assertEquals(d.bonus("hello"), back.bonus("hello")); assertEquals(d.pairBonus("hi", "raghav"), back.pairBonus("hi", "raghav"))
        assertEquals(d.wordCount, back.wordCount)
    }

    @Test fun aDamagedFileLoadsWhatItCan() {
        val f = File.createTempFile("wisp-personal", ".tsv").also { it.deleteOnExit() }
        f.writeText("W\tgood\tgood\t3.0\t1700000000000\t1\nnot a line\nW\tbroken\n")
        val d = dict(); d.load(f)
        assertEquals(1, d.wordCount)
    }
}
