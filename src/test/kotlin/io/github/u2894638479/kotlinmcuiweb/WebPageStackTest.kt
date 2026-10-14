package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.test.TestColorPage
import io.github.u2894638479.kotlinmcui.test.TestLayoutPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page stack.
 *
 * The framework's screen model is a stack - `showScreen` enters a page and closing it goes back - and
 * a backend that replaced the mounted page instead would have no way back at all. The bottom page is
 * the floor: there is nothing under it, so closing it does nothing rather than leaving the client
 * with an empty surface.
 */
class WebPageStackTest {
    @Test
    fun `showing a page enters it, closing it comes back, and the bottom page stays`() {
        WebTestRuntime.install()

        WebBackend.create("root") { TestColorPage() }.show()
        assertEquals(1, WebBackend.pageCount)

        WebBackend.create("second") { TestLayoutPage() }.show()
        assertEquals(2, WebBackend.pageCount)

        assertTrue(WebBackend.pop(), "closing the top page did not leave it")
        assertEquals(1, WebBackend.pageCount)

        assertFalse(WebBackend.pop(), "the bottom page has nothing below it to return to")
        assertEquals(1, WebBackend.pageCount)

        // The page that came back is the one that was below, and it is mounted again: a frame walks it.
        WebTestRuntime.frame()
        assertTrue(Recorder.size > 0, "the restored page rendered nothing")
    }
}
