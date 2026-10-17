package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRootScreenFactory
import io.github.u2894638479.kotlinmcui.dsl.DslFunction

/**
 * Creates the screens mounted on this backend's root.
 *
 * Screens are pages on the one page stack, so the factory is the backend's own `create`. It is a
 * top-level object because a root is now built by whoever renders into it - production builds one at
 * startup, and the tests build their own - and all of them have to make screens the same way.
 */
internal object WebScreenFactory : DslRootScreenFactory<WebScreen> {
    override fun create(title: String, dslFunction: DslFunction) = WebBackend.create(title, dslFunction)
}
