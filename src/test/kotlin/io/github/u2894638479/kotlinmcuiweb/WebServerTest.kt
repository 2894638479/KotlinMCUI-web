package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.input.Key
import io.github.u2894638479.kotlinmcui.test.TestLayoutPage
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        // A stopped server must not leave its page on the stack: the tests share a process, and the
        // next one starts from whatever the last one left behind. Releasing happens on the frame
        // thread, because the framework's context belongs to the thread that created it.
        val done = CountDownLatch(1)
        WebUtils.frameExecutor.execute {
            WebBackend.closeAll()
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a browser handshake, a keyframe, a still frame and a resize`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            assertTrue(client.statusLine.contains("101"), "not an upgrade: ${client.statusLine}")
            assertEquals(client.expectedAccept, client.accept, "the accept value is wrong")

            client.setTimeout(10_000)
            client.sendText("tick")
            val first = first(client)
            assertEquals(Encoder.KEYFRAME, first[0])

            val words = Decoder.apply(null, first)
            assertTrue(words.isNotEmpty())
            walkToEnd(words)
            assertTrue(words.any { it == Op.QUAD } || words.any { it == Op.TEXT }, "the frame drew nothing")

            client.sendText("tick")
            val second = first(client)
            assertEquals(Encoder.NOCHANGE, second[0], "a still page was not a one-byte record")
            assertEquals(1, second.size)

            client.sendText("resize 640 480")
            val resized = first(client)
            assertEquals(Encoder.KEYFRAME, resized[0], "a resize has to start over in full")
            val resizedWords = Decoder.apply(null, resized)
            walkToEnd(resizedWords)
            assertFalse(
                Decoder.apply(null, first).contentEquals(resizedWords),
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
            val answer = CompletableFuture<WebServer.Capture?>()
            Thread { answer.complete(running.capture(answerMillis = 5_000, waitForClientMillis = 2_000)) }
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

            val captured = answer.get()
            assertTrue(captured is WebServer.Capture.Answered, "the capture did not end with a picture: $captured")
            captured as WebServer.Capture.Answered
            assertContentEquals(png, captured.png, "the client's answer did not reach the server")
            // One viewer per root, so the outcome has to name the root it photographed.
            assertEquals(1, captured.session, "the capture did not name the browser it photographed")
        }
    }

    /**
     * A capture with nobody watching has to fail on the *first* wait.
     *
     * Both waits ending in the same silent null is what made a slow machine look like a broken page:
     * the two faults are fixed in different places, so the outcome has to say which one happened, and
     * a bounded first wait is what keeps the gate from hanging.
     */
    @Test
    fun `a capture with no browser says nothing connected`() {
        val running = start()

        val started = System.currentTimeMillis()
        val result = running.capture(answerMillis = 5_000, waitForClientMillis = 200)
        val waited = System.currentTimeMillis() - started

        assertTrue(result is WebServer.Capture.NoClient, "expected no client, got $result")
        assertEquals(200, (result as WebServer.Capture.NoClient).waitedMillis, "the outcome lost the wait it gave up on")
        assertTrue(waited < 3_000, "it gave up on the answer wait instead of the connect wait (waited ${waited}ms)")
    }

    /**
     * A browser that connects and then says nothing is a third outcome, and it names the browser.
     *
     * This is the case a capture driver sees when the page loaded but the client script did not run -
     * "connected but silent" is a different fault from "never connected", and the session in the
     * outcome is what says which root was asked.
     */
    @Test
    fun `a browser that connects and stays quiet is reported as unanswered`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            client.setTimeout(2_000)
            client.sendText("tick")
            assertTrue(client.readMessage() != null, "no frame arrived before the capture")

            // Deliberately do not answer the capture request the server is about to send.
            val result = running.capture(answerMillis = 300, waitForClientMillis = 5_000)

            assertTrue(result is WebServer.Capture.Unanswered, "expected an unanswered capture, got $result")
            result as WebServer.Capture.Unanswered
            assertEquals(1, result.session, "the outcome did not name the browser it waited on")
            assertEquals(300, result.waitedMillis, "the outcome lost the wait it gave up on")
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

    @Test
    fun `a wheel event with shift held shows up as the shift key`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            client.setTimeout(5_000)
            // The framework asks isKeyDown(LEFT_SHIFT) - the polled half of input - to decide whether a
            // vertical wheel may scroll a horizontal scroller, so the modifier flag has to reach it.
            client.sendText("wheel 0 -100 1")
            assertTrue(await { WebInput.isKeyDown(Key.LEFT_SHIFT) }, "the shift flag did not reach the polled state")

            client.sendText("wheel 0 -100 0")
            assertTrue(await { !WebInput.isKeyDown(Key.LEFT_SHIFT) }, "the shift key never came up")
        }
    }

    @Test
    fun `escape leaves the page when nothing on it consumed the key`() {
        val running = start()

        WsClient(port = running.boundPort).use { client ->
            client.setTimeout(10_000)
            client.sendText("tick")
            assertTrue(client.readMessage() != null, "no frame arrived")
            val before = WebBackend.pageCount

            // Enter a page the way the entry list does - on the frame thread, where the tree lives.
            val entered = CountDownLatch(1)
            WebUtils.frameExecutor.execute {
                WebBackend.create("second") { TestLayoutPage() }.show()
                entered.countDown()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the page was never entered")
            assertEquals(before + 1, WebBackend.pageCount)

            client.sendText("key Escape down 0")
            assertTrue(await { WebBackend.pageCount == before }, "escape did not leave the page")
        }
    }

    @Test
    fun `the framework's own assets are served and unknown ones are not`() {
        val running = start()

        // The icon the entry list draws: `namespace:path` resolves to the classpath's assets.
        val icon = read("/asset/" + URLEncoder.encode("kotlinmcui:icon.png", "UTF-8"))
        assertTrue(icon.startsWith("HTTP/1.1 200 OK"), icon.take(40))
        assertTrue(icon.contains("Content-Type: image/png"), "not served as an image")
        val length = Regex("Content-Length: (\\d+)").find(icon)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(length > 100, "the icon came back empty (length $length)")

        assertTrue(
            read("/asset/" + URLEncoder.encode("nobody:nothing.png", "UTF-8")).contains("404"),
            "an asset that does not exist has to be a 404, so the client keeps its placeholder",
        )
    }

    @Test
    fun `a client that connects later is told the asset table before the frame that uses it`() {
        val running = start()

        // The first connection's keyframe is where the recorder defines what the page first uses.
        WsClient(port = running.boundPort).use { first ->
            first.setTimeout(10_000)
            first.sendText("tick")
            assertTrue(first.readMessage() != null, "no frame arrived")
        }

        // The second one gets no definitions in the frame - the table is the server's - so it has to be
        // sent separately, or a page's icons stay placeholders for every connection after the first.
        WsClient(port = running.boundPort).use { second ->
            second.setTimeout(10_000)
            second.sendText("tick")

            val table = second.readMessage() ?: error("nothing arrived")
            assertEquals(Encoder.ASSETS.toInt(), table.payload[0].toInt(), "the table did not come first")
            val keyframe = second.readMessage() ?: error("no keyframe arrived")
            assertEquals(Encoder.KEYFRAME.toInt(), keyframe.payload[0].toInt())
            assertEquals(Recorder.assetTable().size, Encoder.getInt(table.payload, 1), "not the recorder's table")
        }
    }

    @Test
    fun `the asset table round-trips`() {
        val entries = listOf(0 to "kotlinmcui:icon.png", 1 to "minecraft:diamond_sword")
        val message = Encoder.assets(entries)

        assertEquals(Encoder.ASSETS.toInt(), message[0].toInt())
        val parsed = ArrayList<Pair<Int, String>>()
        val count = Encoder.getInt(message, 1)
        var at = 5
        repeat(count) {
            val index = Encoder.getInt(message, at)
            val length = Encoder.getInt(message, at + 4)
            at += 8
            val id = StringBuilder()
            repeat(length) {
                id.appendCodePoint(Encoder.getInt(message, at))
                at += 4
            }
            parsed += index to id.toString()
        }
        assertEquals(entries, parsed)
    }

    /** The next frame. A keyframe is preceded by the asset table, which is not a frame. */
    private fun first(client: WsClient): ByteArray {
        while (true) {
            val message = client.readMessage() ?: error("no frame arrived")
            if (message.payload[0] != Encoder.ASSETS) return message.payload
        }
    }

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
