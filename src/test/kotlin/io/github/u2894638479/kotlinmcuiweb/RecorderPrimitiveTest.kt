package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.MutRect
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The four typed primitive entry points of the recorder itself, not a stand-in renderer.
 *
 * The checks fall into three groups. Structure: a triangle list has one colour per vertex, three
 * vertices per triangle, and a fixed stride per form - a mismatch is a caller bug and throws before
 * anything is written. Numbers: unmeasured, non-finite or Float-overflowing values make the whole
 * call a no-op, including in the last vertex or UV. Snapshots: everything borrowed is copied into
 * the frame's words before the call returns, so rewriting the source afterwards cannot change what
 * a client will replay.
 */
class RecorderPrimitiveTest {
    private val red = Color(255, 0, 0)
    private val blue = Color(0, 0, 255)

    private val rect = Rect(10.px, 20.px, 30.px, 50.px)
    private val uv = Rect(0.px, 0.px, 16.px, 16.px)

    @Test
    fun `a colour-only quad is one untextured record`() {
        Recorder.beginFrame()
        context(Unit) { Recorder.renderQuad(rect, red, red, red, blue) }

        val words = frame()
        assertEquals(10, words.size, "an untextured quad is one ten-word record")
        assertEquals(Op.QUAD, words[0])
        assertEquals(listOf(10f, 20f, 30f, 50f), floats(words.part(1, 5)))
        assertEquals(Op.NO_ASSET, words[9], "a colour-only quad must not carry an asset")
        assertEquals(1, Recorder.counts[Op.QUAD])
    }

    @Test
    fun `a colour-only quad with unusable values writes nothing`() {
        val rejects = listOf(
            "zero width" to Rect(10.px, 10.px, 10.px, 30.px),
            "inverted height" to Rect(10.px, 30.px, 20.px, 10.px),
            "unmeasured" to Rect(10.px, 10.px, Measure.AUTO, 30.px),
            "NaN" to Rect(10.px, 10.px, Double.NaN.px, 30.px),
            "infinite" to Rect(10.px, 10.px, Double.POSITIVE_INFINITY.px, 30.px),
            "Float overflow" to Rect(10.px, 10.px, 1.0e300.px, 30.px),
        )
        for ((name, bad) in rejects) {
            Recorder.beginFrame()
            context(Unit) { Recorder.renderQuad(bad, red, red, red, red) }
            assertEquals(0, Recorder.size, "$name: the quad wrote a partial record")
            assertEquals(0, Recorder.counts[Op.QUAD], "$name: the quad was counted")
        }
    }

    @Test
    fun `a textured quad keeps its stride and accepts out-of-range uv`() {
        val image = ImageHolder(ImageSource.Resource("test:quad-texture"), 16.px, 16.px)
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(rect, red, red, red, red, image, Rect((-8).px, (-4).px, 40.px, 64.px))
        }

        val quad = records(Op.QUAD).single()
        assertEquals(16, quad.size, "a textured quad must keep its fixed sixteen-word stride")
        val asset = quad[9]
        assertNotEquals(Op.NO_ASSET, asset)
        assertEquals("test:quad-texture", Recorder.assetName(asset))
        assertEquals(listOf(-8f, -4f, 40f, 64f), floats(quad.part(10, 14)), "uv was clamped or rewritten")
        assertEquals(listOf(16f, 16f), floats(quad.part(14, 16)), "the draw's nominal size was not carried")
    }

    @Test
    fun `a textured quad with unusable geometry defines no asset`() {
        val image = ImageHolder(ImageSource.Resource("test:geometry-skip"), 16.px, 16.px)
        val before = Recorder.assetCount
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(Rect(10.px, 10.px, 10.px, 30.px), red, red, red, red, image, uv)
            Recorder.renderQuad(rect, red, red, red, red, image, Rect(0.px, 0.px, Double.NaN.px, 16.px))
        }
        assertEquals(0, Recorder.size, "a rejected call wrote words")
        assertEquals(before, Recorder.assetCount, "a rejected call touched the asset table")
        assertEquals(0, Recorder.counts[Op.TEXTURE])
    }

    @Test
    fun `an empty image draws nothing and defines nothing`() {
        Recorder.beginFrame()
        context(Unit) { Recorder.renderQuad(rect, red, red, red, red, ImageHolder.empty, uv) }
        assertEquals(0, Recorder.size, "an empty holder produced words")
        assertEquals(0, Recorder.counts[Op.TEXTURE], "an empty holder defined a texture")

        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderTriangles(
                vertices(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 16.0, 0.0, 0.0, 10.0, 0.0, 16.0),
                colors(listOf(red, red, red)),
                ImageHolder.empty,
            )
        }
        assertEquals(0, Recorder.size, "an empty holder produced triangle words")
        assertEquals(0, Recorder.counts[Op.TEXTURE])
    }

    @Test
    fun `a non-empty image with an empty identifier still keeps the textured stride`() {
        val image = ImageHolder(ImageSource.Resource(""), 16.px, 16.px)
        Recorder.beginFrame()
        context(Unit) { Recorder.renderQuad(rect, red, red, red, red, image, uv) }

        val quad = records(Op.QUAD).single()
        assertEquals(16, quad.size, "an unnamed image fell back to an untextured record")
        val asset = quad[9]
        assertNotEquals(Op.NO_ASSET, asset)
        assertEquals("kotlinmcuiweb:missing", Recorder.assetName(asset), "the explicit missing id is not used")
    }

    @Test
    fun `an image with an unusable nominal size draws nothing`() {
        val rejects = listOf(
            "zero" to ImageHolder(ImageSource.Resource("test:zero"), 0.px, 16.px),
            "negative" to ImageHolder(ImageSource.Resource("test:negative"), (-16).px, 16.px),
            "unmeasured" to ImageHolder(ImageSource.Resource("test:auto"), Measure.AUTO, 16.px),
            "overflow" to ImageHolder(ImageSource.Resource("test:overflow"), 1.0e300.px, 16.px),
        )
        for ((name, image) in rejects) {
            Recorder.beginFrame()
            context(Unit) { Recorder.renderQuad(rect, red, red, red, red, image, uv) }
            assertEquals(0, Recorder.size, "$name: a textured quad was written")
            assertEquals(0, Recorder.counts[Op.TEXTURE], "$name: an asset was defined")
            assertTrue(Recorder.assetTable().none { it.second == image.idOf() }, "$name: the asset table was mutated")
        }
    }

    @Test
    fun `an untextured triangle list is one flat record`() {
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderTriangles(vertices(0.0, 0.0, 10.0, 0.0, 0.0, 10.0), colors(listOf(red, red, blue)))
        }

        val words = frame()
        assertEquals(13, words.size, "three vertices and three colours are a thirteen-word record")
        assertEquals(Op.TRIANGLES, words[0])
        assertEquals(3, words[1])
        assertEquals(0, words[2], "an untextured list must not set the textured flag")
        assertEquals(Op.NO_ASSET, words[3])
        assertEquals(listOf(0f, 0f, 10f, 0f, 0f, 10f), floats(words.part(4, 10)))
        assertEquals(listOf(red.rgbaInt, red.rgbaInt, blue.rgbaInt), words.part(10, 13))
        assertEquals(1, Recorder.counts[Op.TRIANGLES])
        assertEquals(3, Recorder.vertices)
    }

    @Test
    fun `a textured triangle list keeps xyuv and the textured flag`() {
        val image = ImageHolder(ImageSource.Resource("test:triangle-texture"), 16.px, 16.px)
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderTriangles(
                vertices(0.0, 0.0, -8.0, -8.0, 10.0, 0.0, 40.0, 0.0, 0.0, 10.0, 0.0, 64.0),
                colors(listOf(red, blue, red)),
                image,
            )
        }

        val record = records(Op.TRIANGLES).single()
        assertEquals(21, record.size, "three xyuv vertices plus nominal size are a twenty-one-word record")
        assertEquals(1, record[2], "a textured list lost its textured flag")
        assertNotEquals(Op.NO_ASSET, record[3])
        assertEquals("test:triangle-texture", Recorder.assetName(record[3]))
        assertEquals(
            listOf(0f, 0f, -8f, -8f, 10f, 0f, 40f, 0f, 0f, 10f, 0f, 64f),
            floats(record.part(4, 16)),
            "uvs outside the image were clamped or dropped",
        )
        assertEquals(listOf(red.rgbaInt, blue.rgbaInt, red.rgbaInt), record.part(16, 19))
        assertEquals(listOf(16f, 16f), floats(record.part(19, 21)), "the draw's nominal size was not carried")
        assertEquals(3, Recorder.vertices)
    }

    @Test
    fun `the same asset id carries a different nominal size per textured draw`() {
        val first = ImageHolder(ImageSource.Resource("test:same-id"), 16.px, 8.px)
        val second = ImageHolder(ImageSource.Resource("test:same-id"), 64.px, 32.px)
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(rect, red, red, red, red, first, uv)
            Recorder.renderQuad(rect, red, red, red, red, second, uv)
        }

        val quads = records(Op.QUAD)
        assertEquals(2, quads.size)
        assertEquals(quads[0][9], quads[1][9], "the two draws should share one asset id")
        assertEquals(listOf(16f, 8f), floats(quads[0].part(14, 16)))
        assertEquals(listOf(64f, 32f), floats(quads[1].part(14, 16)))
        assertEquals(1, Recorder.counts[Op.TEXTURE], "the second draw should reuse the definition")
    }

    @Test
    fun `a malformed but non-empty id keeps texture semantics and does not throw`() {
        val malformed = ImageHolder(ImageSource.Resource("bad id \u0000 / ??"), 16.px, 16.px)
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderQuad(rect, red, red, red, red, malformed, uv)
            Recorder.renderTriangles(
                vertices(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 16.0, 0.0, 0.0, 10.0, 0.0, 16.0),
                colors(listOf(red, red, red)),
                ImageHolder(ImageSource.Remote(""), 16.px, 16.px),
            )
        }

        val quad = records(Op.QUAD).single()
        assertEquals(16, quad.size, "a malformed id turned a textured quad into a colour quad")
        assertEquals("bad id \u0000 / ??", Recorder.assetName(quad[9]))
        val triangle = records(Op.TRIANGLES).single()
        assertEquals(21, triangle.size, "an empty remote url lost the textured stride")
        assertEquals("kotlinmcuiweb:missing", Recorder.assetName(triangle[3]))
    }

    @Test
    fun `a source this backend cannot serve is still recorded as a texture, not rejected`() {
        val remote = ImageHolder(ImageSource.Remote("https://example.invalid/not-served.png"), 16.px, 8.px)
        Recorder.beginFrame()
        context(Unit) { Recorder.renderQuad(rect, red, red, red, red, remote, uv) }

        val quad = records(Op.QUAD).single()
        assertEquals(16, quad.size, "an unsupported source changed the textured stride")
        assertEquals("https://example.invalid/not-served.png", Recorder.assetName(quad[9]))
        assertEquals(listOf(16f, 8f), floats(quad.part(14, 16)))
    }

    @Test
    fun `triangle structure is rejected before the image is looked at`() {
        val image = ImageHolder(ImageSource.Resource("test:must-not-be-defined"), 16.px, 16.px)
        val before = Recorder.assetCount
        Recorder.beginFrame()

        // Untextured: two measures per vertex.
        assertFailsWith<IllegalArgumentException>("a non-multiple-of-three colour count") {
            context(Unit) {
                Recorder.renderTriangles(vertices(0.0, 0.0, 1.0, 1.0), colors(listOf(red)))
            }
        }
        assertFailsWith<IllegalArgumentException>("a missing vertex pair") {
            context(Unit) {
                Recorder.renderTriangles(
                    vertices(0.0, 0.0, 1.0, 1.0, 2.0, 2.0),
                    colors(listOf(red, red, red, red)),
                )
            }
        }
        assertFailsWith<IllegalArgumentException>("vertices without colours") {
            context(Unit) { Recorder.renderTriangles(vertices(0.0, 0.0), colors(emptyList())) }
        }
        // Textured: four measures per vertex, checked even for an empty image.
        assertFailsWith<IllegalArgumentException>("textured xy-only input") {
            context(Unit) {
                Recorder.renderTriangles(
                    vertices(0.0, 0.0, 1.0, 1.0, 2.0, 2.0),
                    colors(listOf(red, red, red)),
                    image,
                )
            }
        }
        assertFailsWith<IllegalArgumentException>("textured non-multiple-of-four") {
            context(Unit) {
                Recorder.renderTriangles(
                    vertices(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0),
                    colors(listOf(red, red, red)),
                    image,
                )
            }
        }
        assertFailsWith<IllegalArgumentException>("textured empty colours with vertices") {
            context(Unit) {
                Recorder.renderTriangles(
                    vertices(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0),
                    colors(emptyList()),
                    image,
                )
            }
        }
        assertFailsWith<IllegalArgumentException>("an empty image does not bypass the structural check") {
            context(Unit) {
                Recorder.renderTriangles(
                    vertices(0.0, 0.0, 1.0, 1.0, 2.0, 2.0),
                    colors(listOf(red, red, red)),
                    ImageHolder.empty,
                )
            }
        }

        assertEquals(0, Recorder.size, "a rejected call wrote words")
        assertEquals(before, Recorder.assetCount, "a rejected call touched the asset table")
        assertEquals(0, Recorder.counts[Op.TRIANGLES])
    }

    @Test
    fun `both arrays empty is a valid no-op in either form`() {
        Recorder.beginFrame()
        context(Unit) {
            Recorder.renderTriangles(MeasureArray(0), ColorArray(0))
            Recorder.renderTriangles(MeasureArray(0), ColorArray(0), ImageHolder.empty)
        }
        assertEquals(0, Recorder.size)
        assertEquals(0, Recorder.counts[Op.TRIANGLES])
    }

    @Test
    fun `an unusable last vertex or uv skips the entire call`() {
        val image = ImageHolder(ImageSource.Resource("test:numeric-skip"), 16.px, 16.px)
        val bad = listOf(
            "unmeasured" to Measure.AUTO,
            "NaN" to Double.NaN.px,
            "infinite" to Double.POSITIVE_INFINITY.px,
            "overflow" to 1.0e300.px,
        )
        for ((name, value) in bad) {
            Recorder.beginFrame()
            val flat = floatBits(0.0, 0.0, 10.0, 0.0, 0.0, 10.0).also { it[it.size - 1] = value }
            context(Unit) { Recorder.renderTriangles(flat, colors(listOf(red, red, red))) }
            assertEquals(0, Recorder.size, "$name in the last untextured vertex: the call was merged")
            assertEquals(0, Recorder.counts[Op.TRIANGLES])

            Recorder.beginFrame()
            val textured = floatBits(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 16.0, 0.0, 0.0, 10.0, 0.0, 16.0)
                .also { it[it.size - 1] = value }
            context(Unit) { Recorder.renderTriangles(textured, colors(listOf(red, red, red)), image) }
            assertEquals(0, Recorder.size, "$name in the last uv: the call was merged")
            assertEquals(0, Recorder.counts[Op.TEXTURE], "$name: an asset was defined")
            assertTrue(Recorder.assetTable().none { it.second == "test:numeric-skip" })
        }
    }

    @Test
    fun `borrowed rectangles and arrays are snapshotted by return`() {
        val mutableRect = MutRect(0.px, 0.px, 10.px, 10.px)
        val mutableUv = MutRect(0.px, 0.px, 16.px, 16.px)
        val image = ImageHolder(ImageSource.Resource("test:snapshot"), 16.px, 16.px)

        Recorder.beginFrame()
        context(Unit) { Recorder.renderQuad(mutableRect, red, red, red, red, image, mutableUv) }
        val quad = records(Op.QUAD).single()

        Recorder.beginFrame()
        val points = floatBits(0.0, 0.0, 10.0, 0.0, 0.0, 10.0)
        val tint = colors(listOf(red, red, red))
        context(Unit) { Recorder.renderTriangles(points, tint) }
        val triangles = records(Op.TRIANGLES).single()

        mutableRect.right = 900.px
        mutableRect.bottom = 900.px
        mutableUv.right = 900.px
        mutableUv.bottom = 900.px
        points[0] = 900.0.px
        tint[0] = blue

        assertEquals(listOf(0f, 0f, 10f, 10f), floats(quad.part(1, 5)), "the recorded quad followed its mutable rect")
        assertEquals(16f, Float.fromBits(quad[12]), "the recorded uv followed its mutable rect")
        assertEquals(0f, Float.fromBits(triangles[4]), "the recorded vertex followed its mutable array")
        assertEquals(red.rgbaInt, triangles[10], "the recorded colour followed its mutable array")
    }

    private fun frame(): IntArray = Recorder.buffer().copyOf(Recorder.size)

    /** Every record of [op] in the frame, each exactly one stride wide. */
    private fun records(op: Int): List<List<Int>> {
        val words = frame()
        var at = 0
        val found = ArrayList<List<Int>>()
        while (at < words.size) {
            val stride = Op.strideAt(words, at)
            if (words[at] == op) found += words.part(at, at + stride)
            at += stride
        }
        assertEquals(words.size, at, "the frame does not walk to its own end")
        return found
    }

    private fun vertices(vararg values: Double): MeasureArray = floatBits(*values)

    private fun floatBits(vararg values: Double): MeasureArray =
        MeasureArray(values.size) { values[it].px }

    private fun colors(values: List<Color>): ColorArray =
        ColorArray(values.size) { values[it] }

    private fun IntArray.part(from: Int, until: Int): List<Int> = (from until until).map { this[it] }

    private fun List<Int>.part(from: Int, until: Int): List<Int> = subList(from, until).toList()

    private fun floats(words: List<Int>): List<Float> = words.map { Float.fromBits(it) }

    private fun ImageHolder.idOf(): String? = when (val it = source) {
        is ImageSource.Resource -> it.location
        is ImageSource.Remote -> it.url
        is ImageSource.LocalFile -> it.file.absolutePath
        null -> null
    }
}
