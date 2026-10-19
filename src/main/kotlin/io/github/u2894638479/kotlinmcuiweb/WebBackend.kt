package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslPreview
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.px

/**
 * The web backend's page stack: what a client is shown, and in which order.
 *
 * The aggregate backend the framework used to publish is gone, so this object no longer implements
 * anything - it holds the one thing that is neither the platform's nor the root provider's: the stack
 * of pages, because the framework's own screen model is a stack (`showScreen` enters a page, closing
 * it goes back).
 *
 * The pieces it used to be assembled from now live where the contract puts them: metadata installed
 * through [DslRuntime.install], the process helpers and the per-output helpers both on [WebUtils],
 * and recording/input/screens on [Recorder], [WebInput] and [WebScreenFactory].
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

    internal fun create(title: String, dslFunction: DslFunction): DslScreenHolder<WebScreen> =
        object : DslScreenHolder<WebScreen> {
            override val screen = WebScreen(title, dslFunction)

            /** Showing a page enters it: it becomes the top of the stack. */
            override fun show() = push(screen)

            override val dslScreen get() = screen.dslScreen
        }

    /**
     * This backend's own root: the process main root when it is ours, otherwise a root of our own.
     *
     * A coexistence process has more than one backend, and the process main root is built from
     * whichever provider registered first - so it is not necessarily ours, and rendering into it
     * would draw somebody else's tree. [adoptRoot] decides once, at startup, and everything else here
     * follows this field.
     */
    internal lateinit var root: DslRoot
        private set

    /**
     * Claim the root this backend will render into.
     *
     * The decision has to be made **before** registering, because the provider list is the only signal
     * for whether the main root will be built from ours; `DslRoot.provider` is internal to the core and
     * cannot be compared from here.
     *
     * When the main root is not ours, this backend gets **its own process-lifetime root** - not "the
     * main root", which in a coexistence process belongs to somebody else. Both behave the same today;
     * the difference is who else can see it.
     */
    /**
     * Build the root this backend renders into.
     *
     * The runtime no longer owns roots, so this backend constructs its own: the recorder draws, the
     * web input carries pointer and keyboard state, [WebScreenFactory] makes the pages, and [WebUtils]
     * answers the per-output questions.
     *
     * A refused platform publication only says the process platform belongs to another backend - this
     * one still builds its root and serves it, so there is no early return here, and that is a
     * decision rather than an omission. The publication is asked **once per process**, because that is
     * how many times the runtime answers it; building a root is a separate, repeatable act, so a
     * backend that closed its root can build the next one without re-asking a question already
     * answered.
     *
     * Entries launch only after a publication this backend won: the loser's hooks are the winner's to
     * run, and the runtime ignores a second launch.
     *
     * `DslPreview.start` has to be called explicitly now. It used to ride along with the publication,
     * so leaving it out does not fail loudly - the preview simply never appears.
     */
    internal fun adoptRoot() {
        if (!platformAttempted) {
            platformAttempted = true
            if (DslRuntime.start { WebUtils }) {
                DslRuntime.launch()
            } else {
                dslLogger.warn("web: another backend published this process's platform; this one serves its own root")
            }
        }
        root = DslRoot(WebInput, Recorder, WebScreenFactory, WebUtils)
        DslPreview.start(root)
    }

    /** Whether the process platform has already been asked for; the runtime answers only once. */
    private var platformAttempted = false

    /**
     * Close this backend's root, if it has one.
     *
     * From here on every way of *using* the root - mounting, unmounting, rendering, setting the focus -
     * fails loudly, while teardown itself carries on. Idempotent, and safe before a root exists
     * because a server can be stopped without ever having started.
     */
    internal fun closeRoot() {
        if (::root.isInitialized) root.close()
    }

    /**
     * One frame, recorded, for [root].
     *
     * The transport owns the frame clock, so it is what says how big the output is; the frame itself
     * belongs to the root's own pieces. Keeping the two together here means a caller does not have to
     * know which object owns which half.
     *
     * **To be changed when roots become several.** This is the only place that picks which root a frame
     * goes to; a multi-root backend routes there instead.
     */
    internal fun frame(width: Int, height: Int) {
        Recorder.beginFrame()
        Recorder.surfaceWidth = width
        Recorder.surfaceHeight = height
        root.rect.apply {
            left = 0.px
            top = 0.px
            right = width.px
            bottom = height.px
        }
        context(Recorder, Unit, WebInput.mouse) { root.component.render() }
        context(Unit) { Recorder.flush() }
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
        // A page's own close action asks to leave the page; during teardown that would take the page
        // below with it, so the same reentrancy guard `pop` uses holds the stack still while this
        // walks it. The pages are released one by one, and no release may pop another.
        popping = true
        try {
            while (pages.isNotEmpty()) pages.removeLast().release()
        } finally {
            popping = false
        }
    }

    private fun push(screen: WebScreen) {
        val current = pages.lastOrNull()
        if (current === screen) return
        current?.unmount()
        pages.addLast(screen)
        screen.mount()
    }
}
