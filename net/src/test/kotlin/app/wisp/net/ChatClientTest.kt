package app.wisp.net

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

/** Runs the chat client against a real HTTP server on this machine that answers the way providers do. */
class ChatClientTest {
    private lateinit var server: HttpServer
    private var base = ""
    private var asked: JSONObject? = null
    private var auth: String? = null

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            asked = JSONObject(ex.requestBody.bufferedReader().readText())
            if (auth != "Bearer good") {
                val b = """{"error":{"message":"No auth credentials found"}}""".toByteArray()
                ex.sendResponseHeaders(401, b.size.toLong()); ex.responseBody.use { it.write(b) }; return@createContext
            }
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { o ->
                o.write(": PROCESSING\n\n".toByteArray())
                for (p in listOf("Hello", ", ", "world.")) o.write("data: {\"choices\":[{\"delta\":{\"content\":\"$p\"}}]}\n\n".toByteArray())
                o.write("data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
            }
        }
        server.start(); base = "http://127.0.0.1:${server.address.port}/v1/"
    }

    @After fun stop() = server.stop(0)

    @Test fun readsTheAnswerAsItGrows() {
        val seen = ArrayList<String>()
        val out = ChatClient({ true }).complete(base, "good", "some/model", "Tidy this.", "helo wrld", 500) { seen.add(it) }
        assertEquals("Hello, world.", out)
        assertEquals(listOf("Hello", "Hello, ", "Hello, world."), seen)
        assertEquals("some/model", asked!!.getString("model"))
        assertEquals("helo wrld", asked!!.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun stopsAtTheLengthLimit() = assertEquals("Hello, ", ChatClient({ true }).complete(base, "good", "m", "s", "u", 6))

    @Test fun aRefusedKeyIsSaidPlainly() {
        try { ChatClient({ true }).complete(base, "bad", "m", "s", "u", 500); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("API key")) }
    }

    @Test fun nothingIsSentWhileTheNetworkIsOff() {
        try { ChatClient({ false }).complete(base, "good", "m", "s", "u", 500); fail() } catch (e: NetworkOffException) { assertEquals(null, asked) }
    }

    @Test fun plainHttpIsRefusedAwayFromThisMachine() {
        try { ChatClient({ true }).complete("http://example.org/v1", "good", "m", "s", "u", 500); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("https")) }
    }
}
