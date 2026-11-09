package app.wisp.keyboard.voice.model

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Puts a speech model's files in place, from a `.tar.bz2` archive (the form the models are published in; from Handy's ModelManager) and
 * takes them out again. Only the files the model needs are unpacked, into a temporary folder that is renamed into place once complete,
 * so an interrupted or wrong archive never leaves a half-installed model that looks installed.
 */
object ModelInstaller {

    class BadArchiveException(message: String) : IOException(message)

    /** Reads [archive] and installs the files [spec] needs into [target]. [progress] gets the bytes of the archive read so far. */
    fun installFromArchive(spec: ModelSpec, archive: InputStream, target: File, progress: (Long) -> Unit = {}) {
        val parent = target.parentFile ?: throw IOException("no folder")
        parent.mkdirs()
        val tmp = File(parent, target.name + ".installing")
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val counting = CountingInputStream(archive, progress)
            TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered(), true)).use { tar ->
                val wanted = spec.requiredFiles.toSet()
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = File(e.name).name
                    if (name !in wanted) continue
                    File(tmp, name).outputStream().use { out -> tar.copyTo(out) }
                }
            }
            val missing = spec.requiredFiles.filter { !File(tmp, it).isFile }
            if (missing.isNotEmpty()) throw BadArchiveException("This archive is not the ${spec.name} model: it has no ${missing.joinToString(", ")}")
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IOException("Could not save the model")
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
    }

    /** Deletes an installed model. */
    fun remove(target: File) { target.deleteRecursively() }

    private class CountingInputStream(private val inner: InputStream, private val onBytes: (Long) -> Unit) : InputStream() {
        private var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) { count++; if (count % 65_536L == 0L) onBytes(count) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) { count += it; onBytes(count) } }
        override fun close() = inner.close()
    }
}
