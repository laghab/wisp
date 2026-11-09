package app.wisp.keyboard.voice

import app.wisp.keyboard.voice.model.ModelCatalog
import app.wisp.keyboard.voice.model.ModelInstaller
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ModelInstallerTest {
    private val spec = ModelCatalog.byId("moonshine-tiny-en")!!
    private val dir: File = Files.createTempDirectory("wisp-models").toFile().also { it.deleteOnExit() }

    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, text) in files) {
                val b = text.toByteArray(); tar.putArchiveEntry(TarArchiveEntry(name).also { it.size = b.size.toLong() }); tar.write(b); tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val good = arrayOf(
        "moonshine-folder/encoder_model.ort" to "enc", "moonshine-folder/decoder_model_merged.ort" to "dec", "moonshine-folder/tokens.txt" to "tok",
        "moonshine-folder/LICENSE" to "mit", "moonshine-folder/test_wavs/0.wav" to "wav",
    )

    @Test fun installsOnlyTheFilesTheModelNeeds() {
        val target = File(dir, "moonshine-tiny-en")
        ModelInstaller.installFromArchive(spec, archive(*good).inputStream(), target)
        assertEquals(spec.requiredFiles.toSet(), target.list()!!.toSet())
        assertEquals("enc", File(target, "encoder_model.ort").readText())
        assertFalse(File(dir, "moonshine-tiny-en.installing").exists())
    }

    @Test fun anArchiveOfTheWrongModelIsRefusedAndLeavesNothing() {
        val target = File(dir, "m2")
        try { ModelInstaller.installFromArchive(spec, archive("x/encoder_model.ort" to "a").inputStream(), target); fail() } catch (e: ModelInstaller.BadArchiveException) { assertTrue(e.message!!.contains("decoder_model_merged.ort")) }
        assertFalse(target.exists()); assertFalse(File(dir, "m2.installing").exists())
    }

    @Test fun aDamagedArchiveLeavesNothing() {
        val target = File(dir, "m3"); val bytes = archive(*good).let { it.copyOf(it.size / 2) }
        try { ModelInstaller.installFromArchive(spec, bytes.inputStream(), target); fail() } catch (e: Exception) { }
        assertFalse(target.exists()); assertFalse(File(dir, "m3.installing").exists())
    }

    @Test fun installingAgainReplacesTheOldFiles() {
        val target = File(dir, "m4")
        ModelInstaller.installFromArchive(spec, archive(*good).inputStream(), target)
        ModelInstaller.installFromArchive(spec, archive("a/encoder_model.ort" to "NEW", "a/decoder_model_merged.ort" to "d", "a/tokens.txt" to "t").inputStream(), target)
        assertEquals("NEW", File(target, "encoder_model.ort").readText())
    }

    @Test fun removeDeletesTheModel() {
        val target = File(dir, "m5"); ModelInstaller.installFromArchive(spec, archive(*good).inputStream(), target)
        ModelInstaller.remove(target)
        assertFalse(target.exists())
    }

    @Test fun progressIsReported() {
        var last = 0L; ModelInstaller.installFromArchive(spec, archive(*good).inputStream(), File(dir, "m6")) { last = it }
        assertTrue(last > 0)
    }
}
