package app.wisp.keyboard.voice.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.wisp.keyboard.voice.ipc.VoiceProtocol.SAMPLE_RATE
import app.wisp.keyboard.voice.model.ModelCatalog
import app.wisp.keyboard.voice.model.ModelSpec
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * The loaded speech recognizer (from Handy's TranscriptionEngine). Only ever used from the voice process's worker thread,
 * so loading, decoding and unloading cannot race. It is loaded when a dictation starts and released when the process unloads it.
 */
class VoiceEngine(private val context: Context) {
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null
    var spec: ModelSpec? = null
        private set

    /** Loads [modelId] from [dir] unless it is already loaded with the same settings. Returns the load time in ms (0 if it was loaded). */
    fun load(modelId: String, dir: File, language: String, threads: Int): Long {
        val s = ModelCatalog.byId(modelId) ?: throw IllegalArgumentException("Unknown model $modelId")
        val lang = if (s.supportsLanguageSelection) language else ModelCatalog.AUTO
        val key = "$modelId|$lang|$threads"
        if (recognizer != null && loadedKey == key) return 0
        release()
        val t0 = SystemClock.elapsedRealtime()
        val modelConfig = s.buildConfig(dir, lang).apply { numThreads = threads; provider = "cpu"; debug = false }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        loadedKey = key; spec = s
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "loaded $modelId in ${ms}ms with $threads threads")
        return ms
    }

    /** Raw text for one stretch of 16 kHz mono audio. */
    fun transcribe(samples: FloatArray): String {
        if (samples.size < SAMPLE_RATE / 10) return ""
        val r = recognizer ?: throw IllegalStateException("model not loaded")
        val stream = r.createStream()
        try { stream.acceptWaveform(samples, SAMPLE_RATE); r.decode(stream); return r.getResult(stream).text } finally { stream.release() }
    }

    val maxChunkSeconds: Float get() = spec?.maxChunkSeconds ?: 28f

    /** A Silero voice-activity detector tuned for dictation (from Handy): natural pauses mid-sentence do not split a thought. */
    fun newVad(): Vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx", threshold = 0.45f, minSilenceDuration = 0.6f, minSpeechDuration = 0.2f,
                windowSize = VAD_WINDOW, maxSpeechDuration = maxChunkSeconds,
            ),
            sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu",
        ),
    )

    fun release() {
        recognizer?.release(); recognizer = null; loadedKey = null; spec = null
    }

    companion object { private const val TAG = "VoiceEngine"; const val VAD_WINDOW = 512 }
}
