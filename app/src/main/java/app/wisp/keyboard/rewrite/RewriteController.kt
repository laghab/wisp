package app.wisp.keyboard.rewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.wisp.keyboard.BuildConfig
import app.wisp.net.CancelledException
import app.wisp.net.ChatClient

/**
 * One rewrite at a time: sends the text to the provider the user set up and reports the answer as it grows. Nothing here touches the
 * field; the panel decides what to do with the result. All methods run on the main thread; the request runs on its own.
 */
class RewriteController(private val context: Context, private val onChange: () -> Unit) {

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

    private val main = Handler(Looper.getMainLooper())
    private var client: ChatClient? = null
    private var run = 0

    /** Where the text is sent. */
    val host: String get() = RewriteSettings.host(context)
    val hasKey get() = RewriteSettings.hasKey(context)
    val working get() = phase == Phase.WORKING

    fun start(tone: Tone, text: String) {
        stop()
        this.tone = tone
        val key = RewriteSettings.key(context)
        if (key == null) { failure = "The saved API key cannot be read. Enter it again in Wisp's settings."; phase = Phase.FAILED; onChange(); return }
        result = ""; failure = null; phase = Phase.WORKING
        val mine = ++run
        val startedAt = SystemClock.elapsedRealtime()
        val chat = ChatClient(RewriteSettings.gate(context), "Wisp/${BuildConfig.VERSION_NAME}").also { client = it }
        val address = RewriteSettings.address(context); val model = RewriteSettings.model(context)
        fun report(change: () -> Unit) = main.post { if (mine == run && phase == Phase.WORKING) { change(); onChange() } }
        Thread {
            try {
                val out = chat.complete(address, key, model, tone.system(), text, text.length * 4 + 400) { soFar -> report { result = soFar } }
                report { result = out.trim().removeSurrounding("\""); elapsedMs = SystemClock.elapsedRealtime() - startedAt; phase = Phase.DONE }
            } catch (e: CancelledException) {
            } catch (e: Exception) {
                val why = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException) "Could not reach $host. Your text is unchanged." else e.message ?: "The rewrite failed"
                report { failure = why; phase = Phase.FAILED }
            }
        }.start()
        onChange()
    }

    fun cancel() { stop(); if (phase == Phase.WORKING) { phase = Phase.IDLE; result = "" }; onChange() }

    private fun stop() { run++; client?.cancel(); client = null }
}
