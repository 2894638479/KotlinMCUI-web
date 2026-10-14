package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRuntime

/**
 * The framework's runtime, set up once for the whole test run.
 *
 * `DslRuntime.install` is single-shot by design - one backend per process - so every test class has
 * to share this one rather than install for itself, which is also why [frame] exists: taking a frame
 * is only legal after the runtime is up.
 */
internal object WebTestRuntime {
    private var installed = false

    fun install() {
        if (installed) return
        DslRuntime.install { WebMetadata }
        DslRuntime.platformReady { WebBackend }
        installed = true
    }

    fun frame(width: Int = WIDTH, height: Int = HEIGHT) {
        install()
        WebBackend.frame(width, height)
    }

    const val WIDTH = 1280
    const val HEIGHT = 720
}
