package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.component.DslComponent
import io.github.u2894638479.kotlinmcui.component.isFocused
import io.github.u2894638479.kotlinmcui.container.DslChild
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.container.DslScope
import io.github.u2894638479.kotlinmcui.container.acquireFocusFromInput
import io.github.u2894638479.kotlinmcui.identity.DslId
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.MutRect
import io.github.u2894638479.kotlinmcui.modifier.Modifier
import io.github.u2894638479.kotlinmcui.test.TestColorPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Can a module outside the core build a root of its own, render it, and drive it?
 *
 * This is the question the per-root design turns on. If an outside module can do it, what the core
 * has to change is the process-wide UI state; if it cannot, the core has to publish a root type.
 *
 * Everything here uses the public surface only. A root built with an `internal` symbol would pass
 * without answering the question, because an outside module cannot reach one.
 *
 * **What this file can and cannot claim, after the singleton was deleted.** An outside root can hold
 * its own rect and scale, render a tree of its own components, take focus through its own tree with
 * its own writer, and a component whose tree no root owns now fails loud instead of answering for
 * another tree. It **cannot** host framework content: the framework pages sampled ask `isFocused`
 * while they build, that question needs a root state, and an outside root has none - so the page
 * cannot be built at all. The case pinning that half says so plainly; it is not written to look like
 * something that works.
 */
class SecondRootTest {

    /**
     * A root written from outside the core.
     *
     * Everything `DslScope` leaves abstract turns out to be reachable from outside: `rect` has a
     * public factory, `modifier` has a companion instance of the default, `identity` has a public
     * constructor, and `scale` is simply per-root state. Every behaviour is a default that routes
     * through `instance.children`, so a root is a child list plus a self-reference plus a rect.
     */
    private class SecondRoot(
        override val scale: Double,
        name: String,
    ) : DslScope {
        override var instance: DslComponent = this
        override val children = DslChild.Group()
        override val rect = MutRect()
        override val modifier: Modifier = Modifier
        override val identity = DslId(name)
    }

    /** A leaf that can take focus, so a root has something for the focus state to be about. */
    private class FocusProbe(
        override val identity: DslId,
        override val scale: Double,
    ) : DslComponent {
        override var instance: DslComponent = this
        override val rect = MutRect()
        override val modifier: Modifier = Modifier
        override val focusable = true
    }

    /** A leaf that records being rendered, and nothing else, so the render claim has an observer. */
    private class DrawProbe(
        override val identity: DslId,
        override val scale: Double,
    ) : DslComponent {
        override var instance: DslComponent = this
        override val rect = MutRect()
        override val modifier: Modifier = Modifier

        var rendered = false
            private set

        context(backend: DslBackendRenderer<RP>, renderParam: RP, mouse: Position)
        override fun <RP> render() {
            rendered = true
        }
    }

    /** A probe that covers the point the tests press at, so the root's own hit test finds it. */
    private fun probe(name: String): FocusProbe = FocusProbe(DslId(name), 1.0).apply {
        rect.left = 0.px
        rect.top = 0.px
        rect.right = 100.px
        rect.bottom = 100.px
    }

    /**
     * An outside root renders the tree in it.
     *
     * The claim is deliberately scoped to **the root's own components**: a framework page cannot be
     * hosted today (the case further down says why), so claiming "a framework page renders" here
     * would be a green that does not carry the claim it looks like it carries.
     *
     * **Falsified once, on purpose:** with the probe not collected into the root, so the render path
     * never reaches it, `rendered` stays false and this case fails. Observed red:
     * `AssertionFailedError: the root's render never reached the component in it`. That is what makes
     * the flag evidence rather than decoration - it is only ever set from inside a real render call.
     */
    @Test
    fun `an outside module can build a root and render a tree in it`() {
        WebTestRuntime.install()

        val root = SecondRoot(1.0, "second-root")
        val probe = DrawProbe(DslId("draw-probe"), 1.0)
        root.children.collect(probe)

        Recorder.beginFrame()
        context(Recorder, Unit, Position(0.px, 0.px)) { root.render() }

        assertTrue(probe.rendered, "the root's render never reached the component in it")
    }

    @Test
    fun `two roots keep their own scale and their own rect`() {
        WebTestRuntime.install()

        val first = SecondRoot(1.0, "root-a")
        val second = SecondRoot(3.0, "root-b")

        assertEquals(1.0, first.scale, "the first root lost its scale")
        assertEquals(3.0, second.scale, "the second root saw the first root's scale")

        assertTrue(first.rect !== second.rect, "two roots share one rect")

        first.rect.right = 100.px
        second.rect.right = 200.px
        assertTrue(
            first.rect.right.raw != second.rect.right.raw,
            "writing one root's rect changed the other root's",
        )
    }

    /**
     * Each root decides its own focus, through its own tree, and writes it to its own slot.
     *
     * `acquireFocusFromInput` is a `DslScope` extension, so any root runs the same lookup against its
     * own children and hands the identity to its own writer. Two roots therefore hold two independent
     * focus values.
     *
     * This case used to also assert that the process-wide slot was never touched. The singleton was
     * deleted, so that assertion has nothing left to name: there is no process slot to go through any
     * more, which is a stronger guarantee than checking that a call avoided one.
     */
    @Test
    fun `each root takes focus through its own tree and its own writer`() {
        WebTestRuntime.install()

        val rootA = SecondRoot(1.0, "root-a")
        val rootB = SecondRoot(3.0, "root-b")
        val probeA = probe("probe-a")
        val probeB = probe("probe-b")
        rootA.children.collect(probeA)
        rootB.children.collect(probeB)

        var focusA: DslId? = null
        var focusB: DslId? = null

        context(Position(10.px, 10.px)) { rootA.acquireFocusFromInput { id, _ -> focusA = id } }
        assertTrue(focusA === probeA.identity, "A's own tree did not decide A's focus")
        assertNull(focusB, "A's input reached B's writer")

        context(Position(10.px, 10.px)) { rootB.acquireFocusFromInput { id, _ -> focusB = id } }
        assertTrue(focusB === probeB.identity, "B's own tree did not decide B's focus")
        assertTrue(focusA === probeA.identity, "B's input replaced A's focus")
    }

    /**
     * A component in a tree that no root owns fails loud.
     *
     * `isFocused` resolves through `dataStore?.rootState`, and a component built outside the runtime
     * has neither. Reading it therefore throws instead of guessing which tree was meant. That is the
     * ruling, and it is the right one: an answer that silently describes somebody else's tree looks
     * like a correct answer and is not - a loud failure is the cheaper of the two mistakes.
     *
     * **Criterion (C form - unreachable today): "a component in a root-owned tree reports the focus of
     * its own tree".** Producing that state needs a way to mount a tree onto a root the runtime
     * recognises, which does not exist yet; the missing piece is the public root-building capability
     * (step 5: `registerProvider` / `createRoot`). It is deliberately **not** written as a test here:
     * there is no way to make it fail today, so any green it produced would be a green that was never
     * triggered - the failure mode this repository has already paid for more than once.
     */
    @Test
    fun `a component in a tree that no root owns fails loud`() {
        WebTestRuntime.install()

        val rootA = SecondRoot(1.0, "root-a")
        val probeA = probe("probe-a")
        rootA.children.collect(probeA)

        assertFailsWith<IllegalStateException> {
            probeA.isFocused
        }
    }

    /**
     * A framework page cannot be built on a root the runtime does not own - **deliberately red**.
     *
     * - **Red is deliberate**: this asserts the behaviour we want, so it fails today on purpose. It is
     *   not quarantined, because a skipped criterion reports green without ever being triggered.
     * - **Where it goes red**: not in rendering - in **building**. The colour page's editor asks
     *   `isFocused` from inside its own `build` (`Colors.kt:507` via `ColorEdit`), that question
     *   resolves through `dataStore?.rootState`, and a foreign root has no root state, so the tree is
     *   never built. The failure message carries the thrown text, which says the component is "not
     *   mounted on any root".
     * - **What it waits for**: step 5's `createRoot`, which has to stamp a root state **before** the
     *   build, not merely answer queries afterwards.
     * - **Wider than this page**: the layout page trips the same constraint, so today an outside root
     *   can host **its own components and no framework content at all**.
     */
    @Test
    fun `a framework page cannot be built on a foreign root`() {
        WebTestRuntime.install()

        val root = SecondRoot(1.0, "root-with-colour-page")
        val store = DslDataStore(WebBackend, "colour-page-on-foreign-root", {}, { TestColorPage() })
        root.children.collect(store.dslScreen)

        val thrown = runCatching {
            Recorder.beginFrame()
            context(Recorder, Unit, Position(0.px, 0.px)) { root.render() }
        }.exceptionOrNull()

        assertNull(
            thrown,
            "a framework page has to be buildable on an outside root; it threw " +
                "${thrown?.javaClass?.simpleName}: ${thrown?.message} - the build-phase focus read " +
                "needs a root state stamped before the build, which is step 5",
        )
    }
}
