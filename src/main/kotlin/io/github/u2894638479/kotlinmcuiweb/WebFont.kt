package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.text.DslFont
import io.github.u2894638479.kotlinmcui.text.DslGlyph
import io.github.u2894638479.kotlinmcui.text.DslRenderableChar

/**
 * The backend's font: advances only.
 *
 * The framework measures text with [DslFont.glyph] and lays it out itself, so a backend that does
 * not draw on this side needs nothing but an advance per character - and the advance has to be the
 * one the client will actually use, because the positions are computed here and only *drawn* there.
 *
 * [ADVANCES] is therefore an approximation of the game's font, not a measurement of the browser's.
 * It is honest for what this object is used for today (recording and measuring frames) and is the
 * one thing that has to change when the client gets real glyphs: either the same font file is served
 * to the browser, or the game's glyphs are rasterised into an atlas whose advances are these - in
 * both cases this table is replaced by the real metrics and nothing else in the format moves.
 *
 * [DslGlyph.boldOffset] and [DslGlyph.shadowOffset] are the framework's own default offsets, which
 * is what the in-game backends report too.
 */
internal class WebFont(@Suppress("UNUSED_PARAMETER") name: String?) : DslFont<Unit> {
    private val glyphs = HashMap<Int, DslGlyph>()
    private var loadedVersion = -1

    override val lineHeight: Measure = LINE_HEIGHT.px

    /**
     * The advance of [code], from the client's own measurements when it has reported any.
     *
     * The glyph cache is dropped whenever [FontMetrics] changes: a reported advance arrives once per
     * connection, and every glyph measured before it would otherwise keep the approximation forever.
     */
    override fun glyph(code: Int): DslGlyph {
        if (FontMetrics.version != loadedVersion) {
            glyphs.clear()
            loadedVersion = FontMetrics.version
        }
        return glyphs.getOrPut(code) { Metrics(advanceOf(code).px) }
    }

    private class Metrics(advance: Measure) : DslGlyph {
        override val normalAdvance = advance
        override val boldOffset = 1.px
        override val shadowOffset = 1.px
    }

    context(renderParam: Unit)
    override fun renderChar(char: DslRenderableChar, x: Measure, y: Measure, effectLeft: Measure, effectRight: Measure) =
        Recorder.text(char, x, y, effectLeft, effectRight)

    private fun advanceOf(code: Int): Float =
        FontMetrics.advanceOf(code, LINE_HEIGHT.toFloat()) ?: approximationOf(code)

    private fun approximationOf(code: Int): Float = when {
        code == SPACE -> SPACE_ADVANCE
        code < 0x20 -> 0f
        code in NARROW -> NARROW_ADVANCE
        code in WIDE -> WIDE_ADVANCE
        // A script this table knows nothing about - CJK, Hangul, kana - is drawn about one em wide,
        // so a glyph the client has not measured yet is wider than a Latin default, not narrower.
        code > WIDE_SCRIPT_FLOOR -> LINE_HEIGHT.toFloat()
        else -> DEFAULT_ADVANCE
    }

    private companion object {
        const val LINE_HEIGHT = 9
        const val SPACE = 0x20
        const val SPACE_ADVANCE = 4f
        const val NARROW_ADVANCE = 2f
        const val DEFAULT_ADVANCE = 6f
        const val WIDE_ADVANCE = 9f

        /** Everything from here up is a wide script: CJK, kana, Hangul. */
        const val WIDE_SCRIPT_FLOOR = 0x1100

        /** The narrow and wide characters of the game's font, near enough to lay a page out. */
        val NARROW = "!',.:;iIl|`".map { it.code }.toSet()
        val WIDE = "MW@m".map { it.code }.toSet()
    }
}
