package app.wisp.keyboard.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The scheduling and retention rules, free of Android so they can be tested directly. */
object BackupPolicy {
    const val MANUAL_PREFIX = "wisp-backup-"
    const val AUTO_PREFIX = "wisp-auto-"
    const val DEFAULT_KEEP = 7

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** [mode] is "off", "daily" or "weekly". A backup is due when the last one is older than the interval (or there never was one). */
    fun isDue(mode: String, lastBackupMs: Long, nowMs: Long): Boolean {
        val interval = when (mode) { "daily" -> DAY_MS; "weekly" -> 7 * DAY_MS; else -> return false }
        return lastBackupMs <= 0 || nowMs - lastBackupMs >= interval || nowMs < lastBackupMs - DAY_MS   // a clock set back counts as due
    }

    fun fileName(manual: Boolean, nowMs: Long): String =
        (if (manual) MANUAL_PREFIX else AUTO_PREFIX) + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".zip"

    /** Which of the files in the folder to delete after a scheduled backup: the oldest scheduled ones beyond [keep]. Manual backups and other files are never listed. */
    fun toDelete(names: List<String>, keep: Int): List<String> =
        names.filter { it.startsWith(AUTO_PREFIX) && it.endsWith(".zip") }.sortedDescending().drop(keep.coerceAtLeast(1))
}
