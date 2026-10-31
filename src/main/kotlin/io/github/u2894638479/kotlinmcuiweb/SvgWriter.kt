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
 * have. A clip the client would treat as empty (unmeasured, nonfinite, reversed, singular transform,
 * eye-plane crossing) becomes an empty clip path here too, never an unclipped fallback.
 *
 * This writer is a DIAGNOSTIC, not a renderer. It has no texture server and no pixels, so textured
 * records are checkerboard polygons carrying their asset id, geometry and nominal size; that checker is a
 * marker for "a texture was sampled here", never a claim about the pixels. A four-corner bilinear
 * field and a barycentric triangle field cannot be expressed by one SVG fill, so they are sampled on
 * a coarse grid of flat-coloured polygons (SVG has no four-corner interpolant) and are therefore an
 * approximation. The browser client is the conforming consumer; this file must not be read as a
 * pixel-exact reference.
 */
internal object SvgWriter {
    fun write(frame: IntArray, size: Int, width: Int, height: Int, file: File) {
        val svg = StringBuilder(frame.size * 8)
        svg.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        svg.append("<!-- kotlinmcui-web frame snapshot: ${size} words, ${size * 4} bytes -->\n")
        svg.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">""")
        svg.append('\n')
        svg.append("""<rect width="$width" height="$height" fill="#101014"/>""").append('\n')
        // Explicit texture marker for this diagnostic artifact: no real pixels exist here, so a
        // textured draw must never turn into a colour fill. The checker is intentional.
        svg.append("""<defs><pattern id="texture-checker" patternUnits="userSpaceOnUse" width="8" height="8">""")
        svg.append("""<rect width="8" height="8" fill="#d000d0"/><rect width="4" height="4" fill="#181818"/>""")
        svg.append("""<rect x="4" y="4" width="4" height="4" fill="#181818"/></pattern></defs>""").append('\n')
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
                    // One group per push, whatever the rectangle is, so the matching CLIP_POP always
                    // closes the group it opened. An unusable clip becomes an empty clipPath rather
                    // than being skipped: an unclipped diagnostic would show content the browser
                    // does not, which is the one thing a diagnostic must never do.
                    val id = "clip${clips.size}"
                    val cornerList = clipCorners(frame, at, transform)
                    if (cornerList != null) {
                        svg.append("<clipPath id=\"$id\"><polygon points=\"${points(cornerList)}\"/></clipPath>\n")
                    } else {
                        svg.append("<clipPath id=\"$id\"><polygon points=\"0,0 0,0 0,0\"/></clipPath>\n")
                    }
                    svg.append("<g clip-path=\"url(#$id)\">\n")
                    clips += id
                    openClips += 1
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
        if (asset != Op.NO_ASSET) {
            // No pixels on this side: an explicit checker pattern plus the asset, UV and nominal
            // size it would have sampled. It is a diagnostic marker, not a sampled texture.
            val u0 = Float.fromBits(frame[at + 10])
            val v0 = Float.fromBits(frame[at + 11])
            val u1 = Float.fromBits(frame[at + 12])
            val v1 = Float.fromBits(frame[at + 13])
            val nominalWidth = Float.fromBits(frame[at + 14])
            val nominalHeight = Float.fromBits(frame[at + 15])
            svg.append(
                "<polygon points=\"${rectPoints(corners)}\" fill=\"url(#texture-checker)\" " +
                    "stroke=\"#d8d8d8\" stroke-dasharray=\"3 3\">" +
                    "<title>texture asset #$asset uv=[${number(u0)},${number(v0)},${number(u1)},${number(v1)}] " +
                    "nominal=${number(nominalWidth)}x${number(nominalHeight)} " +
                    "(diagnostic checkerboard: no pixels or tint in SVG)</title></polygon>\n",
            )
            return
        }
        if (lt == rt && lt == lb && lt == rb) {
            svg.append("<polygon points=\"${rectPoints(corners)}\" fill=\"${css(lt)}\"/>\n")
            return
        }
        // A four-corner bilinear field has no single SVG fill. Sample it on a coarse grid in the
        // quad's ORIGINAL coordinates, project each grid vertex, and fill each cell with the field
        // at its centre. The result is an approximation (the writer says so) but it follows the
        // same local coordinate system, including a projective transform.
        val colours = intArrayOf(lt, rt, lb, rb)
        svg.append("<g shape-rendering=\"crispEdges\"><title>bilinear quad field sampled on a ${MESH}x$MESH grid (approximation)</title>\n")
        for (i in 0 until MESH) {
            for (j in 0 until MESH) {
                val u0 = i.toDouble() / MESH
                val u1 = (i + 1).toDouble() / MESH
                val v0 = j.toDouble() / MESH
                val v1 = (j + 1).toDouble() / MESH
                val cu = (u0 + u1) / 2
                val cv = (v0 + v1) / 2
                val cell = listOf(
                    transform.project(lerp(rect[0], rect[2], u0), lerp(rect[1], rect[3], v0)),
                    transform.project(lerp(rect[0], rect[2], u1), lerp(rect[1], rect[3], v0)),
                    transform.project(lerp(rect[0], rect[2], u1), lerp(rect[1], rect[3], v1)),
                    transform.project(lerp(rect[0], rect[2], u0), lerp(rect[1], rect[3], v1)),
                )
                val colour = fieldColour(colours, doubleArrayOf(
                    (1 - cu) * (1 - cv), cu * (1 - cv), (1 - cu) * cv, cu * cv,
                ))
                svg.append("<polygon points=\"${points(cell)}\" fill=\"${css(colour)}\"/>\n")
            }
        }
        svg.append("</g>\n")
    }

    private fun triangles(svg: StringBuilder, frame: IntArray, at: Int, transform: Matrix) {
        val count = frame[at + 1]
        val textured = frame[at + 2] != 0
        val stride = if (textured) 4 else 2
        val verticesAt = at + 4
        val coloursAt = verticesAt + count * stride
        val asset = frame[at + 3]
        val nominalWidth = if (textured) Float.fromBits(frame[coloursAt + count]) else 0f
        val nominalHeight = if (textured) Float.fromBits(frame[coloursAt + count + 1]) else 0f
        var i = 0
        while (i + 2 < count) {
            val projected = (0..2).map { step ->
                val base = verticesAt + (i + step) * stride
                transform.project(Float.fromBits(frame[base]), Float.fromBits(frame[base + 1]))
            }
            if (textured) {
                // A textured triangle keeps its texture marker even without pixels; it never falls
                // back to the per-vertex colour average.
                svg.append(
                    "<polygon points=\"${points(projected)}\" fill=\"url(#texture-checker)\" " +
                        "stroke=\"#d8d8d8\" stroke-dasharray=\"3 3\">" +
                        "<title>texture asset #$asset nominal=${number(nominalWidth)}x${number(nominalHeight)} " +
                        "(diagnostic checkerboard: no pixels or tint in SVG)</title></polygon>\n",
                )
            } else {
                val local = (0..2).map { step ->
                    val base = verticesAt + (i + step) * stride
                    Float.fromBits(frame[base]) to Float.fromBits(frame[base + 1])
                }
                val colours = intArrayOf(
                    frame[coloursAt + i], frame[coloursAt + i + 1], frame[coloursAt + i + 2],
                )
                meshTriangle(svg, local, colours, transform)
            }
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

    /**
     * A clip's projected quadrilateral in perimeter order, or null when the clip is empty.
     *
     * The same rule the client applies: the four edges must be finite with a strictly positive
     * extent, the transform must be finite and invertible, and every corner must stay in front of
     * the eye. A null result is written as an empty clip, never as no clip at all.
     */
    private fun clipCorners(frame: IntArray, at: Int, transform: Matrix): List<Pair<Float, Float>>? {
        val rect = bounds(frame, at + 1) ?: return null
        if (!rect.all { it.isFinite() }) return null
        if (!(rect[2] - rect[0]).isFinite() || !(rect[3] - rect[1]).isFinite()) return null
        if (!(rect[2] > rect[0]) || !(rect[3] > rect[1])) return null
        if (!transform.isInvertible()) return null
        val corners = listOf(
            rect[0] to rect[1],
            rect[2] to rect[1],
            rect[2] to rect[3],
            rect[0] to rect[3],
        )
        return corners.map { (x, y) -> transform.projectPositive(x, y) ?: return null }
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

    private fun lerp(a: Float, b: Float, t: Double): Float = (a + (b - a) * t).toFloat()

    /**
     * The field at the given vertex weights, as a straight packed colour.
     *
     * Premultiply each corner, weight the premultiplied RGB and the alpha, then divide the colour
     * back out by the resulting alpha for SVG's straight `rgba()`. This is the same arithmetic the
     * browser applies; the SVG only approximates it by using one flat sample per mesh cell.
     */
    private fun fieldColour(colours: IntArray, weights: DoubleArray): Int {
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var alpha = 0.0
        for (index in colours.indices) {
            val packed = colours[index]
            val a = (packed and 0xFF).toDouble()
            red += ((packed ushr 24) and 0xFF) * a / 255.0 * weights[index]
            green += ((packed ushr 16) and 0xFF) * a / 255.0 * weights[index]
            blue += ((packed ushr 8) and 0xFF) * a / 255.0 * weights[index]
            alpha += a * weights[index]
        }
        if (alpha <= 0.0) return 0
        val scale = 255.0 / alpha
        return (channel(red * scale) shl 24) or (channel(green * scale) shl 16) or
            (channel(blue * scale) shl 8) or channel(alpha)
    }

    private fun channel(value: Double): Int =
        Math.round(value).toInt().coerceIn(0, 255)

    /**
     * A triangle's barycentric field, sampled on a coarse barycentric grid.
     *
     * SVG has no barycentric fill, so each sub-triangle is a flat polygon coloured at its centroid.
     * The geometry is exact - every sub-triangle is a real sub-triangle of the recorded one, placed
     * through the same projection - while the colour is an approximation, and the group says so.
     */
    private fun meshTriangle(
        svg: StringBuilder,
        local: List<Pair<Float, Float>>,
        colours: IntArray,
        transform: Matrix,
    ) {
        val a = local[0]
        val b = local[1]
        val c = local[2]
        fun point(i: Int, j: Int): Pair<Float, Float> {
            val wa = (MESH - i - j).toFloat() / MESH
            val wb = i.toFloat() / MESH
            val wc = j.toFloat() / MESH
            return transform.project(
                a.first * wa + b.first * wb + c.first * wc,
                a.second * wa + b.second * wb + c.second * wc,
            )
        }
        fun weights(i: Int, j: Int): DoubleArray =
            doubleArrayOf((MESH - i - j).toDouble() / MESH, i.toDouble() / MESH, j.toDouble() / MESH)
        fun emit(
            first: Pair<Float, Float>,
            second: Pair<Float, Float>,
            third: Pair<Float, Float>,
            wa: DoubleArray,
            wb: DoubleArray,
            wc: DoubleArray,
        ) {
            val colour = fieldColour(
                colours,
                doubleArrayOf(
                    (wa[0] + wb[0] + wc[0]) / 3,
                    (wa[1] + wb[1] + wc[1]) / 3,
                    (wa[2] + wb[2] + wc[2]) / 3,
                ),
            )
            svg.append("<polygon points=\"${points(first, second, third)}\" fill=\"${css(colour)}\"/>\n")
        }
        svg.append("<g shape-rendering=\"crispEdges\"><title>barycentric triangle field sampled on a ${MESH}x$MESH grid (approximation)</title>\n")
        for (i in 0 until MESH) {
            for (j in 0 until MESH - i) {
                emit(point(i, j), point(i + 1, j), point(i, j + 1),
                    weights(i, j), weights(i + 1, j), weights(i, j + 1))
                if (i + j < MESH - 1) {
                    emit(point(i + 1, j), point(i + 1, j + 1), point(i, j + 1),
                        weights(i + 1, j), weights(i + 1, j + 1), weights(i, j + 1))
                }
            }
        }
        svg.append("</g>\n")
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

        /** Whether every entry is finite and the matrix has a non-degenerate determinant. */
        fun isInvertible(): Boolean {
            if (!(m00.isFinite() && m01.isFinite() && m02.isFinite() &&
                    m10.isFinite() && m11.isFinite() && m12.isFinite() &&
                    m20.isFinite() && m21.isFinite() && m22.isFinite())
            ) {
                return false
            }
            val determinant = m00.toDouble() * (m11.toDouble() * m22 - m12.toDouble() * m21) +
                m01.toDouble() * (m12.toDouble() * m20 - m10.toDouble() * m22) +
                m02.toDouble() * (m10.toDouble() * m21 - m11.toDouble() * m20)
            return determinant.isFinite() && determinant != 0.0
        }

        /** Project one point, requiring a finite positive w; null when the eye plane is crossed. */
        fun projectPositive(x: Float, y: Float): Pair<Float, Float>? {
            val w = m20 * x + m21 * y + m22
            if (!w.isFinite() || w <= 0f) return null
            val px = m00 * x + m01 * y + m02
            val py = m10 * x + m11 * y + m12
            if (!px.isFinite() || !py.isFinite()) return null
            val dx = px / w
            val dy = py / w
            return if (dx.isFinite() && dy.isFinite()) dx to dy else null
        }

        companion object {
            fun of(words: IntArray, at: Int) = Matrix(
                Float.fromBits(words[at]), Float.fromBits(words[at + 1]), Float.fromBits(words[at + 2]),
                Float.fromBits(words[at + 3]), Float.fromBits(words[at + 4]), Float.fromBits(words[at + 5]),
                Float.fromBits(words[at + 6]), Float.fromBits(words[at + 7]), Float.fromBits(words[at + 8]),
            )
        }
    }

    /** Mesh cells per axis in the diagnostic approximation of an interpolated colour field. */
    private const val MESH = 6

    /** Where the baseline sits inside the box a glyph is given, as a fraction of its size. */
    private const val ASCENT = 0.8f
    private const val UNDERLINE = 0.95f
    private const val STRIKE = 0.45f
}
