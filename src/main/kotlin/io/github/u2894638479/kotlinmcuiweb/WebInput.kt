package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRootInput
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.input.Key
import io.github.u2894638479.kotlinmcui.input.KeyModifiers
import io.github.u2894638479.kotlinmcui.input.MouseButton
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.nio.file.Path

/**
 * Input for a browser client.
 *
 * The framework reads input two ways, and a browser only supplies one of them: events arrive
 * (a key goes down, a pointer moves) while [isKeyDown], [isMouseDown] and [mouse] are *polled* every
 * frame. So the polled half is derived from the event stream here - the browser has no "is this key
 * down right now" to ask, and asking it per frame over a socket would put a round trip in front of
 * every frame.
 *
 * The one case that half cannot cover on its own is a client that stops sending: a browser window
 * that loses focus never delivers the key-up, and a broken connection never delivers anything at all.
 * Both would leave a key stuck down forever, so [blur] - which the transport calls on focus loss and
 * on disconnect - drops everything that was held.
 *
 * Every entry point is wrapped in [guarded]: a component's handler is user code, and an exception in
 * it must not take the input path down with it. What the framework gets back is "not consumed",
 * which is exactly what it would have got from a component that ignored the event.
 */
internal object WebInput : DslRootInput {
    override var mouse = Position(0.px, 0.px)

    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()
    private var modifiers = KeyModifiers()

    override fun isKeyDown(key: Key) = heldKeys.contains(key.code)

    override fun isMouseDown(mouse: Int) = heldButtons.contains(mouse)

    /** The client positions its own IME box, so nothing is delegated to a platform here. */
    override fun setPreeditArea(rect: Rect) = Unit

    /** The modifier state that arrives with each event, as the client last reported it. */
    fun modifiers(value: KeyModifiers) {
        modifiers = value
        // The framework does not only read modifiers from the event context: a horizontal scroller
        // accepts a *vertical* wheel when `isKeyDown(LEFT_SHIFT)` is true, which is the polled half of
        // input. A browser reports modifiers as flags on each event rather than as key events that
        // always arrive, so the flags are what has to keep the polled state honest.
        hold(Key.LEFT_SHIFT, Key.RIGHT_SHIFT, value.shift)
        hold(Key.LEFT_CTRL, Key.RIGHT_CTRL, value.ctrl)
        hold(Key.LEFT_ALT, Key.RIGHT_ALT, value.alt)
        hold(Key.LEFT_SUPER, Key.RIGHT_SUPER, value.superKey)
    }

    private fun hold(left: Key, right: Key, down: Boolean) {
        if (down) {
            heldKeys.add(left.code)
        } else {
            heldKeys.remove(left.code)
            heldKeys.remove(right.code)
        }
    }

    fun moved(x: Float, y: Float) {
        mouse = Position(x.px, y.px)
        guarded("a cursor move", Unit) { context(mouse) { WebBackend.root.component.mouseMove() } }
    }

    fun pressed(button: MouseButton): Boolean {
        heldButtons.add(button.ordinal)
        return guarded("a mouse press", false) { context(mouse, modifiers) { WebBackend.root.component.mouseDown(button) } }
    }

    fun released(button: MouseButton): Boolean {
        heldButtons.remove(button.ordinal)
        return guarded("a mouse release", false) { context(mouse, modifiers) { WebBackend.root.component.mouseUp(button) } }
    }

    fun scrolled(amount: Double, horizontal: Boolean) {
        guarded("a scroll", Unit) {
            context(mouse) {
                if (horizontal) WebBackend.root.component.mouseScrollHorizontal(amount) else WebBackend.root.component.mouseScrollVertical(amount)
            }
        }
    }

    fun keyPressed(key: Key, scanCode: Int): Boolean {
        heldKeys.add(key.code)
        return guarded("a key press", false) { context(modifiers) { WebBackend.root.component.keyDown(key, scanCode) } }
    }

    fun keyReleased(key: Key, scanCode: Int): Boolean {
        heldKeys.remove(key.code)
        return guarded("a key release", false) { context(modifiers) { WebBackend.root.component.keyUp(key, scanCode) } }
    }

    /**
     * A typed character, arriving as a code point.
     *
     * The framework takes one `Char`, so a character outside the basic plane - which a browser
     * happily reports as a single code point - has to be split into its surrogate pair and handed
     * over as two units. Anything else would truncate it to the high surrogate.
     */
    fun typed(codePoint: Int): Boolean {
        val units = when {
            codePoint < 0 || codePoint > MAX_CODE_POINT -> return false
            Character.isBmpCodePoint(codePoint) -> charArrayOf(codePoint.toChar())
            else -> charArrayOf(Character.highSurrogate(codePoint), Character.lowSurrogate(codePoint))
        }
        var consumed = false
        for (unit in units) consumed = guarded("a typed character", false) { WebBackend.root.component.charTyped(unit) } || consumed
        return consumed
    }

    fun dropped(files: Array<Path>) {
        guarded("a file drop", Unit) { context(mouse) { WebBackend.root.component.dropFiles(files) } }
    }

    /** Focus loss or disconnect: nothing may stay held down. */
    fun blur() {
        heldKeys.clear()
        heldButtons.clear()
        mouse = Position(0.px, 0.px)
    }

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (throwable: Throwable) {
            dslLogger.error("web input: $what failed", throwable)
            fallback
        }

    private const val MAX_CODE_POINT = 0x10FFFF
}
