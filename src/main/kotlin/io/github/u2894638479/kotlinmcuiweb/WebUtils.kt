package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.px
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The non-drawing half of the backend contract, for a client that is on the other side of a socket.
 *
 * Everything here either answers from the server's own state or is a request the transport will
 * relay: the clipboard is mirrored rather than read from a platform, [translate] answers nothing
 * until the language files are served (the client shows the key until then), and [capture] has to
 * wait for the browser because the pixels it wants are in the browser's canvas.
 *
 * [mainDispatcher] is a single thread on purpose. The framework's context is thread-confined - it
 * records the thread that created it and refuses to be touched from another - so a backend hosted by
 * a server has to name *one* thread as the frame thread, and this is it. A transport that wants to
 * render from a socket reader must hop onto this dispatcher first.
 */
internal object WebUtils : DslBackendUtils {
    private val frameThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kotlinmcui-web").apply { isDaemon = true }
    }

    private val localImages = HashMap<String, ImageHolder>()

    override var clipboard: String = ""

    override fun narrate(string: String) {
        // Screen readers are the client's business: it can hand the string to the speech synthesis
        // API, which is better than anything a server could do with it.
    }

    override fun translate(key: String, vararg args: Any?): String? = null

    override val isInWorld: Boolean get() = false

    /**
     * A local image, described but not read.
     *
     * The contract wants an answer immediately, so this reads the *size* - which is what layout and
     * UV mapping need - and leaves the pixels to the transport: the id it returns is what the client
     * asks for, and it is derived from the path so a second call answers from the cache.
     */
    override fun loadLocalImage(file: File): ImageHolder {
        val id = "local:" + file.absolutePath
        return localImages.getOrPut(id) {
            val size = runCatching { ImageIO.read(file) }.getOrNull()
            if (size == null) {
                dslLogger.warn("web assets: '$file' could not be read, the client will draw its placeholder")
                ImageHolder.empty
            } else {
                ImageHolder(id, size.width.px, size.height.px)
            }
        }
    }

    /** The same, with the cache dropped, so the transport serves the file as it is now. */
    override fun forceLoadLocalImage(file: File): ImageHolder {
        localImages.remove("local:" + file.absolutePath)
        return loadLocalImage(file)
    }

    override fun playButtonSound() {
        // The client has the audio device; the transport turns this into a message.
    }

    override fun openUri(uri: String) {
        dslLogger.info("web utils: asked to open '$uri', which only the client can do")
    }

    override val mainDispatcher: CoroutineDispatcher = frameThread.asCoroutineDispatcher()

    /** The pixels live in the client's canvas, so the transport answers this, not the server. */
    override suspend fun capture(fileName: String): File? = null

    override fun quit(exitCode: Int) {
        dslLogger.info("web utils: the framework asked to quit with $exitCode")
    }
}
