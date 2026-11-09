package app.wisp.keyboard.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun app(): File = tmp.newFolder().also { root ->
        File(root, "personal").mkdirs(); File(root, "personal/en_US.tsv").writeText("wisp\t5\n")
        File(root, "personal/gesture_style.txt").writeText("0.1 0.2 1.0 0.7 60.0")
        File(root, "wordlists").mkdirs(); File(root, "wordlists/hi.tsv").writeText("kya\t200\n")
        File(root, "wisp-settings.properties").writeText("style_theme=light\nnetwork_enabled=true\nbench_x=1,2,3\nvoice_model=a\nstyle_accent=teal\n")
        File(root, "no_backup").mkdirs()
    }

    private fun bytes(entries: List<BackupFormat.Entry>): ByteArray = ByteArrayOutputStream().also { BackupFormat.write(it, entries, 1234L, "0.1") }.toByteArray()

    @Test fun roundTripKeepsDataAndLeavesDeviceSettingsOut() {
        val root = app()
        val entries = UserData.collect(root, File(root, "wisp-settings.properties"))
        val parsed = BackupFormat.read(ByteArrayInputStream(bytes(entries)))
        assertEquals(1234L, parsed.createdAt)
        assertEquals("wisp\t5\n", String(parsed.entry("personal/en_US.tsv")!!.bytes))
        val settings = String(parsed.entry(BackupFormat.SETTINGS)!!.bytes)
        assertTrue(settings.contains("style_theme=light") && settings.contains("style_accent=teal"))
        assertFalse(settings.contains("network_enabled")); assertFalse(settings.contains("bench_")); assertFalse(settings.contains("voice_model"))
    }

    @Test fun restoreOnAWipedInstallBringsBackTheWords() {
        val src = app()
        val parsed = BackupFormat.read(ByteArrayInputStream(bytes(UserData.collect(src, File(src, "wisp-settings.properties")))))
        val fresh = tmp.newFolder()
        File(fresh, "personal").mkdirs(); File(fresh, "personal/other.tsv").writeText("old")
        val settings = UserData.restore(fresh, parsed)
        assertEquals("wisp\t5\n", File(fresh, "personal/en_US.tsv").readText())
        assertFalse("data not in the backup is replaced, not merged", File(fresh, "personal/other.tsv").exists())
        assertEquals("kya\t200\n", File(fresh, "wordlists/hi.tsv").readText())
        assertEquals("light", settings["style_theme"])
        assertFalse(File(fresh, "personal.restoring").exists() || File(fresh, "personal.old").exists())
    }

    @Test fun settingsOnlyBackupLeavesLearnedDataAlone() {
        val src = app()
        val scopes = setOf(BackupFormat.Scope.SETTINGS, BackupFormat.Scope.LAYOUTS)
        val entries = UserData.collect(src, File(src, "wisp-settings.properties"), scopes)
        assertTrue(entries.none { it.path.startsWith("personal/") || it.path.startsWith("wordlists/") })
        val parsed = BackupFormat.read(ByteArrayInputStream(ByteArrayOutputStream().also { BackupFormat.write(it, entries, 1L, "0.1", scopes) }.toByteArray()))
        val fresh = tmp.newFolder(); File(fresh, "personal").mkdirs(); File(fresh, "personal/mine.tsv").writeText("keep me")
        UserData.restore(fresh, parsed)
        assertEquals("keep me", File(fresh, "personal/mine.tsv").readText())
    }

    @Test fun backupHasNoAudioOrModels() {
        val root = app()
        File(root, "no_backup/models/m").mkdirs(); File(root, "no_backup/models/m/x.onnx").writeText("m")
        File(root, "debug_mic.wav").writeText("riff")
        val paths = UserData.collect(root, File(root, "wisp-settings.properties")).map { it.path }
        assertTrue(paths.none { it.contains("onnx") || it.endsWith(".wav") })
        assertTrue(paths.all { BackupFormat.isAllowedPath(it) })
    }

    @Test fun damagedAndForeignFilesAreRefused() {
        val good = bytes(listOf(BackupFormat.Entry("personal/a.tsv", "abc".toByteArray())))
        assertThrows(BackupException::class.java) { BackupFormat.read(ByteArrayInputStream("hello".toByteArray())) }
        // same manifest, but the data file no longer matches its checksum
        val manifest = java.util.zip.ZipInputStream(ByteArrayInputStream(good)).use { z -> z.nextEntry; z.readBytes() }
        val damaged = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z ->
            z.putNextEntry(ZipEntry(BackupFormat.MANIFEST)); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("personal/a.tsv")); z.write("abd".toByteArray()); z.closeEntry() } }.toByteArray()
        val e = assertThrows(BackupException::class.java) { BackupFormat.read(ByteArrayInputStream(damaged)) }
        assertTrue(e.message!!.contains("damaged"))
        // a zip that is not a Wisp backup
        val plain = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write(1); z.closeEntry() } }.toByteArray()
        assertThrows(BackupException::class.java) { BackupFormat.read(ByteArrayInputStream(plain)) }
    }

    @Test fun pathsOutsideTheDataFoldersAreRefused() {
        val evil = bytes(listOf(BackupFormat.Entry("personal/../../x", "a".toByteArray())))
        assertThrows(BackupException::class.java) { BackupFormat.read(ByteArrayInputStream(evil)) }
        val other = bytes(listOf(BackupFormat.Entry("shared_prefs/a.xml", "a".toByteArray())))
        assertThrows(BackupException::class.java) { BackupFormat.read(ByteArrayInputStream(other)) }
    }

    @Test fun refusedRestoreChangesNothing() {
        val root = app()
        val before = File(root, "personal/en_US.tsv").readText()
        try { BackupFormat.read(ByteArrayInputStream("junk".toByteArray())); fail() } catch (_: BackupException) {}
        assertEquals(before, File(root, "personal/en_US.tsv").readText())
    }

    @Test fun scheduleIsDueOnlyWhenTheIntervalPassed() {
        val day = BackupPolicy.DAY_MS
        assertFalse(BackupPolicy.isDue("off", 0, 10 * day))
        assertTrue(BackupPolicy.isDue("daily", 0, day))
        assertFalse(BackupPolicy.isDue("daily", 5 * day, 5 * day + day / 2))
        assertTrue(BackupPolicy.isDue("daily", 5 * day, 6 * day))
        assertFalse(BackupPolicy.isDue("weekly", 5 * day, 11 * day))
        assertTrue(BackupPolicy.isDue("weekly", 5 * day, 12 * day))
    }

    @Test fun retentionDeletesOldestScheduledAndNeverManual() {
        val names = listOf("wisp-auto-20260101-000000.zip", "wisp-auto-20260102-000000.zip", "wisp-auto-20260103-000000.zip", "wisp-auto-20260104-000000.zip",
            "wisp-backup-20250101-000000.zip", "notes.zip")
        assertEquals(listOf("wisp-auto-20260102-000000.zip", "wisp-auto-20260101-000000.zip"), BackupPolicy.toDelete(names, 2))
        assertTrue(BackupPolicy.toDelete(names, 10).isEmpty())
        assertTrue(BackupPolicy.toDelete(names, 2).none { it.startsWith(BackupPolicy.MANUAL_PREFIX) })
    }
}
