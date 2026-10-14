package io.github.u2894638479.kotlinmcuiweb

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wire format, without a framework in the way: the encoder's messages have to be exactly what the
 * decoder rebuilds, and "nothing changed" has to be cheaper than anything else.
 */
class FrameStreamTest {
    @Test
    fun `a still frame travels as one byte`() {
        val stream = FrameStream()
        val frame = intArrayOf(7, 8, 9, 10)

        assertEquals(Encoder.KEYFRAME, stream.encode(frame, frame.size)[0])

        val second = stream.encode(frame, frame.size)
        assertEquals(Encoder.NOCHANGE, second[0])
        assertEquals(1, second.size)
        assertContentEquals(frame, Decoder.apply(frame, second))
    }

    @Test
    fun `a keyframe carries the whole frame and the decoder rebuilds it`() {
        val stream = FrameStream()
        val frame = IntArray(100) { it * 7 }

        val message = stream.encode(frame, frame.size)
        assertEquals(Encoder.KEYFRAME, message[0])
        assertEquals(Encoder.KEYFRAME_HEADER + 400, message.size)
        assertContentEquals(frame, Decoder.apply(null, message))
    }

    @Test
    fun `a delta carries only what differs and rebuilds the frame from the client's own copy`() {
        val stream = FrameStream()
        val base = IntArray(200) { it }
        stream.encode(base, base.size)

        val changed = base.copyOf()
        changed[150] = -1
        val message = stream.encode(changed, changed.size)

        assertEquals(Encoder.DELTA, message[0])
        assertEquals(Encoder.DELTA_HEADER + 4, message.size)
        assertContentEquals(changed, Decoder.apply(base, message))
    }

    @Test
    fun `a frame that shares too little goes out in full`() {
        val stream = FrameStream()
        val base = IntArray(200) { it }
        stream.encode(base, base.size)

        val other = IntArray(200) { -it }
        val message = stream.encode(other, other.size)

        assertEquals(Encoder.KEYFRAME, message[0])
        assertContentEquals(other, Decoder.apply(base, message))
    }

    @Test
    fun `a client that holds nothing cannot take a delta`() {
        val stream = FrameStream()
        val base = IntArray(8) { it }
        stream.encode(base, base.size)
        val changed = base.copyOf().also { it[4] = 99 }
        val message = stream.encode(changed, changed.size)

        assertEquals(Encoder.DELTA, message[0])
        assertFailsWith<IllegalArgumentException> { Decoder.apply(null, message) }
    }

    @Test
    fun `a reset makes the next frame a keyframe again`() {
        val stream = FrameStream()
        val frame = IntArray(50) { it }
        stream.encode(frame, frame.size)

        stream.reset()
        val message = stream.encode(frame, frame.size)

        assertEquals(Encoder.KEYFRAME, message[0])
        assertTrue(message.size > 1)
    }
}
