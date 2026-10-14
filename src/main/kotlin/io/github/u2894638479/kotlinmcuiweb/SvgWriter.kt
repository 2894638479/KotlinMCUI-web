package io.github.u2894638479.kotlinmcuiweb

import java.io.File
import java.util.Locale

/**
 * Turns one recorded frame into SVG.
 *
 * It exists for two reasons. It is the cheap end-to-end check of the format - the second reader of
 * the buffer after the report's walker, so a record type whose stride is wrong shows up as a broken
 * picture rather than as a silent desynchronisation later - and it is a picture of a page that can be
 * opened in a browser and inspected element by element, which a screenshot cannot offer.
 *
 * Everything is projected by hand into screen space rather than emitted as nested SVG transforms, so
 * a perspective in the frame comes out as the trapezoid it is. Clips are emitted as real `<clipPath>`
 * polygons for the same reason: a rotated or projected clip is a quadrilateral, and SVG clips to
 * shapes, not to axis-aligned rectangles - which is exactly the freedom the Minecraft backends do not
 * have.
 *
 * Two deliberate approximations, both noted in the file itself: a quad whose four corner colours are
 * not affine-related is emitted as the two triangles the game's backends draw (not the bilinear field
 * a canvas can produce), and a textured quad is a placeholder carrying its asset id, because this
 * writer has no texture server.
 */
internal object SvgWriter {
    fun write(frame: IntArray, size: Int, width: Int, height: Int, file: File) {
        val svg = StringBuilder(frame.size * 8)
        svg.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        svg.append("<!-- kotlinmcui-web frame snapshot: ${size} words, ${size * 4} bytes -->\n")
        svg.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">""")
        svg.append('\n')
        svg.append("""<rect width="$width" height="$height" fill="#101014"/>""").append('\n')
        val perOp = LongArray(Op.COUNT)
        val clips = ArrayList<String>()
        var transform = Matrix()
        val stack = ArrayList<Matrix>()
        var at = 0
        var openClips = 0
        while (at < size) {
            val op = frame[at]
            val stride = Op.strideAt(frame, at)
            if (op in 0 until Op.COUNT) perOp[op] += stride.toLong()
            when (op) {
                Op.QUAD -> quad(svg, frame, at, transform)
                Op.TRIANGLES -> triangles(svg, frame, at, transform)
                Op.TEXT -> text(svg, frame, at, transform)
                Op.BUTTON -> box(svg, frame, at, transform, if (frame[at + 5] and 1 != 0) "#6a6a6a" else "#4a4a4a", "button")
                Op.SLOT -> box(svg, frame, at, transform, "#2b2b30", "slot")
                Op.CONTAINER -> box(svg, frame, at, transform, "#2e2e34", "container")
                Op.TOOLTIP -> box(svg, frame, at, transform, "#1b1b28", "tooltip")
                Op.DEFAULT_BACKGROUND -> box(svg, frame, at, transform, "#1d1a16", "background")
                Op.ITEM -> item(svg, frame, at, transform)
                Op.CLIP_PUSH -> {
                    if (bounds(frame, at + 1)?.let { rect ->
                            val corners = corners(rect, transform)
                            val id = "clip${clips.size}"
                            svg.append("<clipPath id=\"$id\"><polygon points=\"${rectPoints(corners)}\"/></clipPath>\n")
                            svg.append("<g clip-path=\"url(#$id)\">\n")
                            true
                        } ?: false
                    ) {
                        clips += ""
                        openClips += 1
                    }
                }
                Op.CLIP_POP -> if (openClips > 0) {
                    svg.append("</g>\n")
                    openClips -= 1
                }
                Op.TRANSFORM_PUSH -> {
                    stack += transform
                    transform = transform * Matrix.of(frame, at + 1)
                }
                Op.TRANSFORM_POP -> if (stack.isNotEmpty()) transform = stack.removeAt(stack.size - 1)
            }
            at += stride
        }
        repeat(openClips) { svg.append("</g>\n") }
        svg.append("</svg>\n")
        file.parentFile?.mkdirs()
        file.writeText(svg.toString())
    }

    private fun quad(svg: StringBuilder, frame: IntArray, at: Int, transform: Matrix) {
        val rect = bounds(frame, at + 1) ?: return
        val lt = frame[at + 5]
        val rt = frame[at + 6]
        val lb = frame[at + 7]
        val rb = frame[at + 8]
        val corners = corners(rect, transform)
        val asset = frame[at + 9]
        val title = if (asset == Op.NO_ASSET) "" else "<title>asset #$asset</title>"
        if (asset != Op.NO_ASSET) {
            // No pixels on this side: the placeholder marks where a texture goes and carries its id.
            svg.append("<polygon points=\"${rectPoints(corners)}\" fill=\"${css(mix(lt, rt, lb, rb))}\" fill-opacity=\"0.5\" stroke=\"#8a8a8a\" stroke-dasharray=\"3 3\">$title</polygon>\n")
            return
        }
        if (lt == rt && lt == lb && lt == rb) {
            svg.append("<polygon points=\"${rectPoints(corners)}\" fill=\"${css(lt)}\">$title</polygon>\n")
            return
        }
        // The two triangles the game's backends draw, in the same (lt, rt, lb) and (rt, rb, lb) order.
        svg.append("<polygon points=\"${points(corners[0], corners[1], corners[2])}\" fill=\"${css(mix(lt, rt, lb, lb))}\">$title</polygon>\n")
        svg.append("<polygon points=\"${points(corners[1], corners[3], corners[2])}\" fill=\"${css(mix(rt, rb, lb, lb))}\"/>\n")
    }

    private fun triangles(svg: StringBuilder, frame: IntArray, at: Int, transform: Matrix) {
        val count = frame[at + 1]
        val textured = frame[at + 2] != 0
        val stride = if (textured) 4 else 2
        val verticesAt = at + 4
        val coloursAt = verticesAt + count * stride
        var i = 0
        while (i + 2 < count) {
            val projected = (0..2).map { step ->
                val base = verticesAt + (i + step) * stride
                transform.project(Float.fromBits(frame[base]), Float.fromBits(frame[base + 1]))
            }
            val colour = mix(frame[coloursAt + i], frame[coloursAt + i + 1], frame[coloursAt + i + 2], 0)
            svg.append("<polygon points=\"${points(projected)}\" fill=\"${css(colour)}\"/>\n")
            i += 3
        }
    }

    private fun text(svg: StringBuilder, frame: IntArray, at: Int, transform: Matrix) {
        val code = frame[at + 1]
        val position = transform.project(Float.fromBits(frame[at + 2]), Float.fromBits(frame[at + 3]))
        val style = frame[at + 4]
        val colour = frame[at + 5]
        val size = Float.fromBits(frame[at + 8])
        val character = runCatching { String(Character.toChars(code)) }.getOrNull() ?: return
        val escaped = escape(character)
        val x = position.first
        val baseline = position.second + size * ASCENT
        val attributes = buildString {
            append("x=\"${number(x)}\" y=\"${number(baseline)}\" font-size=\"${number(size)}\"")
            append(" font-family=\"monospace\" fill=\"${css(colour)}\"")
            if (style and 1 != 0) append(" font-style=\"italic\"")
            if (style and (1 shl 1) != 0) append(" font-weight=\"bold\"")
        }
        if (style and (1 shl 5) != 0) {
            svg.append("<text x=\"${number(x + 1)}\" y=\"${number(baseline + 1)}\" font-size=\"${number(size)}\" font-family=\"monospace\" fill=\"#000000\">$escaped</text>\n")
        }
        svg.append("<text $attributes>${if (style and (1 shl 4) != 0) "?" else escaped}</text>\n")
        val left = Float.fromBits(frame[at + 6])
        val right = Float.fromBits(frame[at + 7])
        val width = (right - left).coerceAtLeast(size * 0.5f)
        if (style and (1 shl 2) != 0) {
            svg.append("<rect x=\"${number(left)}\" y=\"${number(position.second + size * UNDERLINE)}\" width=\"${number(width)}\" height=\"1\" fill=\"${css(colour)}\"/>\n")
        }
        if (style and (1 shl 3) != 0) {
            svg.append("<rect x=\"${number(left)}\" y=\"${number(position.second + size * STRIKE)}\" width=\"${number(width)}\" height=\"1\" fill=\"${css(colour)}\"/>\n")
        }
    }

    private fun item(svg: StringBuilder, frame: IntArray, at: Int, transform: Matrix) {
        val rect = bounds(frame, at + 1) ?: return
        val corners = corners(rect, transform)
        val asset = frame[at + 5]
        val count = frame[at + 6]
        svg.append("<polygon points=\"${rectPoints(corners)}\" fill=\"#3a3a44\" stroke=\"#8a8a8a\"><title>item asset #$asset x$count</title></polygon>\n")
    }

    private fun box(svg: StringBuilder, frame: IntArray, at: Int, transform: Matrix, fill: String, name: String) {
        val rect = bounds(frame, at + 1) ?: return
        svg.append("<polygon points=\"${rectPoints(corners(rect, transform))}\" fill=\"$fill\"><title>$name</title></polygon>\n")
    }

    /** The four corners of a record's rectangle as floats, or null when it is not fully measured. */
    private fun bounds(frame: IntArray, at: Int): FloatArray? {
        val out = FloatArray(4)
        for (i in 0..3) {
            val value = Float.fromBits(frame[at + i])
            if (value.isNaN()) return null
            out[i] = value
        }
        return out
    }

    /** left, top, right, bottom in the record order, projected to screen space. */
    private fun corners(rect: FloatArray, transform: Matrix): List<Pair<Float, Float>> = listOf(
        transform.project(rect[0], rect[1]),
        transform.project(rect[2], rect[1]),
        transform.project(rect[0], rect[3]),
        transform.project(rect[2], rect[3]),
    )

    private fun points(vararg corners: Pair<Float, Float>): String =
        corners.joinToString(" ") { "${number(it.first)},${number(it.second)}" }

    private fun points(corners: List<Pair<Float, Float>>): String = points(*corners.toTypedArray())

    /**
     * A rectangle's corners in perimeter order: left-top, right-top, right-bottom, left-bottom.
     *
     * The record order is left-top, right-top, left-bottom, right-bottom, which as a polygon path
     * would draw the two diagonals; only the filled area would come out right.
     */
    private fun rectPoints(corners: List<Pair<Float, Float>>): String =
        points(corners[0], corners[1], corners[3], corners[2])

    /** Numbers are formatted in the invariant locale: a decimal comma would not be valid SVG. */
    private fun number(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(Locale.US, "%.2f", value)

    private fun css(packed: Int): String {
        val r = (packed ushr 24) and 0xFF
        val g = (packed ushr 16) and 0xFF
        val b = (packed ushr 8) and 0xFF
        val a = packed and 0xFF
        return if (a == 255) {
            String.format(Locale.US, "#%02x%02x%02x", r, g, b)
        } else {
            String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0)
        }
    }

    /** The average of up to four packed colours: what a triangle of that set is filled with here. */
    private fun mix(a: Int, b: Int, c: Int, d: Int): Int {
        var r = 0
        var g = 0
        var bl = 0
        var al = 0
        var count = 0
        for (packed in intArrayOf(a, b, c, d)) {
            if (packed == 0) continue
            r += (packed ushr 24) and 0xFF
            g += (packed ushr 16) and 0xFF
            bl += (packed ushr 8) and 0xFF
            al += packed and 0xFF
            count += 1
        }
        if (count == 0) return 0
        return (((r / count) and 0xFF) shl 24) or (((g / count) and 0xFF) shl 16) or
            (((bl / count) and 0xFF) shl 8) or ((al / count) and 0xFF)
    }

    private fun escape(text: String): String = buildString {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(character)
            }
        }
    }

    /** Row-major 3x3, the framework's own layout, with the homogeneous divide a perspective needs. */
    private class Matrix(
        private val m00: Float = 1f, private val m01: Float = 0f, private val m02: Float = 0f,
        private val m10: Float = 0f, private val m11: Float = 1f, private val m12: Float = 0f,
        private val m20: Float = 0f, private val m21: Float = 0f, private val m22: Float = 1f,
    ) {
        /** `this * other`: [other] applies first, which is what a nested push means. */
        operator fun times(other: Matrix) = Matrix(
            m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
            m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
            m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
            m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
            m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
            m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
            m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
            m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
            m20 * other.m02 + m21 * other.m12 + m22 * other.m22,
        )

        fun project(x: Float, y: Float): Pair<Float, Float> {
            val w = m20 * x + m21 * y + m22
            val px = m00 * x + m01 * y + m02
            val py = m10 * x + m11 * y + m12
            return if (w == 0f) px to py else px / w to py / w
        }

        companion object {
            fun of(words: IntArray, at: Int) = Matrix(
                Float.fromBits(words[at]), Float.fromBits(words[at + 1]), Float.fromBits(words[at + 2]),
                Float.fromBits(words[at + 3]), Float.fromBits(words[at + 4]), Float.fromBits(words[at + 5]),
                Float.fromBits(words[at + 6]), Float.fromBits(words[at + 7]), Float.fromBits(words[at + 8]),
            )
        }
    }

    /** Where the baseline sits inside the box a glyph is given, as a fraction of its size. */
    private const val ASCENT = 0.8f
    private const val UNDERLINE = 0.95f
    private const val STRIKE = 0.45f
}
