package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.input.Key
import io.github.u2894638479.kotlinmcui.input.KeyModifiers
import io.github.u2894638479.kotlinmcui.input.MouseButton
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The host: an HTTP server for the page and the client script, and one WebSocket per browser for the
 * frame stream and the input.
 *
 * ### One frame, many clients
 * The framework renders one tree for the process, so a frame is taken **once** and then encoded for
 * every connection (each has its own [FrameStream], because each holds a different base frame). A
 * tick that arrives while a frame is already being taken is dropped rather than queued - the rule the
 * delta design was built around, and the reason a slow client cannot make the server fall behind.
 *
 * The viewport, on the other hand, is shared: `TopComponent.rect` is process-wide, so a resize from
 * one client resizes the UI for all of them and forces every session back to a keyframe.
 *
 * ### Threads
 * Everything that touches the framework happens on [WebUtils.frameExecutor] - the context records the
 * thread that created it - so the socket readers only parse a command and hand it over. Frames are
 * therefore written from the frame thread alone, while each reader thread only ever reads its own
 * socket: no lock around the socket, and no interleaved writes.
 */
internal class WebServer(
    private val port: Int = 0,
    private val page: String = "entry",
    initialWidth: Int = 1280,
    initialHeight: Int = 720,
    private val title: String = "KotlinMCUI",
) {
    private val server = ServerSocket(port)
    private val sessions = CopyOnWriteArrayList<Session>()
    private val renderPending = AtomicBoolean(false)

    @Volatile
    private var width = initialWidth

    @Volatile
    private var height = initialHeight

    /** The port actually bound, which is what a caller wants when it asked for 0. */
    val boundPort: Int get() = server.localPort

    /** Where a client's answer to a capture request lands, until the caller takes it. */
    @Volatile
    private var capture: java.util.concurrent.CompletableFuture<ByteArray>? = null

    /**
     * Bind the page and start accepting.
     *
     * The page is built on the frame thread and this returns once it exists, so a caller (or a test)
     * can connect the moment this returns rather than racing the first tick.
     */
    fun start() {
        val ready = CountDownLatch(1)
        WebUtils.frameExecutor.execute {
            WebBackend.create(title) { pageNamed(page) }.show()
            ready.countDown()
        }
        ready.await()
        // `capture` asks the framework for a picture of the client, and this is what answers it.
        WebUtils.captureSource = { capture() }
        acceptLoopThread().start()
        dslLogger.info("web server: page '$page' on http://127.0.0.1:$boundPort/ (${width}x$height)")
    }

    fun stop() {
        WebUtils.captureSource = null
        runCatching { server.close() }
        for (session in sessions) session.close()
    }

    /**
     * Ask the browser to photograph its canvas and hand the PNG back.
     *
     * This is where `capture` in [WebUtils] ends up: the pixels only exist in the client, so the
     * server asks over the socket and waits. The answer arrives as a binary message on the session,
     * which is the one thing this server accepts on that channel - everything else the client sends
     * is a text command.
     *
     * Returns null when there is no client, or when it does not answer in time.
     */
    fun capture(timeoutMillis: Long = 20_000, waitForClientMillis: Long = 30_000): ByteArray? {
        val deadline = System.currentTimeMillis() + waitForClientMillis
        while (sessions.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (sessions.isEmpty()) return null
        val pending = java.util.concurrent.CompletableFuture<ByteArray>()
        capture = pending
        try {
            val giveUp = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < giveUp) {
                // A request that arrives before the page has painted is simply ignored, so ask again.
                sessions.firstOrNull()?.sendText("capture")
                return try {
                    pending.get(1, java.util.concurrent.TimeUnit.SECONDS)
                } catch (timeout: java.util.concurrent.TimeoutException) {
                    continue
                }
            }
            return null
        } finally {
            capture = null
        }
    }

    private fun captured(png: ByteArray) {
        capture?.complete(png)
    }

    private fun acceptLoopThread() = Thread({ acceptLoop() }, "kotlinmcui-web-accept").apply { isDaemon = true }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (throwable: Throwable) {
                return
            }
            Thread({ handle(socket) }, "kotlinmcui-web-connection").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val request = readRequest(socket.getInputStream())
            if (request == null) {
                socket.close()
                return
            }
            val key = request.headers["sec-websocket-key"]
            if (request.path == "/ws" && key != null) upgrade(socket, key) else staticFile(socket, request.path)
        } catch (throwable: Throwable) {
            dslLogger.warn("web server: connection ended: ${throwable.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun upgrade(socket: Socket, key: String) {
        val session = Session(WebSocket(socket))
        session.accept(key)
        sessions += session
        dslLogger.info("web server: a client connected (${sessions.size} connected)")
        try {
            session.serve()
        } finally {
            sessions -= session
            dslLogger.info("web server: a client left (${sessions.size} connected)")
        }
    }

    /** A file from this jar's `web/` resources. Nothing else is reachable, so no path can escape. */
    private fun staticFile(socket: Socket, path: String) {
        val name = when (path) {
            "/", "", "/index.html" -> "index.html"
            "/client.js" -> "client.js"
            else -> null
        }
        val bytes = name?.let { javaClass.getResourceAsStream("/web/$it")?.use { stream -> stream.readBytes() } }
        val output = BufferedOutputStream(socket.getOutputStream())
        if (bytes == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val type = if (name.endsWith(".js")) "text/javascript; charset=utf-8" else "text/html; charset=utf-8"
        output.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        output.write(bytes)
        output.flush()
    }

    /** Take a frame (once) and send it to everybody. */
    private fun requestFrame() {
        if (!renderPending.compareAndSet(false, true)) return
        WebUtils.frameExecutor.execute {
            try {
                WebBackend.frame(width, height)
                val words = Recorder.buffer()
                val size = Recorder.size
                for (session in sessions) session.send(words, size)
            } catch (throwable: Throwable) {
                dslLogger.error("web server: a frame failed", throwable)
            } finally {
                renderPending.set(false)
            }
        }
    }

    /**
     * Resize the shared viewport.
     *
     * Layout depends on the size, so every client's base frame is void: the sessions reset and the
     * next frame goes out in full.
     */
    private fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) return
        width = newWidth
        height = newHeight
        for (session in sessions) session.reset()
        requestFrame()
    }

    private fun input(command: String) {
        WebUtils.frameExecutor.execute { dispatch(command) }
        requestFrame()
    }

    /**
     * One input command, on the frame thread.
     *
     * The client sends platform names (DOM key codes, DOM button numbers) and the translation happens
     * here, where the framework's own key and button constants live - the client is a browser script
     * and cannot know them.
     */
    private fun dispatch(command: String) {
        val parts = command.split(' ')
        when (parts.firstOrNull()) {
            "move" -> WebInput.moved(number(parts, 1).toFloat(), number(parts, 2).toFloat())
            "button" -> {
                val button = buttonOf(parts.getOrNull(1)?.toIntOrNull()) ?: return
                val modifiers = modifiersOf(parts.getOrNull(3)?.toIntOrNull())
                WebInput.modifiers(modifiers)
                if (parts.getOrNull(2) == "down") WebInput.pressed(button) else WebInput.released(button)
            }
            "wheel" -> {
                WebInput.modifiers(modifiersOf(parts.getOrNull(3)?.toIntOrNull()))
                val horizontal = parts.getOrNull(3)?.toIntOrNull()?.and(1) == 1
                if (horizontal) WebInput.scrolled(number(parts, 1) / WHEEL_STEP, true)
                else WebInput.scrolled(number(parts, 2) / WHEEL_STEP, false)
            }
            "key" -> {
                val key = keyOf(parts.getOrNull(1) ?: return) ?: return
                WebInput.modifiers(modifiersOf(parts.getOrNull(3)?.toIntOrNull()))
                // There is no platform scan code over a socket; the framework's own code is the honest
                // stand-in, and the components that read it (the game's text box) are not here.
                if (parts.getOrNull(2) == "down") WebInput.keyPressed(key, key.code) else WebInput.keyReleased(key, key.code)
            }
            "char" -> parts.getOrNull(1)?.toIntOrNull()?.let { WebInput.typed(it) }
            "blur" -> WebInput.blur()
        }
    }

    private fun number(parts: List<String>, index: Int): Double = parts.getOrNull(index)?.toDoubleOrNull() ?: 0.0

    private inner class Session(private val socket: WebSocket) {
        private val stream = FrameStream()

        /**
         * Frames are written by the frame thread, a capture request by whoever asked for it, so
         * every write goes through one lock - a WebSocket message cannot be interleaved.
         */
        private val writeLock = Any()

        fun accept(key: String) = socket.accept(key)

        fun send(words: IntArray, size: Int) {
            runCatching {
                val message = stream.encode(words, size)
                synchronized(writeLock) { socket.sendBinary(message) }
            }.onFailure { dslLogger.warn("web server: a frame could not be sent: ${it.message}") }
        }

        fun reset() = stream.reset()

        fun sendText(text: String) = runCatching { synchronized(writeLock) { socket.sendText(text) } }

        fun close() = socket.closeSocket()

        /**
         * Read commands until the client goes away.
         *
         * Text messages are commands; a binary message is the answer to a capture request, which is
         * the only pixel data that ever travels from a client.
         */
        fun serve() {
            while (true) {
                val message = socket.read() ?: return
                if (message.opcode == WebSocket.OPCODE_BINARY) {
                    captured(message.bytes)
                    continue
                }
                when (message.text.trim()) {
                    "" -> Unit
                    "tick" -> requestFrame()
                    "keyframe" -> {
                        reset()
                        requestFrame()
                    }
                    "blur" -> input(message.text.trim())
                    else -> {
                        val parts = message.text.trim().split(' ')
                        when (parts.firstOrNull()) {
                            "resize" -> resize(
                                parts.getOrNull(1)?.toIntOrNull() ?: width,
                                parts.getOrNull(2)?.toIntOrNull() ?: height,
                            )
                            "move", "button", "wheel", "key", "char" -> input(message.text.trim())
                        }
                    }
                }
            }
        }
    }

    private fun buttonOf(domButton: Int?): MouseButton? = when (domButton) {
        0 -> MouseButton.entries.getOrNull(0)
        1 -> MouseButton.entries.getOrNull(2)
        2 -> MouseButton.entries.getOrNull(1)
        else -> null
    }

    private fun modifiersOf(bits: Int?): KeyModifiers {
        val value = bits ?: 0
        return KeyModifiers(
            shift = value and 1 != 0,
            ctrl = value and 2 != 0,
            alt = value and 4 != 0,
            superKey = value and 8 != 0,
            capsLock = value and 16 != 0,
            numLock = value and 32 != 0,
        )
    }

    private class Request(val path: String, val headers: Map<String, String>)

    /**
     * Read a request line and its headers.
     *
     * Only what this server needs: the path, and the headers it looks up by name. The body is never
     * read, because nothing here takes one - the WebSocket upgrade is a GET with no payload.
     */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val path = requestLine.split(' ').getOrNull(1) ?: return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return Request(path, headers)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > MAX_LINE) return null
            line.append(byte.toChar())
        }
    }

    private companion object {
        const val MAX_LINE = 8192

        /** Browsers report wheel deltas in pixels; one notch is a hundred of them, near enough. */
        const val WHEEL_STEP = 100f

        /**
         * The extension range the framework documents for key codes it does not name.
         *
         * A key this table does not know still has to be a *stable* code, or the same key would look
         * different every time it was pressed; hashing the DOM code into the extension range is what
         * the framework's own `custom` codes are for.
         */
        const val CUSTOM_KEY_FLOOR = 1000
    }

    /** DOM `KeyboardEvent.code` to the framework's key. Physical keys, so no layout is assumed. */
    private fun keyOf(code: String): Key? = when (code) {
        "Enter" -> Key.ENTER
        "Escape" -> Key.ESCAPE
        "Backspace" -> Key.BACKSPACE
        "Delete" -> Key.DELETE
        "Tab" -> Key.TAB
        "ArrowLeft" -> Key.LEFT
        "ArrowRight" -> Key.RIGHT
        "ArrowUp" -> Key.UP
        "ArrowDown" -> Key.DOWN
        "Home" -> Key.HOME
        "End" -> Key.END
        "PageUp" -> Key.PAGE_UP
        "PageDown" -> Key.PAGE_DOWN
        "Insert" -> Key.INSERT
        "ShiftLeft" -> Key.LEFT_SHIFT
        "ShiftRight" -> Key.RIGHT_SHIFT
        "ControlLeft" -> Key.LEFT_CTRL
        "ControlRight" -> Key.RIGHT_CTRL
        "AltLeft" -> Key.LEFT_ALT
        "AltRight" -> Key.RIGHT_ALT
        "MetaLeft" -> Key.LEFT_SUPER
        "MetaRight" -> Key.RIGHT_SUPER
        in LETTERS -> Key(Key.A.code + (code[3] - 'A'))
        in DIGITS -> Key(Key.N0.code + (code[3] - '0'))
        in FUNCTIONS -> Key(Key.F1.code + (code.substring(1).toInt() - 1))
        else -> runCatching { Key.custom(CUSTOM_KEY_FLOOR + (code.hashCode() and 0xFFFF)) }.getOrNull()
    }

    private val LETTERS = (('A'..'Z').map { "Key$it" }).toSet()
    private val DIGITS = (('0'..'9').map { "Digit$it" }).toSet()
    private val FUNCTIONS = (1..12).map { "F$it" }.toSet()
}
