package app.wisp.keyboard.training

import java.io.File
import java.util.Random

/** How often each practice word was swiped and decoded wrongly, so practice can favour the words that give this user trouble. */
class TrainingStats {
    private class Row(var attempts: Int, var misses: Int)
    private val rows = HashMap<String, Row>()

    val totalAttempts get() = rows.values.sumOf { it.attempts }

    fun record(word: String, decodedFirstTry: Boolean) {
        val r = rows.getOrPut(word) { Row(0, 0) }
        r.attempts++
        if (!decodedFirstTry) r.misses++
    }

    /** Words never tried count as slightly interesting; words often missed count most. */
    fun weight(word: String): Double {
        val r = rows[word] ?: return UNSEEN_WEIGHT
        return 1.0 + 4.0 * (r.misses + 0.5) / (r.attempts + 1.0)
    }

    /** Picks [count] different words from [candidates], favouring the hard ones. */
    fun pick(candidates: List<String>, count: Int, rnd: Random): List<String> {
        if (candidates.size <= count) return candidates.shuffled(rnd)
        val pool = candidates.toMutableList(); val out = ArrayList<String>(count)
        repeat(count) {
            val weights = DoubleArray(pool.size) { weight(pool[it]) }
            var r = rnd.nextDouble() * weights.sum(); var i = 0
            while (i < pool.size - 1 && r >= weights[i]) { r -= weights[i]; i++ }
            out.add(pool.removeAt(i))
        }
        return out
    }

    fun clear() = rows.clear()

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w -> for ((k, r) in rows) w.write("$k\t${r.attempts}\t${r.misses}\n") }
    }

    fun load(file: File) {
        rows.clear()
        if (!file.isFile) return
        file.forEachLine { l -> val f = l.split('\t'); try { rows[f[0]] = Row(f[1].toInt(), f[2].toInt()) } catch (e: Exception) { } }
    }

    companion object { const val UNSEEN_WEIGHT = 2.0 }
}
