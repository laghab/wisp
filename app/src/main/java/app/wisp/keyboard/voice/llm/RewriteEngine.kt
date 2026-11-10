package app.wisp.keyboard.voice.llm

import java.io.File

/**
 * Runs a rewrite in the voice process. The model is loaded when a rewrite is asked for and freed with the speech model when the
 * process goes idle, so the two share one idle timer and memory goes back to the system together.
 */
class RewriteEngine {
    private var handle = 0L
    private var loadedPath = ""

    val available get() = LlamaBridge.available

    /** Returns the rewritten text, or null when it failed or was cancelled. [onPiece] gets the text so far each time it grows. */
    fun rewrite(model: File, tone: Tone, text: String, threads: Int, onPiece: (String) -> Unit): String? {
        if (!LlamaBridge.available) return null
        if (handle == 0L || loadedPath != model.path) {
            release()
            handle = LlamaBridge.load(model.path, threads, CONTEXT_TOKENS)
            if (handle == 0L) return null
            loadedPath = model.path
        }
        val soFar = StringBuilder()
        val maxTokens = (text.length / 2 + 128).coerceIn(128, 640)
        return LlamaBridge.chat(handle, tone.system(), text, maxTokens, 0.3f, object : LlamaBridge.Tokens {
            override fun onToken(piece: String) { soFar.append(piece); onPiece(soFar.toString()) }
        })?.trim()?.removeSurrounding("\"")
    }

    /** Safe from any thread: the running rewrite stops at its next token. */
    fun cancel() { val h = handle; if (h != 0L) LlamaBridge.cancel(h) }

    fun release() { if (handle != 0L) { LlamaBridge.free(handle); handle = 0; loadedPath = "" } }

    private companion object { const val CONTEXT_TOKENS = 2048 }
}
