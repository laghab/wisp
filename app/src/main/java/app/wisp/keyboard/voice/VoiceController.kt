package app.wisp.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.wisp.keyboard.voice.audio.MicCapture
import app.wisp.keyboard.voice.engine.DeviceProfile
import app.wisp.keyboard.voice.ipc.VoiceClient
import app.wisp.keyboard.voice.ipc.VoiceProtocol as P
import app.wisp.keyboard.voice.model.ModelStore
import java.io.File

/**
 * One dictation at a time, on the keyboard side: opens the microphone, keeps the audio safe while the model loads, streams it to
 * the voice process, and reports live text and the final transcript. The model is only ever loaded because of a call to [start]
 * (design D3); until it is ready, what is said is held in a pre-roll buffer, so no words are lost.
 *
 * All methods run on the main thread.
 */
class VoiceController(
    private val context: Context,
    /** Something the panel shows changed (phase, text). */
    private val onChange: () -> Unit,
    /** The final transcript of a dictation (not empty). */
    private val onResult: (String) -> Unit,
) : VoiceClient.Listener {

    enum class Phase { IDLE, WARMING, LISTENING, FINISHING, FAILED }
    enum class Problem { NO_PERMISSION, NO_MODEL, MIC_BUSY, ENGINE }
    class Failure(val problem: Problem, val message: String)

    var phase = Phase.IDLE
        private set
    var partial = ""
        private set
    var failure: Failure? = null
        private set
    /** 0..1, written by the microphone thread; read by the panel's animation. */
    @Volatile var level = 0f
        private set
    var startedAt = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private val models = ModelStore(context)
    private val client = VoiceClient(context, this)
    private var mic: MicCapture? = null
    private val preRoll = ArrayList<ShortArray>()
    private var ready = false
    private var stopWhenReady = false

    private val prefs get() = app.wisp.keyboard.settings.WispSettings.get(context)

    val isActive get() = phase == Phase.WARMING || phase == Phase.LISTENING || phase == Phase.FINISHING

    fun hasMicPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening; it keeps going until [stop] (a tap on the mic again). */
    fun start(): Boolean {
        if (isActive) return true
        failure = null; partial = ""
        if (!hasMicPermission()) return fail(Problem.NO_PERMISSION, "Wisp needs microphone access to hear you. Tap the mic to allow it.")
        val model = models.active() ?: return fail(Problem.NO_MODEL, "No speech model yet. Tap the mic to choose one.")
        ready = false; stopWhenReady = false; preRoll.clear()
        phase = Phase.WARMING; startedAt = android.os.SystemClock.elapsedRealtime()
        val replay = File(context.filesDir, "debug_mic.wav")
        val m = MicCapture(onFrame = { f -> main.post { frame(f) } }, onLevel = { level = it }, debugReplay = replay)
        if (!m.start()) return fail(Problem.MIC_BUSY, "The microphone is busy")
        mic = m
        client.start(Bundle().apply {
            putString(P.K_MODEL_ID, model.id); putString(P.K_MODEL_DIR, models.dirFor(model).path); putString(P.K_LANGUAGE, "auto")
            putLong(P.K_IDLE_MS, idleMs()); putInt(P.K_THREADS, DeviceProfile.recommendedThreads()); putBoolean(P.K_LIVE, prefs.getBoolean("voice_live_text", true))
        })
        onChange()
        return true
    }

    /** Ends the dictation; the transcript arrives through [onResult]. */
    fun stop() {
        when (phase) {
            Phase.LISTENING -> { mic?.stop(); mic = null; phase = Phase.FINISHING; client.stop() }
            Phase.WARMING -> { mic?.stop(); mic = null; stopWhenReady = true; phase = Phase.FINISHING }   // the audio is in the pre-roll
            else -> return
        }
        level = 0f
        onChange()
    }

    /** Throws the dictation away. */
    fun cancel() {
        if (!isActive && phase != Phase.FAILED) return
        mic?.stop(); mic = null; preRoll.clear(); ready = false; stopWhenReady = false
        if (isActive) client.cancel()
        phase = Phase.IDLE; partial = ""; failure = null; level = 0f
        onChange()
    }

    /** The keyboard is going away. */
    fun release() { mic?.stop(); mic = null; client.unbind(); phase = Phase.IDLE }

    private fun idleMs(): Long = when (val minutes = prefs.getInt("voice_idle_minutes", 3)) { 0 -> 0L; in 1..Int.MAX_VALUE -> minutes * 60_000L; else -> -1L }

    private fun frame(f: ShortArray) {
        if (phase != Phase.WARMING && phase != Phase.LISTENING) return
        if (ready) client.pcm(f) else if (preRoll.size < MAX_PREROLL_FRAMES) preRoll.add(f)
    }

    private fun fail(p: Problem, message: String): Boolean {
        mic?.stop(); mic = null; preRoll.clear()
        failure = Failure(p, message); phase = Phase.FAILED; level = 0f
        onChange()
        return false
    }

    // ---- from the voice process

    override fun onReady(loadMs: Long) {
        ready = true
        for (f in preRoll) client.pcm(f)
        preRoll.clear()
        if (stopWhenReady) { stopWhenReady = false; client.stop() } else if (phase == Phase.WARMING) phase = Phase.LISTENING
        onChange()
    }

    override fun onPartial(text: String) { partial = text; onChange() }

    override fun onFinal(text: String, finishMs: Long) {
        if (app.wisp.keyboard.BuildConfig.DEBUG) android.util.Log.i("WISP_PROBE", "voice_finish_ms=$finishMs words=${text.split(' ').size}")
        phase = Phase.IDLE; partial = ""; ready = false; level = 0f
        onChange()
        if (text.isNotBlank()) onResult(text)
    }

    override fun onError(message: String) { fail(Problem.ENGINE, message) }

    private companion object { const val MAX_PREROLL_FRAMES = 31 * 60 }   // a minute of audio
}
