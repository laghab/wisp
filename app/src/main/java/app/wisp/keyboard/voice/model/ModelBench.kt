package app.wisp.keyboard.voice.model

import android.content.Context
import android.os.Bundle
import app.wisp.keyboard.settings.WispSettings
import app.wisp.keyboard.voice.engine.DeviceProfile
import app.wisp.keyboard.voice.ipc.VoiceClient
import app.wisp.keyboard.voice.ipc.VoiceProtocol as P

/** A model's measured speed on this phone. */
class BenchResult(val loadMs: Long, val decodeMs: Long, val audioMs: Long) {
    /** How many times faster than real time the model runs (10 = a minute of speech in six seconds). */
    val timesRealTime: Float get() = if (decodeMs <= 0) 0f else audioMs / decodeMs.toFloat()

    fun toStored() = "$loadMs,$decodeMs,$audioMs"
    companion object { fun fromStored(s: String?): BenchResult? = s?.split(',')?.let { p -> if (p.size == 3) runCatching { BenchResult(p[0].toLong(), p[1].toLong(), p[2].toLong()) }.getOrNull() else null } }
}

/** Runs a model through the voice process and remembers the result. */
class ModelBench(private val context: Context) {
    private val settings get() = WispSettings.get(context)
    private val models = ModelStore(context)

    fun result(id: String): BenchResult? = BenchResult.fromStored(settings.getString("bench_$id"))

    /** Measures an installed model. Loads it (a second or two), so run it only when the user asks. [onDone] runs on the main thread; null = it failed. */
    fun run(spec: ModelSpec, onDone: (BenchResult?) -> Unit) {
        if (!models.isInstalled(spec)) { onDone(null); return }
        var client: VoiceClient? = null
        client = VoiceClient(context, object : VoiceClient.Listener {
            override fun onReady(loadMs: Long) {}
            override fun onPartial(text: String) {}
            override fun onFinal(text: String, finishMs: Long) {}
            override fun onError(message: String) { client?.unbind(); onDone(null) }
            override fun onBenchmark(loadMs: Long, decodeMs: Long, audioMs: Long) {
                val r = BenchResult(loadMs, decodeMs, audioMs)
                settings.putString("bench_${spec.id}", r.toStored())
                onDone(r)
            }
        })
        client.benchmark(Bundle().apply {
            putString(P.K_MODEL_ID, spec.id); putString(P.K_MODEL_DIR, models.dirFor(spec).path); putString(P.K_LANGUAGE, "auto")
            putInt(P.K_THREADS, DeviceProfile.recommendedThreads()); putLong(P.K_IDLE_MS, 0L)       // unload again right away
        })
    }
}
