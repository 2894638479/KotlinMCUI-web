package io.github.u2894638479.kotlinmcuiweb

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
}
