package app.wisp.keyboard.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.settings.WispSettings
import java.io.File
import java.util.concurrent.Executors

/**
 * Backing up to, and restoring from, files in a folder the user chose (Android's folder picker, so Wisp gets access to that one folder
 * only). Scheduled backups are "lazy": they are checked when the keyboard or the app opens, so no timer, alarm or service exists for them.
 */
class BackupManager(context: Context) {
    private val context = context.applicationContext
    private val settings get() = WispSettings.get(this.context)
    private val filesDir get() = context.filesDir

    class Result(val ok: Boolean, val message: String, val name: String? = null, val bytes: Long = 0)
    class Found(val name: String, val uri: Uri, val manual: Boolean)

    var folder: Uri?
        get() = settings.getString("backup_folder")?.let(Uri::parse)
        set(v) { settings.putString("backup_folder", v?.toString()) }
    /** "off", "daily" or "weekly". */
    var schedule: String
        get() = settings.getString("backup_schedule", "off")!!
        set(v) { settings.putString("backup_schedule", v) }
    var keep: Int
        get() = settings.getInt("backup_keep", BackupPolicy.DEFAULT_KEEP)
        set(v) { settings.putInt("backup_keep", v.coerceIn(1, 60)) }
    val lastBackupMs get() = settings.getLong("backup_last", 0L)
    val lastMessage get() = settings.getString("backup_last_message")

    /** Takes the persistent permission for a folder the user picked, and remembers it. */
    fun chooseFolder(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        folder?.takeIf { it != tree }?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        folder = tree
    }

    fun folderLabel(): String? = folder?.let { Uri.decode(it.lastPathSegment ?: it.toString()).substringAfter(':').ifEmpty { "Internal storage" } }

    /** Backs up now. Safe to call from any thread. */
    @Synchronized fun backUp(manual: Boolean, nowMs: Long = System.currentTimeMillis(), scopes: Set<String> = UserData.ALL): Result {
        val tree = folder ?: return Result(false, "Choose a backup folder first.")
        return try {
            val entries = UserData.collect(filesDir, File(filesDir, "wisp-settings.properties"), scopes)
            val name = BackupPolicy.fileName(manual, nowMs)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", name) ?: throw java.io.IOException("The folder would not let Wisp create a file.")
            var size = 0L
            try {
                context.contentResolver.openOutputStream(doc)!!.use { BackupFormat.write(it, entries, nowMs, BuildConfig.VERSION_NAME, scopes) }
                size = sizeOf(doc)
            } catch (t: Throwable) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }; throw t }
            settings.putLong("backup_last", nowMs)
            if (!manual) prune()
            val shown = name
            settings.putString("backup_last_message", "$shown (${if (size < 10_240) "$size bytes" else "${size / 1024} KB"})")
            Result(true, "Backed up to $shown", shown, size)
        } catch (t: Throwable) {
            val m = t.message ?: "The backup failed."
            if (!manual) settings.putString("backup_last_message", "Last automatic backup failed: $m")
            Result(false, m)
        }
    }

    /** Called when the keyboard or the app opens. Does nothing (one settings read) unless a scheduled backup is due. */
    fun runIfDue(background: Boolean = true) {
        if (schedule == "off" || folder == null) return
        if (!due()) return
        // Checked again on the worker: opening the keyboard can ask twice before the first backup has finished.
        if (background) worker.execute { if (due()) backUp(manual = false) } else if (due()) backUp(manual = false)
    }

    private fun due() = BackupPolicy.isDue(schedule, lastBackupMs, System.currentTimeMillis())

    /** What is in the backup folder (Wisp's own files only), newest first. */
    fun list(): List<Found> {
        val tree = folder ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Found>()
        runCatching {
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.endsWith(".zip") && (name.startsWith(BackupPolicy.MANUAL_PREFIX) || name.startsWith(BackupPolicy.AUTO_PREFIX)))
                        out.add(Found(name, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name.startsWith(BackupPolicy.MANUAL_PREFIX)))
                }
            }
        }
        return out.sortedByDescending { it.name.substringAfter('-').substringAfter('-') }
    }

    private fun prune() {
        val found = list()
        for (name in BackupPolicy.toDelete(found.map { it.name }, keep)) found.firstOrNull { it.name == name }?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
    }

    private fun sizeOf(doc: Uri): Long = context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    /** Writes a backup of [scopes] to a file the user picked (for moving settings to another phone, or keeping a copy anywhere). */
    fun exportTo(uri: Uri, scopes: Set<String>): Result = try {
        val entries = UserData.collect(filesDir, File(filesDir, "wisp-settings.properties"), scopes)
        context.contentResolver.openOutputStream(uri)!!.use { BackupFormat.write(it, entries, System.currentTimeMillis(), BuildConfig.VERSION_NAME, scopes) }
        Result(true, "Saved ${entries.size} files")
    } catch (t: Throwable) { Result(false, t.message ?: "Could not write that file.") }

    /** Reads and checks a backup without changing anything. Throws [BackupException] with a user-readable reason. */
    fun inspect(uri: Uri): BackupFormat.Parsed =
        context.contentResolver.openInputStream(uri)?.use { BackupFormat.read(it) } ?: throw BackupException("Could not open that file.")

    /** Replaces the learned data and settings with the backup's. Validate with [inspect] first. */
    fun restore(parsed: BackupFormat.Parsed) {
        val newSettings = UserData.restore(filesDir, parsed)
        if (BackupFormat.Scope.SETTINGS in parsed.scopes && parsed.entry(BackupFormat.SETTINGS) != null) settings.replaceAll(newSettings) { UserData.isDeviceOnly(it) }
        // the keyboard notices the changed files the next time it opens (modification times)
    }

    companion object { private val worker = Executors.newSingleThreadExecutor { Thread(it, "wisp-backup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } } }
}
