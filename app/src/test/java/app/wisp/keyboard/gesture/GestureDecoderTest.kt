package app.wisp.keyboard.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDecoderTest {
    private val layout = GestureTestSupport.layout()
    private val alphabet = alphabetOf(layout)
    private val geo = GestureGeometry.from(layout, alphabet)

    private val words = listOf(
        "the" to 222, "tie" to 130, "tire" to 120, "to" to 215, "hello" to 120, "help" to 160, "held" to 130, "hell" to 100,
        "world" to 150, "word" to 170, "would" to 180, "wild" to 120, "good" to 170, "food" to 150, "gold" to 140,
        "and" to 212, "ant" to 100, "send" to 150, "sand" to 130, "thanks" to 140, "thank" to 150, "think" to 160,
        "please" to 150, "pleasure" to 110, "quick" to 120, "typing" to 110, "keyboard" to 110, "gesture" to 90,
    )
    private val index: GestureIndex by lazy {
        val f = GestureTestSupport.tempFile("gd"); GestureIndex.build(words.asSequence(), alphabet, 0, f); GestureIndex.open(f)
    }

    /** A perfect swipe: straight lines through the key centres, sampled every few pixels. */
    private fun perfect(word: String): Pair<FloatArray, FloatArray> {
        val idx = word.map { alphabet.indexOf(it.code) }.fold(ArrayList<Int>()) { l, c -> if (l.isEmpty() || l.last() != c) l.add(c); l }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        xs.add(geo.x[idx[0]]); ys.add(geo.y[idx[0]])
        for (i in 1 until idx.size) {
            val a = idx[i - 1]; val b = idx[i]; val steps = 12
            for (s in 1..steps) { xs.add(geo.x[a] + (geo.x[b] - geo.x[a]) * s / steps); ys.add(geo.y[a] + (geo.y[b] - geo.y[a]) * s / steps) }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun decode(word: String): GestureDecoder.Results {
        val (xs, ys) = perfect(word)
        return GestureDecoder.Results(10).also { GestureDecoder(index, geo).decode(xs, ys, xs.size, it) }
    }

    @Test fun aPerfectSwipeFindsItsWord() {
        for (w in listOf("hello", "world", "keyboard", "thanks", "gesture", "quick", "typing", "please")) {
            val r = decode(w)
            assertEquals("swipe for $w -> $r", w, r.word(0))
        }
    }

    @Test fun doubleLettersAreSwipedOnce() {
        assertEquals("hello", decode("hello").word(0))
        assertEquals("good", decode("good").word(0))
    }

    @Test fun similarPathsAreSeparatedByTheirEnds() {
        assertEquals("thank", decode("thank").word(0))
        assertEquals("thanks", decode("thanks").word(0))
    }

    @Test fun aTapOrTinyWiggleIsNotASwipe() {
        val out = GestureDecoder.Results(10)
        assertEquals(0, GestureDecoder(index, geo).decode(floatArrayOf(100f), floatArrayOf(100f), 1, out))
        assertEquals(0, GestureDecoder(index, geo).decode(floatArrayOf(100f, 104f, 99f), floatArrayOf(100f, 101f, 98f), 3, out))
    }

    @Test fun anImpossibleSwipeFindsNothingOrLowConfidenceJunk() {
        // From the far left to the far right and back: no word has such a long path for so few letters
        val xs = floatArrayOf(10f, 1070f, 10f, 1070f, 10f); val ys = FloatArray(5) { 600f }
        val out = GestureDecoder.Results(10)
        GestureDecoder(index, geo).decode(xs, ys, 5, out)
        assertTrue(out.count == 0 || out.cost[0] > 0.5f)
    }

    @Test fun commonWordsWinTiesBetweenSimilarPaths() {
        // "the" and "tie" and "tire" differ little geometrically; the frequent word is first.
        val r = decode("the")
        assertEquals("the", r.word(0))
    }

    // ---- personal words

    private fun extra(word: String, points: Int) = GestureDecoder.ExtraWord(word, IntArray(word.length) { alphabet.indexOf(word.lowercase()[it].code) }, points)

    private fun decodeWith(word: String, setup: (GestureDecoder) -> Unit): GestureDecoder.Results {
        val (xs, ys) = perfect(word)
        val dec = GestureDecoder(index, geo).also(setup)
        return GestureDecoder.Results(10).also { dec.decode(xs, ys, xs.size, it) }
    }

    @Test fun aNameTheDictionaryLacksCanBeSwiped() {
        assertTrue("raghav" !in (0 until index.count).map { index.original(it) })
        val r = decodeWith("raghav") { it.extras = arrayOf(extra("Raghav", 90)) }
        assertEquals("Raghav", r.word(0))
    }

    @Test fun withoutTheNameTheSwipeFindsSomethingElse() {
        val r = decodeWith("raghav") { }
        assertTrue(r.count == 0 || r.word(0) != "Raghav")
    }

    @Test fun aFavouriteWordWinsAnAmbiguousPath() {
        // "tie" and "the" are one swipe apart; boosting "tie" makes it first.
        val tie = (0 until index.count).first { index.original(it) == "tie" }
        val plain = decodeWith("tie") { }
        val boosted = decodeWith("tie") { it.boostPoints = mapOf(tie to 80) }
        assertTrue(boosted.cost[0] <= plain.cost[0])
        assertEquals("tie", boosted.word(0))
    }
}
