package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.px
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The non-drawing half of the backend contract, for a client that is on the other side of a socket.
 *
 * Most of it answers from the server's own state rather than from a platform: [translate] reads the
 * language files off the classpath - which is what makes every label already translated by the time a
 * frame is recorded - and the clipboard is mirrored rather than read. What cannot be answered here is
 * a request for pixels that live in the browser, which is why [capture] waits for the transport.
 *
 * The local images are only *described* here: [loadLocalImage] reads a file's size so layout works and
 * remembers it, and the transport serves the bytes when a client asks for that id. Nothing else may
 * be served for a `local:` id, so a client cannot name a path of its own.
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

    /**
     * The thread every framework call must happen on: creating a screen, taking a frame, dispatching
     * input. Exposed as an [java.util.concurrent.Executor] because a transport wants to hand work to
     * it rather than suspend on it.
     */
    internal val frameExecutor: Executor get() = frameThread

    private val localImages = HashMap<String, ImageHolder>()

    override var clipboard: String = ""

    override fun narrate(string: String) {
        // Screen readers are the client's business: it can hand the string to the speech synthesis
        // API, which is better than anything a server could do with it.
    }

    /**
     * Translation, answered from the language files on the classpath.
     *
     * The framework asks for a translation while it *builds* the tree, so translating here is what
     * makes every label arrive already translated - the client never sees a key and has nothing to
     * resolve. A key that is not in the table returns null, which is the framework's own convention
     * for "no translation": it shows the key rather than nothing.
     */
    override fun translate(key: String, vararg args: Any?): String? {
        val text = languageTable()?.get(key) ?: return null
        if (args.isEmpty()) return text
        return runCatching { String.format(locale(), text, *args) }.getOrDefault(text)
    }

    /**
     * Switch the language. Returns whether anything changed, so a caller knows a frame is worth
     * rebuilding.
     *
     * The tree is one tree for the whole process - every client sees the same page - so the language
     * is a property of the process rather than of a connection, and the first client to say what it
     * speaks decides. That is the honest answer for a shared surface; a page per client would be the
     * other one, and it would mean a page per language.
     */
    internal fun useLanguage(tag: String): Boolean {
        val requested = parseLocale(tag)
        if (requested == language) return false
        language = requested
        table = null
        return true
    }

    private var language: Locale? = null

    private var table: Map<String, String>? = null

    private fun locale(): Locale = language ?: Locale.getDefault()

    private fun languageTable(): Map<String, String>? {
        table?.let { return it.ifEmpty { null } }
        val loaded = loadTranslations(locale())
        table = loaded ?: emptyMap()
        return loaded
    }

    /** `zh-CN` and `zh_CN` both arrive from browsers, and both mean the same thing here. */
    private fun parseLocale(tag: String): Locale? {
        val parts = tag.trim().replace('_', '-').split('-')
        val language = parts.firstOrNull()?.lowercase()?.takeIf { it.length == 2 } ?: return null
        val country = parts.getOrNull(1)?.uppercase()?.takeIf { it.length == 2 }.orEmpty()
        return Locale(language, country)
    }

    /** `<language>_<country>`, then `<language>`, then the language every file is guaranteed to have. */
    private fun loadTranslations(locale: Locale): Map<String, String>? {
        val country = locale.country
        // Both spellings of a region are tried: the framework's own files are lower case (`zh_cn`),
        // while the usual convention - and what a mod is likely to have named its own - is upper.
        val candidates = listOfNotNull(
            "${locale.language}_${country.lowercase()}".takeIf { country.isNotEmpty() },
            "${locale.language}_${country.uppercase()}".takeIf { country.isNotEmpty() },
            locale.language.takeIf { it.isNotEmpty() },
            FALLBACK_LANGUAGE,
        ).distinct()
        for (candidate in candidates) {
            val text = runCatching {
                javaClass.getResourceAsStream("/assets/$NAMESPACE/lang/$candidate.json")
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            val parsed = runCatching { parseLanguage(text) }.getOrNull() ?: continue
            if (parsed.isNotEmpty()) {
                dslLogger.info("web utils: ${parsed.size} translations from $candidate.json")
                return parsed
            }
        }
        dslLogger.warn("web utils: no language file found, labels will show their keys")
        return null
    }

    /** The files are a flat object of strings, so a JSON element walk is the whole parser. */
    private fun parseLanguage(text: String): Map<String, String> =
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }

    override val isInWorld: Boolean get() = false

    /**
     * A local image, described but not read.
     *
     * The contract wants an answer immediately, so this reads the *size* - which is what layout and
     * UV mapping need - and leaves the pixels to the transport: the id it returns is what the client
     * asks for, and it is derived from the path so a second call answers from the cache.
     */
    override fun loadLocalImage(file: File): ImageHolder {
        val id = LOCAL_PREFIX + file.absolutePath
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
        localImages.remove(LOCAL_PREFIX + file.absolutePath)
        return loadLocalImage(file)
    }

    /**
     * The file behind a `local:` id, or null if this backend never described it.
     *
     * The asset endpoint asks before it reads anything, so an id is only ever a file this process
     * offered a page - a client cannot name a path of its own.
     */
    internal fun localImageFile(id: String): File? =
        localImages[id]?.takeIf { it !== ImageHolder.empty }?.let { File(id.removePrefix(LOCAL_PREFIX)) }?.takeIf { it.isFile }

    override fun playButtonSound() {
        // The client has the audio device; the transport turns this into a message.
    }

    override fun openUri(uri: String) {
        dslLogger.info("web utils: asked to open '$uri', which only the client can do")
    }

    override val mainDispatcher: CoroutineDispatcher = frameThread.asCoroutineDispatcher()

    /**
     * Photograph the client's canvas.
     *
     * This is the one contract member whose answer is on the other side of the socket: the pixels are
     * in the browser, so the transport registers [captureSource] and the framework's request becomes
     * a round trip. Without a transport - a measurement run, a test - there is nothing to photograph
     * and `null` is the honest answer.
     */
    internal var captureSource: (() -> ByteArray?)? = null

    override suspend fun capture(fileName: String): File? {
        val png = captureSource?.invoke() ?: return null
        val file = WebMetadata.configDir.resolve("screenshots").resolve(fileName).toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(png)
        return file
    }

    override fun quit(exitCode: Int) {
        dslLogger.info("web utils: the framework asked to quit with $exitCode")
    }

    /** The framework's own assets: the namespace its language files and its icon live under. */
    private const val NAMESPACE = "kotlinmcui"

    private const val FALLBACK_LANGUAGE = "en_us"

    /** Ids this backend mints for a file it described; only those may be served as a file. */
    internal const val LOCAL_PREFIX = "local:"
}
