package app.wisp.keyboard.voice.llm

import android.os.Build
import java.io.File

/** The llama.cpp engine (tools/llm), reached through JNI. Only the voice process loads it. */
object LlamaBridge {
    /** Receives the answer piece by piece while it is generated. */
    interface Tokens { fun onToken(piece: String) }

    /** True when the engine library for this phone could be loaded; the rewrite feature is unavailable otherwise (e.g. 32-bit phones). */
    val available: Boolean = run {
        val names = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && hasDotProduct()) listOf("wisp_llm_dotprod", "wisp_llm") else listOf("wisp_llm")
        names.any { n -> try { System.loadLibrary(n); true } catch (e: UnsatisfiedLinkError) { false } }
    }

    /** The dot-product instructions make 4-bit models several times faster; older phones use the plain build. */
    private fun hasDotProduct(): Boolean = try {
        File("/proc/cpuinfo").useLines { l -> l.any { it.startsWith("Features") && " asimddp" in it } }
    } catch (e: Exception) { false }

    /** Loads a GGUF model; 0 on failure. */
    external fun load(path: String, threads: Int, contextTokens: Int): Long
    external fun free(handle: Long)
    /** Makes a running [chat] stop at the next token. Safe to call from another thread. */
    external fun cancel(handle: Long)
    /** One chat turn; null when it failed, was cancelled or did not fit the context. */
    external fun chat(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, cb: Tokens): String?
}
