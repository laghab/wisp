package app.wisp.keyboard.settings

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * The app's settings, in one small text file that every process reads and writes. (SharedPreferences cannot be shared safely between
 * the keyboard, the voice engine and the settings screens, which run in different processes.) A read notices when another process
 * changed the file; a write is atomic. Values are looked up by key with a default, so a missing key never breaks anything.
 */
class WispSettings(private val file: File) {
    private var cache = Properties()
    private var stamp = -1L
    private var size = -1L

    /** Modification time in nanoseconds and size, so two quick edits that keep the same length are still told apart. */
    private fun version(): Pair<Long, Long> = try {
        val a = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        a.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS) to a.size()
    } catch (e: Exception) { 0L to 0L }

    @Synchronized private fun current(): Properties {
        val (s, n) = version()
        if (s != stamp || n != size) {
            val p = Properties()
            if (file.isFile) try { file.inputStream().use { p.load(it) } } catch (e: Exception) { /* a damaged file reads as empty */ }
            cache = p; stamp = s; size = n
        }
        return cache
    }

    fun getString(key: String, default: String? = null): String? = current().getProperty(key, default)
    fun getInt(key: String, default: Int): Int = current().getProperty(key)?.toIntOrNull() ?: default
    fun getLong(key: String, default: Long): Long = current().getProperty(key)?.toLongOrNull() ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = current().getProperty(key)?.let { it == "true" } ?: default
    fun getStringSet(key: String): Set<String> = current().getProperty(key)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun putString(key: String, value: String?) = change { if (value == null) remove(key) else setProperty(key, value) }
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun putLong(key: String, value: Long) = putString(key, value.toString())
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    fun putStringSet(key: String, value: Set<String>) = putString(key, value.joinToString(","))
    fun remove(key: String) = putString(key, null)

    /** Replaces every setting the user chose with [values], leaving device-only settings (those [keep] accepts) as they are. */
    fun replaceAll(values: Map<String, String>, keep: (String) -> Boolean) = change {
        val kept = entries.filter { keep(it.key.toString()) }.associate { it.key to it.value }
        clear(); putAll(kept); for ((k, v) in values) setProperty(k, v)
    }

    /** Reads the newest file, applies [edit], and writes it back atomically. */
    @Synchronized private fun change(edit: Properties.() -> Unit) {
        val p = Properties().also { it.putAll(current()) }
        p.edit()
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cache = p; version().let { stamp = it.first; size = it.second }
    }

    companion object {
        @Volatile private var instance: WispSettings? = null
        fun get(context: Context): WispSettings = instance ?: synchronized(this) {
            instance ?: WispSettings(File(context.applicationContext.filesDir, "wisp-settings.properties")).also { instance = it }
        }
    }
}
