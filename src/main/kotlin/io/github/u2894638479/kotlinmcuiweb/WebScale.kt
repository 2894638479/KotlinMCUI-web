package io.github.u2894638479.kotlinmcuiweb

/**
 * The default DSL scale this backend gives a root for a canvas backing size.
 *
 * It is a policy of this backend, not a fact about the drawing API: the framework's root takes the
 * scale explicitly, and the renderer has no `guiScale` to ask. Input here is already the output
 * target's size - CSS pixels have been converted by the browser to canvas backing pixels - so the
 * ratio the client uses for input is the same one this policy sees.
 *
 * The same policy the Swing window backend uses, for the same reason: a page is authored against a
 * 320x240-ish canvas and there is no game to ask, so the scale is whatever makes that fit, with a
 * floor of one so a small surface does not shrink text to nothing. It is deliberately *fractional* -
 * a browser and Java2D both antialias, so rounding to whole numbers (which the game does because its
 * font is a bitmap) would only throw away resolution.
 */
internal object WebScale {
    /**
     * The scale for a canvas backing size in output pixels.
     *
     * A zero or minimized surface still has to produce a usable scale: the framework's contract says
     * scale is never zero and that "nothing to draw" is expressed by the empty rect, not by pausing
     * through scale. The rect is written from the same size, so a zero-sized frame stays an empty
     * output at the ordinary minimum scale rather than an invalid one.
     */
    fun of(width: Int, height: Int): Double {
        if (width <= 0 || height <= 0) return MINIMUM
        return maxOf(MINIMUM, minOf(width / BASE_WIDTH, height / BASE_HEIGHT))
    }

    private const val BASE_WIDTH = 320.0
    private const val BASE_HEIGHT = 240.0
    private const val MINIMUM = 1.0
}
