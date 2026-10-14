package io.github.u2894638479.kotlinmcuiweb

/**
 * The glyph advances the client reports.
 *
 * Layout happens on the server, so the server has to know how wide the client will draw a character -
 * and only the client can answer that, because it is the one with the font. So the browser measures a
 * batch of code points once, at a reference size, and sends them; everything then scales linearly,
 * exactly as the framework does when it applies `char.size / lineHeight`.
 *
 * This is what makes text agree without shipping a font file and without assuming the two sides
 * picked the same one: the framework still lays out, it just lays out against the metrics of the
 * renderer that will draw the result. Until a client reports anything, the approximation in [WebFont]
 * stands - a measurement run or a test never sends metrics, and a page must still render.
 *
 * [version] changes on every update so a cached glyph can tell that its advance is stale.
 */
internal object FontMetrics {
    /** Advances in the client's pixels, per code point, at [reference] pixels of font size. */
    @Volatile
    private var advances: Map<Int, Float> = emptyMap()

    @Volatile
    private var reference: Float = 0f

    @Volatile
    var version: Int = 0
        private set

    val hasMetrics: Boolean get() = reference > 0f && advances.isNotEmpty()

    fun update(reference: Float, measured: Map<Int, Float>) {
        if (reference <= 0f || measured.isEmpty()) return
        this.reference = reference
        advances = measured
        version += 1
    }

    /**
     * The advance of [code] at [lineHeight], or null when the client has not measured it.
     *
     * A code point the client did not measure (a script it never sent a sample for) falls back to the
     * approximation rather than to zero, because zero would stack the rest of the line on top of it.
     */
    fun advanceOf(code: Int, lineHeight: Float): Float? {
        val width = advances[code] ?: return null
        return width * (lineHeight / reference)
    }
}
