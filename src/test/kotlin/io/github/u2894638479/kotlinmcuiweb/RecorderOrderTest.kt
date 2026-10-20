package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Recorded order equals call order, flush included.
 *
 * The recorder writes its buffer as it is called, so a client replaying the frame sees the page's
 * shapes in the order the page drew them - last drawn on top. That is the property a type-batching
 * recorder would lose, so the overlap test below draws half red, opaque white and half blue and
 * checks the records themselves; a client composites them in this order, which makes "later covers
 * earlier" follow from the record order alone.
 *
 * The flush half is about what survives the contract: a flush is a marker, not a batch boundary to
 * commit, so it must cost exactly one record and move nothing around it.
 */
class RecorderOrderTest {

    private val red = Color(255, 0, 0, 128)
    private val white = Color(255, 255, 255)
    private val blue = Color(0, 0, 255, 128)

    private val redRect = Rect(0.px, 0.px, 30.px, 30.px)
    private val whiteRect = Rect(10.px, 10.px, 40.px, 40.px)
    private val blueRect = Rect(20.px, 20.px, 50.px, 50.px)

    @Test
    fun `the recorded ops are the drawing calls in the order they were made`() {
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(redRect, red, red, red, red)
            // A clip between two draws: the pair has to stay exactly where it was called rather
            // than being lifted out to a pass of its own.
            Recorder.withScissor(whiteRect) {
                Recorder.renderQuad(whiteRect, white, white, white, white)
            }
            Recorder.renderQuad(blueRect, blue, blue, blue, blue)
        }
        val frame = Recorder.buffer().copyOf(Recorder.size)

        assertEquals(
            listOf(Op.QUAD, Op.CLIP_PUSH, Op.QUAD, Op.CLIP_POP, Op.QUAD),
            opcodes(frame),
            "a drawing call or a clip was moved out of call order",
        )

        val quads = records(Op.QUAD, frame).map(::quad)
        assertEquals(
            listOf(
                Quad(0f, 0f, 30f, 30f, red),
                Quad(10f, 10f, 40f, 40f, white),
                Quad(20f, 20f, 50f, 50f, blue),
            ),
            quads,
            "the quads are not the calls in the order they were made",
        )

        val clips = records(Op.CLIP_PUSH, frame)
        assertEquals(1, clips.size, "one scissor is one clip record")
        assertEquals(listOf(10f, 10f, 40f, 40f), floats(clips[0].subList(1, 5)), "the clip lost its rectangle")
    }

    @Test
    fun `flush appends one op where it is called and moves nothing else`() {
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(redRect, red, red, red, red)
            Recorder.renderQuad(whiteRect, white, white, white, white)
            Recorder.renderQuad(blueRect, blue, blue, blue, blue)
        }
        val plain = Recorder.buffer().copyOf(Recorder.size)

        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(redRect, red, red, red, red)
            Recorder.flush()
            Recorder.renderQuad(whiteRect, white, white, white, white)
            Recorder.flush()
            Recorder.renderQuad(blueRect, blue, blue, blue, blue)
        }
        val flushed = Recorder.buffer().copyOf(Recorder.size)

        assertEquals(listOf(Op.QUAD, Op.QUAD, Op.QUAD), opcodes(plain), "an unflushed frame grew a flush record")
        assertEquals(
            listOf(Op.QUAD, Op.FLUSH, Op.QUAD, Op.FLUSH, Op.QUAD),
            opcodes(flushed),
            "a flush did not land where it was called",
        )
        assertEquals(2, Recorder.counts[Op.FLUSH], "the report counts a different number of flushes than were called")
        assertEquals(plain.size + 2, flushed.size, "a flush costs more than the one word it is")
        // Delete the two markers and the unflushed frame is back word for word: nothing was
        // reordered, merged or committed differently around them.
        assertContentEquals(plain, without(Op.FLUSH, flushed), "the ops around the flush records moved")
    }

    private data class Quad(val l: Float, val t: Float, val r: Float, val b: Float, val color: Color) {
        override fun toString() = "Quad($l, $t, $r, $b, #${color.rgbaInt.toUInt().toString(16)})"
    }

    /** One quad record, decoded: the four corner colours of a solid call are all the same. */
    private fun quad(words: List<Int>): Quad {
        val corners = (5..8).map { words[it] }
        check(corners.all { it == corners[0] }) { "a solid quad recorded different corner colours: $corners" }
        return Quad(
            l = Float.fromBits(words[1]),
            t = Float.fromBits(words[2]),
            r = Float.fromBits(words[3]),
            b = Float.fromBits(words[4]),
            color = Color(corners[0]),
        )
    }

    private fun floats(words: List<Int>): List<Float> = words.map { Float.fromBits(it) }

    /** The opcodes of a frame, walked with the same strides every other reader uses. */
    private fun opcodes(frame: IntArray): List<Int> {
        var at = 0
        val ops = ArrayList<Int>()
        while (at < frame.size) {
            ops += frame[at]
            at += Op.strideAt(frame, at)
        }
        return ops
    }

    /** Every record of [op], payload included, in frame order. */
    private fun records(op: Int, frame: IntArray): List<List<Int>> {
        var at = 0
        val records = ArrayList<List<Int>>()
        while (at < frame.size) {
            val stride = Op.strideAt(frame, at)
            if (frame[at] == op) records += (at until at + stride).map { frame[it] }
            at += stride
        }
        return records
    }

    /** The frame with every record of [op] cut out, in order. */
    private fun without(op: Int, frame: IntArray): IntArray {
        var at = 0
        var size = 0
        val kept = IntArray(frame.size)
        while (at < frame.size) {
            val stride = Op.strideAt(frame, at)
            if (frame[at] != op) {
                for (index in 0 until stride) kept[size++] = frame[at + index]
            }
            at += stride
        }
        return kept.copyOf(size)
    }
}
