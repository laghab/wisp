package app.wisp.keyboard.voice.llm

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.wisp.keyboard.voice.engine.DeviceProfile
import app.wisp.keyboard.voice.ipc.VoiceClient
import app.wisp.keyboard.voice.ipc.VoiceProtocol as P
import app.wisp.keyboard.voice.model.ModelSpec
import app.wisp.keyboard.voice.model.ModelStore

/**
 * One rewrite at a time, on the keyboard side: asks the voice process to run the writing-assistant model and reports the text as it grows.
 * Nothing here touches the field; the panel decides what to do with the result. All methods run on the main thread.
 */
class RewriteController(private val context: Context, private val onChange: () -> Unit) : VoiceClient.Listener {

    enum class Phase { IDLE, WORKING, DONE, FAILED }

    var phase = Phase.IDLE
        private set
    var tone = Tone.POLISH
        private set
    var result = ""
        private set
    var failure: String? = null
        private set
    var elapsedMs = 0L
        private set

    private val models = ModelStore(context)
    private val client = VoiceClient(context, this)
    private var startedAt = 0L

    /** The model that will be used, or null when none is installed or rewrite is turned off. */
    val model: ModelSpec? get() = models.activeRewrite()
    val isOff get() = models.rewriteId == "off"

    val working get() = phase == Phase.WORKING

    fun start(tone: Tone, text: String) {
        this.tone = tone
        val spec = model ?: return
        if (!LlamaBridge.available) { fail("Rewrite is not available on this phone"); return }
        result = ""; failure = null; phase = Phase.WORKING; startedAt = SystemClock.elapsedRealtime()
        val prefs = app.wisp.keyboard.settings.WispSettings.get(context)
        val idle = when (val m = prefs.getInt("voice_idle_minutes", 3)) { 0 -> 0L; in 1..Int.MAX_VALUE -> m * 60_000L; else -> -1L }
        client.rewrite(Bundle().apply {
            putString(P.K_MODEL_PATH, java.io.File(models.dirFor(spec), spec.requiredFiles.first()).path)
            putString(P.K_TONE, tone.name); putString(P.K_TEXT, text)
            putLong(P.K_IDLE_MS, idle); putInt(P.K_THREADS, DeviceProfile.recommendedThreads())
        })
        onChange()
    }

    fun cancel() {
        if (phase == Phase.WORKING) { client.cancelRewrite(); phase = Phase.IDLE; result = "" }
        onChange()
    }

    /** The keyboard is going away. */
    fun release() { client.unbind(); phase = Phase.IDLE }

    private fun fail(message: String) { failure = message; phase = Phase.FAILED; onChange() }

    override fun onReady(loadMs: Long) {}
    override fun onPartial(text: String) {}
    override fun onFinal(text: String, finishMs: Long) {}
    override fun onError(message: String) { if (phase == Phase.WORKING) fail(message) }

    override fun onRewritePiece(text: String) { if (phase == Phase.WORKING) { result = text; onChange() } }
    override fun onRewriteDone(text: String) { if (phase != Phase.WORKING) return; result = text; elapsedMs = SystemClock.elapsedRealtime() - startedAt; phase = Phase.DONE; onChange() }
    override fun onRewriteError(message: String) { if (phase == Phase.WORKING) fail(message) }
}
