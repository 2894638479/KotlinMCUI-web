package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.input.Key
import io.github.u2894638479.kotlinmcui.input.KeyModifiers
import io.github.u2894638479.kotlinmcui.input.MouseButton
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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
 * The viewport, on the other hand, is shared: `DslRuntime.ownerRoot.rect` is one rect for the process,
 * so a resize from one client resizes the UI for all of them and forces every session back to a
 * keyframe.
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

    /** Names a viewer in a log line; with one viewer per root it also names the root. */
    private val nextSessionId = java.util.concurrent.atomic.AtomicInteger()

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
        // `capture` asks the framework for a picture of the client, and this is what answers it. The
        // framework gets pixels or nothing, so the reason nothing came back is said here, where it can
        // name the session that was waited on.
        WebUtils.captureSource = {
            when (val result = capture()) {
                is Capture.Answered -> result.png
                is Capture.NoClient -> {
                    dslLogger.warn("web server: no browser connected within ${result.waitedMillis} ms, nothing to photograph")
                    null
                }
                is Capture.Unanswered -> {
                    dslLogger.warn("web server: browser ${result.session} did not answer within ${result.waitedMillis} ms")
                    null
                }
            }
        }
        acceptLoopThread().start()
        dslLogger.info("web server: page '$page' on http://127.0.0.1:$boundPort/ (${width}x$height)")
    }

    fun stop() {
        WebUtils.captureSource = null
        runCatching { server.close() }
        for (session in sessions) session.close()
    }

    /**
     * How a capture request ended.
     *
     * The two waits are separate because they measure different things: waiting for a browser is the
     * latency of whoever is meant to launch one, while waiting for its answer is the socket round trip.
     * Which of the two ran out is the difference between "nothing ever connected" and "something
     * connected and then went quiet" - two faults with two fixes - and both carry the session, because
     * with one viewer per root the session *is* the root being captured.
     */
    sealed interface Capture {
        class Answered(val png: ByteArray, val session: Int) : Capture

        class NoClient(val waitedMillis: Long) : Capture

        class Unanswered(val session: Int, val waitedMillis: Long) : Capture
    }

    /**
     * Ask the browser to photograph its canvas and hand the PNG back.
     *
     * This is where `capture` in [WebUtils] ends up: the pixels only exist in the client, so the
     * server asks over the socket and waits. The answer arrives as a binary message on the session,
     * which is the one thing this server accepts on that channel - everything else the client sends
     * is a text command.
     *
     * The session is picked once and then asked until the answer deadline, rather than re-picked every
     * second: the outcome names the root it waited on, so it has to be the same one throughout. A
     * session that leaves mid-wait therefore ends as [Capture.Unanswered] naming it, which is the
     * honest report - it was asked, and it never answered.
     */
    fun capture(
        answerMillis: Long = ANSWER_MILLIS,
        waitForClientMillis: Long = DEFAULT_WAIT_FOR_CLIENT_MILLIS,
    ): Capture {
        val clientDeadline = System.currentTimeMillis() + waitForClientMillis
        while (sessions.isEmpty() && System.currentTimeMillis() < clientDeadline) Thread.sleep(POLL_MILLIS)
        val session = sessions.firstOrNull() ?: return Capture.NoClient(waitForClientMillis)
        val pending = java.util.concurrent.CompletableFuture<ByteArray>()
        capture = pending
        try {
            val giveUp = System.currentTimeMillis() + answerMillis
            while (System.currentTimeMillis() < giveUp) {
                // A request that arrives before the page has painted is simply ignored, so ask again.
                session.sendText(CAPTURE_COMMAND)
                try {
                    return Capture.Answered(pending.get(1, java.util.concurrent.TimeUnit.SECONDS), session.id)
                } catch (timeout: java.util.concurrent.TimeoutException) {
                    continue
                }
            }
            return Capture.Unanswered(session.id, answerMillis)
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
            when {
                request.path == "/ws" && key != null -> upgrade(socket, key)
                // A browser asks for the tab icon by name whether or not the page declares one.
                request.path == FAVICON_PATH -> asset(socket, FRAMEWORK_ICON)
                request.path.startsWith(ASSET_PATH) -> asset(socket, request.path.removePrefix(ASSET_PATH))
                else -> staticFile(socket, request.path)
            }
        } catch (throwable: Throwable) {
            dslLogger.warn("web server: connection ended: ${throwable.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun upgrade(socket: Socket, key: String) {
        val session = Session(WebSocket(socket), nextSessionId.incrementAndGet())
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

    /**
     * The pixels behind an asset id.
     *
     * Ids are the framework's `namespace:path`, which resolves to `/assets/<namespace>/<path>` on the
     * classpath - that is where the framework's own icon lives, and where a mod's assets live in its
     * own jar. A plain path is tried as it is, and a `local:` id is served only when this backend
     * described that file, so a client cannot name a path of its own. Anything else is a 404 and the
     * client keeps drawing its placeholder.
     */
    private fun asset(socket: Socket, rawId: String) {
        val id = runCatching { URLDecoder.decode(rawId, "UTF-8") }.getOrDefault(rawId)
        val bytes = assetBytes(id)
        val output = BufferedOutputStream(socket.getOutputStream())
        if (bytes == null) {
            dslLogger.info("web assets: '$id' was not found, the client keeps its placeholder")
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: $IMAGE_TYPE\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: max-age=3600\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            output.write(bytes)
        }
        output.flush()
    }

    private fun assetBytes(id: String): ByteArray? {
        if (id.startsWith(WebUtils.LOCAL_PREFIX)) {
            return WebUtils.localImageFile(id)?.let { file -> runCatching { file.readBytes() }.getOrNull() }
        }
        return WebAssets.bytes(id)
    }

    /** A file from this jar's `web/` resources. Nothing else is reachable, so no path can escape. */
    private fun staticFile(socket: Socket, path: String) {        val name = when (path) {
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
                val horizontal = number(parts, 1)
                val vertical = number(parts, 2)
                // The framework counts wheel notches upwards - the direction GLFW reports in, which is
                // why the window backend negates AWT's - so both axes arrive negated here as well.
                // An event that carries a horizontal delta is horizontal (a trackpad, or a browser that
                // already swapped the axes for a held shift); otherwise it is a plain vertical wheel,
                // and the framework routes it to a horizontal scroller itself while shift is down.
                when {
                    horizontal != 0.0 -> WebInput.scrolled(-horizontal / WHEEL_STEP, true)
                    vertical != 0.0 -> WebInput.scrolled(-vertical / WHEEL_STEP, false)
                }
            }
            "key" -> {
                val key = keyOf(parts.getOrNull(1) ?: return) ?: return
                WebInput.modifiers(modifiersOf(parts.getOrNull(3)?.toIntOrNull()))
                // There is no platform scan code over a socket; the framework's own code is the honest
                // stand-in, and the components that read it (the game's text box) are not here.
                val scanCode = key.code
                if (parts.getOrNull(2) == "down") {
                    val consumed = WebInput.keyPressed(key, scanCode)
                    // Escape leaves the page when nothing on it wanted the key. It is the only way back
                    // from a page that was entered, and it is the rule the window backend follows too.
                    if (!consumed && key == Key.ESCAPE) WebBackend.pop()
                } else {
                    WebInput.keyReleased(key, scanCode)
                }
            }
            "char" -> parts.getOrNull(1)?.toIntOrNull()?.let { WebInput.typed(it) }
            "blur" -> WebInput.blur()
            "metrics" -> applyMetrics(parts)
            "lang" -> parts.getOrNull(1)?.let { WebUtils.useLanguage(it) }
            "log" -> dslLogger.warn("web client: " + parts.drop(1).joinToString(" "))
        }
    }

    /**
     * The advances the client measured, in one line: a reference font size and `codePoint:width`
     * pairs. See [FontMetrics] for why the client is the one that knows.
     */
    private fun applyMetrics(parts: List<String>) {
        val reference = parts.getOrNull(1)?.toFloatOrNull() ?: return
        val measured = HashMap<Int, Float>()
        for (entry in parts.drop(2)) {
            val separator = entry.indexOf(':')
            if (separator <= 0) continue
            val code = entry.substring(0, separator).toIntOrNull() ?: continue
            val width = entry.substring(separator + 1).toFloatOrNull() ?: continue
            measured[code] = width
        }
        if (measured.isEmpty()) return
        FontMetrics.update(reference, measured)
        dslLogger.info("web server: the client reported ${measured.size} glyph advances (reference ${reference}px)")
    }

    private fun number(parts: List<String>, index: Int): Double = parts.getOrNull(index)?.toDoubleOrNull() ?: 0.0

    private inner class Session(private val socket: WebSocket, val id: Int) {
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
                synchronized(writeLock) {
                    // A keyframe can reference assets this client was never told about: the table is the
                    // server's and an id is defined once per server, not once per connection. So the
                    // table travels with every keyframe, which is what makes a reload resolve its icons.
                    if (message[0] == Encoder.KEYFRAME) socket.sendBinary(Encoder.assets(Recorder.assetTable()))
                    socket.sendBinary(message)
                }
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
                            "move", "button", "wheel", "key", "char", "metrics", "log", "lang" -> input(message.text.trim())
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

    internal companion object {
        /** How long a capture waits for a browser to connect before giving up. */
        const val DEFAULT_WAIT_FOR_CLIENT_MILLIS = 30_000L

        /** How long it then waits for that browser's answer; this is the socket round trip. */
        const val ANSWER_MILLIS = 20_000L

        /** How often the wait for a browser looks again. */
        private const val POLL_MILLIS = 50L

        /**
         * The text command that asks the client for a picture of its canvas.
         *
         * The bytes are the client's to interpret, so this string is part of the wire protocol and
         * has to match what the client script compares against.
         */
        private const val CAPTURE_COMMAND = "capture"

        private const val MAX_LINE = 8192

        /** Where a client asks for an asset, by the id a frame referenced. */
        private const val ASSET_PATH = "/asset/"

        /** Where a browser asks for the tab icon; the framework's own icon answers it. */
        private const val FAVICON_PATH = "/favicon.ico"

        private const val FRAMEWORK_ICON = "kotlinmcui:icon.png"

        private const val IMAGE_TYPE = "image/png"

        /** Browsers report wheel deltas in pixels; one notch is a hundred of them, near enough. */
        private const val WHEEL_STEP = 100f

        /**
         * The extension range the framework documents for key codes it does not name.
         *
         * A key this table does not know still has to be a *stable* code, or the same key would look
         * different every time it was pressed; hashing the DOM code into the extension range is what
         * the framework's own `custom` codes are for.
         */
        private const val CUSTOM_KEY_FLOOR = 1000
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
