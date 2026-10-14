package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackend
import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.DslBackendScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.container.TopComponent
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.math.px

/**
 * The web backend: the framework's rendering contract, recorded instead of drawn.
 *
 * It is assembled from the same four pieces every backend is - a renderer, metadata, utilities and
 * input - and adds one thing: a frame that can be *taken* from outside, because a web client asks
 * for one. [Recorder] is the renderer, so this object never touches a canvas; what a client receives
 * is the word buffer [frame] leaves behind.
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
    private var current: WebScreen? = null

    override fun create(title: String, dslFunction: DslFunction): DslBackendScreenHolder<WebScreen> =
        object : DslBackendScreenHolder<WebScreen> {
            override val screen = WebScreen(this@WebBackend, title, dslFunction)

            /**
             * Showing a page makes it the rendered tree and takes the previous one out of it.
             *
             * The previous page is unmounted but not released: the framework's own `showScreen` is
             * how a page is replaced, and a page that can be returned to must keep what it declared.
             */
            override fun show() {
                val previous = current
                current = screen
                if (previous !== screen) previous?.unmount()
                screen.mount()
            }

            override val dslScreen get() = screen.dslScreen
        }

    /**
     * One frame, recorded.
     *
     * Called by whoever owns the frame clock - a socket reader serving a `tick`, or the measurement
     * tool - so it is synchronous and returns once the whole tree has been walked. It must run on
     * one thread for the lifetime of the screens: the framework's context records the thread that
     * created it and refuses to be used from another, which is why [WebUtils.mainDispatcher] is a
     * single thread as well.
     */
    internal fun frame(width: Int, height: Int) {
        Recorder.beginFrame()
        Recorder.surfaceWidth = width
        Recorder.surfaceHeight = height
        TopComponent.rect.apply {
            left = 0.px
            top = 0.px
            right = width.px
            bottom = height.px
        }
        context(this, Unit, WebInput.mouse) { TopComponent.render() }
        context(Unit) { flush() }
    }

    /** Drop the reference to a page that ended; the page itself has already closed its screen. */
    internal fun forget(screen: WebScreen) {
        if (current === screen) current = null
    }

    internal fun closeAll() {
        current?.release()
    }
}
