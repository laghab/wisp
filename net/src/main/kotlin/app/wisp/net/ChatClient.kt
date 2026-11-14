package app.wisp.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One chat turn with a provider that speaks the OpenAI chat format (OpenRouter, OpenAI, Groq, Mistral and most others): the text goes to
 * `<address>/chat/completions` with the user's own key, and the answer is read as it is written. This is the only place where text the
 * user typed leaves the phone, and it runs only when the [gate] allows it.
 */
class ChatClient(private val gate: NetworkGate, private val userAgent: String = "Wisp") {
    @Volatile private var open: HttpURLConnection? = null
    @Volatile private var cancelled = false

    /**
     * Returns the whole answer. [onPiece] gets the answer so far each time it grows. An answer longer than [maxChars] is cut off there,
     * so a model that runs away cannot spend the user's credit. Throws [NetworkOffException], [CancelledException] or an [IOException]
     * whose message can be shown to the user.
     */
    fun complete(address: String, key: String, model: String, system: String, user: String, maxChars: Int, onPiece: (String) -> Unit = {}): String {
        gate.require()
        cancelled = false
        val url = URL(address.trim().trimEnd('/') + "/chat/completions")
        if (url.protocol != "https" && url.host !in LOCAL) throw IOException("The address must start with https://")
        val body = JSONObject().put("model", model).put("stream", true).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))).toString().toByteArray()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 45_000; requestMethod = "POST"; doOutput = true
            setRequestProperty("User-Agent", userAgent); setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "text/event-stream")
        }
        open = conn
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) throw IOException(problem(code, conn.errorStream?.bufferedReader()?.use { it.readText() }))
            val out = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    gate.require()
                    if (!line.startsWith("data:")) continue                      // blank lines and keep-alive comments
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    event.optJSONObject("error")?.let { throw IOException(it.optString("message", "The provider reported an error")) }
                    val piece = event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.let { if (it.isNull("content")) "" else it.optString("content") } ?: ""
                    if (piece.isEmpty()) continue
                    out.append(piece); onPiece(out.toString())
                    if (out.length >= maxChars) break
                }
            }
            if (out.isBlank()) throw IOException("The provider sent back nothing")
            return out.toString()
        } catch (e: IOException) {
            throw if (cancelled) CancelledException() else e
        } finally { open = null; conn.disconnect() }
    }

    /** Stops the turn in progress, from any thread. */
    fun cancel() { cancelled = true; open?.disconnect() }

    private fun problem(code: Int, body: String?): String {
        val said = runCatching { JSONObject(body ?: "").getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401, 403 -> "The provider did not accept the API key"
            402 -> "The account for this API key is out of credit"
            404 -> said ?: "The provider does not know this address or model"
            429 -> "The provider is busy or the key's limit is reached. Try again in a moment."
            else -> said ?: "The provider answered $code"
        }
    }

    private companion object { val LOCAL = setOf("127.0.0.1", "localhost") }
}
