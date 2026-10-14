package io.github.u2894638479.kotlinmcuiweb

/**
 * The wire messages of one session.
 *
 * A frame becomes one of three messages, and which one is decided by comparing it with the frame the
 * client is known to hold:
 *
 * - [NOCHANGE] - one byte - when nothing changed. Measured on the framework's own pages, a static
 *   page is byte-identical frame after frame, so this is the common case and it is why a page that
 *   nobody is touching costs nothing to keep on screen;
 * - [KEYFRAME] - the whole word buffer - for the first frame, and whenever the client's base is not
 *   the frame the server thinks it holds (a fresh connection, a resize, a reconnecting client);
 * - [DELTA] - the common prefix plus the words that differ.
 *
 * A delta carries no suffix. Everything after the literals is known to be unchanged, so the client
 * keeps its own tail and only a prefix length and a literal run travel. That is also why a patch is
 * safe with respect to the clip and transform stacks: those are records in the stream like any other,
 * and the client replays the whole buffer, so a literal run that starts inside a clip scope is never
 * interpreted on its own.
 *
 * Words are little-endian on the wire; the client reads them with a `DataView` set to little-endian.
 * The message layout is:
 *
 * ```
 * NOCHANGE  [0x4E]
 * KEYFRAME  [0x4B, frame:u32, words:u32, word * words]
 * DELTA     [0x44, frame:u32, words:u32, prefix:u32, literals:u32, word * literals]
 * ```
 */
internal object Encoder {
    const val NOCHANGE: Byte = 0x4E
    const val KEYFRAME: Byte = 0x4B
    const val DELTA: Byte = 0x44

    /** The bytes a keyframe header costs before its words. */
    const val KEYFRAME_HEADER = 9

    /** The bytes a delta header costs before its literals. */
    const val DELTA_HEADER = 17
}

/**
 * The server half of a session: it remembers the frame the client holds and turns the recorder's
 * buffer into the smallest message that brings the client up to date.
 *
 * One instance per client, because the base frame differs per client. A session that has just been
 * told to start over - a new socket, a resize, a client that lost its base - calls [reset], and the
 * next frame goes out as a keyframe.
 */
internal class FrameStream {
    private var frame = 0
    private var base: IntArray? = null

    /** The frame number the client holds, or 0 before the first frame was sent. */
    val sentFrame: Int get() = frame

    /** Send the next frame in full: the next call to [encode] is a keyframe. */
    fun reset() {
        base = null
    }

    /**
     * The message that brings a client holding [base] up to [current].
     *
     * [current] is the recorder's own buffer and must not be modified while this call runs; the
     * session copies whatever it has to remember.
     */
    fun encode(current: IntArray, size: Int): ByteArray {
        frame += 1
        val previous = base
        val message = when {
            previous == null -> keyframe(current, size)
            previous.size == size && sameWords(previous, current, size) -> byteArrayOf(Encoder.NOCHANGE)
            else -> delta(previous, current, size) ?: keyframe(current, size)
        }
        base = current.copyOf(size)
        return message
    }

    private fun keyframe(current: IntArray, size: Int): ByteArray {
        val message = ByteArray(Encoder.KEYFRAME_HEADER + size * 4)
        message[0] = Encoder.KEYFRAME
        putInt(message, 1, frame)
        putInt(message, 5, size)
        for (i in 0 until size) putInt(message, Encoder.KEYFRAME_HEADER + i * 4, current[i])
        return message
    }

    /**
     * A prefix plus literals, or `null` when the patch would not be smaller than a keyframe.
     *
     * The tail after the literals is what the client already holds, so the words that do not travel
     * are the prefix and the suffix. When those leave more literals than the frame itself, a keyframe
     * is both smaller and simpler - which is the honest answer for a frame that was rebuilt with a
     * different number of records, the case where the measured literal runs covered almost the whole
     * frame.
     */
    private fun delta(previous: IntArray, current: IntArray, size: Int): ByteArray? {
        var prefix = 0
        val shared = minOf(previous.size, size)
        while (prefix < shared && previous[prefix] == current[prefix]) prefix += 1
        var suffix = 0
        val suffixLimit = shared - prefix
        while (suffix < suffixLimit && previous[previous.size - 1 - suffix] == current[size - 1 - suffix]) suffix += 1
        val literals = size - prefix - suffix
        if (literals <= 0 && previous.size == size) return byteArrayOf(Encoder.NOCHANGE)
        if (Encoder.DELTA_HEADER + literals * 4 >= Encoder.KEYFRAME_HEADER + size * 4) return null
        val message = ByteArray(Encoder.DELTA_HEADER + literals * 4)
        message[0] = Encoder.DELTA
        putInt(message, 1, frame)
        putInt(message, 5, size)
        putInt(message, 9, prefix)
        putInt(message, 13, literals)
        for (i in 0 until literals) putInt(message, Encoder.DELTA_HEADER + i * 4, current[prefix + i])
        return message
    }

    private fun putInt(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value ushr 8) and 0xFF).toByte()
        target[at + 2] = ((value ushr 16) and 0xFF).toByte()
        target[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /**
     * Whether the first [size] words are the same.
     *
     * [current] is the recorder's buffer, which is as long as it has ever needed to be and usually
     * longer than the frame, so the comparison has to be bounded rather than a whole-array one.
     */
    private fun sameWords(previous: IntArray, current: IntArray, size: Int): Boolean {
        for (i in 0 until size) if (previous[i] != current[i]) return false
        return true
    }
}

/**
 * The client half, written down once so the format has an executable definition.
 *
 * The browser gets the same eleven lines in JavaScript; keeping them here means the format can be
 * round-tripped in a test instead of being believed to be decodable. [base] is what the client holds
 * (null before its first message), and only a keyframe may be applied to a null base.
 */
internal object Decoder {
    fun apply(base: IntArray?, message: ByteArray): IntArray = when (message[0]) {
        Encoder.NOCHANGE -> requireNotNull(base) { "a client with no frame cannot take a no-change record" }
        Encoder.KEYFRAME -> {
            val words = getInt(message, 5)
            IntArray(words) { getInt(message, Encoder.KEYFRAME_HEADER + it * 4) }
        }
        Encoder.DELTA -> {
            val words = getInt(message, 5)
            val prefix = getInt(message, 9)
            val literals = getInt(message, 13)
            val held = requireNotNull(base) { "a client with no frame cannot take a delta" }
            require(prefix + literals <= words && prefix <= held.size && prefix + literals <= held.size) {
                "the patch does not fit the frame the client holds"
            }
            IntArray(words) { index ->
                when {
                    index < prefix -> held[index]
                    index < prefix + literals -> getInt(message, Encoder.DELTA_HEADER + (index - prefix) * 4)
                    else -> held[index + held.size - words]
                }
            }
        }
        else -> error("unknown message type ${message[0]}")
    }

    private fun getInt(source: ByteArray, at: Int): Int =
        (source[at].toInt() and 0xFF) or
            ((source[at + 1].toInt() and 0xFF) shl 8) or
            ((source[at + 2].toInt() and 0xFF) shl 16) or
            ((source[at + 3].toInt() and 0xFF) shl 24)
}
