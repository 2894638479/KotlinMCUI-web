package io.github.u2894638479.kotlinmcuiweb

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a disconnect does to the root, the executor and the pages.
 *
 * A web backend has one frame thread and one root, and a server that stops has to end both without
 * leaving the tree half-torn. The two rulings pinned here are the ones a teardown can get wrong
 * silently:
 *
 * - **A frame that is in flight when the connection goes has to fail on the root's own gate.** A
 *   teardown that only released the pages would let that frame reach the page and fail on
 *   `check(!released)` instead - a message about a page when the truth is that the whole output is
 *   gone. The root's gate names the thing that actually ended.
 * - **After the close, nothing on the executor side records anything.** Releasing the pages is what
 *   cancels their session-scoped coroutines, so it has to happen, has to happen on the frame thread
 *   (that is where the framework's contexts live), and `stop()` has to wait for it: a caller that is
 *   told the server stopped must not still have work queued behind it.
 */
class RootTeardownTest {
    private var server: WebServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop()
    }

    /**
     * A frame already queued when the disconnect happens fails on the closed root, not on the page.
     *
     * The frame thread is held first, so the frame below is genuinely *in flight* - queued and
     * unable to run - while the server is stopped. The root is closed before the hold is released,
     * so the frame meets the root's gate; the false green this rules out is the frame reaching the
     * page first and reporting `was released and cannot be shown again`, which reads as a page bug
     * even though the page was torn down correctly.
     *
     * Falsifier: a teardown that releases the pages and never closes the root lets the frame render
     * (or fail somewhere else), and every assertion below is red.
     */
    @Test
    fun `a frame in flight when the connection goes fails on the closed root, not the released page`() {
        val running = start()

        val holding = CountDownLatch(1)
        val held = CountDownLatch(1)
        try {
            WebUtils.frameExecutor.execute {
                held.countDown()
                holding.await()
            }
            assertTrue(held.await(5, TimeUnit.SECONDS), "the frame thread never took the hold")

            val frame = CompletableFuture<Throwable?>()
            WebUtils.frameExecutor.execute {
                frame.complete(runCatching { WebBackend.frame(800, 600) }.exceptionOrNull())
            }

            // Disconnect from another thread: stop() closes the root first and only then waits for
            // this backend's remaining work, which includes the frame queued above.
            val stopper = Thread({ running.stop() }, "test-stop").apply { isDaemon = true }
            stopper.start()

            val deadline = System.currentTimeMillis() + 5_000
            while (!WebBackend.root.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(WebBackend.root.isClosed, "stop() never closed the root")

            holding.countDown()
            stopper.join(10_000)
            assertFalse(stopper.isAlive, "stop() never returned")

            val failure = frame.get(5, TimeUnit.SECONDS)
            assertTrue(
                failure is IllegalStateException,
                "the in-flight frame was not stopped at all, it ended with $failure",
            )
            val message = failure.message.orEmpty()
            assertTrue(
                message.contains("this root is closed"),
                "the frame was not stopped by the root's gate: $message",
            )
            assertFalse(
                message.contains("was released"),
                "the frame hit the page's release check instead of the root's gate: $message",
            )

            assertEquals(0, WebBackend.pageCount, "the disconnect left a page on the stack")
        } finally {
            holding.countDown()
        }
    }

    /**
     * After the close, the executor side holds no more work, the session is gone, and a frame fed in
     * afterwards records nothing.
     *
     * `Recorder.size` is the assertion that matters for the wire: a frame that never reached the
     * tree must leave the buffer empty, or a client would be sent a frame built from a torn tree.
     */
    @Test
    fun `the disconnect reclaims the executor-side session work and a fed frame records nothing`() {
        val running = start()
        val client = WsClient(port = running.boundPort)
        try {
            client.setTimeout(10_000)
            client.sendText("tick")
            assertTrue(nextFrame(client) != null, "no frame arrived before the disconnect")
            assertTrue(Recorder.size > 0, "the page recorded nothing before the disconnect")

            running.stop()

            assertTrue(WebBackend.root.isClosed, "the disconnect did not close the root")
            assertEquals(0, WebBackend.pageCount, "the disconnect left a page on the stack")
            assertTrue(
                await { WebUtils.pendingFrameTasks == 0 },
                "the frame executor still holds the teardown",
            )
            assertTrue(await { running.sessionCount == 0 }, "the session outlived the disconnect")

            assertFailsWith<IllegalStateException> { WebBackend.frame(800, 600) }
            assertEquals(0, Recorder.size, "a frame fed after the disconnect still recorded content")
        } finally {
            client.close()
        }
    }

    /** The next message that is a frame: a keyframe is preceded by the asset table. */
    private fun nextFrame(client: WsClient): ByteArray? {
        while (true) {
            val message = client.readMessage() ?: return null
            if (message.payload[0] != Encoder.ASSETS) return message.payload
        }
    }

    private fun start(): WebServer {
        WebTestRuntime.install()
        val server = WebServer(port = 0, page = "color", initialWidth = 800, initialHeight = 600)
        server.start()
        this.server = server
        return server
    }

    private fun await(millis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
