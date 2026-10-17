package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.DslRootProvider
import io.github.u2894638479.kotlinmcui.backend.DslRootScreenFactory
import io.github.u2894638479.kotlinmcui.backend.DslRootUtils
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.math.px

/**
 * What a web root needs from this backend: recording, input, screens and the per-output helpers.
 *
 * A provider may be shared by several roots and is registered once, so nothing here may hold
 * per-root state - that is the contract's "one answer, many roots". The surface size is the honest
 * example: it belongs to whoever is driving the frame, which is why [frame] reads it off [Recorder]
 * rather than keeping it here.
 */
internal object WebRootProvider : DslRootProvider<Unit, WebScreen> {
    /** The recorder, so a root's frame never touches a canvas; the client receives the word buffer. */
    override val renderer: DslBackendRenderer<Unit> get() = Recorder

    override val input: DslBackendInput get() = WebInput

    override val screenFactory: DslRootScreenFactory<WebScreen> get() = ScreenFactory

    override val rootUtils: DslRootUtils get() = WebUtils

    /**
     * One frame of [root], recorded.
     *
     * The surface size is the one the transport last reported on [Recorder], because a shared provider
     * cannot own a size that changes per client. The rect is written **first**: the render pass copies
     * it onto every mounted screen, so the tree lays itself out for the size it is about to be drawn at.
     *
     * The render parameter is [Unit] on purpose: a recording backend has no platform object to pass
     * around, which is also the honest answer to "what would a second implementation of this renderer
     * need" - nothing.
     */
    override fun frame(root: DslRoot, renderParam: Unit) {
        Recorder.beginFrame()
        root.rect.apply {
            left = 0.px
            top = 0.px
            right = Recorder.surfaceWidth.px
            bottom = Recorder.surfaceHeight.px
        }
        context(Recorder, Unit, WebInput.mouse) { root.component.render() }
        context(Unit) { Recorder.flush() }
    }

    /** Screens are pages on the one page stack, so the factory is the backend's own `create`. */
    private object ScreenFactory : DslRootScreenFactory<WebScreen> {
        override fun create(title: String, dslFunction: DslFunction) = WebBackend.create(title, dslFunction)
    }
}
