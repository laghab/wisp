package app.wisp.keyboard.backup

import java.io.File
import java.util.Properties

/**
 * Which of the app's files are "the user's data" and how they are gathered into, and put back from, a backup. Works on plain
 * files so it can be tested without Android. Audio and downloaded models are never included.
 */
object UserData {
    /** Settings that describe this phone or this install rather than the user's choices; a restore leaves them as they are. */
    private val DEVICE_ONLY_PREFIXES = listOf("network_enabled", "backup_", "bench_", "modelvercode_", "voice_model", "first_run_done", "incognito")

    val ALL = setOf(BackupFormat.Scope.LEARNED, BackupFormat.Scope.LAYOUTS, BackupFormat.Scope.SETTINGS)

    fun isDeviceOnly(key: String) = DEVICE_ONLY_PREFIXES.any { key.startsWith(it) }

    /** Reads every data file under [filesDir] (and the filtered settings text) as backup entries. */
    fun collect(filesDir: File, settingsFile: File, scopes: Set<String> = ALL): List<BackupFormat.Entry> {
        val out = ArrayList<BackupFormat.Entry>()
        for (prefix in BackupFormat.ALLOWED_PREFIXES) {
            if (BackupFormat.Scope.of(prefix) !in scopes) continue
            val dir = File(filesDir, prefix.trimEnd('/'))
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.path }.forEach { f ->
                out.add(BackupFormat.Entry(prefix + f.relativeTo(dir).invariantSeparatorsPath, f.readBytes()))
            }
        }
        if (BackupFormat.Scope.SETTINGS in scopes && settingsFile.isFile) {
            val p = Properties().also { pr -> settingsFile.inputStream().use { pr.load(it) } }
            p.keys.map { it.toString() }.filter { isDeviceOnly(it) }.forEach { p.remove(it) }
            val sb = java.io.ByteArrayOutputStream(); p.store(sb, null)
            out.add(BackupFormat.Entry(BackupFormat.SETTINGS, sb.toByteArray()))
        }
        return out
    }

    /**
     * Replaces the data folders and the user's settings with the backup's. Each folder is written aside and swapped in, so a failure
     * part-way leaves the old data. Returns the settings from the backup (without device-only keys) for the caller to merge.
     */
    fun restore(filesDir: File, parsed: BackupFormat.Parsed): Map<String, String> {
        for (prefix in BackupFormat.ALLOWED_PREFIXES) {
            if (BackupFormat.Scope.of(prefix) !in parsed.scopes) continue
            val name = prefix.trimEnd('/')
            val staging = File(filesDir, "$name.restoring").also { it.deleteRecursively(); it.mkdirs() }
            for (e in parsed.entries.filter { it.path.startsWith(prefix) }) {
                val f = File(staging, e.path.removePrefix(prefix))
                if (!f.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw BackupException("Unsafe path in the backup: ${e.path}")
                f.parentFile?.mkdirs(); f.writeBytes(e.bytes)
            }
            val live = File(filesDir, name)
            val old = File(filesDir, "$name.old").also { it.deleteRecursively() }
            if (live.exists()) live.renameTo(old)
            if (!staging.renameTo(live)) { old.renameTo(live); throw BackupException("Could not put the restored files in place.") }
            old.deleteRecursively()
        }
        if (BackupFormat.Scope.SETTINGS !in parsed.scopes) return emptyMap()
        val s = parsed.entry(BackupFormat.SETTINGS) ?: return emptyMap()
        val p = Properties().also { it.load(s.bytes.inputStream()) }
        return p.entries.associate { it.key.toString() to it.value.toString() }.filterKeys { !isDeviceOnly(it) }
    }
}
