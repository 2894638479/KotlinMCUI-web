package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The local image cache: what `localImage` may do on the frame thread, and when an entry goes away.
 *
 * The contract these cases pin says the frame thread never reads a file. That is only observable as a
 * pair of facts, so the first case states both: the answer is [ImageHolder.empty] the moment the frame
 * thread asks, and the size is there once the background read has published - which it publishes on
 * the frame thread, because the cache is a plain map and only that thread changes it.
 *
 * The ttl is exercised with an injected clock rather than by sleeping: the clock decides `lastUsed`,
 * so moving it is exactly what a minute of frames would have done, without the minute.
 */
class LocalImageCacheTest {

    @Test
    fun `a local image answers empty at once and its size once the read has published`() {
        val file = png(3, 2)

        var first: ImageHolder? = null
        WebUtils.onFrameThread { first = WebUtils.localImage(file) }
        assertTrue(
            first!!.isEmpty,
            "the frame thread must not wait for the file, so the first answer is 'nothing to draw yet': $first",
        )

        assertTrue(WebUtils.awaitLocalImageLoads(10_000), "the background size read never published")

        var loaded: ImageHolder? = null
        WebUtils.onFrameThread { loaded = WebUtils.localImage(file) }
        assertFalse(loaded!!.isEmpty, "the published entry still answers 'nothing to draw': $loaded")
        val source = loaded!!.source
        assertTrue(source is ImageSource.LocalFile, "a loaded local image must name its file, not $source")
        assertEquals(file.absolutePath, source.file.absolutePath)
        assertEquals(3.0, loaded!!.width.raw, "the width came from the file, not from a guess")
        assertEquals(2.0, loaded!!.height.raw, "the height came from the file, not from a guess")
    }

    @Test
    fun `an image the page stopped asking for is swept after the ttl, a used one is kept`() {
        val file = png(2, 2)
        val realClock = WebUtils.wallClock
        var now = 1_000L
        WebUtils.wallClock = { now }
        try {
            WebUtils.onFrameThread { WebUtils.localImage(file) }
            assertTrue(WebUtils.awaitLocalImageLoads(10_000))
            val id = WebUtils.localImageId(file)
            assertNotNull(WebUtils.localImageFile(id), "a loaded image must be served before its ttl passes")

            // Half a ttl later the page still asks for it, which refreshes the entry.
            now += WebUtils.LOCAL_IMAGE_TTL_MILLIS / 2
            WebUtils.onFrameThread { WebUtils.localImage(file) }

            // Another half ttl plus a millisecond: the entry's own age is now past the ttl, so only
            // the refresh above can keep it alive.
            now += WebUtils.LOCAL_IMAGE_TTL_MILLIS / 2 + 1
            WebUtils.onFrameThread { WebUtils.sweepLocalImages() }
            assertTrue(WebUtils.isLocalImageCached(file), "a page that still asks for the image must keep it")
            assertNotNull(WebUtils.localImageFile(id))

            now += WebUtils.LOCAL_IMAGE_TTL_MILLIS + 1
            WebUtils.onFrameThread { WebUtils.sweepLocalImages() }
            assertFalse(WebUtils.isLocalImageCached(file), "an entry nothing asked for must be swept")
            assertNull(WebUtils.localImageFile(id))
        } finally {
            WebUtils.wallClock = realClock
        }
    }

    @Test
    fun `a failed read is remembered, and reloading forgets it`() {
        // A name that is a real temp path but has no file behind it.
        val missing = File.createTempFile("kotlinmcui-web-missing", ".png").apply { delete() }
        assertFalse(missing.isFile)

        var answer: ImageHolder? = null
        WebUtils.onFrameThread { answer = WebUtils.localImage(missing) }
        assertTrue(answer!!.isEmpty, "a file that cannot be measured has nothing to draw")
        assertTrue(WebUtils.awaitLocalImageLoads(10_000))
        assertTrue(
            WebUtils.isLocalImageCached(missing),
            "the failure must be remembered, or every frame reopens the file",
        )
        assertNull(WebUtils.localImageFile(WebUtils.localImageId(missing)), "a failed read must not be served")

        WebUtils.onFrameThread { WebUtils.reloadLocalImage(missing) }
        assertFalse(WebUtils.isLocalImageCached(missing), "reloading is what makes the next call read again")
    }

    /** A real png of [width]x[height], so the size comes from ImageIO rather than from a fixture. */
    private fun png(width: Int, height: Int): File {
        val file = File.createTempFile("kotlinmcui-web-image", ".png")
        file.deleteOnExit()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        check(ImageIO.write(image, "png", file)) { "no png writer on this runtime" }
        return file
    }
}
