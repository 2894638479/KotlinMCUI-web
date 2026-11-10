package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRootRenderer
import io.github.u2894638479.kotlinmcui.component.*
import io.github.u2894638479.kotlinmcui.container.*
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.dsl.decorator.*
import io.github.u2894638479.kotlinmcui.dsl.ui.Spacer
import io.github.u2894638479.kotlinmcui.identity.DslId
import io.github.u2894638479.kotlinmcui.input.*
import io.github.u2894638479.kotlinmcui.math.*
import io.github.u2894638479.kotlinmcui.math.rect.*
import io.github.u2894638479.kotlinmcui.modifier.*
import io.github.u2894638479.kotlinmcui.test.TestColorPage
import io.github.u2894638479.kotlinmcui.text.CodePoints
import kotlin.test.*

/** Public API regression: every root owns its screen, geometry and input independently. */
class SecondRootTest {
    private class Notes {
        val focus = mutableListOf<DslId?>()
        val hover = mutableListOf<DslId?>()
        val preedit = mutableListOf<PreeditInfo>()
        var component: DslComponent? = null
        var rendered = false
    }
    private fun surface(body: DslFunction): Pair<DslRoot, DslScreen> {
        WebTestRuntime.install()
        val root = DslRoot(WebInput, Recorder, null, WebUtils).apply {
            scale = 1.0
            rect.apply { left = 0.px; top = 0.px; right = 100.px; bottom = 100.px }
        }
        val screen = DslDataStore(WebUtils, root, "independent", {}, body).dslScreen
        root.children.collect(screen)
        Recorder.beginFrame()
        context(Recorder, Unit) { root.component.render() }
        return root to screen
    }
    private fun surface(notes: Notes, offset: Int = 0) = surface {
        Spacer(Modifier.size(100.px, 100.px), "probe").change { inner ->
            object : DslComponent by inner {
                override val focusable get() = true
                override val preeditArea get() = instance.rect
                override fun globalFocusChanged(newFocus: DslId?, source: FocusSource) { notes.focus += newFocus }
                override fun globalHoverChanged(pointerId: PointerId, newHover: DslId?) { notes.hover += newHover }
                override fun preedit(info: PreeditInfo) { notes.preedit += info }
                context(backend: DslRootRenderer<RP>, renderParam: RP)
                override fun <RP> render() { notes.rendered = true; notes.component = instance }
            }
        }.move(offset.px, offset.px)
    }

    @Test fun `an outside module can build a root and render a tree in it`() {
        val notes = Notes(); val (root, _) = surface(notes)
        try { assertTrue(notes.rendered) } finally { root.close() }
    }
    @Test fun `two roots keep their own scale and their own rect`() {
        val (a, _) = surface {}; val (b, _) = surface {}
        try {
            a.scale = 2.0; b.scale = 3.0
            a.rect.right = 200.px; b.rect.right = 300.px
            assertEquals(2.0, a.scale); assertEquals(3.0, b.scale)
            assertEquals(200.px, a.rect.right); assertEquals(300.px, b.rect.right)
        } finally { a.close(); b.close() }
    }
    @Test fun `an outside root finds the focus target in its own tree`() {
        val na = Notes(); val nb = Notes()
        val (a, _) = surface(na); val (b, _) = surface(nb)
        try {
            val point = Position(10.px, 10.px)
            val ca = assertNotNull(a.component.testHit(point) { it.takeIf { it.focusable } })
            val cb = assertNotNull(b.component.testHit(point) { it.takeIf { it.focusable } })
            assertSame(na.component, ca); assertSame(nb.component, cb)
            a.setFocus(ca.identity, FocusSource.POINTER)
            assertEquals(listOf<DslId?>(ca.identity), na.focus); assertTrue(nb.focus.isEmpty())
        } finally { a.close(); b.close() }
    }
    @Test fun `focus follows each root's own tree`() {
        val na = Notes(); val nb = Notes()
        val (a, _) = surface(na); val (b, _) = surface(nb)
        try {
            a.setFocus(na.component!!.identity)
            b.setFocus(nb.component!!.identity)
            assertTrue(na.component!!.isFocused); assertTrue(nb.component!!.isFocused)
            a.setFocus(null)
            assertFalse(na.component!!.isFocused); assertTrue(nb.component!!.isFocused)
        } finally { a.close(); b.close() }
    }
    @Test fun `a component in a tree that no root owns fails loud`() {
        val unowned = object : DslComponent {
            override val identity = DslId("unowned")
            override var instance: DslComponent = this
            override val scale = 1.0
            override val rect = MutRect()
            override val modifier = Modifier
            override val focusable = true
        }
        assertFailsWith<IllegalStateException> { unowned.isFocused }
    }
    @Test fun `a framework page can be built and rendered on a root of its own`() {
        val (root, _) = surface { TestColorPage() }
        try { assertTrue(Recorder.size > 0) } finally { root.close() }
    }
    @Test fun `hover follows each root's own tree`() {
        val na = Notes(); val nb = Notes()
        val (a, _) = surface(na); val (b, _) = surface(nb, 200)
        try {
            val move = PointerEvent.Move(PointerId(1), PointerKind.MOUSE, Position(10.px, 10.px), emptySet(), true)
            a.dispatchPointer(move); b.dispatchPointer(move)
            assertTrue(na.component!!.isHovered)
            assertFalse(nb.component!!.isHovered)
            assertTrue(nb.hover.none { it == na.component!!.identity })
        } finally { a.close(); b.close() }
    }
    @Test fun `preedit goes to the tree it was raised on`() {
        val na = Notes(); val nb = Notes()
        val (a, _) = surface(na); val (b, _) = surface(nb)
        try {
            a.setFocus(na.component!!.identity)
            a.dispatchPreedit(PreeditInfo(listOf(CodePoints.Const("ni")), 0, 2))
            a.dispatchPreedit(PreeditInfo.empty)
            assertEquals(2, na.preedit.size)
            assertTrue(nb.preedit.isEmpty())
        } finally { a.close(); b.close() }
    }
}
