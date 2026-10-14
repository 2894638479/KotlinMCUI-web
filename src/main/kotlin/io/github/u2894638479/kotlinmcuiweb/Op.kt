package io.github.u2894638479.kotlinmcuiweb

/**
 * The wire format of one recorded frame.
 *
 * A frame is a flat `IntArray` of `[opcode, payload...]` records, so everything a frame carries is
 * 32-bit wide: floats travel as their raw bits. Two consequences are deliberate.
 *
 * The recorder writes this buffer directly and it *is* the payload of the binary transport, so there
 * is no separate encoding step to keep in sync. And a frame-to-frame patch is a word comparison:
 * equality, common prefix and common suffix are all `Int` compares, which is what makes the delta
 * encoding cheap enough to do on every frame.
 *
 * Strides are read from [strideAt] rather than kept in a table, because the two variable-length
 * records ([QUAD] with and without a texture, and [TRIANGLES]) carry their own shape in the first
 * words of the payload. The recorder, the report, the SVG writer and the client all walk a frame
 * through the same function.
 */
internal object Op {
    /** Frame end: everything before it belongs to the frame, and the client swaps buffers here. */
    const val FLUSH = 0

    /** `[op, x0, y0, x1, y1, lt, rt, lb, rb, asset]` plus `[u0, v0, u1, v1]` when [asset] is not -1. */
    const val QUAD = 1

    /** `[op, vertices, textured, asset]` then `vertices * (2|4)` coordinates then `vertices` colours. */
    const val TRIANGLES = 2

    /** `[op, l, t, r, b]`; nested clips intersect, and the client keeps a stack. */
    const val CLIP_PUSH = 3
    const val CLIP_POP = 4

    /** `[op, m00, m01, m02, m10, m11, m12, m20, m21, m22]`, row-major, the framework's own layout. */
    const val TRANSFORM_PUSH = 5
    const val TRANSFORM_POP = 6

    /** `[op, code, x, y, style, colour, effectLeft, effectRight, size]`. */
    const val TEXT = 7

    /** `[op, l, t, r, b, flags, colour]`; flags is bit 0 highlighted, bit 1 active. */
    const val BUTTON = 8

    /** `[op, l, t, r, b]`, and the same shape for the three below it. */
    const val SLOT = 9
    const val CONTAINER = 10
    const val TOOLTIP = 11
    const val DEFAULT_BACKGROUND = 12

    /** `[op, l, t, r, b, asset, count, damage, flags]`; flags is bit 0 has damage, bit 1 enchanted. */
    const val ITEM = 13

    /**
     * `[op, index, length, ...chars]`: the definition of an asset id.
     *
     * An id is defined once per session and referenced by `index` afterwards, which is what keeps a
     * texture reference at one word instead of a forty-character string on every quad. A client that
     * joins later gets the whole table again with the keyframe; a frame on its own only ever
     * references what it defined.
     */
    const val TEXTURE = 14

    const val COUNT = 15

    /** The asset reference of an untextured record. */
    const val NO_ASSET = -1

    /** The words a record occupies, its opcode included. */
    fun strideAt(words: IntArray, at: Int): Int = when (words[at]) {
        QUAD -> if (words[at + 9] == NO_ASSET) 10 else 14
        TRIANGLES -> {
            val vertices = words[at + 1]
            4 + vertices * (if (words[at + 2] != 0) 4 else 2) + vertices
        }
        CLIP_PUSH -> 5
        TRANSFORM_PUSH -> 10
        TEXT -> 9
        BUTTON -> 7
        SLOT, CONTAINER, TOOLTIP, DEFAULT_BACKGROUND -> 5
        ITEM -> 9
        TEXTURE -> 3 + words[at + 2]
        else -> 1
    }

    fun nameOf(op: Int): String = when (op) {
        FLUSH -> "flush"
        QUAD -> "quad"
        TRIANGLES -> "triangles"
        CLIP_PUSH -> "clip+"
        CLIP_POP -> "clip-"
        TRANSFORM_PUSH -> "transform+"
        TRANSFORM_POP -> "transform-"
        TEXT -> "text"
        BUTTON -> "button"
        SLOT -> "slot"
        CONTAINER -> "container"
        TOOLTIP -> "tooltip"
        DEFAULT_BACKGROUND -> "background"
        ITEM -> "item"
        TEXTURE -> "texture"
        else -> "op$op"
    }

    /** The [DslCharStyle] bits as they travel: the framework's own order, so a client can reuse them. */
    fun styleOf(style: io.github.u2894638479.kotlinmcui.text.DslCharStyle): Int =
        (if (style.isItalic) 1 else 0) or
            (if (style.isBold) 1 shl 1 else 0) or
            (if (style.isUnderlined) 1 shl 2 else 0) or
            (if (style.isStrikeThrough) 1 shl 3 else 0) or
            (if (style.isObfuscated) 1 shl 4 else 0) or
            (if (style.isShadowed) 1 shl 5 else 0)
}
