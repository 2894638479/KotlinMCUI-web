package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.DslChild
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.dsl.DslFunction

/**
 * One page of the web UI: the framework screen and the state that belongs to it.
 *
 * The framework wants two things from a screen - a [DslDataStore] and a [io.github.u2894638479.kotlinmcui.container.DslScreen]
 * - and this is where both come from, exactly as in the other backends. The store takes the platform
 * and the root provider separately now, because the contract separates what the process knows from
 * what one output answers for itself.
 *
 * A page is closed by leaving it: the close action pops the stack, so `closeScreen()` and the
 * framework's own "go back" both end up in the same place. [release] is the other thing - it ends the
 * page for good - and only the backend calls it.
 */
class WebScreen internal constructor(
    val title: String,
    dslFunction: DslFunction,
) {
    private var child: DslChild? = null
    private var released = false

    val dataStore = DslDataStore(WebPlatform, WebRootProvider, title, { WebBackend.pop() }, dslFunction)

    val dslScreen = dataStore.dslScreen

    /**
     * Make this page the tree the next frame renders.
     *
     * The root's child list is appended to rather than rebuilt, because the framework's overlay
     * layer is collected into it once and dropping the list would take the overlays with it.
     */
    internal fun mount() {
        check(!released) { "page '$title' was released and cannot be shown again" }
        if (child != null) return
        child = DslRuntime.ownerRoot.children.collect(dslScreen)
    }

    /** Take the page out of the rendered tree, leaving what it declared in place. */
    internal fun unmount() {
        val mounted = child ?: return
        child = null
        DslRuntime.ownerRoot.children.remove(mounted)
    }

    /** End the page and close its framework screen, which releases everything it scoped. */
    internal fun release() {
        if (released) return
        released = true
        unmount()
        WebBackend.forget(this)
        dslScreen.close()
    }
}
