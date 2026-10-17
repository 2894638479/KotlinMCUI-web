package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.entry.DslEntryLoader
import io.github.u2894638479.kotlinmcui.logger.dslLogger

/**
 * The framework's runtime, set up once for the whole test run.
 *
 * `DslRuntime.install` is single-shot by design - one backend per process - so every test class has
 * to share this one rather than install for itself, which is also why [frame] exists: taking a frame
 * is only legal after the runtime is up.
 *
 * The demo overlays are switched off here. They are entries like any other and the framework renders
 * whichever ones a machine has enabled - the web repository's own config directory remembers that -
 * and the debug overlay draws per-frame timings, so a test that leaves it on is testing a page that
 * can never repeat itself.
 */
internal object WebTestRuntime {
    private var installed = false

    fun install() {
        if (installed) return
        DslRuntime.install { WebMetadata }
        WebBackend.adoptRoot()
        // A refused publication is not a failure: the process platform belongs to whoever published
        // first, and this backend still serves its own roots.
        if (!DslRuntime.platformReady { WebPlatform }) {
            dslLogger.warn("web tests: another backend published this process's platform")
        }
        for (overlay in DslEntryLoader.enabledOverlays) DslEntryLoader.setOverlayEnabled(overlay, false)
        installed = true
    }

    fun frame(width: Int = WIDTH, height: Int = HEIGHT) {
        install()
        WebBackend.frame(width, height)
    }

    const val WIDTH = 1280
    const val HEIGHT = 720
}
