package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.dsl.DslFunction

/**
 * The web backend's page stack: what a client is shown, and in which order.
 *
 * The aggregate backend the framework used to publish is gone, so this object no longer implements
 * anything - it holds the one thing that is neither the platform's nor the root provider's: the stack
 * of pages, because the framework's own screen model is a stack (`showScreen` enters a page, closing
 * it goes back).
 *
 * The pieces it used to be assembled from now live where the contract puts them: metadata and the
 * process helpers on [WebPlatform], recording/input/screens on [WebRootProvider], and the helpers one
 * output answers for itself on [WebUtils].
 */
object WebBackend {

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

    internal fun create(title: String, dslFunction: DslFunction): DslBackendScreenHolder<WebScreen> =
        object : DslBackendScreenHolder<WebScreen> {
            override val screen = WebScreen(title, dslFunction)

            /** Showing a page enters it: it becomes the top of the stack. */
            override fun show() = push(screen)

            override val dslScreen get() = screen.dslScreen
        }

    /**
     * One frame, recorded, for the process owner root.
     *
     * The transport owns the frame clock, so it is what says how big the output is; the frame itself
     * belongs to [WebRootProvider]. Keeping the two together here means a caller does not have to know
     * which object owns which half.
     *
     * **To be changed when roots become several.** This is the only place in the backend that names
     * [DslRuntime.ownerRoot]; a multi-root backend has to route a frame to the root the caller meant,
     * and that routing starts here. Until then, naming the owner root in one method is the single
     * point of contact this backend wants - not a habit to copy elsewhere.
     */
    internal fun frame(width: Int, height: Int) {
        Recorder.surfaceWidth = width
        Recorder.surfaceHeight = height
        WebRootProvider.frame(DslRuntime.ownerRoot, Unit)
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
