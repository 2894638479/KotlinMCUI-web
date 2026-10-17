package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackend
import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.DslBackendScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.math.px

/**
 * The web backend: the framework's rendering contract, recorded instead of drawn.
 *
 * It is assembled from the same four pieces every backend is - a renderer, metadata, utilities and
 * input - and adds two things: a frame that can be *taken* from outside, because a client asks for
 * one, and a page stack, because the framework's own screen model is one (`showScreen` enters a page,
 * closing it goes back).
 *
 * [Recorder] is the renderer, so this object never touches a canvas; what a client receives is the
 * word buffer [frame] leaves behind.
 *
 * The type parameters are this backend's own. The render parameter is [Unit] on purpose: a recording
 * backend has no platform object to pass around, which is also the honest answer to "what would a
 * second implementation of this renderer need" - nothing.
 *
 * One instance per process, because the framework publishes one backend per process.
 */
object WebBackend : DslBackend<Unit, WebScreen>,
    DslBackendRenderer<Unit> by Recorder,
    DslBackendMetadata by WebMetadata,
    DslBackendUtils by WebUtils,
    DslBackendInput by WebInput
{
    /**
     * The pages, oldest first; the last one is what a frame renders.
     *
     * Only the stack top is mounted, so a page that is merely below another keeps everything it
     * declared - the same promise the framework makes when a screen is left and entered again.
     */
    private val pages = ArrayDeque<WebScreen>()

    /** Guards against a page's own close action popping a second page while one is being popped. */
    private var popping = false

    internal val pageCount: Int get() = pages.size

    internal val currentPage: WebScreen? get() = pages.lastOrNull()

    override fun create(title: String, dslFunction: DslFunction): DslBackendScreenHolder<WebScreen> =
        object : DslBackendScreenHolder<WebScreen> {
            override val screen = WebScreen(this@WebBackend, title, dslFunction)

            /** Showing a page enters it: it becomes the top of the stack. */
            override fun show() = push(screen)

            override val dslScreen get() = screen.dslScreen
        }

    /**
     * One frame, recorded.
     *
     * Called by whoever owns the frame clock - a socket reader serving a `tick`, or the measurement
     * tool - so it is synchronous and returns once the whole tree has been walked. It must run on
     * one thread for the lifetime of the pages: the framework's context records the thread that
     * created it and refuses to be used from another, which is why [WebUtils.mainDispatcher] is a
     * single thread as well.
     *
     * The root comes from [DslRuntime.ownerRoot] rather than a singleton, and the order is part of the
     * contract: the rect is written first, then the frame is rendered, so the tree lays itself out for
     * the size it is about to be drawn at.
     */
    internal fun frame(width: Int, height: Int) {
        Recorder.beginFrame()
        Recorder.surfaceWidth = width
        Recorder.surfaceHeight = height
        val root = DslRuntime.ownerRoot
        root.rect.apply {
            left = 0.px
            top = 0.px
            right = width.px
            bottom = height.px
        }
        context(this, Unit, WebInput.mouse) { root.component.render() }
        context(Unit) { flush() }
    }

    /**
     * Leave the top page: it is released and the page below comes back.
     *
     * The page at the bottom is the floor - there is nothing below it to return to - so closing it
     * does nothing rather than leaving the client with an empty surface. Returns whether a page was
     * actually left, which is also the answer "should this frame be re-recorded".
     */
    internal fun pop(): Boolean {
        if (popping) return false
        val top = pages.lastOrNull() ?: return false
        if (pages.size <= 1) return false
        popping = true
        try {
            pages.removeLast()
            // The page's own close action asks for this pop as well; the guard is what stops it from
            // taking the page below down with it.
            top.release()
            pages.lastOrNull()?.mount()
            return true
        } finally {
            popping = false
        }
    }

    /** Drop a page that ended on its own; the page itself has already closed its framework screen. */
    internal fun forget(screen: WebScreen) {
        pages.remove(screen)
    }

    internal fun closeAll() {
        while (pages.isNotEmpty()) pages.removeLast().release()
    }

    private fun push(screen: WebScreen) {
        val current = pages.lastOrNull()
        if (current === screen) return
        current?.unmount()
        pages.addLast(screen)
        screen.mount()
    }
}
