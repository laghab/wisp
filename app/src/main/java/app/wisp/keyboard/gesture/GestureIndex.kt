package app.wisp.keyboard.gesture

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer

/**
 * The words gesture typing can produce, in a compact file that is memory-mapped, so a language costs address space
 * and not heap. Letters are stored as small indexes into the language's alphabet, grouped by first letter (the
 * decoder only scans the groups near where the swipe started) and ordered by word frequency inside each group.
 *
 * File layout (little endian):
 *   header: magic, version, alphabetSize, count, recBase, foldBase, origBase, size (8 ints)
 *   alphabet: alphabetSize ints (code points), then bucketStart: alphabetSize+1 ints (first record of each first letter)
 *   records at recBase, 12 bytes each: foldedOffset:int, originalOffset:int, foldedLen:byte, originalLen:byte, prob:byte, lastLetter:byte
 *   folded pool at foldBase (one byte per letter: alphabet index), original pool at origBase (UTF-8)
 */
class GestureIndex private constructor(
    private val buf: ByteBuffer,
    val alphabet: IntArray,
    val count: Int,
    private val bucketStart: IntArray,
    private val recBase: Int,
    private val foldBase: Int,
    private val origBase: Int,
    val fileSize: Int,
) {
    fun bucketStart(letter: Int) = bucketStart[letter]
    fun bucketEnd(letter: Int) = bucketStart[letter + 1]

    private fun rec(i: Int) = recBase + i * REC

    fun foldedLen(i: Int) = buf.get(rec(i) + 8).toInt() and 0xFF
    fun prob(i: Int) = buf.get(rec(i) + 10).toInt() and 0xFF
    /** Alphabet index of letter [j] of word [i]. */
    fun letter(i: Int, j: Int) = buf.get(foldBase + buf.getInt(rec(i)) + j).toInt() and 0xFF
    /** Stored in the record itself so the decoder's first check is a single read. */
    fun lastLetter(i: Int) = buf.get(rec(i) + 11).toInt() and 0xFF

    fun original(i: Int): String {
        val r = rec(i)
        val off = origBase + buf.getInt(r + 4)
        val len = buf.get(r + 9).toInt() and 0xFF
        val bytes = ByteArray(len)
        for (k in 0 until len) bytes[k] = buf.get(off + k)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x31494757      // "WGI1"
        private const val REC = 12
        private const val HEADER = 8 * 4

        fun open(file: File): GestureIndex {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
                require(map.getInt(0) == MAGIC) { "not a gesture index: $file" }
                val aSize = map.getInt(8); val count = map.getInt(12)
                val recBase = map.getInt(16); val foldBase = map.getInt(20); val origBase = map.getInt(24); val size = map.getInt(28)
                val alphabet = IntArray(aSize) { map.getInt(HEADER + it * 4) }
                val buckets = IntArray(aSize + 1) { map.getInt(HEADER + aSize * 4 + it * 4) }
                return GestureIndex(map, alphabet, count, buckets, recBase, foldBase, origBase, size)
            }
        }

        /** Alphabet index of [word]'s letters, or null when it cannot be swiped on this alphabet. Punctuation is skipped; digits reject the word. */
        fun fold(word: String, alphabetIndex: Map<Int, Int>, out: ByteArray): Int {
            var n = 0
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i); i += Character.charCount(cp)
                if (Character.isDigit(cp)) return -1
                if (!Character.isLetter(cp)) continue                     // apostrophes, hyphens
                val lc = Character.toLowerCase(cp)
                val idx = alphabetIndex[lc]
                    ?: alphabetIndex[Normalizer.normalize(String(Character.toChars(lc)), Normalizer.Form.NFD).codePointAt(0)]   // é -> e when the layout has no é key
                    ?: return -1
                if (n >= 255) return -1
                out[n++] = idx.toByte()
            }
            return if (n >= 2) n else -1
        }

        /**
         * Gesture typing should favour everyday lowercase words. The dictionary rates acronyms and names almost as
         * high as common words ("ADHD" 91, "hello" 120), so they are pushed down: a name loses [NAME_PENALTY]
         * probability points, an acronym [ACRONYM_PENALTY]. Words with an uppercase letter that are not at the start
         * count as names/acronyms too (McKee, iPhone).
         */
        fun adjustedProbability(word: String, p: Int): Int {
            val letters = word.filter { it.isLetter() }
            if (letters.length < 2) return p
            val upper = letters.count { it.isUpperCase() }
            return when {
                upper == 0 -> p
                upper == letters.length -> p - ACRONYM_PENALTY
                else -> p - NAME_PENALTY
            }
        }

        const val NAME_PENALTY = 45
        const val ACRONYM_PENALTY = 90

        /**
         * Builds an index file from (word, probability) pairs. Words that differ only in case/punctuation keep the most
         * probable spelling; words below [minProb] are left out.
         */
        fun build(words: Sequence<Pair<String, Int>>, alphabet: IntArray, minProb: Int, out: File) {
            val aIdx = alphabet.withIndex().associate { it.value to it.index }
            val tmp = ByteArray(256)
            class Entry(val folded: ByteArray, val original: String, val prob: Int)
            val best = HashMap<String, Entry>()
            for ((w, raw) in words) {
                val p = adjustedProbability(w, raw)
                if (p < minProb) continue
                val n = fold(w, aIdx, tmp)
                if (n < 0) continue
                val folded = tmp.copyOf(n)
                val key = String(folded, Charsets.ISO_8859_1)
                val cur = best[key]
                if (cur == null || p > cur.prob || (p == cur.prob && w.length < cur.original.length)) best[key] = Entry(folded, w, p)
            }
            val sorted = best.values.sortedWith(compareBy<Entry> { it.folded[0].toInt() and 0xFF }.thenByDescending { it.prob })
            val aSize = alphabet.size
            val bucket = IntArray(aSize + 1)
            for (e in sorted) bucket[(e.folded[0].toInt() and 0xFF) + 1]++
            for (i in 1..aSize) bucket[i] += bucket[i - 1]

            val recBase = HEADER + aSize * 4 + (aSize + 1) * 4
            val foldBase = recBase + sorted.size * REC
            val foldedSize = sorted.sumOf { it.folded.size }
            val origBase = foldBase + foldedSize
            val origBytes = sorted.map { it.original.toByteArray(Charsets.UTF_8) }
            val total = origBase + origBytes.sumOf { it.size }

            val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(MAGIC).putInt(2).putInt(aSize).putInt(sorted.size).putInt(recBase).putInt(foldBase).putInt(origBase).putInt(total)
            for (c in alphabet) b.putInt(c)
            for (v in bucket) b.putInt(v)
            var fo = 0; var oo = 0
            for ((i, e) in sorted.withIndex()) {
                val ob = origBytes[i]
                require(ob.size < 256) { "word too long: ${e.original}" }
                b.putInt(fo).putInt(oo).put(e.folded.size.toByte()).put(ob.size.toByte()).put(e.prob.coerceIn(0, 255).toByte()).put(e.folded.last())
                fo += e.folded.size; oo += ob.size
            }
            for (e in sorted) b.put(e.folded)
            for (ob in origBytes) b.put(ob)
            out.parentFile?.mkdirs()
            val tmpFile = File(out.path + ".tmp")
            tmpFile.writeBytes(b.array())
            if (!tmpFile.renameTo(out)) { out.delete(); tmpFile.renameTo(out) }
        }
    }
}
