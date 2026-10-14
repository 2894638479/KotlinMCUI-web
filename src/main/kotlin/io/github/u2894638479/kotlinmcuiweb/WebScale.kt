package io.github.u2894638479.kotlinmcuiweb

/**
 * The GUI scale this backend reports for a surface size.
 *
 * The same policy the Swing window backend uses, for the same reason: a page is authored against a
 * 320x240-ish canvas and there is no game to ask, so the scale is whatever makes that fit, with a
 * floor of one so a small surface does not shrink text to nothing. It is deliberately *fractional* -
 * a browser and Java2D both antialias, so rounding to whole numbers (which the game does because its
 * font is a bitmap) would only throw away resolution.
 */
internal object WebScale {
    fun of(width: Int, height: Int): Double =
        maxOf(MINIMUM, minOf(width / BASE_WIDTH, height / BASE_HEIGHT))

    private const val BASE_WIDTH = 320.0
    private const val BASE_HEIGHT = 240.0
    private const val MINIMUM = 1.0
}
