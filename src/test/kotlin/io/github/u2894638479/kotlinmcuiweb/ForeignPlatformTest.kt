package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslPlatform
import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.backend.dslPlatform
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.test.TestLayoutPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two backends in one process: whoever publishes the platform first owns it, and everyone else still
 * serves their own roots.
 *
 * The framework publishes exactly one platform per process, so a second backend's `platformReady`
 * returns `false` and is refused. That refusal says **one thing only**: this backend did not become
 * the process's platform. It must not stop the backend from registering its provider, creating its
 * own roots, or drawing pages - which is the whole point of "translate comes from the platform,
 * rendering comes from the root".
 *
 * This class is deliberately not built on `WebTestRuntime`: that helper publishes the web platform,
 * and this case needs to lose the race on purpose, from a fresh JVM (`forkEvery = 1` gives it one).
 */
class ForeignPlatformTest {

    /** A platform that wins the publication and is not ours. */
    private object ForeignPlatform : DslPlatform {
        override val metadata: DslBackendMetadata = WebMetadata

        override val utils: DslPlatformUtils = object : DslPlatformUtils {
            override fun translate(key: String, vararg args: Any?): String? = "foreign:$key"
            override val isInWorld: Boolean get() = false
            override fun loadLocalImage(file: File): ImageHolder = error("not this platform")
            override fun forceLoadLocalImage(file: File): ImageHolder = error("not this platform")
            override fun quit(exitCode: Int) = Unit
        }

        override val dispatcher = WebUtils.sessionDispatcher
    }

    /**
     * A backend that lost the platform race still serves its own roots and pages.
     *
     * The assertions are the three things a loser could plausibly get wrong: it stops registering its
     * provider, it cannot create a root, or its pages end up using the winner's platform. The third is
     * the subtle one - the screen's store is built with **our** platform object, so translation and
     * world questions keep coming from us even though `dslPlatform` is somebody else's.
     */
    @Test
    fun `a backend that did not win the platform still serves its own roots`() {
        DslRuntime.install { WebMetadata }
        assertTrue(
            DslRuntime.platformReady { ForeignPlatform },
            "the foreign platform should have won the publication",
        )

        DslRuntime.registerProvider(WebRootProvider)
        assertFalse(
            DslRuntime.platformReady { WebPlatform },
            "a second platform must be refused - one publisher per process",
        )
        assertTrue(
            DslRuntime.registeredProviders.contains(WebRootProvider),
            "losing the platform must not stop this backend registering its provider",
        )
        assertEquals(ForeignPlatform, dslPlatform, "the process platform should be the first publisher")

        val root = DslRuntime.createRoot(WebRootProvider, ViewerLifetime.TRANSIENT)
        with(DslRuntime) {
            assertTrue(root.isRegistered, "losing the platform must not stop this backend creating a root")
        }

        val store = DslDataStore(WebPlatform, WebRootProvider, "coexist", {}, { TestLayoutPage() })
        root.children.collect(store.dslScreen)

        Recorder.surfaceWidth = 800
        Recorder.surfaceHeight = 600
        WebRootProvider.frame(root, Unit)

        assertTrue(Recorder.size > 0, "the page drew nothing on a root this backend owns")
        assertTrue(
            store.platform === WebPlatform,
            "the page must be built on this backend's platform, not the winner's",
        )
        assertEquals("foreign:key", dslPlatform.utils.translate("key"), "the process platform is still the winner's")
    }
}
