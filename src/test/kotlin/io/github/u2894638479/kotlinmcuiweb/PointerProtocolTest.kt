package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.component.DslComponent
import io.github.u2894638479.kotlinmcui.dsl.ui.Spacer
import io.github.u2894638479.kotlinmcui.input.*
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.modifier.Modifier
import io.github.u2894638479.kotlinmcui.modifier.size
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class PointerProtocolTest {
    @Test fun `socket identities are namespaced and source loss cancels only its own pointer`() {
        WebTestRuntime.install()
        val server = WebServer(port = 0, page = "multitouch", initialWidth = 200, initialHeight = 200)
        val events = CopyOnWriteArrayList<PointerEvent>()
        server.start()
        try {
            WebUtils.onFrameThread {
                WebBackend.create("pointer-probe") {
                    Spacer(Modifier.size(100.px, 100.px), "probe").change { inner -> object : DslComponent by inner {
                        override fun onPointer(event: PointerEvent): PointerResult {
                            events += event
                            return if (event is PointerEvent.Down) PointerResult.CAPTURE else PointerResult.IGNORED
                        }
                    } }
                }.show()
                WebBackend.frame(200, 200)
            }
            WsClient(port = server.boundPort).use { a ->
                WsClient(port = server.boundPort).use { b ->
                    a.sendText("pointer down 7 touch 100 100 0 1 0 0.5 0")
                    b.sendText("pointer down 7 pen 100 100 0 1 1 0.2 0")
                    assertTrue(await { events.count { it is PointerEvent.Down } == 2 })
                    val down = events.filterIsInstance<PointerEvent.Down>()
                    assertNotEquals(down[0].id, down[1].id)
                    assertTrue(down.all { it.id.value and 0xffffffffL == 7L })
                    a.sendText("blur")
                    assertTrue(await { events.count { it is PointerEvent.Cancel } == 1 })
                    val pen = down.single { it.kind == PointerKind.PEN }
                    assertNotEquals(pen.id, events.filterIsInstance<PointerEvent.Cancel>().single().id)
                    b.sendText("pointer move 7 pen 100 100 -1 1 1 0.9 0")
                    b.sendText("pointer up 7 pen 100 100 0 0 1 0 0")
                    assertTrue(await { events.any { it is PointerEvent.Up } })
                    assertEquals(0.9f, events.filterIsInstance<PointerEvent.Move>().single().pressure)
                    assertEquals(pen.id, events.filterIsInstance<PointerEvent.Up>().single().id)
                }
            }
        } finally { server.stop() }
    }
    @Test fun `a browser composition cannot commit into a field focused after it started`() {
        WebTestRuntime.install()
        val server = WebServer(port = 0, page = "multitouch", initialWidth = 200, initialHeight = 200)
        val seen = CopyOnWriteArrayList<String>()
        val ids = mutableListOf<io.github.u2894638479.kotlinmcui.identity.DslId>()
        server.start()
        try {
            WebUtils.onFrameThread {
                WebBackend.create("composition-probe") {
                    for (name in listOf("a", "b")) Spacer(Modifier.size(100.px, 100.px), name).change { inner ->
                        object : DslComponent by inner {
                            override val focusable get() = true
                            override val preeditArea get() = instance.rect
                            override fun preedit(info: PreeditInfo) { seen += "$name:${info.isEmpty}" }
                            override fun charTyped(c: Char): Boolean { seen += "$name:$c"; return true }
                        }
                    }
                }.show()
                WebBackend.frame(200, 200)
                WebBackend.root.component.testHit { if (it.focusable && it.preeditArea != null) ids.add(it.identity); null }
                WebBackend.root.setFocus(ids[0])
            }
            WsClient(port = server.boundPort).use { client ->
                client.sendText("compositionstart")
                client.sendText("preedit ni")
                assertTrue(await { seen.contains("a:false") })
                WebUtils.onFrameThread { WebBackend.root.setFocus(ids[1]) }
                client.sendText("compositionend old")
                client.sendText("key KeyA down 0")
                assertTrue(await { WebInput.isKeyDown(io.github.u2894638479.kotlinmcui.input.Key.A) })
                assertTrue(seen.contains("a:true"))
                assertTrue(seen.none { it.startsWith("b:") })
                client.sendText("compositionstart")
                client.sendText("compositionend x")
                assertTrue(await { seen.contains("b:x") })
            }
        } finally { server.stop() }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) { if (condition()) return true; Thread.sleep(10) }
        return condition()
    }
}
