package io.github.u2894638479.kotlinmcuiweb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What concurrent asset requests may and may not do to the answer cache.
 *
 * [WebAssets] is reached from every connection's own thread - [WebServer] starts one thread per
 * socket - so its caches are read and written by several threads at once. The severe failure this
 * case is aimed at is a corrupted hash table: a spin inside the table's own lookup that pins a core
 * forever, which no single-threaded case can see.
 *
 * Stated plainly, because the distinction decides how much weight the result carries:
 *
 * - It **can** fail if a worker never finishes (a spin), if a call throws, or if a concurrent call
 *   answers with the wrong bytes.
 * - It **cannot** prove the absence of a lost cache entry. A lost entry is self-healing here:
 *   [WebAssets.bytes] falls back to a deterministic lookup, so a forgotten hit is recomputed and a
 *   forgotten miss is rescanned, both invisibly.
 *
 * A green run is therefore evidence that the cache still answers correctly under concurrency. It is
 * not proof that the cache is structurally sound - for that the argument has to be about the
 * containers, not about a run.
 */
class WebAssetsTest {

    /**
     * Hammer the cache from many threads and insist that every answer is still the right one.
     *
     * Each round reads a known id (so the hit path and the shared byte arrays are exercised) and asks
     * for an id nobody has seen (so the miss path keeps inserting - reads alone would never grow the
     * table and would never race a resize).
     */
    @Test
    fun `concurrent lookups answer correctly and finish`() {
        val known = listOf("kotlinmcui:icon.png", "kotlinmcui:lang/en_us.json")
        val expected = known.associateWith { id -> classpathBytes(id) }
        for ((id, bytes) in expected) {
            assertTrue(bytes.isNotEmpty(), "the fixture '$id' is not on the classpath, so the case would be vacuous")
        }

        val threads = 8
        val phases = 8
        val rounds = 300
        val start = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val thrown = AtomicReference<Throwable?>(null)
        val wrong = AtomicInteger(0)

        repeat(threads) { worker ->
            Thread({
                start.await()
                try {
                    repeat(phases) { phase ->
                        repeat(rounds) { round ->
                            val id = known[(worker + round) % known.size]
                            if (!expected.getValue(id).contentEquals(WebAssets.bytes(id))) wrong.incrementAndGet()
                            if (WebAssets.bytes("kotlinmcui-test:absent-$worker-$phase-$round.png") != null) wrong.incrementAndGet()
                        }
                    }
                } catch (throwable: Throwable) {
                    thrown.compareAndSet(null, throwable)
                } finally {
                    finished.countDown()
                }
            }, "web-assets-test-$worker").apply {
                isDaemon = true
                start()
            }
        }

        start.countDown()
        assertTrue(
            finished.await(60, TimeUnit.SECONDS),
            "a concurrent lookup never came back, which is what a corrupted cache looks like",
        )
        thrown.get()?.let { throw AssertionError("a concurrent lookup threw", it) }
        assertEquals(0, wrong.get(), "a concurrent lookup answered with bytes that are not that asset's")
    }

    /** The bytes the classpath holds for an asset id, resolved the way [WebAssets] resolves it. */
    private fun classpathBytes(id: String): ByteArray {
        val separator = id.indexOf(':')
        val candidate = "/assets/${id.substring(0, separator)}/${id.substring(separator + 1)}"
        return javaClass.getResourceAsStream(candidate)?.use { it.readBytes() }
            ?: error("the classpath has no $candidate")
    }
}
