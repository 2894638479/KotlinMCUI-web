package io.github.u2894638479.kotlinmcuiweb

import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * A WebSocket client for the tests, written independently of the server's framing.
 *
 * It is deliberately not shared code with [WebSocket]: a handshake that accepts its own accept value,
 * or a frame reader that agrees with its own writer, would prove nothing. This one computes the
 * accept value from the protocol's GUID on its own and masks what it sends, as a browser does.
 */
internal class WsClient(host: String = "127.0.0.1", port: Int) : AutoCloseable {
    private val socket = Socket(host, port)
    private val input: InputStream = socket.getInputStream().buffered()
    private val output = socket.getOutputStream().buffered()

    val statusLine: String
    val accept: String
    val expectedAccept: String

    init {
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        expectedAccept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
        )
        output.write(
            (
                "GET /ws HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                ).toByteArray(),
        )
        output.flush()
        val headers = ArrayList<String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            headers += line
        }
        statusLine = headers.firstOrNull() ?: ""
        accept = headers.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
            ?.substringAfter(':')?.trim().orEmpty()
    }

    fun sendText(text: String) = send(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(payload: ByteArray) = send(OPCODE_BINARY, payload)

    private fun send(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4) { (it + 1).toByte() }
        val header = ArrayList<Byte>()
        header += (0x80 or opcode).toByte()
        if (payload.size < 126) {
            header += (0x80 or payload.size).toByte()
        } else {
            header += (0x80 or 126).toByte()
            header += ((payload.size ushr 8) and 0xFF).toByte()
            header += (payload.size and 0xFF).toByte()
        }
        header.addAll(mask.toList())
        output.write(header.toByteArray())
        output.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        output.flush()
    }

    /** The next message, or null when the socket has nothing within the current timeout. */
    fun readMessage(): Message? {
        val first = input.read()
        if (first < 0) return null
        val opcode = first and 0x0F
        val second = input.read()
        if (second < 0) return null
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = readLength(2) else if (length == 127L) length = readLength(8)
        val payload = readBytes(length.toInt())
        return Message(opcode, payload)
    }

    fun setTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun readLength(bytes: Int): Long {
        var value = 0L
        for (byte in readBytes(bytes)) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    private fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val step = input.read(bytes, read, count - read)
            if (step < 0) throw EOFException("the server closed mid-frame")
            read += step
        }
        return bytes
    }

    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }

    internal class Message(val opcode: Int, val payload: ByteArray)

    internal companion object {
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
