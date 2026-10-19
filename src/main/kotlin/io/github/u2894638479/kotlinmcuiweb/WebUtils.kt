package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import io.github.u2894638479.kotlinmcui.backend.DslRootUtils
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import io.github.u2894638479.kotlinmcui.math.px
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import kotlin.concurrent.withLock

/**
 * The non-drawing half of the backend contract, for a client that is on the other side of a socket.
 *
 * Most of it answers from the server's own state rather than from a platform: [translate] reads the
 * language files off the classpath - which is what makes every label already translated by the time a
 * frame is recorded - and the clipboard is mirrored rather than read. What cannot be answered here is
 * a request for pixels that live in the browser, which is why [capture] waits for the transport.
 *
 * The local images are only *described* here: [localImage] answers from a cache, and the first time
 * a file is seen its size is read on [Dispatchers.IO] rather than on the frame thread the contract
 * hands it to - the frame thread gets [ImageHolder.empty] back and the size is published to the
 * cache, on the frame thread, once the read is done. The transport serves the bytes when a client
 * asks for the id the recorder wrote for that file. Nothing else may be served for a `local:` id, so
 * a client cannot name a path of its own. Entries nothing has asked for in a while are swept once per
 * frame; see [sweepLocalImages].
 *
 * [sessionDispatcher] is a single thread on purpose. The framework's context is thread-confined - it
 * records the thread that created it and refuses to be touched from another - so a backend hosted by
 * a server has to name *one* thread as the frame thread, and this is it. A transport that wants to
 * render from a socket reader must hop onto this dispatcher first.
 *
 * The two interface halves are implemented here because both answer from server state rather than from
 * a platform: the process-level questions ([translate], [isInWorld], the local images, [quit]) and the
 * per-output ones ([clipboard], [capture], [narrate], [playButtonSound], [openUri]). Splitting them
 * across two objects would only move the same fields around.
 */
internal object WebUtils : DslPlatformUtils, DslRootUtils {
    @Volatile
    private var frameThreadRef: Thread? = null

    /**
     * The one thread the framework lives on, kept as the pool rather than as an `Executor` so
     * teardown can ask what is still queued for it.
     */
    private val framePool = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, FRAME_THREAD_NAME).apply {
            isDaemon = true
            frameThreadRef = this
        }
    }

    /**
     * The thread every framework call must happen on: creating a screen, taking a frame, dispatching
     * input. Exposed as an [java.util.concurrent.Executor] because a transport wants to hand work to
     * it rather than suspend on it.
     */
    internal val frameExecutor: Executor get() = framePool

    /** Whether the calling thread is the frame thread, which decides between inline and hand-off. */
    internal val isFrameThread: Boolean get() = Thread.currentThread() === frameThreadRef

    /** The contract's name for the same fact: this root's session thread is the frame thread. */
    override val isOnSessionThread: Boolean get() = isFrameThread

    /**
     * Work queued on the frame thread and not started yet.
     *
     * A teardown that hands the page release to the frame thread waits for it, so this reads zero
     * once the backend is actually finished. It is an observable and not a private detail because
     * "the executor side was reclaimed" is otherwise a claim no test can check.
     */
    internal val pendingFrameTasks: Int get() = framePool.queue.size

    /**
     * Run [block] on the frame thread and wait for it, or inline when already there.
     *
     * The framework's contexts belong to the thread that created them, so releasing a page - which
     * disposes its life scopes and their session coroutines - has to happen on the frame thread.
     * A caller of `stop()` needs it synchronously: a backend that has said it stopped must not still
     * have teardown queued behind it.
     */
    internal fun onFrameThread(block: () -> Unit) {
        if (isFrameThread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        framePool.execute {
            try {
                block()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                done.countDown()
            }
        }
        if (!done.await(FRAME_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
            dslLogger.warn("web utils: the frame thread did not finish teardown within ${FRAME_JOIN_MILLIS} ms")
            return
        }
        failure?.let { throw it }
    }

    /**
     * Local images by the id the recorder writes for them, with the last time each was asked for.
     *
     * The key is the id and not the file, because [localImageFile] is the only thing that turns an id
     * back into a path: a file a page never named has no entry, so it cannot be served.
     *
     * A plain [HashMap] is enough because the frame thread is the only thread that changes it -
     * [localImage], [reloadLocalImage] and [sweepLocalImages] all run there, and the background size
     * read never touches it: what it learns is handed back to the frame thread, which writes it.
     *
     * A null [CacheEntry.holder] means "still being read"; [ImageHolder.empty] is a finished answer
     * that the file could not be read, remembered so a broken file is warned about once rather than
     * reopened every frame.
     */
    private val localImages = HashMap<String, CacheEntry>()

    private class CacheEntry(val file: File, var lastUsed: Long, var holder: ImageHolder?)

    /**
     * Where a file's size is read.
     *
     * [Dispatchers.IO] is daemon-backed, which is what lets a headless `measure` or a stopping server
     * exit while a read is still in flight: no thread here keeps the process alive, and a read that
     * never resumes has nobody waiting on it.
     */
    private val imageLoader = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The wall clock, injectable so the ttl can be tested by moving time instead of sleeping. */
    internal var wallClock: () -> Long = System::currentTimeMillis

    /** Background size reads that have not published yet; guarded by [loads] and changed on the frame thread. */
    private var loadingImages = 0

    private val loads = ReentrantLock()

    private val loadsSettled = loads.newCondition()

    /**
     * Wait until every background size read has published, or [timeoutMillis] passes.
     *
     * Test-facing: the frame thread never waits for an image, so this exists for the one thing the
     * contract needs said out loud - that the reading happens at all. It must not be called from the
     * frame thread, which is the thread that publishes.
     */
    internal fun awaitLocalImageLoads(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        loads.withLock {
            while (loadingImages > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                loadsSettled.awaitNanos(remaining)
            }
            return true
        }
    }

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
     * A local image, described but not read on this thread.
     *
     * The contract runs this on the frame thread and forbids it to wait for io, so it only reads the
     * cache: a known file refreshes its entry and answers at once, and an unknown one starts a
     * background read and answers [ImageHolder.empty] - "nothing to draw yet", which is exactly what
     * the framework draws for an image that has not arrived. The next call, once the read has
     * published, answers with the file's size.
     *
     * The id is derived from the path, so the same file asked for twice is one entry and one read.
     */
    override fun localImage(file: File): ImageHolder {
        val id = localImageId(file)
        val cached = localImages[id]
        if (cached != null) {
            cached.lastUsed = wallClock()
            return cached.holder ?: ImageHolder.empty
        }
        val entry = CacheEntry(file, wallClock(), holder = null)
        localImages[id] = entry
        loads.withLock { loadingImages++ }
        imageLoader.launch {
            val size = runCatching { ImageIO.read(file) }.getOrNull()
            if (size == null) {
                dslLogger.warn("web assets: '$file' could not be read, the client will draw its placeholder")
            }
            publishLocalImage(id, entry, size)
        }
        return ImageHolder.empty
    }

    /**
     * Put a background read's answer where the cache is allowed to change: back on the frame thread.
     *
     * The entry is matched by identity, not by id: a [reloadLocalImage] during the read removed that
     * entry, and this answer belongs to the entry that is gone - writing it back would resurrect a
     * file the page has already dropped.
     */
    private fun publishLocalImage(id: String, entry: CacheEntry, size: BufferedImage?) {
        val queued = runCatching {
            framePool.execute {
                if (localImages[id] === entry) {
                    entry.holder = size
                        ?.let { ImageHolder(ImageSource.LocalFile(entry.file), it.width.px, it.height.px) }
                        ?: ImageHolder.empty
                }
                settleLocalImageLoad()
            }
        }.isSuccess
        if (!queued) settleLocalImageLoad()
    }

    private fun settleLocalImageLoad() = loads.withLock {
        loadingImages--
        loadsSettled.signalAll()
    }

    /**
     * Drop the entries nothing has asked for within [LOCAL_IMAGE_TTL_MILLIS].
     *
     * Called once per frame from `WebBackend.frame`, which happens on the frame thread, so this is
     * neither a timer nor a concurrent operation. A page that still draws an image asks for it while
     * it builds the frame, which refreshes its entry, so what the sweep drops is the images a page
     * has stopped using - keeping the map bounded by what the current pages actually draw.
     */
    internal fun sweepLocalImages(now: Long = wallClock()) {
        val entries = localImages.entries.iterator()
        while (entries.hasNext()) {
            if (now - entries.next().value.lastUsed > LOCAL_IMAGE_TTL_MILLIS) entries.remove()
        }
    }

    /** Whether [file] has an entry, loading or answered. Test-facing: the cache has no other observer. */
    internal fun isLocalImageCached(file: File): Boolean = localImages.containsKey(localImageId(file))

    /** The same, with the cache dropped, so the transport serves the file as it is now. */
    override fun reloadLocalImage(file: File) {
        // The failed reads are entries too, so this is also what lets a file that appeared since be
        // picked up: the next call starts over rather than answering from the remembered failure.
        localImages.remove(localImageId(file))
    }

    /**
     * A remote image: nothing to draw yet.
     *
     * The contract forbids waiting on io here, so this cannot download anything. A transport that
     * gains a downloader can start answering from a cache; until then `empty` is the honest answer,
     * and a page that uses one draws the client's placeholder.
     */
    override fun remoteImage(url: String): ImageHolder = ImageHolder.empty

    /** Nothing is cached, so there is nothing to drop. */
    override fun reloadRemoteImage(url: String) = Unit

    /**
     * The id the recorder writes for a local file.
     *
     * The transport serves only the files a page described, so the path travels under a prefix of
     * this backend's own and [localImageFile] is the only thing that turns it back into a file.
     */
    internal fun localImageId(file: File): String = LOCAL_PREFIX + file.absolutePath

    /**
     * The file behind a `local:` id, or null if this backend never described it *or* the description
     * is not finished yet.
     *
     * The asset endpoint asks before it reads anything, so an id is only ever a file this process
     * offered a page - a client cannot name a path of its own. The lookup itself is a plain map read
     * because the transport asks from the connection's own thread; it is the *frame* thread that is
     * forbidden to read the file, and it never does.
     */
    internal fun localImageFile(id: String): File? =
        (localImages[id]?.holder?.source as? ImageSource.LocalFile)?.file?.takeIf { it.isFile }

    override fun playButtonSound() {
        // The client has the audio device; the transport turns this into a message.
    }

    override fun openUri(uri: String) {
        dslLogger.info("web utils: asked to open '$uri', which only the client can do")
    }

    override val sessionDispatcher: CoroutineDispatcher = framePool.asCoroutineDispatcher()

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

    /**
     * How long a local image entry nothing has asked for survives the once-a-frame sweep.
     *
     * A page that draws an image asks for it on every frame it builds, so a minute is long enough to
     * cover a page whose tree is rebuilt only when something changes, and short enough that images a
     * page has stopped showing do not accumulate. Eviction changes nothing a frame records: only
     * later calls can tell, and they start a new read.
     */
    internal const val LOCAL_IMAGE_TTL_MILLIS = 60_000L

    /** The frame thread's name, which a log line and a thread dump both use to identify it. */
    private const val FRAME_THREAD_NAME = "kotlinmcui-web"

    /** How long a teardown waits for the frame thread before saying so and giving up. */
    private const val FRAME_JOIN_MILLIS = 10_000L
}
