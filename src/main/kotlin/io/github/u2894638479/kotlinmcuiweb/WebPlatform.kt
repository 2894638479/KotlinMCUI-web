package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslPlatform
import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The web platform: the process-wide half of the contract.
 *
 * It is a plain object rather than something derived from a backend, because the framework no longer
 * has one aggregate to derive it from. What it names is what the process has: the installation facts
 * ([metadata]), the helpers that answer about the process rather than about one output ([utils]), and
 * the serial dispatcher every framework call hops onto ([dispatcher]).
 *
 * The per-output half - clipboard, capture, narration, sound, links, the session dispatcher - is
 * [WebRootProvider]'s, reached through its `rootUtils`. The split is the contract's: a root must be
 * able to have its own clipboard and its own capture even when it shares this platform.
 */
internal object WebPlatform : DslPlatform {
    override val metadata: DslBackendMetadata = WebMetadata

    override val utils: DslPlatformUtils = WebUtils

    /**
     * The one frame thread, named here as the platform's dispatcher.
     *
     * It is process-lifetime on purpose: a `static` property scope binds to the dispatcher of the
     * platform, so it must outlive every root, not belong to one.
     */
    override val dispatcher: CoroutineDispatcher = WebUtils.sessionDispatcher
}
