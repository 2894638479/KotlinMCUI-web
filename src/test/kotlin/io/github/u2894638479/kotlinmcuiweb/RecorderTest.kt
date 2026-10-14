package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.test.TestColorPage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recorder, against a real page rather than a hand-built buffer.
 *
 * Two things are worth pinning here. The first is structural: a frame has to be walkable by the
 * strides in [Op] and land exactly on its own end, because that is what the report, the SVG writer
 * and the client's replay all do - an op that writes one word too few would desynchronise every
 * reader at once. The second is the whole point of the design: a page nobody is touching repeats
 * itself byte for byte, so its frames cost one byte each.
 */
class RecorderTest {
    @Test
    fun `a frame walks to its own end and a still page repeats it byte for byte`() {
        WebTestRuntime.install()
        WebBackend.create("recorder-test") { TestColorPage() }.show()

        val stream = FrameStream()
        val first = frame(stream)
        val second = frame(stream)

        assertEquals(Encoder.KEYFRAME, first[0])
        assertEquals(Encoder.NOCHANGE, second[0])
        assertContentEquals(Decoder.apply(null, first), lastFrame)
    }

    private fun frame(stream: FrameStream): ByteArray {
        WebTestRuntime.frame()
        val size = Recorder.size
        assertTrue(size > 0, "a page drew nothing")
        walkToEnd(size)
        lastFrame = Recorder.buffer().copyOf(size)
        return stream.encode(Recorder.buffer(), size)
    }

    /** Every stride in [Op] must consume a whole frame, whatever the page put in it. */
    private fun walkToEnd(size: Int) {
        val words = Recorder.buffer()
        var at = 0
        while (at < size) {
            val stride = Op.strideAt(words, at)
            assertTrue(stride > 0, "op ${Op.nameOf(words[at])} at $at has no stride")
            at += stride
        }
        assertEquals(size, at, "the frame does not walk to its own end")
    }

    private lateinit var lastFrame: IntArray
}
