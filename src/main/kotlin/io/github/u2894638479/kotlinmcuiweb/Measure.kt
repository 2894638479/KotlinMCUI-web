package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.entry.DslEntryLoader
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.io.File

/**
 * Measures what a web client would have to receive for a page.
 *
 * The readings come from the recorder's own buffer - the words counted here are the words that go on
 * the wire - and the sizes reported are the messages [FrameStream] actually produced, not estimates.
 * Four of them decide the transport's shape:
 *
 * - the size of a frame, and its split between the cold frame (which carries every asset definition)
 *   and the steady state;
 * - how often a frame is byte-identical to the one before it, because that is what the one-byte
 *   no-change record buys;
 * - of the frames that did change, how many words a patch had to carry, against the whole frame;
 * - where the words go: which record type owns the frame. Text is one record per character, so this
 *   is the line that says whether coalescing glyph runs is worth more than anything else.
 *
 * Usage: `--page=<name> --frames=<n> --width=<n> --height=<n>`.
 */
fun main(args: Array<String>) {
    val page = option(args, "--page") ?: "color"
    val frames = (option(args, "--frames") ?: "30").toInt()
    val width = (option(args, "--width") ?: "1280").toInt()
    val height = (option(args, "--height") ?: "720").toInt()

    DslRuntime.install { WebMetadata }
    DslRuntime.registerProvider(WebRootProvider)
    // Same rule as the serve entry point: a refused publication means the process platform is
    // somebody else's, not that this backend cannot measure its own roots.
    if (!DslRuntime.platformReady { WebPlatform }) {
        dslLogger.warn("web: another backend published this process's platform; measuring on this backend's own roots")
    }
    WebBackend.adoptRoot()
    // Whatever demo overlays this machine has enabled are switched off for the run: an overlay draws
    // per-frame timings, and a page that reports its own frame time is a page that never repeats
    // itself. The numbers below are meant to be the page's, not the overlay's.
    for (overlay in DslEntryLoader.enabledOverlays) DslEntryLoader.setOverlayEnabled(overlay, false)
    WebBackend.create("measure") { pageNamed(page) }.show()

    val stream = FrameStream()
    val records = ArrayList<FrameRecord>(frames)
    var previous: IntArray? = null
    repeat(frames) { index ->
        val started = System.nanoTime()
        WebBackend.frame(width, height)
        val elapsed = System.nanoTime() - started
        val size = Recorder.size
        val current = Recorder.buffer().copyOf(size)
        val message = stream.encode(Recorder.buffer(), size)
        records += FrameRecord(
            index = index,
            words = current,
            previous = previous,
            messageBytes = message.size,
            messageType = message[0],
            counts = Recorder.counts.copyOf(),
            vertices = Recorder.vertices,
            glyphs = Recorder.glyphs,
            newAssets = Recorder.newAssets,
            nanos = elapsed,
        )
        previous = current
    }
    WebBackend.closeAll()

    option(args, "--svg")?.let { path ->
        val file = File(path)
        val last = records.last().words
        SvgWriter.write(last, last.size, width, height, file)
        println("svg written: ${file.absolutePath} (${file.length()} B)")
    }

    report(page, width, height, records, Recorder.assetCount)
}

private class FrameRecord(
    val index: Int,
    val words: IntArray,
    previous: IntArray?,
    val messageBytes: Int,
    val messageType: Byte,
    val counts: IntArray,
    val vertices: Int,
    val glyphs: Int,
    val newAssets: Int,
    val nanos: Long,
) {
    val bytes = words.size * 4

    val identical = messageType == Encoder.NOCHANGE

    /** Words at the head that are unchanged; diagnostics for what a patch was able to leave out. */
    val prefix = previous?.let { before ->
        var i = 0
        val limit = minOf(before.size, words.size)
        while (i < limit && before[i] == words[i]) i++
        i
    } ?: 0

    val suffix = previous?.let { before ->
        var j = 0
        val limit = minOf(before.size, words.size) - prefix
        while (j < limit && before[before.size - 1 - j] == words[words.size - 1 - j]) j++
        j
    } ?: 0

    val literal = if (previous == null) words.size else (words.size - prefix - suffix).coerceAtLeast(0)

    /** Words per record type, walked from the frame itself rather than counted as it was recorded. */
    val wordsByOp: LongArray = walk(words)

    /**
     * Which record the first difference from the previous frame falls in.
     *
     * A frame that changes has to be explained: the sizes alone say a page is not still, and this says
     * which part of it moved.
     */
    val firstDifference: String = locateDifference(previous, words)

    private companion object {
        fun walk(words: IntArray): LongArray {
            val totals = LongArray(Op.COUNT)
            var at = 0
            while (at < words.size) {
                val op = words[at]
                val stride = Op.strideAt(words, at)
                if (op in 0 until Op.COUNT) totals[op] += stride.toLong()
                at += stride
            }
            return totals
        }

        fun locateDifference(previous: IntArray?, words: IntArray): String {
            if (previous == null) return "-"
            var index = 0
            val limit = minOf(previous.size, words.size)
            while (index < limit && previous[index] == words[index]) index++
            if (index >= limit && previous.size == words.size) return "none"
            var at = 0
            while (at < words.size) {
                val stride = Op.strideAt(words, at)
                if (index < at + stride) {
                    val op = Op.nameOf(words[at])
                    // For text, name the character and the run around it: a per-frame number is only
                    // identifiable from the label it sits in.
                    return if (words[at] == Op.TEXT) {
                        "$op@$at '${character(previous, at)}'->'${character(words, at)}' in \"${run(words, at)}\""
                    } else {
                        "$op@$at"
                    }
                }
                at += stride
            }
            return "tail@${words.size}"
        }

        fun character(words: IntArray, at: Int): String {
            val code = words.getOrNull(at + 1) ?: return "?"
            return runCatching { String(Character.toChars(code)) }.getOrDefault("?")
        }

        /**
         * The characters of the text run the record at [at] belongs to.
         *
         * A text op carries one glyph, so a label is a streak of text records; walking outwards while
         * the records stay text (and close enough to be the same line) is what turns "a digit moved"
         * into "this label is animating".
         */
        fun run(words: IntArray, at: Int): String {
            val before = StringBuilder()
            var step = at
            while (before.length < RUN_LIMIT) {
                val previousAt = previousRecord(words, step) ?: break
                if (words[previousAt] != Op.TEXT) break
                before.insert(0, character(words, previousAt))
                step = previousAt
            }
            val after = StringBuilder()
            step = at
            while (after.length < RUN_LIMIT) {
                val nextAt = step + Op.strideAt(words, step)
                if (nextAt >= words.size || words[nextAt] != Op.TEXT) break
                after.append(character(words, nextAt))
                step = nextAt
            }
            return before.toString() + character(words, at) + after
        }

        /** The record that ends where [at] begins, by walking from the frame's start. */
        fun previousRecord(words: IntArray, at: Int): Int? {
            var step = 0
            var last = -1
            while (step < at) {
                last = step
                step += Op.strideAt(words, step)
            }
            return if (step == at && last >= 0) last else null
        }

        const val RUN_LIMIT = 24
    }
}

private fun report(page: String, width: Int, height: Int, records: List<FrameRecord>, assets: Int) {
    val cold = records.first()
    val steady = records.takeLast(minOf(STEADY_FRAMES, records.size))
    val changed = records.drop(1)

    println("== web frame record: page=$page ${width}x$height, ${records.size} frames ==")
    println()
    println("frame   words    bytes  type   prefix   suffix  literal   wire B   record us  first difference")
    for (record in records) {
        println(
            "%5d %7d %8d %5s %8d %8d %8d %8d %11d  %s".format(
                record.index,
                record.words.size,
                record.bytes,
                typeName(record.messageType),
                record.prefix,
                record.suffix,
                record.literal,
                record.messageBytes,
                record.nanos / 1000,
                record.firstDifference,
            )
        )
    }
    println()
    println("== summary ==")
    println("cold frame      : ${cold.words.size} words = ${cold.bytes} B, ${cold.newAssets} asset definitions, ${cold.messageBytes} B on the wire")
    println("steady state    : mean ${mean(steady) { it.bytes.toLong() }} B/frame, median ${median(steady) { it.bytes.toLong() }} B/frame")
    println("wire            : mean ${mean(steady) { it.messageBytes.toLong() }} B/frame, median ${median(steady) { it.messageBytes.toLong() }} B/frame")
    println("identical frames: ${changed.count { it.identical }}/${changed.size} of the frames after the first")
    val patches = changed.filter { !it.identical }
    if (patches.isNotEmpty()) {
        println(
            "wire (changed)  : mean ${mean(patches) { it.messageBytes.toLong() }} B over the ${patches.size} changed frames " +
                "(${patches.count { it.messageType == Encoder.KEYFRAME }} of them keyframes), " +
                "mean ${mean(patches) { it.literal.toLong() }} literal words of ${mean(patches) { it.words.size.toLong() }}"
        )
    }
    println("record time     : mean ${mean(records) { it.nanos / 1000 }} us, median ${median(records) { it.nanos / 1000 }} us")
    println("content         : ${cold.counts[Op.QUAD]} quads, ${cold.glyphs} glyphs, ${cold.vertices} vertices, $assets assets, ${cold.counts[Op.FLUSH]} flushes")
    println("words by record : " + breakdown(cold))
}

private fun typeName(type: Byte): String = when (type) {
    Encoder.NOCHANGE -> "same"
    Encoder.KEYFRAME -> "key"
    Encoder.DELTA -> "delta"
    else -> "?$type"
}

/** The record types that own the frame, largest share first, as `name count/words (share)`. */
private fun breakdown(record: FrameRecord): String {
    val total = record.words.size.toLong()
    return (0 until Op.COUNT)
        .map { op -> Triple(Op.nameOf(op), record.counts[op], record.wordsByOp[op]) }
        .filter { it.third > 0 }
        .sortedByDescending { it.third }
        .joinToString(", ") { "${it.first} ${it.second}/${it.third}w (${it.third * 100 / total}%)" }
}

private fun mean(records: List<FrameRecord>, of: (FrameRecord) -> Long): Long =
    if (records.isEmpty()) 0L else records.sumOf(of) / records.size

private fun median(records: List<FrameRecord>, of: (FrameRecord) -> Long): Long =
    if (records.isEmpty()) 0L else records.map(of).sorted()[records.size / 2]

private const val STEADY_FRAMES = 10
