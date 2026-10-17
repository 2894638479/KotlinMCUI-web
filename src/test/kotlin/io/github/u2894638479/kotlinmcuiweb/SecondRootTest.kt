package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.component.DslComponent
import io.github.u2894638479.kotlinmcui.component.isFocused
import io.github.u2894638479.kotlinmcui.container.DslChild
import io.github.u2894638479.kotlinmcui.container.DslDataStore
import io.github.u2894638479.kotlinmcui.container.DslScope
import io.github.u2894638479.kotlinmcui.container.acquireFocusFromInput
import io.github.u2894638479.kotlinmcui.identity.DslId
import io.github.u2894638479.kotlinmcui.input.PreeditInfo
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.MutRect
import io.github.u2894638479.kotlinmcui.modifier.Modifier
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
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
 * **What this file can claim.** An outside module can build a root of its own, render its own tree in
 * it, take focus through its own tree with its own writer, and host framework content - the last one
 * by mounting the screen onto a root the runtime created, because **that mount is what stamps the
 * screen's store with a root state**. A component whose tree no root owns still fails loud, which is
 * the ruling: an answer that silently describes somebody else's tree is worse than a loud failure.
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

        /**
         * What this component was told, so hover and preedit have an observer.
         *
         * Both are dispatched down each root's own tree, so recording what arrives reads "whose input
         * reached whom" directly - no geometry guessing on the assertion side.
         */
        val hoverNotes = mutableListOf<DslId?>()
        val preeditNotes = mutableListOf<PreeditInfo>()

        override fun globalHoverChanged(newHover: DslId?) {
            hoverNotes += newHover
        }

        override fun globalPreedit(info: PreeditInfo) {
            preeditNotes += info
        }
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

    /**
     * A container that is a direct child of a root, so its own child keeps its own rect.
     *
     * The root writes its rect onto its **direct** children every frame; a container below that level
     * keeps its children's rects, which is the only way two roots can differ geometrically.
     */
    private class ProbeBox(override val identity: DslId) : DslScope {
        override var instance: DslComponent = this
        override val children = DslChild.Group()
        override val rect = MutRect()
        override val modifier: Modifier = Modifier
        override val scale: Double = 1.0
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
     * A framework page can be built and rendered on a root of its own.
     *
     * **This case was red for a while, and the reason is worth keeping.** It used to mount the screen
     * onto a hand-made `DslScope` whose child list is a plain group; nothing stamped the screen's
     * `dataStore.rootState`, so the page's first focus read threw while building. The capability was
     * not missing - **the case was not taking the mounting path**. `DslRoot.children` stamps the
     * store on *every* `collect`, so all it needed was a root obtained from `DslRuntime.createRoot`,
     * and it now walks that path. Recorded here because "it was red" and "it cannot be done" look
     * identical from a failing test, and only one of them was true.
     *
     * The root is `TRANSIENT` rather than the process owner: the point is that an outside module can
     * host framework content on a root of its own, not that the owner root works.
     */
    @Test
    fun `a framework page can be built and rendered on a root of its own`() {
        WebTestRuntime.install()

        val root = DslRuntime.createRoot(WebRootProvider, ViewerLifetime.TRANSIENT)
        val store = DslDataStore(WebPlatform, WebRootProvider, "colour-page-on-its-own-root", {}, { TestColorPage() })
        root.children.collect(store.dslScreen)

        val thrown = runCatching {
            Recorder.beginFrame()
            context(Recorder, Unit, Position(0.px, 0.px)) { root.component.render() }
        }.exceptionOrNull()

        assertNull(
            thrown,
            "a framework page has to build on a root of its own; it threw " +
                "${thrown?.javaClass?.simpleName}: ${thrown?.message}",
        )
        assertTrue(Recorder.size > 0, "the framework page drew nothing on its own root")
    }

    /**
     * Hover is computed per root, from the pointer and that root's **own** tree.
     *
     * Two layout facts decide how this has to be written, and both were found the hard way:
     * - `render()` copies the root's rect onto every **direct** child (`children.forEach { it.rect
     *   copyFrom rect }`) before the hover pass, so a direct child always covers the whole surface.
     *   Two roots of the same size would therefore both hover - correct behaviour, useless as a test.
     *   The probe is nested inside a container, which keeps its own rect.
     * - The surface size has to be set on the recorder first, or the root rect degenerates to 0x0 and
     *   the pointer position means nothing.
     *
     * Falsifier: frame the process owner root instead of A's, or point the assertion at the owner
     * root's tree, and A's probe stays empty - the case goes red.
     */
    @Test
    fun `hover follows each root's own tree`() {
        WebTestRuntime.install()

        val rootA = DslRuntime.createRoot(WebRootProvider, ViewerLifetime.TRANSIENT)
        val rootB = DslRuntime.createRoot(WebRootProvider, ViewerLifetime.TRANSIENT)
        val probeA = probe("hover-a")
        val probeB = probe("hover-b").apply {
            rect.left = 200.px
            rect.right = 300.px
            rect.top = 200.px
            rect.bottom = 300.px
        }
        val boxA = ProbeBox(DslId("box-a"))
        val boxB = ProbeBox(DslId("box-b"))
        boxA.children.collect(probeA)
        boxB.children.collect(probeB)
        rootA.children.collect(boxA)
        rootB.children.collect(boxB)

        // A real surface, or the root rect is 0x0 and the pointer is nowhere.
        Recorder.surfaceWidth = 800
        Recorder.surfaceHeight = 600
        WebInput.mouse = Position(10.px, 10.px)

        WebRootProvider.frame(rootA, Unit)
        WebRootProvider.frame(rootB, Unit)

        assertTrue(
            probeA.hoverNotes.contains(probeA.identity),
            "A's own tree never reported the component under the pointer",
        )
        // B is told "nothing is hovered" - `setHovered(null)` dispatches even when the value does not
        // change, so requiring silence would be wrong. What must never happen is B learning A's id.
        assertTrue(
            probeB.hoverNotes.none { it == probeA.identity },
            "B's tree was told about A's component: ${probeB.hoverNotes}",
        )
        assertTrue(
            probeB.hoverNotes.all { it == null },
            "B's own geometry contains nothing, so it may only ever hear 'nothing is hovered': ${probeB.hoverNotes}",
        )
    }

    /**
     * Preedit raised on one root reaches that root's tree and no other.
     *
     * Falsifier: raise it on the other root and A's notes stay empty.
     */
    @Test
    fun `preedit goes to the tree it was raised on`() {
        WebTestRuntime.install()

        val rootA = DslRuntime.createRoot(WebRootProvider, ViewerLifetime.TRANSIENT)
        val rootB = DslRuntime.createRoot(WebRootProvider, ViewerLifetime.TRANSIENT)
        val probeA = probe("preedit-a")
        val probeB = probe("preedit-b")
        rootA.children.collect(probeA)
        rootB.children.collect(probeB)

        rootA.component.globalPreedit(PreeditInfo.empty)

        assertTrue(
            probeA.preeditNotes.isNotEmpty(),
            "A's own tree never received the preedit raised on it",
        )
        assertTrue(
            probeB.preeditNotes.isEmpty(),
            "B's tree received a preedit raised on another root: ${probeB.preeditNotes}",
        )
    }
}
