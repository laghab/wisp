package app.wisp.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable file: where it is, how big, and its checksum. */
class Artifact(val version: String, val versionCode: Int, val url: String, val sizeBytes: Long, val sha256: String?, val notes: String)

/**
 * What a server says is available: a newer Wisp and newer versions of speech models. Plain JSON over HTTPS:
 * `{"wisp": {"version": "0.2.0", "versionCode": 2, "url": "...", "size": 1, "sha256": "...", "notes": "..."},
 *   "models": {"moonshine-tiny-en": {"version": "2", "versionCode": 2, "url": "...", "size": 1, "sha256": "..."}}}`.
 */
class UpdateManifest(val wisp: Artifact?, val models: Map<String, Artifact>) {

    /** The hosts this manifest would have the app contact, so the Downloads screen can name them. */
    fun hosts(): Set<String> = (listOfNotNull(wisp) + models.values).mapNotNull { runCatching { URL(it.url).host }.getOrNull() }.toSet()

    companion object {
        fun parse(json: String): UpdateManifest {
            val root = JSONObject(json)
            fun artifact(o: JSONObject) = Artifact(
                version = o.optString("version", ""), versionCode = o.optInt("versionCode", 0), url = o.getString("url"),
                sizeBytes = o.optLong("size", -1), sha256 = o.optString("sha256", "").ifEmpty { null }, notes = o.optString("notes", ""),
            )
            val models = HashMap<String, Artifact>()
            root.optJSONObject("models")?.let { m -> for (k in m.keys()) models[k] = artifact(m.getJSONObject(k)) }
            return UpdateManifest(root.optJSONObject("wisp")?.let(::artifact), models)
        }

        /** Fetches and parses the manifest at [url]. Only runs when the network switch is on. */
        fun fetch(gate: NetworkGate, url: String): UpdateManifest {
            gate.require()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 20_000; setRequestProperty("User-Agent", "Wisp") }
            try {
                if (conn.responseCode != 200) throw IOException("The update server answered ${conn.responseCode}")
                return parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally { conn.disconnect() }
        }
    }
}
