package app.wisp.keyboard.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureIndexTest {
    private val az = IntArray(26) { 'a'.code + it }

    private fun build(words: List<Pair<String, Int>>, alphabet: IntArray = az, minProb: Int = 0): GestureIndex {
        val f = GestureTestSupport.tempFile("gi"); GestureIndex.build(words.asSequence(), alphabet, minProb, f); return GestureIndex.open(f)
    }

    private fun GestureIndex.words() = (0 until count).map { original(it) }

    @Test fun groupsByFirstLetterAndOrdersByProbability() {
        val idx = build(listOf("the" to 222, "tie" to 120, "to" to 215, "and" to 212, "ant" to 90))
        val t = az.indexOf('t'.code); val a = az.indexOf('a'.code)
        val inT = (idx.bucketStart(t) until idx.bucketEnd(t)).map { idx.original(it) }
        assertEquals(listOf("the", "to", "tie"), inT)
        assertEquals(listOf("and", "ant"), (idx.bucketStart(a) until idx.bucketEnd(a)).map { idx.original(it) })
        assertEquals(5, idx.count)
    }

    @Test fun foldsCaseAccentsAndPunctuation() {
        val idx = build(listOf("Café" to 80, "don't" to 90, "Hello" to 100, "hello" to 120, "naïve" to 70))
        val words = idx.words().toSet()
        assertTrue("hello" in words && "Hello" !in words)              // same letters: the more probable spelling wins
        assertTrue("Café" in words && "don't" in words && "naïve" in words)
        val dont = (0 until idx.count).first { idx.original(it) == "don't" }
        assertEquals(4, idx.foldedLen(dont))                            // d o n t
    }

    @Test fun dropsWordsItCannotSwipe() {
        val idx = build(listOf("a" to 200, "1Weather" to 25, "über" to 50, "ok" to 100), alphabet = IntArray(26) { 'a'.code + it })
        // "a" is a single letter, "1Weather" has a digit; "über" folds to "uber" because the layout has no ü key.
        assertEquals(setOf("über", "ok"), idx.words().toSet())
    }

    @Test fun keepsKeysTheAlphabetHas() {
        val es = az + 'ñ'.code
        val idx = build(listOf("año" to 100, "ano" to 60), alphabet = es.sortedArray())
        assertEquals(setOf("año", "ano"), idx.words().toSet())          // ñ is its own letter, not folded to n
    }

    @Test fun minimumProbabilityLeavesRareWordsOut() {
        val idx = build(listOf("the" to 222, "rare" to 5), minProb = 20)
        assertEquals(listOf("the"), idx.words())
    }

    @Test fun lastLetterAndLettersAreReadable() {
        val idx = build(listOf("help" to 100))
        assertEquals(az.indexOf('p'.code), idx.lastLetter(0))
        assertEquals(az.indexOf('h'.code), idx.letter(0, 0))
        assertEquals(100, idx.prob(0))
    }
}
