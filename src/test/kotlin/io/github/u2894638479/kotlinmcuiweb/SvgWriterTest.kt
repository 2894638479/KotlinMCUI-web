package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.test.TestColorPage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SVG writer, checked by parsing what it writes.
 *
 * Parsing is the assertion that matters: a snapshot whose polygons or clip references are malformed
 * is not a picture, and the writer is the format's second reader - it walks a frame by the same
 * strides the client will - so a wrong stride has to break something visible rather than quietly
 * shift every record after it.
 */
class SvgWriterTest {
    @Test
    fun `quad diagnostic cells cover the rectangle and hide transparent rgb`() {
        val red = Color(255,0,0,255).rgbaInt
        val hiddenBlue = Color(0,0,255,0).rgbaInt
        val words = intArrayOf(Op.QUAD,0f.toBits(),0f.toBits(),24f.toBits(),24f.toBits(),
            red,hiddenBlue,red,hiddenBlue,Op.NO_ASSET)
        checkGradientDiagnostic(words,24.0 * 24.0)
    }

    @Test
    fun `triangle diagnostic cells cover the triangle and hide transparent rgb`() {
        val red = Color(255,0,0,255).rgbaInt
        val words = intArrayOf(Op.TRIANGLES,3,0,Op.NO_ASSET,
            0f.toBits(),0f.toBits(),24f.toBits(),0f.toBits(),0f.toBits(),24f.toBits(),
            red,Color(0,0,255,0).rgbaInt,red)
        checkGradientDiagnostic(words,24.0 * 24.0 / 2)
    }

    private fun checkGradientDiagnostic(words: IntArray, expectedArea: Double) {
        val file = File.createTempFile("kotlinmcui-gradient", ".svg")
        try {
            SvgWriter.write(words,words.size,32,32,file)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val polygons = document.getElementsByTagName("polygon")
            assertTrue(polygons.length > 1)
            var totalArea = 0.0
            for(i in 0 until polygons.length) {
                val polygon = polygons.item(i) as org.w3c.dom.Element
                assertTrue(polygon.getAttribute("fill").startsWith("rgba(255,0,0,"),
                    "transparent blue must not colour a red fade")
                val points = polygon.getAttribute("points").trim().split(Regex("\\s+"))
                    .map { point -> point.split(',').map(String::toDouble) }
                var area = 0.0
                for(j in points.indices) {
                    val a = points[j]
                    val b = points[(j + 1) % points.size]
                    area += a[0] * b[1] - a[1] * b[0]
                }
                area = kotlin.math.abs(area) / 2
                assertTrue(area > 0.0,"a diagnostic cell must not fold into a bow tie")
                totalArea += area
            }
            assertEquals(expectedArea,totalArea,0.1)
            assertTrue(file.readText().contains("approximation"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a frame becomes well-formed svg with the page's elements in it`() {
        WebTestRuntime.install()
        WebBackend.create("svg-test") { TestColorPage() }.show()
        WebTestRuntime.frame()

        val size = Recorder.size
        val quads = Recorder.counts[Op.QUAD]
        val glyphs = Recorder.counts[Op.TEXT]
        val file = File.createTempFile("kotlinmcui-web", ".svg")
        try {
            SvgWriter.write(Recorder.buffer(), size, WebTestRuntime.WIDTH, WebTestRuntime.HEIGHT, file)

            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            assertEquals("svg", document.documentElement.tagName)
            assertEquals(
                WebTestRuntime.WIDTH.toString(),
                document.documentElement.getAttribute("width"),
            )
            assertTrue(glyphs > 0, "the page drew no text")
            // One element per glyph, plus one more for every shadowed glyph.
            assertTrue(
                document.getElementsByTagName("text").length >= glyphs,
                "fewer text elements than glyphs were recorded",
            )
            assertTrue(
                document.getElementsByTagName("polygon").length >= quads,
                "fewer polygons than quads were recorded",
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `textured diagnostics use an explicit checker pattern instead of a colour fallback`() {        val words = IntArray(16 + 21)
        words[0] = Op.QUAD
        for ((index, value) in listOf(10f, 20f, 30f, 50f).withIndex()) words[1 + index] = value.toRawBits()
        for (index in 5..8) words[index] = Color.WHITE.rgbaInt
        words[9] = 3
        for ((index, value) in listOf(1f, 2f, 7f, 8f, 16f, 8f).withIndex()) words[10 + index] = value.toRawBits()

        val at = 16
        words[at] = Op.TRIANGLES
        words[at + 1] = 3
        words[at + 2] = 1
        words[at + 3] = 3
        var cursor = at + 4
        for (value in listOf(0f, 0f, 0f, 0f, 10f, 0f, 16f, 0f, 0f, 10f, 0f, 16f)) {
            words[cursor++] = value.toRawBits()
        }
        repeat(3) { words[cursor++] = Color.WHITE.rgbaInt }
        words[cursor++] = 16f.toRawBits()
        words[cursor] = 8f.toRawBits()

        val file = File.createTempFile("kotlinmcui-web-texture", ".svg")
        try {
            SvgWriter.write(words, words.size, 64, 64, file)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            assertEquals(1, document.getElementsByTagName("pattern").length, "the checker pattern is missing")
            val polygons = document.getElementsByTagName("polygon")
            var checkerPolygons = 0
            var assetLabels = 0
            for (i in 0 until polygons.length) {
                val polygon = polygons.item(i)
                if (polygon.attributes.getNamedItem("fill")?.nodeValue == "url(#texture-checker)") checkerPolygons++
                val titles = polygon.childNodes
                for (j in 0 until titles.length) {
                    if (titles.item(j).textContent.contains("texture asset #3")) assetLabels++
                }
            }
            assertEquals(2, checkerPolygons, "a textured quad and triangle must both use the checker pattern")
            assertEquals(2, assetLabels, "both textured records must carry their asset label")
            assertTrue(file.readText().contains("diagnostic checkerboard"), "the diagnostic limit is not stated")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a projected clip is its own polygon, not its bounding box`() {
        val white = Color.WHITE.rgbaInt
        val c = 0.70710677f
        val s = 0.70710677f
        // Rotate a 60x60 local square by 45 degrees around (100,100), then draw an axis-aligned
        // rectangle in screen space. The clip must be the diamond (area 3600), never its bounding
        // box (area about 7200).
        val words = intArrayOf(
            Op.TRANSFORM_PUSH,
            c.toRawBits(), (-s).toRawBits(), 100f.toRawBits(),
            s.toRawBits(), c.toRawBits(), 100f.toRawBits(),
            0f.toRawBits(), 0f.toRawBits(), 1f.toRawBits(),
            Op.CLIP_PUSH, (-30f).toRawBits(), (-30f).toRawBits(), 30f.toRawBits(), 30f.toRawBits(),
            Op.QUAD,
            0f.toRawBits(), 0f.toRawBits(), 200f.toRawBits(), 200f.toRawBits(),
            white, white, white, white, Op.NO_ASSET,
            Op.CLIP_POP, Op.TRANSFORM_POP,
        )

        val document = documentOf(words, 256, 256)
        val clipPaths = document.getElementsByTagName("clipPath")
        assertEquals(1, clipPaths.length, "one clip scope must be one clipPath")
        val polygon = (clipPaths.item(0) as org.w3c.dom.Element)
            .getElementsByTagName("polygon").item(0) as org.w3c.dom.Element
        val area = areaOf(polygon)
        assertTrue(area in 3599.0..3601.0, "the clip polygon is not the projected square; area is $area")
        val groups = document.getElementsByTagName("g")
        assertEquals(1, groups.length, "the clip must open one group")
        assertEquals(
            "url(#clip0)",
            (groups.item(0) as org.w3c.dom.Element).getAttribute("clip-path"),
            "the group does not reference the clip path",
        )
    }

    @Test
    fun `a nested invalid clip is an empty clipPath, never an unclipped fallback`() {
        val white = Color.WHITE.rgbaInt
        val words = intArrayOf(
            Op.CLIP_PUSH, 0f.toRawBits(), 0f.toRawBits(), 40f.toRawBits(), 40f.toRawBits(),
            Op.CLIP_PUSH, Float.NaN.toRawBits(), 0f.toRawBits(), 40f.toRawBits(), 40f.toRawBits(),
            Op.QUAD,
            0f.toRawBits(), 0f.toRawBits(), 40f.toRawBits(), 40f.toRawBits(),
            white, white, white, white, Op.NO_ASSET,
            Op.CLIP_POP, Op.CLIP_POP,
        )

        val document = documentOf(words, 64, 64)
        val clipPaths = document.getElementsByTagName("clipPath")
        assertEquals(2, clipPaths.length, "every push must emit its own clipPath, valid or not")
        val outer = (clipPaths.item(0) as org.w3c.dom.Element)
            .getElementsByTagName("polygon").item(0) as org.w3c.dom.Element
        val inner = (clipPaths.item(1) as org.w3c.dom.Element)
            .getElementsByTagName("polygon").item(0) as org.w3c.dom.Element
        assertTrue(areaOf(outer) in 1599.0..1601.0, "the valid outer clip changed shape")
        assertEquals(0.0, areaOf(inner), "an invalid clip was emitted as a usable polygon")

        val groups = document.getElementsByTagName("g")
        assertEquals(2, groups.length, "nested clips must open two nested groups")
        val innerGroup = groups.item(1) as org.w3c.dom.Element
        assertEquals(
            "url(#clip1)",
            innerGroup.getAttribute("clip-path"),
            "the inner group does not reference the empty clip",
        )
        assertEquals(
            1,
            innerGroup.getElementsByTagName("polygon").length,
            "the draw must stay inside the inner group and not fall back to an unclipped element",
        )
    }

    @Test
    fun `finite clip edges with overflowing Float extents are empty`() {
        for (rect in listOf(floatArrayOf(-3e38f, 0f, 3e38f, 40f), floatArrayOf(0f, -3e38f, 40f, 3e38f))) {
            val words = intArrayOf(Op.CLIP_PUSH, *rect.map(Float::toRawBits).toIntArray(), Op.CLIP_POP)
            val document = documentOf(words, 64, 64)
            val clip = document.getElementsByTagName("clipPath").item(0) as org.w3c.dom.Element
            val polygon = clip.getElementsByTagName("polygon").item(0) as org.w3c.dom.Element
            assertEquals(0.0, areaOf(polygon))
        }
    }

    @Test
    fun `valid scaled clip transforms do not become empty diagnostics`() {
        for ((matrix, rect) in listOf(
            floatArrayOf(1e-5f,0f,0f,0f,1e-5f,0f,0f,0f,1e-5f) to floatArrayOf(10f,10f,60f,60f),
            floatArrayOf(1e20f,0f,0f,0f,1e20f,0f,0f,0f,1e20f) to floatArrayOf(10f,10f,60f,60f),
            floatArrayOf(1e-7f,0f,0f,0f,1e-7f,0f,0f,0f,1f) to floatArrayOf(1e8f,1e8f,6e8f,6e8f),
        )) {
            val words = intArrayOf(Op.TRANSFORM_PUSH) + matrix.map(Float::toRawBits).toIntArray() +
                intArrayOf(Op.CLIP_PUSH) + rect.map(Float::toRawBits).toIntArray() +
                intArrayOf(Op.CLIP_POP,Op.TRANSFORM_POP)
            val document = documentOf(words,96,96)
            val clip = document.getElementsByTagName("clipPath").item(0) as org.w3c.dom.Element
            val polygon = clip.getElementsByTagName("polygon").item(0) as org.w3c.dom.Element
            assertEquals(2500.0,areaOf(polygon),0.1)
        }
    }

    @Test
    fun `a singular or eye-plane-crossing transform also produces an empty clip`() {
        val white = Color.WHITE.rgbaInt
        for ((name, matrix, rect) in listOf(
            Triple("singular", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f), floatArrayOf(-10f, -10f, 10f, 10f)),
            Triple("eye plane", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.1f, 0f, 1f), floatArrayOf(-100f, -100f, 100f, 100f)),
        )) {
            val words = IntArray(27) { 0 }
            words[0] = Op.TRANSFORM_PUSH
            for (index in 0..8) words[1 + index] = matrix[index].toRawBits()
            words[10] = Op.CLIP_PUSH
            for (index in 0..3) words[11 + index] = rect[index].toRawBits()
            words[15] = Op.QUAD
            for ((index, value) in floatArrayOf(0f, 0f, 32f, 32f).withIndex()) {
                words[16 + index] = value.toRawBits()
            }
            for (index in 20..23) words[index] = white
            words[24] = Op.NO_ASSET
            words[25] = Op.CLIP_POP
            words[26] = Op.TRANSFORM_POP

            val document = documentOf(words, 64, 64)
            val clipPaths = document.getElementsByTagName("clipPath")
            assertEquals(1, clipPaths.length, "$name: missing clipPath")
            val polygon = (clipPaths.item(0) as org.w3c.dom.Element)
                .getElementsByTagName("polygon").item(0) as org.w3c.dom.Element
            assertEquals(0.0, areaOf(polygon), "$name: transform was treated as usable")
        }
    }

    private fun documentOf(words: IntArray, width: Int, height: Int): org.w3c.dom.Document {
        val file = File.createTempFile("kotlinmcui-clip", ".svg")
        try {
            SvgWriter.write(words, words.size, width, height, file)
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        } finally {
            file.delete()
        }
    }

    private fun areaOf(polygon: org.w3c.dom.Element): Double {
        val points = polygon.getAttribute("points").trim().split(Regex("\\s+"))
            .map { point -> point.split(',').map(String::toDouble) }
        var doubled = 0.0
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            doubled += a[0] * b[1] - a[1] * b[0]
        }
        return kotlin.math.abs(doubled) / 2
    }
}
