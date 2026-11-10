package app.wisp.keyboard.voice.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import app.wisp.keyboard.voice.ipc.VoiceProtocol as P

/**
 * The keyboard side of the conversation with the voice process. It binds when a dictation starts and, importantly,
 * unbinds when the voice process says it is exiting: staying bound would make Android restart the process, and
 * the model would reload without anyone asking.
 */
class VoiceClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady(loadMs: Long)
        fun onPartial(text: String)
        fun onFinal(text: String, finishMs: Long)
        fun onError(message: String)
        fun onBenchmark(loadMs: Long, decodeMs: Long, audioMs: Long) {}
        fun onRewritePiece(text: String) {}
        fun onRewriteDone(text: String) {}
        fun onRewriteError(message: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private var bound = false
    private val pending = ArrayList<Message>()

    private val reply = Messenger(Handler(Looper.getMainLooper()) { m ->
        when (m.what) {
            P.READY -> listener.onReady(m.data.getLong(P.K_MS))
            P.PARTIAL -> listener.onPartial(m.data.getString(P.K_TEXT) ?: "")
            P.FINAL -> listener.onFinal(m.data.getString(P.K_TEXT) ?: "", m.data.getLong(P.K_MS))
            P.ERROR -> listener.onError(m.data.getString(P.K_MESSAGE) ?: "The speech engine stopped")
            P.BENCH_RESULT -> listener.onBenchmark(m.data.getLong(P.K_MS), m.data.getLong(P.K_DECODE_MS), m.data.getLong(P.K_AUDIO_MS))
            P.REWRITE_PIECE -> listener.onRewritePiece(m.data.getString(P.K_TEXT) ?: "")
            P.REWRITE_DONE -> listener.onRewriteDone(m.data.getString(P.K_TEXT) ?: "")
            P.REWRITE_ERROR -> listener.onRewriteError(m.data.getString(P.K_MESSAGE) ?: "The rewrite failed")
            P.EXITING -> unbind()
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            val queued = ArrayList(pending); pending.clear()
            queued.forEach { dispatch(it) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // The voice process ended (idle exit, or killed). Nothing is wrong unless a dictation was in progress.
            service = null
        }
    }

    fun start(params: Bundle) = send(P.START, params)
    fun benchmark(params: Bundle) = send(P.BENCH, params)
    fun pcm(frame: ShortArray) = send(P.PCM, Bundle().apply { putShortArray(P.K_PCM, frame) })
    fun stop() = send(P.STOP)
    fun cancel() = send(P.CANCEL)
    fun rewrite(params: Bundle) = send(P.REWRITE, params)
    fun cancelRewrite() = send(P.REWRITE_CANCEL)

    private fun send(what: Int, data: Bundle = Bundle()) {
        val m = Message.obtain(null, what).apply { this.data = data; replyTo = reply }
        if (!bound) { bound = context.bindService(Intent(context, VoiceService::class.java), connection, Context.BIND_AUTO_CREATE) }
        if (service == null) pending.add(m) else dispatch(m)
    }

    private fun dispatch(m: Message) {
        try { service?.send(m) } catch (e: Exception) { Log.w(TAG, "voice process unreachable", e); listener.onError("The speech engine is not responding") }
    }

    fun unbind() {
        if (bound) { try { context.unbindService(connection) } catch (_: IllegalArgumentException) { } }
        bound = false; service = null; pending.clear()
    }

    private companion object { const val TAG = "VoiceClient" }
}
