package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRootRenderer
import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.Measure
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
 * No batching, so a scope change needs no [flush]; the framework's own frame-end flush is recorded
 * as [Op.FLUSH] and means "the frame ends here" to the client. No intersection of nested clips and
 * no composition of nested transforms: both are replayed in order by the client, which keeps its own
 * stacks, so doing the arithmetic twice would only be a second place to get it wrong.
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
    override fun flush() {
        put(Op.FLUSH)
        counts[Op.FLUSH]++
    }

    context(renderParam: Unit)
    override fun renderQuad(
        rect: Rect,
        lt: Color,
        rt: Color,
        lb: Color,
        rb: Color,
        image: ImageHolder?,
        uv: Rect?,
    ) {
        if (!rawCoords(rect)) return
        val textured = uv != null && rawUv(uv)
        val asset = if (textured) assetOf(image) else Op.NO_ASSET
        put(Op.QUAD)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        put(lt.rgbaInt); put(rt.rgbaInt); put(lb.rgbaInt); put(rb.rgbaInt)
        put(asset)
        if (asset != Op.NO_ASSET) {
            put(uvRaw[0]); put(uvRaw[1]); put(uvRaw[2]); put(uvRaw[3])
        }
        counts[Op.QUAD]++
    }

    context(renderParam: Unit)
    override fun renderTriangles(vertices: FloatArray, colors: IntArray, image: ImageHolder?) {
        val count = colors.size
        if (count == 0) return
        val textured = image != null && image.id.isNotEmpty()
        val stride = if (textured) 4 else 2
        if (vertices.size < count * stride) return
        val asset = if (textured) assetOf(image) else Op.NO_ASSET
        put(Op.TRIANGLES)
        put(count)
        put(if (textured) 1 else 0)
        put(asset)
        for (i in 0 until count * stride) put(vertices[i].toRawBits())
        for (i in 0 until count) put(colors[i])
        counts[Op.TRIANGLES]++
        this.vertices += count
    }

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
        if (!rawCoords(rect)) {
            block()
            return
        }
        put(Op.CLIP_PUSH)
        put(raw[0]); put(raw[1]); put(raw[2]); put(raw[3])
        counts[Op.CLIP_PUSH]++
        block()
        put(Op.CLIP_POP)
        counts[Op.CLIP_POP]++
    }

    context(renderParam: Unit)
    override fun withTransform(transform: Transform, block: () -> Unit) {
        put(Op.TRANSFORM_PUSH)
        put(transform.m00.toRawBits()); put(transform.m01.toRawBits()); put(transform.m02.toRawBits())
        put(transform.m10.toRawBits()); put(transform.m11.toRawBits()); put(transform.m12.toRawBits())
        put(transform.m20.toRawBits()); put(transform.m21.toRawBits()); put(transform.m22.toRawBits())
        counts[Op.TRANSFORM_PUSH]++
        block()
        put(Op.TRANSFORM_POP)
        counts[Op.TRANSFORM_POP]++
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

    private fun rawUv(uv: Rect): Boolean {
        uvRaw[0] = uv.left.pixelsOrElse<Float> { return false }.toRawBits()
        uvRaw[1] = uv.top.pixelsOrElse<Float> { return false }.toRawBits()
        uvRaw[2] = uv.right.pixelsOrElse<Float> { return false }.toRawBits()
        uvRaw[3] = uv.bottom.pixelsOrElse<Float> { return false }.toRawBits()
        return true
    }

    private fun assetOf(image: ImageHolder?): Int =
        if (image == null || image.id.isEmpty()) Op.NO_ASSET else assetOf(image.id)

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
