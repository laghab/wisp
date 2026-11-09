package app.wisp.keyboard.gesture

import java.util.Random

/** Measures how often the decoder finds the intended word on synthetic swipes. Shared by the desktop tests and the device self-tests. */
object GestureEval {

    class Report(val noise: Float, val trials: Int, val top1: Float, val top3: Float, val top5: Float, val p50Us: Long, val p95Us: Long, val p99Us: Long, val misses: List<String>, val avgScanned: Int = 0, val avgAligned: Int = 0, val avgEnd: Int = 0, val avgLength: Int = 0, val avgVisit: Int = 0) {
        override fun toString() = "noise=%.2f n=%d top1=%.1f%% top3=%.1f%% top5=%.1f%% decode p50=%dus p95=%dus p99=%dus (avg words: scanned %d, end-ok %d, length-ok %d, visit-ok %d, aligned %d)"
            .format(noise, trials, top1 * 100, top3 * 100, top5 * 100, p50Us, p95Us, p99Us, avgScanned, avgEnd, avgLength, avgVisit, avgAligned)
    }

    /**
     * Words are drawn the way people use them: common words far more often than rare ones (weight grows with the
     * word's probability), and only words of three or more letters, since two-letter words are usually tapped.
     */
    fun run(index: GestureIndex, geo: GestureGeometry, noise: Float, trials: Int, seed: Long, habit: (SyntheticGestures) -> Unit = {}, setup: (GestureDecoder) -> Unit = {}): Report {
        val decoder = GestureDecoder(index, geo).also(setup)
        val gen = SyntheticGestures(geo, seed).also(habit)
        val pick = Random(seed xor 0x5DEECE66DL)
        // cumulative weights over eligible words
        val ids = ArrayList<Int>(); val cumW = ArrayList<Double>(); var total = 0.0
        for (i in 0 until index.count) {
            if (index.foldedLen(i) < 3) continue
            total += Math.exp(0.06 * index.prob(i)); ids.add(i); cumW.add(total)
        }
        val out = GestureDecoder.Results(10)
        var h1 = 0; var h3 = 0; var h5 = 0
        val times = LongArray(trials); val misses = ArrayList<String>()
        var sc = 0L; var en = 0L; var le = 0L; var vi = 0L; var al = 0L
        // warm up the JIT so the first timings are not dominated by compilation
        repeat(30) { val w = ids[pick.nextInt(ids.size)]; val t = gen.generate(letters(index, w), noise); decoder.decode(t.xs, t.ys, t.n, out) }
        for (t in 0 until trials) {
            val r = pick.nextDouble() * total
            var lo = 0; var hi = cumW.size - 1
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (cumW[mid] < r) lo = mid + 1 else hi = mid }
            val w = ids[lo]
            val truth = index.original(w)
            val trace = gen.generate(letters(index, w), noise)
            val t0 = System.nanoTime()
            decoder.decode(trace.xs, trace.ys, trace.n, out)
            times[t] = System.nanoTime() - t0
            sc += decoder.statScanned; en += decoder.statEnd; le += decoder.statLength; vi += decoder.statVisit; al += decoder.statAligned
            val rank = (0 until out.count).firstOrNull { out.word(it) == truth } ?: Int.MAX_VALUE
            if (rank == 0) h1++; if (rank < 3) h3++; if (rank < 5) h5++
            if (rank >= 3 && misses.size < 12) misses.add("$truth -> [${(0 until minOf(3, out.count)).joinToString { out.word(it) }}]")
        }
        times.sort()
        return Report(noise, trials, h1 / trials.toFloat(), h3 / trials.toFloat(), h5 / trials.toFloat(), times[trials / 2] / 1000, times[(trials * 95) / 100] / 1000, times[(trials * 99) / 100] / 1000, misses, (sc / trials).toInt(), (al / trials).toInt(), (en / trials).toInt(), (le / trials).toInt(), (vi / trials).toInt())
    }

    private fun letters(index: GestureIndex, w: Int) = IntArray(index.foldedLen(w)) { index.letter(w, it) }
}
