package io.github.u2894638479.kotlinmcuiweb

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * A minimal RFC 6455 server endpoint.
 *
 * The JDK has a WebSocket *client* and no server, and this repository's other backends bundle
 * nothing, so the framing is written out here rather than pulled in: it is a few dozen lines for what
 * this protocol actually uses - text commands one way, binary frames the other, plus ping and close.
 *
 * Fragmentation is reassembled rather than rejected, because a browser is allowed to split a frame
 * and a text command that happens to straddle two of them must not be lost. Extensions (permessage
 * deflate among them) are never negotiated: the frames are already the smallest form the design has,
 * and a shared compression context would fight the per-frame delta rather than help it.
 */
internal class WebSocket(private val socket: Socket) {
    private val input: InputStream = socket.getInputStream().buffered()
    private val output: OutputStream = socket.getOutputStream().buffered()

    /**
     * Answer an upgrade request.
     *
     * [key] is the client's `Sec-WebSocket-Key`; the accept value is its SHA-1 with the protocol's
     * fixed GUID, base64 encoded, which is the whole of the handshake a server has to do.
     */
    fun accept(key: String) {
        val accept = Base64.getEncoder().encodeToString(sha1((key + GUID).toByteArray(Charsets.US_ASCII)))
        val headers = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    fun sendBinary(payload: ByteArray) = send(OPCODE_BINARY, payload)

    fun sendText(text: String) = send(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /** Close politely; the caller still has to close the socket. */
    fun close(code: Int = 1000) {
        runCatching { send(OPCODE_CLOSE, byteArrayOf((code ushr 8).toByte(), code.toByte())) }
    }

    /**
     * The next message, or null once the peer has closed.
     *
     * Control frames are answered here - a ping becomes a pong, a close ends the loop - so the caller
     * only ever sees a data message. A fragmented message is reassembled rather than rejected, since
     * a browser may split one and a command that straddled two frames must not be lost.
     */
    fun read(): Message? {
        var opcode = -1
        val collected = ByteArrayOutputStream()
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val last = first and 0x80 != 0
            val frameOpcode = first and 0x0F
            val second = input.read()
            if (second < 0) return null
            val masked = second and 0x80 != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = readLength(2) else if (length == 127L) length = readLength(8)
            if (length > MAX_MESSAGE) throw EOFException("a $length byte frame is past the limit")
            val mask = if (masked) readBytes(4) else null
            val bytes = readBytes(length.toInt())
            if (mask != null) for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (frameOpcode) {
                OPCODE_PING -> {
                    send(OPCODE_PONG, bytes)
                    continue
                }
                OPCODE_PONG -> continue
                OPCODE_CLOSE -> {
                    close()
                    return null
                }
                OPCODE_TEXT, OPCODE_BINARY -> {
                    opcode = frameOpcode
                    collected.write(bytes)
                }
                OPCODE_CONTINUATION -> collected.write(bytes)
                else -> continue
            }
            if (last) {
                val payload = collected.toByteArray()
                return Message(opcode, payload, if (opcode == OPCODE_TEXT) String(payload, Charsets.UTF_8) else "")
            }
        }
    }

    fun closeSocket() {
        runCatching { socket.close() }
    }

    private fun send(opcode: Int, payload: ByteArray) {
        val header = ArrayList<Byte>(payload.size + 10)
        header += (0x80 or opcode).toByte()
        when {
            payload.size < 126 -> header += payload.size.toByte()
            payload.size < 0x10000 -> {
                header += 126.toByte()
                header += ((payload.size ushr 8) and 0xFF).toByte()
                header += (payload.size and 0xFF).toByte()
            }
            else -> {
                header += 127.toByte()
                for (shift in 56 downTo 0 step 8) header += ((payload.size.toLong() ushr shift) and 0xFF).toByte()
            }
        }
        output.write(header.toByteArray())
        output.write(payload)
        output.flush()
    }

    private fun readLength(bytes: Int): Long {
        val raw = readBytes(bytes)
        var value = 0L
        for (byte in raw) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    private fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val step = input.read(bytes, read, count - read)
            if (step < 0) throw EOFException("the peer closed in the middle of a frame")
            read += step
        }
        return bytes
    }

    private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    internal class Message(val opcode: Int, val bytes: ByteArray, val text: String)

    internal companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        const val OPCODE_CONTINUATION = 0x0

        /** Frames are one UI frame; anything this large is a mistake, not a message. */
        const val MAX_MESSAGE = 4 * 1024 * 1024
    }
}
