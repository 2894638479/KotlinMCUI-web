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
 * **Why this needs its own JVM, and why that is load-bearing rather than incidental.** `DslRuntime`
 * is a process singleton: `install` runs once, `platformReady` publishes once, and the first
 * publication wins for the life of the process. A JVM that has already published the web platform
 * cannot lose the race, so the publication under test has to happen in a fresh process - the build's
 * `forkEvery = 1` is what provides one. The class also must not go through `WebTestRuntime`, because
 * that helper's whole job is to publish the web platform; it builds its own runtime from the starting
 * state instead.
 *
 * **If that build setting ever goes away, this case goes red - not green.** The first assertion is the
 * precondition self-check: `assertTrue(platformReady { ForeignPlatform })` fails the moment another
 * platform already owns the process, and a repeated `install` throws even earlier. What costs time is
 * not a silent pass but a **misleading failure** - the message would point at "the foreign platform
 * did not win" rather than at the configuration. That is why the prerequisite is written here:
 * otherwise every reader has to trace an assertion back to the build file.
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
