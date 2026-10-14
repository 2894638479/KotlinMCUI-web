package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.input.Key
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The protocol, end to end: a real socket, a real handshake and real frames.
 *
 * What is pinned here is what a browser depends on. The handshake has to produce the accept value the
 * protocol asks for; a tick has to bring back a frame the client's own decoder can rebuild and walk;
 * a tick on a page nobody is touching has to cost one byte; a resize has to force a keyframe, because
 * the layout the client holds is no longer the layout the server renders; and a key command has to
 * reach the framework's input state through the DOM name the browser sent.
 */
class WebServerTest {
    private var server: WebServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop()
    }

    @Test
    fun `a browser handshake, a keyframe, a still frame and a resize`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            assertTrue(client.statusLine.contains("101"), "not an upgrade: ${client.statusLine}")
            assertEquals(client.expectedAccept, client.accept, "the accept value is wrong")

            client.setTimeout(10_000)
            client.sendText("tick")
            val first = client.readMessage() ?: error("no frame arrived")
            assertEquals(WsClient.OPCODE_BINARY, first.opcode)
            assertEquals(Encoder.KEYFRAME, first.payload[0])

            val words = Decoder.apply(null, first.payload)
            assertTrue(words.isNotEmpty())
            walkToEnd(words)
            assertTrue(words.any { it == Op.QUAD } || words.any { it == Op.TEXT }, "the frame drew nothing")

            client.sendText("tick")
            val second = client.readMessage() ?: error("no second frame arrived")
            assertEquals(Encoder.NOCHANGE, second.payload[0], "a still page was not a one-byte record")
            assertEquals(1, second.payload.size)

            client.sendText("resize 640 480")
            val resized = client.readMessage() ?: error("no frame arrived after the resize")
            assertEquals(Encoder.KEYFRAME, resized.payload[0], "a resize has to start over in full")
            val resizedWords = Decoder.apply(null, resized.payload)
            walkToEnd(resizedWords)
            assertFalse(
                Decoder.apply(null, first.payload).contentEquals(resizedWords),
                "the layout did not change with the size",
            )
        }
    }

    @Test
    fun `a key command reaches the framework's input state`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            client.setTimeout(5_000)
            client.sendText("key KeyA down 0")
            assertTrue(await { WebInput.isKeyDown(Key.A) }, "the key never went down")

            client.sendText("key KeyA up 0")
            assertTrue(await { !WebInput.isKeyDown(Key.A) }, "the key never came up")
        }
    }

    @Test
    fun `the page and the client script are served, and nothing else is`() {
        val running = start()

        assertTrue(read("/") .contains("<canvas id=\"screen\""), "the page did not come back")
        assertTrue(read("/client.js").contains("function decode"), "the client script did not come back")
        assertTrue(read("/nope").contains("404"), "an unknown path has to be refused")
    }

    @Test
    fun `a capture request reaches the client and its answer comes back`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            client.setTimeout(2_000)
            client.sendText("tick")
            assertTrue(client.readMessage() != null, "no frame arrived before the capture")

            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
            val answer = CompletableFuture<ByteArray?>()
            Thread { answer.complete(running.capture(timeoutMillis = 5_000, waitForClientMillis = 2_000)) }
                .apply { isDaemon = true }
                .start()

            // Play the browser: answer the request when it arrives, until the server has its picture.
            while (!answer.isDone) {
                val message = try {
                    client.readMessage()
                } catch (timeout: SocketTimeoutException) {
                    continue
                } ?: break
                if (message.opcode == WsClient.OPCODE_TEXT && String(message.payload).trim() == "capture") {
                    client.sendBinary(png)
                }
            }
            assertContentEquals(png, answer.get(), "the client's answer did not reach the server")
        }
    }

    @Test
    fun `reported glyph advances reach the layout`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            client.setTimeout(10_000)
            client.sendText("tick")
            val before = Decoder.apply(null, first(client))

            // Deliberately wide for a character the page is full of, so everything after it moves.
            client.sendText("metrics 90 ${'a'.code}:90")
            client.sendText("keyframe")
            val after = Decoder.apply(null, first(client))

            assertFalse(
                before.contentEquals(after),
                "the advance the client reported did not reach the layout",
            )
        }
    }

    private fun first(client: WsClient): ByteArray = client.readMessage()?.payload ?: error("no frame arrived")

    private fun start(): WebServer {
        WebTestRuntime.install()
        val server = WebServer(port = 0, page = "color", initialWidth = 800, initialHeight = 600)
        server.start()
        this.server = server
        return server
    }

    /** Every stride in [Op] has to consume the frame the client decoded, or nothing after it is usable. */
    private fun walkToEnd(words: IntArray) {
        var at = 0
        while (at < words.size) {
            val stride = Op.strideAt(words, at)
            assertTrue(stride > 0, "op ${Op.nameOf(words[at])} at $at has no stride")
            at += stride
        }
        assertEquals(words.size, at, "the decoded frame does not walk to its own end")
    }

    private fun await(millis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun read(path: String): String = Socket("127.0.0.1", server!!.boundPort).use { socket ->
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
