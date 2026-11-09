package app.wisp.keyboard.dict

/** Reusable result holder: the engine fills these arrays in place, so a query allocates only the result strings. */
class Suggestions {
    var count = 0
        internal set
    val words = arrayOfNulls<String>(MAX)
    val scores = IntArray(MAX)
    val types = IntArray(MAX)

    /** Confidence (engine units) that the first suggestion should be applied without asking. */
    var autoCommitConfidence = 0
        internal set

    /** Insertion sort by score, highest first (at most 18 entries, so this is cheaper than anything fancier). */
    internal fun sortBestFirst() {
        for (i in 1 until count) {
            val w = words[i]; val sc = scores[i]; val t = types[i]
            var j = i - 1
            while (j >= 0 && scores[j] < sc) { words[j + 1] = words[j]; scores[j + 1] = scores[j]; types[j + 1] = types[j]; j-- }
            words[j + 1] = w; scores[j + 1] = sc; types[j + 1] = t
        }
    }

    fun word(i: Int): String = words[i]!!
    override fun toString() = (0 until count).joinToString { "${words[it]}:${scores[it]}" }

    companion object { const val MAX = 18 }
}
