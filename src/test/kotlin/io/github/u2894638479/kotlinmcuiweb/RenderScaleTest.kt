package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.container.DslRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The output scale and rectangle the web backend gives its root.
 *
 * The framework deleted `DslRootRenderer.guiScale`: the scale is a property of the root, it has no
 * implicit value, and the owner has to write both it and the rect before the first build. These cases
 * take the backend's real frame path, so they fail when the adapter forgets either write rather than
 * when a copy of the policy drifts from it.
 */
class RenderScaleTest {

    /**
     * A page that reports the root facts it was built with.
     *
     * The DSL function runs during the render pass, after the backend's rect/scale writes, so what it
     * sees is exactly the configuration the build used.
     */
    private class Observation {
        var scale = Double.NaN
        var width = Double.NaN
        var height = Double.NaN

        fun record() {
            scale = WebBackend.root.scale
            width = WebBackend.root.rect.right.pixelsOrElse { Double.NaN } - WebBackend.root.rect.left.pixelsOrElse { Double.NaN }
            height = WebBackend.root.rect.bottom.pixelsOrElse { Double.NaN } - WebBackend.root.rect.top.pixelsOrElse { Double.NaN }
        }
    }

    @Test
    fun `the first frame sets scale and rect before a page builds`() {
        WebTestRuntime.install()
        val observed = Observation()
        WebBackend.create("scale-init") { observed.record() }.show()

        WebBackend.frame(800, 800)

        assertEquals(WebScale.of(800, 800), observed.scale, "the page built against another scale")
        assertEquals(800.0, observed.width, "the page built against another output width")
        assertEquals(800.0, observed.height, "the page built against another output height")
        assertEquals(WebScale.of(800, 800), WebBackend.root.scale)
    }

    @Test
    fun `a resize rewrites scale and rect before the next build`() {
        WebTestRuntime.install()
        val builds = mutableListOf<Triple<Double, Double, Double>>()
        WebBackend.create("scale-resize") {
            builds += Triple(
                WebBackend.root.scale,
                WebBackend.root.rect.right.pixelsOrElse { Double.NaN },
                WebBackend.root.rect.bottom.pixelsOrElse { Double.NaN },
            )
        }.show()

        WebBackend.frame(640, 480)
        WebBackend.frame(1200, 960)

        assertEquals(2, builds.size, "the page did not rebuild on the second frame: $builds")
        assertEquals(Triple(WebScale.of(640, 480), 640.0, 480.0), builds[0])
        assertEquals(Triple(WebScale.of(1200, 960), 1200.0, 960.0), builds[1])
        assertEquals(1200.0, WebBackend.root.rect.right.pixelsOrElse { Double.NaN })
        assertEquals(960.0, WebBackend.root.rect.bottom.pixelsOrElse { Double.NaN })
        assertEquals(WebScale.of(1200, 960), WebBackend.root.scale)
    }

    @Test
    fun `a zero sized frame keeps a finite positive scale and an empty rect`() {
        WebTestRuntime.install()
        val observed = Observation()
        WebBackend.create("scale-zero") { observed.record() }.show()

        WebBackend.frame(0, 0)

        assertTrue(observed.scale.isFinite(), "a minimized output produced a non-finite scale")
        assertTrue(observed.scale > 0.0, "a minimized output produced scale ${observed.scale}")
        assertEquals(WebScale.of(0, 0), observed.scale)
        assertEquals(0.0, observed.width, "a zero-sized output did not have an empty rect")
        assertEquals(0.0, observed.height)
    }

    @Test
    fun `a root has no implicit scale and rejects invalid or mid-frame writes`() {
        WebTestRuntime.install()
        val root = DslRoot(WebInput, Recorder, WebScreenFactory, WebUtils)
        assertFailsWith<IllegalStateException>("an unconfigured root answered with a scale") {
            root.scale
        }
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("scale $invalid was accepted") {
                root.scale = invalid
            }
        }
        root.scale = 2.0
        assertEquals(2.0, root.scale)

        var duringFrame: Throwable? = null
        WebBackend.create("scale-mid-frame") {
            duringFrame = runCatching { WebBackend.root.scale = 3.0 }.exceptionOrNull()
        }.show()
        WebBackend.frame(800, 600)

        assertTrue(
            duringFrame is IllegalStateException,
            "changing the scale during a frame did not fail loudly: $duringFrame",
        )
        assertEquals(WebScale.of(800, 600), WebBackend.root.scale, "the rejected write changed the scale")
    }
}
