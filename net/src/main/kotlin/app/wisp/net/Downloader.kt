package app.wisp.net

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class InsufficientSpaceException(val neededBytes: Long, val freeBytes: Long) : IOException("Not enough free space: $neededBytes bytes needed, $freeBytes free")
class ChecksumException : IOException("The downloaded file is damaged (its checksum does not match)")
class CancelledException : IOException("Cancelled")

/**
 * Downloads one file, resumably. Data goes to `<dest>.part` and the file is renamed into place only after its checksum has been
 * verified, so a half-downloaded or damaged file is never taken for a model. An interrupted download continues from where it stopped
 * (HTTP Range). The network [gate] is checked before connecting and before every chunk, so switching the network off stops a download at once.
 */
class Downloader(private val gate: NetworkGate, private val userAgent: String = "Wisp") {

    class Request(
        val url: String,
        val dest: File,
        /** Size of the whole file, if known (from the model list); lets free space be checked before anything is downloaded. */
        val expectedBytes: Long? = null,
        /** Lower-case hex SHA-256 of the whole file. When null the file is accepted as received. */
        val sha256: String? = null,
    )

    /**
     * Fetches the file. [freeBytes] says how much room there is on the target storage. [progress] gets (bytes so far, total or -1).
     * Throws [NetworkOffException], [InsufficientSpaceException], [ChecksumException], [CancelledException] or an [IOException].
     */
    fun download(req: Request, freeBytes: () -> Long, cancel: AtomicBoolean = AtomicBoolean(false), progress: (Long, Long) -> Unit = { _, _ -> }) {
        gate.require()
        val part = File(req.dest.path + ".part")
        part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        req.expectedBytes?.let { total ->
            val needed = (total - have).coerceAtLeast(0) + SAFETY_MARGIN
            val free = freeBytes()
            if (needed > free) throw InsufficientSpaceException(needed, free)
        }

        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            when {
                code == 416 && have > 0 -> { finish(req, part, progress, have); return }       // the part is already the whole file
                code == 200 -> have = 0                                                          // the server ignored the range: start again
                code != 206 -> throw IOException("The server answered $code")
            }
            val length = conn.contentLengthLong
            val total = if (length >= 0) have + length else (req.expectedBytes ?: -1)
            if (have == 0L && total > 0) { val free = freeBytes(); if (total + SAFETY_MARGIN > free) throw InsufficientSpaceException(total + SAFETY_MARGIN, free) }
            java.io.FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        if (cancel.get()) throw CancelledException()
                        gate.require()                                                          // switched off while downloading: stop now
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); have += n
                        progress(have, total)
                    }
                }
            }
            finish(req, part, progress, total)
        } finally { conn.disconnect() }
    }

    private fun finish(req: Request, part: File, progress: (Long, Long) -> Unit, total: Long) {
        req.sha256?.let { want ->
            if (!sha256(part).equals(want, ignoreCase = true)) { part.delete(); throw ChecksumException() }
        }
        req.dest.delete()
        if (!part.renameTo(req.dest)) throw IOException("Could not save ${req.dest.name}")
        progress(req.dest.length(), req.dest.length().coerceAtLeast(total))
    }

    companion object {
        /** Room left over after a download, so the phone is not filled to the brim. */
        const val SAFETY_MARGIN = 50L * 1024 * 1024

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }
        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
