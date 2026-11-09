package app.wisp.keyboard.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : IOException(message)

/**
 * The backup file: a zip holding `manifest.json` (what is inside, with sizes and SHA-256 checksums) and the data files themselves.
 * Plain files, no encryption and no network. Reading refuses anything that is not a Wisp backup, was damaged, or tries to write outside
 * the folders a backup may contain.
 */
object BackupFormat {
    const val MAGIC = "wisp-backup"
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    /** The only places a backup may put files, relative to the app's files folder. */
    val ALLOWED_PREFIXES = listOf("personal/", "wordlists/", "layouts/")
    const val SETTINGS = "settings.properties"

    class Entry(val path: String, val bytes: ByteArray)
    /** What a backup covers: learned data, layouts and/or settings. A restore replaces only the parts the backup declares. */
    object Scope { const val LEARNED = "learned"; const val LAYOUTS = "layouts"; const val SETTINGS = "settings"
        fun of(path: String) = when { path == BackupFormat.SETTINGS -> SETTINGS; path.startsWith("layouts/") -> LAYOUTS; else -> LEARNED } }

    class Parsed(val createdAt: Long, val appVersion: String, val entries: List<Entry>, val scopes: Set<String> = setOf(Scope.LEARNED, Scope.LAYOUTS, Scope.SETTINGS)) {
        fun entry(path: String) = entries.firstOrNull { it.path == path }
        val wordFiles get() = entries.count { it.path.startsWith("personal/") && it.path.endsWith(".tsv") }
    }

    fun isAllowedPath(path: String) =
        path == SETTINGS || (ALLOWED_PREFIXES.any { path.startsWith(it) } && !path.contains("..") && !path.startsWith("/") && !path.contains('\\') && !path.endsWith("/"))

    fun write(out: OutputStream, entries: List<Entry>, createdAt: Long, appVersion: String, scopes: Set<String> = setOf(Scope.LEARNED, Scope.LAYOUTS, Scope.SETTINGS)) {
        val manifest = JSONObject().put("magic", MAGIC).put("version", VERSION).put("createdAt", createdAt).put("appVersion", appVersion).put("scopes", JSONArray(scopes.toList()))
            .put("entries", JSONArray().also { arr -> for (e in entries) arr.put(JSONObject().put("path", e.path).put("size", e.bytes.size).put("sha256", sha256(e.bytes))) })
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(MANIFEST)); z.write(manifest.toString(2).toByteArray()); z.closeEntry()
            for (e in entries) { z.putNextEntry(ZipEntry(e.path)); z.write(e.bytes); z.closeEntry() }
        }
    }

    fun read(input: InputStream): Parsed {
        val files = HashMap<String, ByteArray>()
        try {
            ZipInputStream(input).use { z ->
                var total = 0L
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) { val n = z.read(chunk); if (n < 0) break; buf.write(chunk, 0, n); total += n; if (total > MAX_BYTES) throw BackupException("This file is too big to be a Wisp backup.") }
                    files[e.name] = buf.toByteArray()
                }
            }
        } catch (e: BackupException) { throw e } catch (e: Exception) { throw BackupException("This is not a Wisp backup, or the file is damaged.") }
        val manifestBytes = files[MANIFEST] ?: throw BackupException("This is not a Wisp backup (it has no manifest).")
        val manifest = try { JSONObject(String(manifestBytes)) } catch (e: Exception) { throw BackupException("The backup's manifest is damaged.") }
        if (manifest.optString("magic") != MAGIC) throw BackupException("This is not a Wisp backup.")
        if (manifest.optInt("version", 0) !in 1..VERSION) throw BackupException("This backup was made by a newer version of Wisp. Update Wisp to restore it.")
        val list = manifest.optJSONArray("entries") ?: throw BackupException("The backup's manifest is damaged.")
        val entries = ArrayList<Entry>(list.length())
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val path = o.optString("path")
            if (!isAllowedPath(path)) throw BackupException("The backup contains a file Wisp would not restore ($path).")
            val bytes = files[path] ?: throw BackupException("The backup is incomplete: $path is missing.")
            if (bytes.size != o.optInt("size", -1) || sha256(bytes) != o.optString("sha256")) throw BackupException("The backup is damaged: $path does not match its checksum.")
            entries.add(Entry(path, bytes))
        }
        val scopes = manifest.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: setOf(Scope.LEARNED, Scope.LAYOUTS, Scope.SETTINGS)
        return Parsed(manifest.optLong("createdAt"), manifest.optString("appVersion"), entries, scopes)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val MAX_BYTES = 64L * 1024 * 1024
}
