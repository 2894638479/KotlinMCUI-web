package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslPlatformMetadata
import io.github.u2894638479.kotlinmcui.backend.DslPlatform
import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.backend.dslPlatform
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.test.TestLayoutPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two backends in one process: whoever publishes the platform first owns it, and everyone else still
 * serves their own root.
 *
 * The runtime now hands out no roots at all - a backend builds its own from its input, renderer,
 * screen factory and utils. That is what makes coexistence structural rather than negotiated: a
 * refused publication says only that the process platform is somebody else's, and this backend's root
 * is unaffected.
 *
 * **Why this needs its own JVM.** `DslRuntime` is a process singleton: `install` runs once and `start`
 * publishes once, and the first publisher wins for the life of the process. A JVM that has already
 * published the web platform cannot lose the race, so the publication under test has to happen in a
 * fresh process - the build's `forkEvery = 1` is what provides one. **If that setting ever goes away
 * the case goes red, not green**: the first assertion is the precondition self-check, and a repeated
 * `install` throws even earlier. What that costs is a misleading message pointing at the platform race
 * rather than at the configuration, which is why the prerequisite is written here.
 */
class ForeignPlatformTest {

    /** A platform that wins the publication and is not ours. */
    private object ForeignPlatform : DslPlatform {
        override val metadata: DslPlatformMetadata = WebMetadata

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
     * A backend that lost the platform race still serves its own root and its own pages.
     *
     * The evidence is the production path end to end: the mount, then a frame through
     * [WebBackend.frame], then a non-empty recording. A root of its own is the only way that can work
     * now that the runtime no longer builds roots for anybody.
     */
    @Test
    fun `a backend that did not win the platform still serves its own root`() {
        DslRuntime.install { WebMetadata }
        assertTrue(
            DslRuntime.start { ForeignPlatform },
            "the foreign platform should have won the publication",
        )

        // Loses the publication on purpose; must carry on anyway.
        WebBackend.adoptRoot()
        assertEquals(ForeignPlatform, dslPlatform, "the process platform should be the first publisher")

        WebBackend.create("mine") { TestLayoutPage() }.show()
        Recorder.surfaceWidth = 800
        Recorder.surfaceHeight = 600
        WebBackend.frame(800, 600)

        assertTrue(Recorder.size > 0, "the production frame path drew nothing from this backend's own page")

        val store = DslDataStore(WebPlatform, WebBackend.root, "coexist", {}, { TestLayoutPage() })
        assertTrue(
            store.platform === WebPlatform,
            "the page must be built on this backend's platform, not the winner's",
        )
        assertEquals("foreign:key", dslPlatform.utils.translate("key"), "the process platform is still the winner's")
    }
}
