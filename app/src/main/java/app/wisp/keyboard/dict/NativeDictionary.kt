package app.wisp.keyboard.dict

import com.android.inputmethod.latin.BinaryDictionary
import com.android.inputmethod.latin.DicTraverseSession
import java.io.Closeable

/**
 * One open dictionary (a `.dict` file) on top of the native engine. The file is memory-mapped, so an open
 * dictionary costs address space, not heap, until words are looked up.
 *
 * Not thread-safe: use it from one worker thread.
 */
class NativeDictionary private constructor(
    private var dict: Long,
    private val dictSize: Long,
    locale: String,
) : Closeable {

    private var session = DicTraverseSession.setDicTraverseSessionNative(locale, dictSize).also {
        DicTraverseSession.initDicTraverseSessionNative(it, dict, null, 0)
    }

    // Buffers reused by every query.
    private val inputCodePoints = IntArray(MAX_WORD)
    private val inputX = IntArray(MAX_WORD)
    private val inputY = IntArray(MAX_WORD)
    private val times = IntArray(MAX_WORD)
    private val pointerIds = IntArray(MAX_WORD)
    private val prevWords = arrayOfNulls<IntArray>(MAX_PREV)
    private val prevIsBos = BooleanArray(MAX_PREV)
    private val outCount = IntArray(1)
    private val outCodePoints = IntArray(MAX_WORD * Suggestions.MAX)
    private val outScores = IntArray(Suggestions.MAX)
    private val outSpaces = IntArray(Suggestions.MAX)
    private val outTypes = IntArray(Suggestions.MAX)
    private val outAutoCommit = IntArray(1)
    private val weight = FloatArray(1)
    private val options = IntArray(5) // isGesture, fullEditDistance, blockOffensive, spaceAwareGesture, weightForLocale*1000

    val isOpen get() = dict != 0L

    /**
     * Words that start with, or are close to, [typed], best first. [xs]/[ys] are the touch points of each typed
     * letter (or null when unknown); [prev] is the previous word for bigram ranking.
     */
    fun suggest(
        typed: String, proximity: Proximity, xs: IntArray?, ys: IntArray?, prev: String?, out: Suggestions,
    ) {
        out.count = 0
        if (dict == 0L || proximity.handle == 0L) return   // the engine dereferences the proximity info unconditionally
        java.util.Arrays.fill(inputCodePoints, NOT_A_CODE)
        java.util.Arrays.fill(inputX, NOT_A_COORDINATE); java.util.Arrays.fill(inputY, NOT_A_COORDINATE)
        var n = 0; var i = 0
        while (i < typed.length && n < MAX_WORD) {
            val cp = typed.codePointAt(i); inputCodePoints[n] = cp
            if (xs != null && ys != null && n < xs.size) { inputX[n] = xs[n]; inputY[n] = ys[n] }
            n++; i += Character.charCount(cp)
        }
        var prevCount = 0
        java.util.Arrays.fill(prevWords, null); java.util.Arrays.fill(prevIsBos, false)
        if (prev != null) { prevWords[0] = prev.codePoints().toArray(); prevCount = 1 }
        options[1] = 0; options[0] = 0; options[2] = 0; options[3] = 0; options[4] = 1000
        weight[0] = NOT_A_WEIGHT
        BinaryDictionary.getSuggestionsNative(
            dict, proximity.handle, session, inputX, inputY, times, pointerIds, inputCodePoints, n, options,
            prevWords as Array<IntArray?>, prevIsBos, prevCount, outCount, outCodePoints, outScores, outSpaces, outTypes,
            outAutoCommit, weight,
        )
        val c = outCount[0].coerceIn(0, Suggestions.MAX)
        var k = 0
        for (j in 0 until c) {
            val start = j * MAX_WORD
            var len = 0
            while (len < MAX_WORD && outCodePoints[start + len] != 0) len++
            if (len == 0) continue
            out.words[k] = String(outCodePoints, start, len); out.scores[k] = outScores[j]; out.types[k] = outTypes[j]; k++
        }
        out.count = k
        out.sortBestFirst()   // the engine lists the best word last
        out.autoCommitConfidence = outAutoCommit[0]
    }

    /** How common [word] is, 0..255, or -1 when the dictionary does not contain it. */
    fun probability(word: String): Int = if (dict == 0L) -1 else BinaryDictionary.getProbabilityNative(dict, word.codePoints().toArray())

    fun containsWord(word: String) = probability(word) >= 0

    /** Probability of [word] following [prev]; -1 when that pair is unknown. */
    fun bigramProbability(prev: String, word: String): Int {
        if (dict == 0L) return -1
        val arr = arrayOf(prev.codePoints().toArray(), null, null)
        return BinaryDictionary.getNgramProbabilityNative(dict, arr, BooleanArray(1), word.codePoints().toArray())
    }

    /** Calls [action] with every word in the dictionary and its probability (0..255). Order is the engine's, not alphabetical. */
    fun forEachWord(action: (word: String, probability: Int) -> Unit) {
        if (dict == 0L) return
        val cps = IntArray(MAX_WORD); val bos = BooleanArray(1)
        var token = 0
        do {
            java.util.Arrays.fill(cps, 0)   // the engine writes the word without a terminator; stale code points would leak in
            token = BinaryDictionary.getNextWordNative(dict, token, cps, bos)
            var len = 0
            while (len < MAX_WORD && cps[len] != 0) len++
            if (len == 0 || bos[0]) continue
            val word = String(cps, 0, len)
            action(word, BinaryDictionary.getProbabilityNative(dict, cps.copyOf(len)))
        } while (token != 0)
    }

    /** Dictionary header attributes, e.g. "locale", "description", "version". */
    fun header(): Map<String, String> {
        val keys = ArrayList<IntArray>(); val values = ArrayList<IntArray>()
        BinaryDictionary.getHeaderInfoNative(dict, IntArray(1), IntArray(1), keys, values)
        fun str(a: IntArray): String { var e = a.size; for (i in a.indices) if (a[i] == 0) { e = i; break }; return String(a, 0, e) }
        return keys.indices.associate { str(keys[it]) to str(values[it]) }
    }

    override fun close() {
        if (session != 0L) { DicTraverseSession.releaseDicTraverseSessionNative(session); session = 0 }
        if (dict != 0L) { BinaryDictionary.closeNative(dict); dict = 0 }
    }

    companion object {
        const val MAX_WORD = 48
        private const val MAX_PREV = 3
        private const val NOT_A_CODE = -1
        private const val NOT_A_COORDINATE = -1
        private const val NOT_A_WEIGHT = -1f

        /** Opens a read-only dictionary stored at [offset]..[offset]+[length] of [path] (an APK works too). Null on failure. */
        fun open(path: String, offset: Long, length: Long, locale: String, updatable: Boolean = false): NativeDictionary? {
            val handle = BinaryDictionary.openNative(path, offset, length, updatable)
            return if (handle == 0L) null else NativeDictionary(handle, length, locale)
        }
    }
}
