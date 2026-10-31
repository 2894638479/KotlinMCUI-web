package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRootRenderer
import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import io.github.u2894638479.kotlinmcui.text.DslFont
import io.github.u2894638479.kotlinmcui.text.DslRenderableChar

/**
 * The frame recorder: a complete renderer that draws nothing and appends [Op] records instead.
 *
 * It is the backend's renderer, so every frame the framework walks goes through it and comes out as
 * a flat word buffer - the wire format itself, with no separate encoding step. That is also what
 * makes the report possible: the same buffer that a client would receive is the one this object can
 * count, compare with the previous frame and turn into SVG.
 *
 * ### What it deliberately does not do
 * No batching, so a scope change needs no [flush]; the backend's private frame-end flush is recorded
 * as [Op.FLUSH] and means "the frame ends here" to the client. No composition of nested transforms:
 * those are replayed in order by the client, which keeps its own stack, so doing the arithmetic twice
 * would only be a second place to get it wrong. A clip is recorded in the local coordinates the call
 * received; the client projects its four corners through the transform in force when it replays the
 * push and intersects the resulting quadrilateral with the clip it already has.
 *
 * ### Clip scopes
 * A clip scope is always one [Op.CLIP_PUSH] and one [Op.CLIP_POP], whatever its rectangle is: an
 * unmeasured, nonfinite, empty or reversed rectangle is recorded as an explicit zero-area sentinel
 * and draws nothing inside the block. The block still runs exactly once, because it may have side
 * effects, and the `finally` closes the scope even when it throws. That keeps every push/pop pair
 * balanced, so a client replaying the frame can never lose the surrounding clip.
 *
 * ### Primitive input
 * The recorder implements all four typed primitive entry points (colour-only and textured quads,
 * `x, y` and `x, y, u, v` triangle lists). Structure is validated before anything is written, and
 * every numeric value is read into the frame's words before the call returns, so a borrowed mutable
 * [Rect] or [MeasureArray] is snapshotted rather than retained. A holder with no source is "nothing
 * to draw"; a non-empty holder always keeps the textured stride and names [MISSING_ASSET] for an
 * empty id. The browser decides readiness from its own asset cache and paints its checkerboard
 * texture for a confirmed load failure, through the recorded geometry, UVs, nominal size and tint.
 *
 * ### The asset table
 * An id (a texture, an item icon) is defined once and referenced by index afterwards. The table
 * lives across frames on purpose: a steady-state frame then carries no definitions at all, and the
 * first frame - the keyframe a client starts from - carries every one of them.
 *
 * One instance per process, because the framework publishes one backend per process.
 */
internal object Recorder : DslRootRenderer<Unit> {
    private const val INITIAL_WORDS = 1 shl 16

    /**
     * The asset id of a non-empty image whose own identifier is empty.
     *
     * The call still has texture semantics, so it cannot travel as [Op.NO_ASSET] without turning a
     * textured record into an untextured one. This id names "the texture that is missing", which is
     * exactly what a failed lookup means to the client.
     */
    private const val MISSING_ASSET = "kotlinmcuiweb:missing"

    private var words = IntArray(INITIAL_WORDS)

    /** How many words the frame in progress occupies. */
    var size = 0
        private set

    /** Records per opcode in the frame in progress. */
    val counts = IntArray(Op.COUNT)

    /** Vertices submitted through [renderTriangles] in the frame in progress. */
    var vertices = 0
        private set

    /** Characters submitted through the font in the frame in progress. */
    var glyphs = 0
        private set

    /** Assets defined during the frame in progress; zero once the table has warmed up. */
    var newAssets = 0
        private set

    /** How many distinct assets the table holds. */
    val assetCount: Int get() = assets.size

    internal var surfaceWidth = 0
    internal var surfaceHeight = 0

    override val guiScale: Double get() = WebScale.of(surfaceWidth, surfaceHeight)

    private val assets = HashMap<String, Int>()
    private val raw = IntArray(4)
    private val uvRaw = IntArray(4)
    private val nominal = IntArray(2)

    /** Start a frame: the payload is dropped, the asset table is not. */
    fun beginFrame() {
        size = 0
        vertices = 0
        glyphs = 0
        newAssets = 0
        counts.fill(0)
    }

    /** The words of the frame in progress. Only valid until the next [beginFrame]. */
    fun buffer(): IntArray = words

    /** The asset id behind an index, for the report and the SVG writer. */
    fun assetName(index: Int): String? = assets.entries.firstOrNull { it.value == index }?.key

    /**
     * The whole table, index to id, in index order.
     *
     * The definitions a frame emits are the ones it was the first to use; a client that connects
     * afterwards has missed them, so the transport replays this before a keyframe.
     */
    fun assetTable(): List<Pair<Int, String>> = assets.entries.sortedBy { it.value }.map { it.value to it.key }

    context(renderParam: Unit)
    fun flush() {
        // The frame's own end marker. The core no longer calls this: it is the recorder's way of saying
        // "one frame's worth of ops ends here", and every consumer ignores the word.
        put(Op.FLUSH)
        counts[Op.FLUSH]++
    }

    context(renderParam: Unit)
    override fun renderQuad(rect: Rect, lt: Color, rt: Color, lb: Color, rb: Color) {
        if (!primitiveRect(rect)) return
        put(Op.QUAD)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        put(lt.rgbaInt); put(rt.rgbaInt); put(lb.rgbaInt); put(rb.rgbaInt)
        put(Op.NO_ASSET)
        counts[Op.QUAD]++
    }

    context(renderParam: Unit)
    override fun renderQuad(
        rect: Rect,
        lt: Color,
        rt: Color,
        lb: Color,
        rb: Color,
        image: ImageHolder,
        uv: Rect,
    ) {
        // Every rejection happens before the asset lookup, which is the one step that mutates the
        // frame (a first-use texture definition) and the session's asset table.
        if (!primitiveRect(rect)) return
        if (!rawUv(uv)) return
        val id = texturedId(image) ?: return
        if (!nominalSize(image)) return
        val asset = assetOf(id)
        put(Op.QUAD)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        put(lt.rgbaInt); put(rt.rgbaInt); put(lb.rgbaInt); put(rb.rgbaInt)
        put(asset)
        put(uvRaw[0]); put(uvRaw[1]); put(uvRaw[2]); put(uvRaw[3])
        put(nominal[0]); put(nominal[1])
        counts[Op.QUAD]++
    }

    context(renderParam: Unit)
    override fun renderTriangles(vertices: MeasureArray, colors: ColorArray) = triangles(vertices, colors, null)

    context(renderParam: Unit)
    override fun renderTriangles(vertices: MeasureArray, colors: ColorArray, image: ImageHolder) =
        triangles(vertices, colors, image)

    context(renderParam: Unit, ctx: DslScaleContext)
    override fun renderButton(rect: Rect, highlighted: Boolean, active: Boolean, color: Color) {
        if (!rawCoords(rect)) return
        put(Op.BUTTON)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        put((if (highlighted) 1 else 0) or (if (active) 1 shl 1 else 0))
        put(color.rgbaInt)
        counts[Op.BUTTON]++
    }

    context(renderParam: Unit, ctx: DslScaleContext)
    override fun renderSlot(rect: Rect) = rectOnly(Op.SLOT, rect)

    context(renderParam: Unit, ctx: DslScaleContext)
    override fun renderContainer(rect: Rect) = rectOnly(Op.CONTAINER, rect)

    context(renderParam: Unit, ctx: DslScaleContext)
    override fun renderTooltip(rect: Rect) = rectOnly(Op.TOOLTIP, rect)

    context(renderParam: Unit, ctx: DslScaleContext)
    override fun renderItem(rect: Rect, item: String, count: Int, damage: Double?, enchanted: Boolean) {
        if (!rawCoords(rect)) return
        val asset = assetOf(item)
        put(Op.ITEM)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        put(asset)
        put(count)
        put(damage?.toFloat()?.toRawBits() ?: 0)
        put((if (damage != null) 1 else 0) or (if (enchanted) 1 shl 1 else 0))
        counts[Op.ITEM]++
    }

    context(ctx: DslScaleContext, renderParam: Unit)
    override fun renderDefaultBackground(rect: Rect) = rectOnly(Op.DEFAULT_BACKGROUND, rect)

    context(renderParam: Unit)
    override fun withScissor(rect: Rect, block: () -> Unit) {
        // The rectangle is borrowed for the duration of the call and may be a mutable
        // implementation: copy every edge before the block can rewrite it. A rectangle that is
        // unmeasured, nonfinite, empty or reversed is still a scope, but an empty one, recorded as
        // the zero-area sentinel so the format never has to guess what "empty" means.
        val measured = putMeasure(rect.left, raw, 0) &&
            putMeasure(rect.top, raw, 1) &&
            putMeasure(rect.right, raw, 2) &&
            putMeasure(rect.bottom, raw, 3)
        val width = Float.fromBits(raw[2]) - Float.fromBits(raw[0])
        val height = Float.fromBits(raw[3]) - Float.fromBits(raw[1])
        val drawable = measured && width.isFinite() && height.isFinite() && width > 0f && height > 0f
        put(Op.CLIP_PUSH)
        if (drawable) {
            put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        } else {
            put(0); put(0); put(0); put(0)
        }
        counts[Op.CLIP_PUSH]++
        try {
            block()
        } finally {
            // Balanced even when the block throws: replaying the frame must not leave a clip open.
            put(Op.CLIP_POP)
            counts[Op.CLIP_POP]++
        }
    }

    context(renderParam: Unit)
    override fun withTransform(transform: Transform, block: () -> Unit) {
        put(Op.TRANSFORM_PUSH)
        put(transform.m00.toRawBits()); put(transform.m01.toRawBits()); put(transform.m02.toRawBits())
        put(transform.m10.toRawBits()); put(transform.m11.toRawBits()); put(transform.m12.toRawBits())
        put(transform.m20.toRawBits()); put(transform.m21.toRawBits()); put(transform.m22.toRawBits())
        counts[Op.TRANSFORM_PUSH]++
        try {
            block()
        } finally {
            // Balanced even when the block throws, for the same reason as withScissor.
            put(Op.TRANSFORM_POP)
            counts[Op.TRANSFORM_POP]++
        }
    }

    /**
     * One glyph of one character.
     *
     * The font contract hands a backend a character and a position to draw it at, so this is what a
     * recording backend does with it. Note what is *not* here: no advance, no measurement. Layout
     * happens on this side, in the framework, so the positions travel with each glyph and the client
     * never measures text - which is also what lets the client be a canvas or a glyph atlas without
     * the layout changing between the two.
     */
    fun text(char: DslRenderableChar, x: Measure, y: Measure, effectLeft: Measure, effectRight: Measure) {
        val xBits = x.pixelsOrElse<Float> { return }.toRawBits()
        val yBits = y.pixelsOrElse<Float> { return }.toRawBits()
        val leftBits = effectLeft.pixelsOrElse<Float> { return }.toRawBits()
        val rightBits = effectRight.pixelsOrElse<Float> { return }.toRawBits()
        val sizeBits = char.size.pixelsOrElse<Float> { return }.toRawBits()
        put(Op.TEXT)
        put(char.code)
        put(xBits)
        put(yBits)
        put(Op.styleOf(char.style))
        put(char.color.rgbaInt)
        put(leftBits)
        put(rightBits)
        put(sizeBits)
        counts[Op.TEXT]++
        glyphs++
    }

    override fun getFont(name: String?): DslFont<Unit> = fonts.getOrPut(name) { WebFont(name) }

    private val fonts = HashMap<String?, DslFont<Unit>>()

    /** The one-argument helpers share their shape; only the opcode differs. */
    private fun rectOnly(op: Int, rect: Rect) {
        if (!rawCoords(rect)) return
        put(op)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        counts[op]++
    }

    /**
     * The shared body of both triangle overloads.
     *
     * Order is the contract: the structure is checked first and always by division and remainder, so
     * the check itself cannot overflow; then every coordinate is checked; only then is the image
     * examined and the asset table written. Validation finishing before the first word is written is
     * what makes "one bad last vertex skips the whole call" true rather than "emits three quarters of
     * a record". A `null` [image] is the untextured overload; a non-empty holder always keeps the
     * textured stride, whatever the identifier looks like.
     */
    private fun triangles(vertices: MeasureArray, colors: ColorArray, image: ImageHolder?) {
        val count = colors.size
        val stride = if (image == null) 2 else 4
        require(count % 3 == 0) {
            "a triangle list needs a multiple of three colours, got $count"
        }
        require(vertices.size % stride == 0 && vertices.size / stride == count) {
            "a triangle list needs ${if (stride == 2) "two" else "four"} measures per colour: " +
                "$count colours need ${count.toLong() * stride} measures, got ${vertices.size}"
        }
        if (count == 0) return
        for (index in 0 until vertices.size) {
            val measure = vertices[index]
            if (!measure.isNumber || !measure.raw.toFloat().isFinite()) return
        }
        var id: String? = null
        if (image != null) {
            // The image is examined only after structure and numbers passed. An empty source is a
            // valid "nothing to draw", not a malformed call.
            id = texturedId(image) ?: return
            if (!nominalSize(image)) return
        }
        val asset = if (id == null) Op.NO_ASSET else assetOf(id)
        put(Op.TRIANGLES)
        put(count)
        put(if (id == null) 0 else 1)
        put(asset)
        for (index in 0 until vertices.size) put(vertices[index].raw.toFloat().toRawBits())
        for (index in 0 until count) put(colors[index].rgbaInt)
        if (id != null) {
            put(nominal[0]); put(nominal[1])
        }
        counts[Op.TRIANGLES]++
        this.vertices += count
    }

    /**
     * The four corners of [rect] as raw float bits, or `false` when any of them is unmeasured.
     *
     * An unmeasured rectangle is the framework's way of saying "this was never laid out"; every
     * drawing entry point treats it as nothing to draw, and so does this one.
     */
    private fun rawCoords(rect: Rect): Boolean {
        raw[0] = rect.left.pixelsOrElse<Float> { return false }.toRawBits()
        raw[1] = rect.top.pixelsOrElse<Float> { return false }.toRawBits()
        raw[2] = rect.right.pixelsOrElse<Float> { return false }.toRawBits()
        raw[3] = rect.bottom.pixelsOrElse<Float> { return false }.toRawBits()
        return true
    }

    /**
     * [uv] as raw float bits, or `false` when any edge is unmeasured or not representable as a
     * finite `Float`. Negative and out-of-range values are kept: sampling is the client's business,
     * and the framework contract explicitly allows them.
     */
    private fun rawUv(uv: Rect): Boolean =
        putMeasure(uv.left, uvRaw, 0) &&
            putMeasure(uv.top, uvRaw, 1) &&
            putMeasure(uv.right, uvRaw, 2) &&
            putMeasure(uv.bottom, uvRaw, 3)

    /**
     * The destination rectangle as raw float bits, or `false` unless every edge is measured, finite,
     * representable as a `Float`, and the rectangle is strictly positive in both axes.
     *
     * Kept separate from [rawCoords] on purpose: the widget ops ([Op.BUTTON] and friends) have always
     * accepted what the layout produced, and this phase tightens only the primitive entry points.
     */
    private fun primitiveRect(rect: Rect): Boolean {
        if (!putMeasure(rect.left, raw, 0)) return false
        if (!putMeasure(rect.top, raw, 1)) return false
        if (!putMeasure(rect.right, raw, 2)) return false
        if (!putMeasure(rect.bottom, raw, 3)) return false
        // Comparing edges instead of subtracting keeps a huge-but-finite width from overflowing.
        return Float.fromBits(raw[2]) > Float.fromBits(raw[0]) &&
            Float.fromBits(raw[3]) > Float.fromBits(raw[1])
    }

    /** One measure as finite float bits, or `false` when it is unmeasured or overflows a `Float`. */
    private fun putMeasure(measure: Measure, into: IntArray, index: Int): Boolean {
        if (!measure.isNumber) return false
        val value = measure.raw.toFloat()
        if (!value.isFinite()) return false
        into[index] = value.toRawBits()
        return true
    }

    /**
     * Copy an image's nominal pixel size into [nominal], or return `false` when it is unusable.
     *
     * The size travels per draw rather than per asset: the same id may be sampled with a different
     * nominal size elsewhere in the frame, and the client needs this draw's size to scale its UVs
     * onto the pixels it actually holds.
     */
    private fun nominalSize(image: ImageHolder): Boolean {
        if (!putMeasure(image.width, nominal, 0)) return false
        if (!putMeasure(image.height, nominal, 1)) return false
        return Float.fromBits(nominal[0]) > 0f && Float.fromBits(nominal[1]) > 0f
    }

    /**
     * The id a textured holder travels under, or `null` when its source is empty.
     *
     * The three sources are three different namespaces for the client: a resource is the framework's
     * own `namespace:path` id, a local file is this backend's `local:` id (the transport serves it
     * only because [WebUtils.localImage] described that path), and a remote url is the url itself.
     * Which of them it is cannot be confused, because [ImageSource] is a type rather than a prefix.
     *
     * A non-empty source with an empty identifier is a resource problem, not a format decision: the
     * call keeps its textured shape and refers to [MISSING_ASSET]. It never turns into an untextured
     * draw. Readiness and failure memory belong to the client's asset cache, which paints the
     * checkerboard texture for a confirmed miss while a pending load draws nothing.
     */
    private fun texturedId(image: ImageHolder): String? {
        val source = image.source ?: return null
        val id = when (source) {
            is ImageSource.LocalFile -> WebUtils.localImageId(source.file)
            is ImageSource.Resource -> source.location
            is ImageSource.Remote -> source.url
        }
        return id.ifEmpty { MISSING_ASSET }
    }

    /**
     * The index of [id], defining it first when it is new.
     *
     * The definition is written where it is first used, so a client that replays the frame in order
     * always sees the definition before the reference.
     */
    private fun assetOf(id: String): Int {
        val existing = assets[id]
        if (existing != null) return existing
        val index = assets.size
        assets[id] = index
        put(Op.TEXTURE)
        put(index)
        put(id.length)
        for (character in id) put(character.code)
        counts[Op.TEXTURE]++
        newAssets++
        return index
    }

    private fun put(value: Int) {
        ensure(1)
        words[size++] = value
    }

    private fun ensure(count: Int) {
        if (size + count <= words.size) return
        words = words.copyOf(maxOf(words.size * 2, size + count))
    }
}
