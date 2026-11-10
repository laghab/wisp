package app.wisp.keyboard.voice.ipc

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.wisp.keyboard.voice.audio.WavReader
import app.wisp.keyboard.voice.engine.DeviceProfile
import app.wisp.keyboard.voice.engine.PcmBuffer
import app.wisp.keyboard.voice.engine.VoiceEngine
import app.wisp.keyboard.voice.ipc.VoiceProtocol as P
import app.wisp.keyboard.voice.llm.RewriteEngine
import app.wisp.keyboard.voice.llm.Tone
import app.wisp.keyboard.voice.text.LoopGuard
import app.wisp.keyboard.voice.text.NonSpeech
import app.wisp.keyboard.voice.text.TextProcessor
import com.k2fsa.sherpa.onnx.Vad
import java.io.File

/**
 * The speech engine, in its own process (`:voice`). It exists to hold the model: it loads it when a dictation starts, and after
 * the idle timeout it unloads it and exits, so the memory really goes back to the system (design.md D2/D3).
 *
 * Everything happens on one worker thread. Audio arrives as 32 ms chunks; a voice-activity detector cuts the speech at natural
 * pauses and each finished stretch is decoded while the user keeps talking. With live text on, the speech still in progress is
 * decoded again every moment, so the words appear while they are being said. On STOP only the tail is left to decode.
 */
class VoiceService : Service() {
    private val workerThread = HandlerThread("wisp-voice", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val worker = Handler(workerThread.looper)
    private val engine by lazy { VoiceEngine(this) }
    private val rewriter = RewriteEngine()

    private var session: Session? = null
    private var client: Messenger? = null
    private var idleMs = DEFAULT_IDLE_MS
    private val idleExit = Runnable { exitNow("idle") }

    private val inbox = Messenger(Handler(Looper.getMainLooper()) { m ->
        val data = Bundle(m.data); val what = m.what; val reply = m.replyTo
        // A rewrite in progress blocks the worker, so cancelling it is done here, on the service's own thread, not queued behind it.
        if (what == P.REWRITE_CANCEL) rewriter.cancel() else worker.post { dispatch(what, data, reply) }
        true
    })

    override fun onBind(intent: Intent): IBinder = inbox.binder

    // Screen off: nobody is dictating, so give the model's memory back at once.
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { worker.post { if (session == null) exitNow("screen off") } }
    }

    override fun onCreate() { super.onCreate(); registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    override fun onDestroy() { try { unregisterReceiver(screenOff) } catch (_: Exception) { }; super.onDestroy() }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) worker.post { if (session == null) exitNow("memory pressure") }
    }

    // ------------------------------------------------------------------ worker thread

    private fun dispatch(what: Int, data: Bundle, replyTo: Messenger?) {
        try {
            when (what) {
                P.START -> start(data, replyTo)
                P.PCM -> pcm(data.getShortArray(P.K_PCM) ?: return)
                P.STOP -> stop()
                P.CANCEL -> cancel()
                P.BENCH -> benchmark(data, replyTo)
                P.REWRITE -> rewrite(data, replyTo)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "voice request $what failed", t)
            endSession()
            send(P.ERROR) { putString(P.K_MESSAGE, t.message ?: t.javaClass.simpleName) }
            startIdleTimer()
        }
    }

    private fun start(data: Bundle, replyTo: Messenger?) {
        worker.removeCallbacks(idleExit)
        client = replyTo ?: client
        endSession()
        rewriter.release()                      // one model in memory at a time
        idleMs = data.getLong(P.K_IDLE_MS, DEFAULT_IDLE_MS)
        // Debug builds can make the load slow, to prove that nothing said while the model loads is lost.
        if (app.wisp.keyboard.BuildConfig.DEBUG) Thread.sleep(app.wisp.keyboard.settings.WispSettings.get(this).getLong("debug_load_delay_ms", 0L))
        val loadMs = engine.load(
            data.getString(P.K_MODEL_ID) ?: error("no model"), File(data.getString(P.K_MODEL_DIR) ?: error("no model folder")),
            data.getString(P.K_LANGUAGE) ?: "auto", data.getInt(P.K_THREADS, DeviceProfile.recommendedThreads()),
        )
        session = Session(live = data.getBoolean(P.K_LIVE, true), vad = engine.newVad())
        send(P.READY) { putLong(P.K_MS, loadMs) }
    }

    /** Rewrites a text with the writing-assistant model and sends it back as it is generated. The speech model is freed first. */
    private fun rewrite(data: Bundle, replyTo: Messenger?) {
        worker.removeCallbacks(idleExit)
        client = replyTo ?: client
        endSession(); engine.release()          // one model in memory at a time
        idleMs = data.getLong(P.K_IDLE_MS, DEFAULT_IDLE_MS)
        val path = data.getString(P.K_MODEL_PATH) ?: error("no rewrite model")
        val out = try {
            rewriter.rewrite(File(path), Tone.byName(data.getString(P.K_TONE)), data.getString(P.K_TEXT) ?: "", data.getInt(P.K_THREADS, DeviceProfile.recommendedThreads())) { sofar ->
                send(P.REWRITE_PIECE) { putString(P.K_TEXT, sofar) }
            }
        } catch (t: Throwable) { Log.e(TAG, "rewrite failed", t); null }
        if (out.isNullOrBlank()) send(P.REWRITE_ERROR) { putString(P.K_MESSAGE, if (!rewriter.available) "Rewrite is not available on this phone" else "The rewrite did not finish. Your text is unchanged.") }
        else send(P.REWRITE_DONE) { putString(P.K_TEXT, out) }
        startIdleTimer()
    }

    /** Loads the model and times it on a short recorded sentence: the figure shown next to each model in the list. */
    private fun benchmark(data: Bundle, replyTo: Messenger?) {
        worker.removeCallbacks(idleExit)
        client = replyTo ?: client
        endSession()
        idleMs = data.getLong(P.K_IDLE_MS, 0L)
        val spec = data.getString(P.K_MODEL_ID) ?: error("no model")
        val loadMs = engine.load(spec, File(data.getString(P.K_MODEL_DIR) ?: error("no model folder")), data.getString(P.K_LANGUAGE) ?: "auto", data.getInt(P.K_THREADS, DeviceProfile.recommendedThreads()))
        val clip = assets.open("bench.wav").use { WavReader.readBytes(it.readBytes()) }
        val samples = FloatArray(clip.size) { clip[it] / 32768f }
        engine.transcribe(samples)                                        // first run warms the model up and is not counted
        val t0 = SystemClock.elapsedRealtime()
        val text = engine.transcribe(samples)
        val decodeMs = SystemClock.elapsedRealtime() - t0
        send(P.BENCH_RESULT) { putLong(P.K_MS, loadMs); putLong(P.K_DECODE_MS, decodeMs); putLong(P.K_AUDIO_MS, clip.size * 1000L / P.SAMPLE_RATE); putString(P.K_TEXT, text) }
        startIdleTimer()
    }

    private fun pcm(shorts: ShortArray) {
        val s = session ?: return
        s.pcm.append(shorts)
        feedVad(s)
        if (s.live && !s.tickPending) { s.tickPending = true; worker.postDelayed(s.tick, TICK_FIRST_MS) }
    }

    private fun feedVad(s: Session) {
        while (s.pcm.size - s.vadFed >= VoiceEngine.VAD_WINDOW) {
            s.vad.acceptWaveform(s.pcm.copy(s.vadFed, s.vadFed + VoiceEngine.VAD_WINDOW))
            s.vadFed += VoiceEngine.VAD_WINDOW
            drainVad(s)
        }
    }

    /** Every speech segment the detector has closed is decoded now, while the user may still be talking. */
    private fun drainVad(s: Session) {
        val v = s.vad
        while (!v.empty()) {
            val seg = v.front(); v.pop()
            // Pad a little on both sides: detector onsets and offsets clip consonants.
            val pre = (0.25f * P.SAMPLE_RATE).toInt(); val post = (0.35f * P.SAMPLE_RATE).toInt()
            val from = maxOf(s.lastSegmentEnd, seg.start.toInt() - pre)
            val to = minOf(s.pcm.size, seg.start.toInt() + seg.samples.size + post)
            if (to - from < P.SAMPLE_RATE / 5) continue
            s.lastSegmentEnd = to
            val text = LoopGuard.clean(NonSpeech.strip(engine.transcribe(s.pcm.copy(from, to))), (to - from) / P.SAMPLE_RATE.toFloat())
            if (text.isNotBlank()) s.parts.add(text)
            s.openText = ""
            publish(s)
        }
    }

    /** Re-reads the speech that is still going on, so its words show up before the user pauses. */
    private fun live(s: Session) {
        s.tickPending = false
        if (session !== s) return
        val from = s.lastSegmentEnd; val to = s.pcm.size
        val seconds = (to - from) / P.SAMPLE_RATE.toFloat()
        if (seconds >= LIVE_MIN_SECONDS && seconds <= engine.maxChunkSeconds && s.vad.isSpeechDetected()) {
            s.openText = LoopGuard.clean(NonSpeech.strip(engine.transcribe(s.pcm.copy(from, to))), seconds)
            publish(s)
        }
        // The next look comes after this one is done, so a slow phone is never swamped by its own live text.
        if (session === s) { s.tickPending = true; worker.postDelayed(s.tick, TICK_GAP_MS) }
    }

    private fun publish(s: Session) {
        val text = TextProcessor.joinSegments(s.parts + s.openText)
        if (text == s.lastPublished) return
        s.lastPublished = text
        send(P.PARTIAL) { putString(P.K_TEXT, text) }
    }

    private fun stop() {
        val s = session ?: return
        val t0 = SystemClock.elapsedRealtime()
        // Feed what is left, close the last segment, and decode the tail.
        val rest = s.pcm.size - s.vadFed
        if (rest > 0) { s.vad.acceptWaveform(s.pcm.copy(s.vadFed, s.pcm.size)); s.vadFed = s.pcm.size }
        s.vad.flush()
        drainVad(s)
        val text = TextProcessor.joinSegments(s.parts)
        endSession()
        send(P.FINAL) { putString(P.K_TEXT, text); putLong(P.K_MS, SystemClock.elapsedRealtime() - t0) }
        startIdleTimer()
    }

    private fun cancel() { endSession(); startIdleTimer() }

    private fun endSession() {
        session?.let { it.vad.release(); worker.removeCallbacks(it.tick) }
        session = null
    }

    private fun startIdleTimer() {
        worker.removeCallbacks(idleExit)
        when {
            idleMs < 0 -> {}                              // never unload on a timer
            idleMs == 0L -> exitNow("unload after each dictation")
            else -> worker.postDelayed(idleExit, idleMs)
        }
    }

    /** Unloads the model and ends this process; the keyboard side unbinds on EXITING and binds again at the next dictation. */
    private fun exitNow(why: String) {
        Log.i(TAG, "exiting ($why)")
        endSession(); engine.release(); rewriter.release()
        send(P.EXITING) {}
        Process.killProcess(Process.myPid())
    }

    private fun send(what: Int, fill: Bundle.() -> Unit) {
        val c = client ?: return
        try { c.send(Message.obtain(null, what).apply { data = Bundle().apply(fill) }) } catch (e: Exception) { Log.w(TAG, "client gone", e) }
    }

    private inner class Session(val live: Boolean, val vad: Vad) {
        val pcm = PcmBuffer()
        var vadFed = 0
        var lastSegmentEnd = 0
        val parts = ArrayList<String>()
        var openText = ""
        var lastPublished = ""
        var tickPending = false
        val tick = Runnable { live(this) }
    }

    companion object {
        private const val TAG = "VoiceService"
        const val DEFAULT_IDLE_MS = 3 * 60_000L
        private const val TICK_FIRST_MS = 700L
        private const val TICK_GAP_MS = 150L
        private const val LIVE_MIN_SECONDS = 0.7f
    }
}
