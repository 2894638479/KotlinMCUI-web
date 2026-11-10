package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRootInput
import io.github.u2894638479.kotlinmcui.input.*
import io.github.u2894638479.kotlinmcui.component.isFocused
import io.github.u2894638479.kotlinmcui.identity.DslId
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.nio.file.Path

/** Browser samples arrive on the frame executor. Connection ids namespace native pointer ids. */
internal object WebInput : DslRootInput {
    private val heldKeys = HashMap<Int, MutableSet<Int>>()
    private val pointers = LinkedHashMap<PointerId, Int>()
    private val modifiers = HashMap<Int, KeyModifiers>()
    private var compositionOwner: Int? = null
    private var compositionFocus: DslId? = null
    private var cursor = Position(0.px, 0.px)

    override fun isKeyDown(key: Key) = heldKeys.values.any { key.code in it }
    override fun setPreeditArea(rect: Rect) = Unit

    fun modifiers(value: KeyModifiers, connection: Int) {
        modifiers[connection] = value
        hold(connection, Key.LEFT_SHIFT, Key.RIGHT_SHIFT, value.shift)
        hold(connection, Key.LEFT_CTRL, Key.RIGHT_CTRL, value.ctrl)
        hold(connection, Key.LEFT_ALT, Key.RIGHT_ALT, value.alt)
        hold(connection, Key.LEFT_SUPER, Key.RIGHT_SUPER, value.superKey)
    }
    private fun hold(connection: Int, left: Key, right: Key, down: Boolean) {
        val keys = heldKeys.getOrPut(connection) { HashSet() }
        if (down) keys.add(left.code) else { keys.remove(left.code); keys.remove(right.code) }
    }

    fun pointer(event: PointerEvent, connection: Int): Boolean {
        when (event) {
            is PointerEvent.Down -> { pointers[event.id] = connection; cursor = event.position; modifiers(event.modifiers, connection) }
            is PointerEvent.Move -> { pointers[event.id] = connection; cursor = event.position; modifiers(event.modifiers, connection) }
            is PointerEvent.Up -> {
                if (!event.canHover && event.buttons.isEmpty()) pointers.remove(event.id)
                cursor = event.position
                modifiers(event.modifiers, connection)
            }
            is PointerEvent.Cancel -> pointers.remove(event.id)
            is PointerEvent.Leave -> Unit
        }
        return guarded("pointer input", false) { WebBackend.root.dispatchPointer(event) }
    }

    fun scrolled(position: Position, amount: Double, horizontal: Boolean) {
        cursor = position
        guarded("scroll", Unit) {
            if (horizontal) WebBackend.root.dispatchScrollHorizontal(position, amount)
            else WebBackend.root.dispatchScrollVertical(position, amount)
        }
    }
    fun keyPressed(key: Key, scanCode: Int, connection: Int): Boolean {
        heldKeys.getOrPut(connection) { HashSet() }.add(key.code)
        return guarded("key press", false) { WebBackend.root.dispatchKeyDown(key, scanCode, modifiers[connection] ?: KeyModifiers()) }
    }
    fun keyReleased(key: Key, scanCode: Int, connection: Int): Boolean {
        heldKeys[connection]?.remove(key.code)
        return guarded("key release", false) { WebBackend.root.dispatchKeyUp(key, scanCode, modifiers[connection] ?: KeyModifiers()) }
    }
    fun typed(codePoint: Int): Boolean {
        if (!Character.isValidCodePoint(codePoint)) return false
        var consumed = false
        for (unit in Character.toChars(codePoint))
            consumed = guarded("typed character", false) { WebBackend.root.dispatchCharTyped(unit) } || consumed
        return consumed
    }
    private fun focusedText(): DslId? = WebBackend.root.component.testHit {
        it.takeIf { it.focusable && it.isFocused && it.preeditArea != null }?.identity
    }
    fun compositionStart(connection: Int) {
        if (compositionOwner != null) preeditEnd()
        compositionOwner = connection
        compositionFocus = focusedText()
    }
    private fun preeditEnd() {
        compositionOwner = null
        compositionFocus = null
        guarded("composition end", Unit) { WebBackend.root.dispatchPreedit(PreeditInfo.empty) }
    }
    fun compositionEnd(text: String, connection: Int) {
        if (compositionOwner != connection) return
        val accept = compositionFocus != null && compositionFocus == focusedText()
        preeditEnd()
        if (accept) text.codePoints().forEach { typed(it) }
    }
    fun preedit(text: String, connection: Int) {
        if (compositionOwner != connection || compositionFocus == null || compositionFocus != focusedText()) return
        val info = if (text.isEmpty()) PreeditInfo.empty else PreeditInfo(
            listOf(io.github.u2894638479.kotlinmcui.text.CodePoints.Const(text)), 0, text.codePointCount(0, text.length),
        )
        guarded("composition", Unit) { WebBackend.root.dispatchPreedit(info) }
    }
    fun dropped(files: Array<Path>) {
        guarded("file drop", Unit) { WebBackend.root.dispatchDropFiles(cursor, files) }
    }

    /** Cancels identities, never fabricates an activating Up. A disconnected client cannot cancel another. */
    fun blur(connection: Int? = null) {
        if (connection == null) { heldKeys.clear(); modifiers.clear() }
        else { heldKeys.remove(connection); modifiers.remove(connection) }
        if (compositionOwner != null && (connection == null || compositionOwner == connection)) preeditEnd()
        val lost = pointers.filterValues { connection == null || it == connection }.keys.toList()
        lost.forEach { id ->
            pointers.remove(id)
            guarded("source loss", Unit) { WebBackend.root.dispatchPointer(PointerEvent.Cancel(id)) }
        }
    }
    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try { block() }
    catch (failure: Throwable) { dslLogger.error("web input: $what failed", failure); fallback }
}
