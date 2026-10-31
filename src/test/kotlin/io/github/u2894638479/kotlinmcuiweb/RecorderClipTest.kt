package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.MutRect
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Scissor and transform scopes, recorded through the real [Recorder].
 *
 * These pin the two properties a replay depends on. Every scope is one push and one pop even when
 * its rectangle is empty or the block throws, because the client's pop is unconditional; and the
 * rectangle is copied before the block runs, because [Rect] is only borrowed for the call and a
 * caller may rewrite a [MutRect] immediately.
 */
class RecorderClipTest {
    private val white = Color(255, 255, 255)
    private val red = Color(255, 0, 0)

    @Test
    fun `an empty or invalid scissor is recorded as an empty clip and still runs its block once`() {
        val rectangles = listOf(
            "unmeasured" to Rect(Measure.ofRaw(Double.NaN), 0.px, 10.px, 10.px),
            "infinite" to Rect(0.px, 0.px, Measure.ofRaw(Double.POSITIVE_INFINITY), 10.px),
            "zero width" to Rect(5.px, 0.px, 5.px, 10.px),
            "reversed" to Rect(10.px, 0.px, 5.px, 10.px),
            "zero height" to Rect(0.px, 5.px, 10.px, 5.px),
            "overflow width" to Rect((-3e38).px, 0.px, 3e38.px, 10.px),
            "overflow height" to Rect(0.px, (-3e38).px, 10.px, 3e38.px),
        )

        for ((name, rect) in rectangles) {
            Recorder.beginFrame()
            var runs = 0
            context(Unit) {
                Recorder.withScissor(rect) {
                    runs++
                    Recorder.renderQuad(Rect(0.px, 0.px, 100.px, 100.px), white, white, white, white)
                }
            }

            assertEquals(1, runs, "$name: the block must run exactly once, not be skipped")
            val words = frame()
            assertEquals(
                listOf(Op.CLIP_PUSH, Op.QUAD, Op.CLIP_POP),
                opcodes(words),
                "$name: an empty clip must still be a balanced push/pop pair",
            )
            val clip = records(Op.CLIP_PUSH, words).single()
            assertEquals(
                listOf(0, 0, 0, 0),
                clip.subList(1, 5),
                "$name: an empty clip must travel as the explicit zero-area sentinel",
            )
            assertEquals(1, Recorder.counts[Op.CLIP_PUSH], "$name: push count")
            assertEquals(1, Recorder.counts[Op.CLIP_POP], "$name: pop count")
            assertEquals(1, Recorder.counts[Op.QUAD], "$name: the block's draw must still be recorded")
        }
    }

    @Test
    fun `a scissor snapshots a borrowed MutRect before the block can rewrite it`() {
        val rect = MutRect(10.px, 20.px, 40.px, 60.px)
        Recorder.beginFrame()
        var runs = 0
        context(Unit) {
            Recorder.withScissor(rect) {
                runs++
                rect.left = 100.px
                rect.top = 200.px
                rect.right = 300.px
                rect.bottom = 400.px
                Recorder.renderQuad(rect, white, white, white, white)
            }
        }

        assertEquals(1, runs, "the block must run once")
        val words = frame()
        val clip = records(Op.CLIP_PUSH, words).single()
        assertEquals(
            listOf(10f, 20f, 40f, 60f),
            floats(clip.subList(1, 5)),
            "the clip retained the mutable rectangle instead of snapshotting it",
        )
        val quad = records(Op.QUAD, words).single()
        assertEquals(
            listOf(100f, 200f, 300f, 400f),
            floats(quad.subList(1, 5)),
            "the draw inside the block must read the caller's new rectangle",
        )
    }

    @Test
    fun `an exception in a scissor block closes the scope and propagates unchanged`() {
        Recorder.beginFrame()
        val failure = IllegalStateException("block")
        val thrown = assertFailsWith<IllegalStateException> {
            context(Unit) {
                Recorder.withScissor(Rect(0.px, 0.px, 10.px, 10.px)) {
                    Recorder.renderQuad(Rect(0.px, 0.px, 10.px, 10.px), red, red, red, red)
                    throw failure
                }
            }
        }

        assertSame(failure, thrown, "the recorder must not replace the caller's exception")
        assertEquals(
            listOf(Op.CLIP_PUSH, Op.QUAD, Op.CLIP_POP),
            opcodes(frame()),
            "a thrown block must still leave a balanced clip pair",
        )
        assertEquals(1, Recorder.counts[Op.CLIP_PUSH], "push count")
        assertEquals(1, Recorder.counts[Op.CLIP_POP], "pop count")

        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(Rect(0.px, 0.px, 10.px, 10.px), white, white, white, white)
        }
        assertEquals(
            listOf(Op.QUAD),
            opcodes(frame()),
            "the clip that failed to close leaked into the next frame",
        )
    }

    @Test
    fun `withTransform records a balanced pair even when the block throws`() {
        val transform = Transform(
            1f, 0f, 2f,
            0f, 1f, 3f,
            0f, 0f, 1f,
        )
        Recorder.beginFrame()
        val failure = IllegalArgumentException("transform block")
        val thrown = assertFailsWith<IllegalArgumentException> {
            context(Unit) {
                Recorder.withTransform(transform) {
                    Recorder.renderQuad(Rect(0.px, 0.px, 10.px, 10.px), red, red, red, red)
                    throw failure
                }
            }
        }

        assertSame(failure, thrown, "the recorder must not replace the caller's exception")
        val words = frame()
        assertEquals(
            listOf(Op.TRANSFORM_PUSH, Op.QUAD, Op.TRANSFORM_POP),
            opcodes(words),
            "a thrown transform block must still leave a balanced pair",
        )
        assertEquals(1, Recorder.counts[Op.TRANSFORM_PUSH], "push count")
        assertEquals(1, Recorder.counts[Op.TRANSFORM_POP], "pop count")
        assertEquals(
            listOf(1f, 0f, 2f, 0f, 1f, 3f, 0f, 0f, 1f),
            floats(records(Op.TRANSFORM_PUSH, words).single().subList(1, 10)),
            "the transform matrix was not snapshotted at entry",
        )

        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(Rect(0.px, 0.px, 10.px, 10.px), white, white, white, white)
        }
        assertEquals(
            listOf(Op.QUAD),
            opcodes(frame()),
            "the transform that failed to close leaked into the next frame",
        )
    }

    @Test
    fun `mixed nested scopes unwind in order when the innermost block throws`() {
        val transform = Transform(
            2f, 0f, 1f,
            0f, 2f, 1f,
            0f, 0f, 1f,
        )
        Recorder.beginFrame()
        val failure = IllegalStateException("innermost")
        val thrown = assertFailsWith<IllegalStateException> {
            context(Unit) {
                Recorder.withScissor(Rect(0.px, 0.px, 40.px, 40.px)) {
                    Recorder.withTransform(transform) {
                        Recorder.withScissor(Rect(5.px, 5.px, 35.px, 35.px)) {
                            throw failure
                        }
                    }
                }
            }
        }

        assertSame(failure, thrown, "the innermost exception must survive every finally")
        assertEquals(
            listOf(
                Op.CLIP_PUSH, Op.TRANSFORM_PUSH, Op.CLIP_PUSH,
                Op.CLIP_POP, Op.TRANSFORM_POP, Op.CLIP_POP,
            ),
            opcodes(frame()),
            "nested scopes must close innermost-first even while unwinding",
        )
        for (op in listOf(Op.CLIP_PUSH, Op.CLIP_POP, Op.TRANSFORM_PUSH, Op.TRANSFORM_POP)) {
            assertEquals(if (op == Op.CLIP_PUSH || op == Op.CLIP_POP) 2 else 1, Recorder.counts[op], "count for ${Op.nameOf(op)}")
        }
    }

    private fun frame(): IntArray = Recorder.buffer().copyOf(Recorder.size)

    private fun opcodes(words: IntArray): List<Int> {
        var at = 0
        val ops = ArrayList<Int>()
        while (at < words.size) {
            ops += words[at]
            at += Op.strideAt(words, at)
        }
        return ops
    }

    private fun records(op: Int, words: IntArray): List<List<Int>> {
        var at = 0
        val found = ArrayList<List<Int>>()
        while (at < words.size) {
            val stride = Op.strideAt(words, at)
            if (words[at] == op) found += (at until at + stride).map { words[it] }
            at += stride
        }
        return found
    }

    private fun floats(words: List<Int>): List<Float> = words.map { Float.fromBits(it) }
}
