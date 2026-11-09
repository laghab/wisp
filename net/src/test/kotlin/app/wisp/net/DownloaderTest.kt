package app.wisp.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs the downloader against a real HTTP server on this machine that honours Range requests. */
class DownloaderTest {
    private lateinit var server: HttpServer
    private val data = ByteArray(3 * 1024 * 1024) { (it * 31 + 7).toByte() }
    private val hits = AtomicInteger()
    private val ranges = ArrayList<String?>()
    private var base = ""
    private val dir: File = Files.createTempDirectory("wisp-net").toFile().also { it.deleteOnExit() }
    private val free = { 10L * 1024 * 1024 * 1024 }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex: HttpExchange ->
            hits.incrementAndGet()
            val range = ex.requestHeaders.getFirst("Range"); ranges.add(range)
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            if (from >= data.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(if (range != null) 206 else 200, (data.size - from).toLong())
            ex.responseBody.use { it.write(data, from, data.size - from) }
        }
        server.createContext("/manifest") { ex ->
            hits.incrementAndGet()
            val body = """{"wisp":{"version":"0.2.0","versionCode":2,"url":"https://example.org/wisp.apk","size":123,"sha256":"ab","notes":"faster"},"models":{"moonshine-tiny-en":{"version":"2","versionCode":2,"url":"https://models.example.net/m.tar.bz2","size":9}}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start(); base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() { server.stop(0) }

    private val on = NetworkGate { true }
    private fun req(name: String = "m.bin", sha: String? = Downloader.sha256(data.inputStream()), size: Long? = data.size.toLong()) = Downloader.Request("$base/file", File(dir, name), size, sha)

    @Test fun downloadsAndVerifiesTheFile() {
        Downloader(on).download(req(), free)
        assertTrue(File(dir, "m.bin").readBytes().contentEquals(data))
        assertFalse(File(dir, "m.bin.part").exists())
    }

    @Test fun nothingIsContactedWhileTheSwitchIsOff() {
        try { Downloader(NetworkGate { false }).download(req(), free); fail() } catch (e: NetworkOffException) { }
        try { UpdateManifest.fetch(NetworkGate { false }, "$base/manifest"); fail() } catch (e: NetworkOffException) { }
        assertEquals("the server must not have been touched", 0, hits.get())
        assertFalse(File(dir, "m.bin").exists())
    }

    @Test fun anInterruptedDownloadResumesFromWhereItStopped() {
        val cancel = AtomicBoolean(false)
        try { Downloader(on).download(req(), free, cancel) { have, _ -> if (have > 1_000_000) cancel.set(true) }; fail() } catch (e: CancelledException) { }
        val partial = File(dir, "m.bin.part").length()
        assertTrue("some bytes kept ($partial)", partial in 1 until data.size)
        Downloader(on).download(req(), free)
        assertTrue(File(dir, "m.bin").readBytes().contentEquals(data))
        assertEquals("bytes=$partial-", ranges.last())
    }

    @Test fun switchingTheNetworkOffStopsADownloadAtOnce() {
        var allowed = true
        val gate = NetworkGate { allowed }
        try { Downloader(gate).download(req(), free) { have, _ -> if (have > 500_000) allowed = false }; fail() } catch (e: NetworkOffException) { }
        val partial = File(dir, "m.bin.part").length()
        assertTrue("stopped within a chunk of the switch: $partial", partial < 500_000 + 2 * 64 * 1024)
        assertFalse(File(dir, "m.bin").exists())
    }

    @Test fun notEnoughRoomIsRefusedBeforeDownloading() {
        try { Downloader(on).download(req(), { 1024L }); fail() } catch (e: InsufficientSpaceException) { assertTrue(e.neededBytes > e.freeBytes) }
        assertEquals(0, hits.get())
    }

    @Test fun aDamagedFileIsRejectedAndRemoved() {
        try { Downloader(on).download(req(sha = "0".repeat(64)), free); fail() } catch (e: ChecksumException) { }
        assertFalse(File(dir, "m.bin").exists()); assertFalse(File(dir, "m.bin.part").exists())
    }

    @Test fun aWholeFileAlreadyOnDiskIsNotDownloadedAgain() {
        Downloader(on).download(req(), free)
        val first = hits.get()
        File(dir, "m.bin.part").writeBytes(data)                           // a complete part left by a crash before the rename
        Downloader(on).download(req(), free)
        assertTrue(File(dir, "m.bin").readBytes().contentEquals(data))
        assertEquals(first + 1, hits.get())                                  // only the probe for the missing range
    }

    @Test fun theManifestIsReadAndNamesItsHosts() {
        val m = UpdateManifest.fetch(on, "$base/manifest")
        assertEquals("0.2.0", m.wisp!!.version); assertEquals(2, m.wisp!!.versionCode); assertEquals("faster", m.wisp!!.notes)
        assertEquals("https://models.example.net/m.tar.bz2", m.models["moonshine-tiny-en"]!!.url)
        assertEquals(setOf("example.org", "models.example.net"), m.hosts())
    }

    @Test fun anEmptyManifestIsFine() { val m = UpdateManifest.parse("{}"); assertEquals(null, m.wisp); assertTrue(m.models.isEmpty()) }
}
