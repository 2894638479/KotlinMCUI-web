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
    fun `textured diagnostics use an explicit checker pattern instead of a colour fallback`() {
        val words = IntArray(16 + 21)
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
}
