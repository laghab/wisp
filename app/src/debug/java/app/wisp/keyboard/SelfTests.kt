package app.wisp.keyboard

import android.content.Context

/** Device-side checks for code that needs the device. Each returns "PASS ..." or "FAIL ...". */
object SelfTests {

    fun all(ctx: Context): List<Pair<String, () -> String>> = listOf("voice.benchmark" to { voiceBenchmark(ctx) })

    /** Benchmarks the installed test model through the voice process (run tools/install-test-model.sh first). */
    private fun voiceBenchmark(ctx: Context): String {
        val spec = app.wisp.keyboard.voice.model.ModelCatalog.byId("moonshine-tiny-en")!!
        val bench = app.wisp.keyboard.voice.model.ModelBench(ctx)
        val latch = java.util.concurrent.CountDownLatch(1); var out: app.wisp.keyboard.voice.model.BenchResult? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { bench.run(spec) { out = it; latch.countDown() } }
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) return "FAIL timed out"
        val r = out ?: return "FAIL benchmark failed"
        val stored = bench.result(spec.id)
        val ok = stored != null && r.timesRealTime > 1f
        return (if (ok) "PASS" else "FAIL") + " load ${r.loadMs} ms, decode ${r.decodeMs} ms for ${r.audioMs} ms of speech = ${"%.1f".format(r.timesRealTime)}x real time; stored=${stored?.toStored()}"
    }
}
