package app.wisp.keyboard.dict

import app.wisp.keyboard.core.Native
import app.wisp.keyboard.kbd.Candidate
import java.io.Closeable

/** One word list in the Rust core: lookups, completions, corrections and next-word guesses. Closing frees the native object. */
class Lexicon(private var handle: Long) : Closeable {
    val isOpen get() = handle != 0L

    /** How common [word] is, 0..255, or -1 when the lexicon does not contain it. */
    fun probability(word: String): Int = if (handle == 0L) -1 else Native.lexFreq(handle, word)

    /** Best first. [typed] empty gives the likely words after [prev]. */
    fun suggest(typed: String, prox: Proximity?, prev: String?, limit: Int = MAX): List<Candidate> {
        if (handle == 0L) return emptyList()
        val text = Native.lexSuggest(handle, prox?.handle ?: 0L, typed, prev, limit)
        if (text.isEmpty()) return emptyList()
        return text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val f = line.split('\u0001')
            Candidate(f[0], f[1].toInt(), f[2].toInt() or (if (f[3] == "1") Candidate.FLAG_APPROPRIATE_FOR_AUTO_CORRECTION else 0))
        }.toList()
    }

    /** Every word with its frequency. */
    fun words(): List<Pair<String, Int>> =
        if (handle == 0L) emptyList() else Native.lexWords(handle).lineSequence().filter { it.isNotEmpty() }.map { it.substringBefore('\t') to it.substringAfter('\t').toInt() }.toList()

    /** The native handle, for core calls that read the lexicon (the swipe index build). */
    fun handleForNative() = handle

    override fun close() { if (handle != 0L) { Native.lexClose(handle); handle = 0 } }

    companion object { const val MAX = 12 }
}
