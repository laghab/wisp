package app.wisp.keyboard.personal

import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * What Wisp has learned about one language from the user: words they use (and how often, how recently), words the
 * main dictionary lacks that they typed repeatedly, and which word tends to follow which. Everything lives on the
 * device in one small file; nothing here ever leaves it.
 *
 * - A word the main dictionary already knows only gets a ranking boost.
 * - A word it does not know is remembered as pending, and becomes a real personal word once it has been used
 *   [PROMOTE_AT] times (so a one-off typo is not learned).
 * - Size is bounded: when full, the entries used least and longest ago are forgotten first.
 *
 * Not thread-safe: use from one thread (the suggestion worker).
 */
class PersonalDictionary(
    private val maxWords: Int = 4_000,
    private val maxPairs: Int = 8_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Entry(var display: String, var count: Float, var lastUsed: Long, var promoted: Boolean)
    class Pair(var count: Float, var lastUsed: Long)

    private val words = HashMap<String, Entry>()
    private val pairs = HashMap<String, Pair>()
    /** Bumped on every change; lets callers cheaply notice that a snapshot is stale. */
    var version = 0
        private set

    val wordCount get() = words.size
    val pairCount get() = pairs.size

    /**
     * Records that the user used [word] (after [previous], when known). [inMain] says whether the main dictionary knows it.
     * [strong] marks a deliberate choice (tapped a suggestion that was not first, or took an auto-correction back).
     */
    fun learn(word: String, previous: String?, inMain: Boolean, strong: Boolean) {
        val key = word.lowercase()
        if (!learnable(key)) return
        val now = clock()
        val e = words.getOrPut(key) { Entry(display = if (word.any { it.isUpperCase() }) word else key, count = 0f, lastUsed = now, promoted = false) }
        e.count = decayed(e.count, e.lastUsed, now) + if (strong) 2f else 1f
        e.lastUsed = now
        if (word.any { it.isUpperCase() } && e.display == key) e.display = word       // keep the capitalised form of names
        if (!inMain && e.count >= PROMOTE_AT) e.promoted = true
        if (previous != null && learnable(previous.lowercase())) {
            val pk = previous.lowercase() + SEP + key
            val p = pairs.getOrPut(pk) { Pair(0f, now) }
            p.count = decayed(p.count, p.lastUsed, now) + 1f
            p.lastUsed = now
        }
        version++
        trim(keepWord = key, keepPair = if (previous != null) previous.lowercase() + SEP + key else null)
    }

    /** True for a word the main dictionary lacks but the user has used enough to count as theirs. */
    fun isKnown(word: String): Boolean = words[word.lowercase()]?.promoted == true

    /** Extra ranking points for [word] in the dictionary engine's score units (typical scores are 300,000 to 500,000). */
    fun bonus(word: String): Int {
        val e = words[word.lowercase()] ?: return 0
        return (MAX_BONUS * (1.0 - exp(-effective(e.count, e.lastUsed) / 3.0))).toInt()
    }

    /** Extra ranking points for [word] directly after [previous]. */
    fun pairBonus(previous: String?, word: String): Int {
        if (previous == null) return 0
        val p = pairs[previous.lowercase() + SEP + word.lowercase()] ?: return 0
        return (MAX_PAIR_BONUS * (1.0 - exp(-effective(p.count, p.lastUsed) / 2.0))).toInt()
    }

    /** Personal words (promoted) that start with [prefix], most used first. */
    fun wordsWithPrefix(prefix: String, limit: Int): List<String> {
        val p = prefix.lowercase()
        return words.entries.asSequence().filter { it.value.promoted && it.key.startsWith(p) && it.key != p }
            .sortedByDescending { effective(it.value.count, it.value.lastUsed) }.take(limit).map { it.value.display }.toList()
    }

    /** All promoted personal words with their ranking bonus, for the gesture decoder (which can swipe them). */
    fun promotedWords(): List<kotlin.Pair<String, Int>> =
        words.values.filter { it.promoted }.map { it.display to bonus(it.display) }

    /** Words (promoted or not) with a ranking bonus, lower-case, so the gesture decoder can boost dictionary words the user favours. */
    /** Every learned pair as (word before, word, strength 0 to 1), for the swipe decoder. */
    fun pairStrengths(): List<Triple<String, String, Float>> =
        pairs.map { (k, p) -> Triple(k.substringBefore(SEP), k.substringAfter(SEP), (1.0 - exp(-effective(p.count, p.lastUsed) / 2.0)).toFloat()) }

    fun boosts(): Map<String, Int> = words.mapValues { bonus(it.key) }.filterValues { it > 0 }

    // ---- inspection and control (the learned-data screen)

    class WordInfo(val word: String, val uses: Int, val lastUsed: Long, val promoted: Boolean)

    fun entries(): List<WordInfo> = words.map { WordInfo(it.value.display, Math.round(it.value.count), it.value.lastUsed, it.value.promoted) }
        .sortedByDescending { it.uses }

    fun remove(word: String) { val k = word.lowercase(); words.remove(k); pairs.keys.removeAll { it.startsWith(k + SEP) || it.endsWith(SEP + k) }; version++ }
    fun clear() { words.clear(); pairs.clear(); version++ }

    // ---- storage

    fun save(file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((k, e) in words) w.write("W\t$k\t${e.display}\t${e.count}\t${e.lastUsed}\t${if (e.promoted) 1 else 0}\n")
            for ((k, p) in pairs) w.write("P\t${k.replace(SEP, '\t')}\t${p.count}\t${p.lastUsed}\n")
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun load(file: File) {
        words.clear(); pairs.clear()
        if (!file.isFile) return
        file.forEachLine { line ->
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "W" -> words[f[1]] = Entry(f[2], f[3].toFloat(), f[4].toLong(), f[5] == "1")
                    "P" -> pairs[f[1] + SEP + f[2]] = Pair(f[3].toFloat(), f[4].toLong())
                }
            } catch (e: Exception) { /* skip a damaged line */ }
        }
        version++
        trim()
    }

    // ---- internals

    private fun learnable(k: String) = k.length in 2..MAX_WORD && k.all { it.isLetter() || it == '\'' } && k.any { it.isLetter() }

    private fun effective(count: Float, lastUsed: Long): Float = decayed(count, lastUsed, clock())

    /** Use counts fade with a half-life of [HALF_LIFE_DAYS] days, so old habits make way for new ones. */
    private fun decayed(count: Float, lastUsed: Long, now: Long): Float {
        val days = ((now - lastUsed) / 86_400_000.0).coerceAtLeast(0.0)
        return (count * 0.5.pow(days / HALF_LIFE_DAYS)).toFloat()
    }

    /** Forgets the least used, longest unused entries once over the limit (down to 90% so it does not run on every word). */
    private fun trim(keepWord: String? = null, keepPair: String? = null) {
        if (words.size > maxWords) {
            val drop = words.size - (maxWords * 0.9).toInt()
            // The entry just used is exempt: a new word always has the lowest count and would otherwise vanish at once.
            words.entries.filter { it.key != keepWord }.sortedWith(compareBy({ effective(it.value.count, it.value.lastUsed) }, { it.value.lastUsed })).take(drop).forEach { words.remove(it.key) }
        }
        if (pairs.size > maxPairs) {
            val drop = pairs.size - (maxPairs * 0.9).toInt()
            pairs.entries.filter { it.key != keepPair }.sortedWith(compareBy({ effective(it.value.count, it.value.lastUsed) }, { it.value.lastUsed })).take(drop).forEach { pairs.remove(it.key) }
        }
    }

    companion object {
        const val SEP = '\u0001'
        const val PROMOTE_AT = 2f
        const val MAX_BONUS = 70_000
        const val MAX_PAIR_BONUS = 50_000
        const val MAX_WORD = 24
        const val HALF_LIFE_DAYS = 90.0
        @Suppress("unused") private val LN2 = ln(2.0)
    }
}
