package app.wisp.keyboard.voice.text

import kotlin.math.ceil

/**
 * Speech models that predict one word after another sometimes fall into a loop on a short or cut-off clip and repeat the same clause
 * again and again (seen with Moonshine when a dictation is stopped mid-sentence). This removes such loops and caps the text to
 * what could actually have been said in the audio. Real repeats ("no no no", "very very good") are left alone.
 */
object LoopGuard {

    /** Most words a person says per second, with room to spare. */
    private const val MAX_WORDS_PER_SECOND = 6f

    fun clean(text: String, audioSeconds: Float): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val collapsed = collapseLoops(words)
        val cap = maxOf(4, ceil(audioSeconds * MAX_WORDS_PER_SECOND).toInt())
        return collapsed.take(cap).joinToString(" ")
    }

    private fun key(w: String) = w.lowercase().filter { it.isLetterOrDigit() }

    /** A phrase repeated back to back is cut down to one copy (and a trailing half-copy of it is dropped). */
    private fun collapseLoops(words: List<String>): List<String> {
        val keys = words.map(::key)
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var advanced = false
            for (n in 1..minOf(MAX_PHRASE, (words.size - i) / 2)) {      // shortest repeating unit first: "yes yes yes yes" is one word looped
                var repeats = 1
                while (i + (repeats + 1) * n <= words.size && same(keys, i, i + repeats * n, n)) repeats++
                val needed = when { n == 1 -> 4; n == 2 -> 3; else -> 2 }
                if (repeats >= needed) {
                    // Also swallow the start of one more copy that was cut off.
                    var partial = 0
                    while (partial < n - 1 && i + repeats * n + partial < words.size && keys[i + partial] == keys[i + repeats * n + partial]) partial++
                    for (k in 0 until n) out.add(words[i + k])
                    i += repeats * n + partial
                    advanced = true
                    break
                }
            }
            if (!advanced) { out.add(words[i]); i++ }
        }
        return out
    }

    private fun same(keys: List<String>, a: Int, b: Int, n: Int): Boolean { for (k in 0 until n) if (keys[a + k] != keys[b + k]) return false; return true }

    private const val MAX_PHRASE = 14
}
