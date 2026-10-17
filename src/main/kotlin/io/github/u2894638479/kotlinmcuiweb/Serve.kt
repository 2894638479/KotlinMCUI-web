package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Serve the UI to a browser.
 *
 * `--port`, `--page`, `--width`, `--height` and `--wait`; the URL is printed as soon as the socket is
 * bound, before the page is built, so whoever drives this can start a browser in parallel rather than
 * waiting the build out. The process then owns a page and a frame clock, and stops on Ctrl+C.
 */
fun main(args: Array<String>) {
    val page = option(args, "--page") ?: "entry"

    // How long a capture waits for a browser to turn up. The default stays where it is and a caller
    // raises it explicitly: a gate that hangs for two minutes when nobody connects slows every batch
    // down, and the wait is there to give a slow machine room, not to blur "no browser" into "browser".
    val waitSeconds = (option(args, "--wait") ?: (WebServer.DEFAULT_WAIT_FOR_CLIENT_MILLIS / 1000).toString()).toLong()

    DslRuntime.install { WebMetadata }
    DslRuntime.registerProvider(WebRootProvider)
    // Losing the platform race says one thing only: this process's platform belongs to another
    // backend. It does not stop this one serving its own roots, so there is no early return here -
    // and that is a decision, not an omission.
    if (!DslRuntime.platformReady { WebPlatform }) {
        dslLogger.warn("web: another backend published this process's platform; this one serves its own roots")
    }

    val server = WebServer(
        port = (option(args, "--port") ?: "8080").toInt(),
        page = page,
        initialWidth = (option(args, "--width") ?: "1280").toInt(),
        initialHeight = (option(args, "--height") ?: "720").toInt(),
    )

    // Bound, but not yet built. Saying so first is what lets a driver launch the browser while the
    // page is still being constructed.
    println("kotlinmcui-web: listening on http://127.0.0.1:${server.boundPort}/  (page '$page', Ctrl+C to stop)")
    System.out.flush()

    server.start()

    // `--capture=<file>` waits for a browser, asks it for a picture of its canvas, writes it and
    // leaves. It is the acceptance check a person would otherwise do by looking at the screen, and it
    // exercises the whole path: page, script, socket, frames, canvas and the capture round trip.
    val captureFile = option(args, "--capture")
    if (captureFile != null) {
        println(
            "kotlinmcui-web: waiting for a browser " +
                "(up to ${waitSeconds}s to connect, then ${WebServer.ANSWER_MILLIS / 1000}s to answer)",
        )
        System.out.flush()

        val result = server.capture(waitForClientMillis = waitSeconds * 1000)
        server.stop()

        // The three outcomes are three different faults, so they say three different things. The two
        // that failed name what they waited for, because "no client" and "a client that went quiet"
        // are fixed in different places.
        when (result) {
            is WebServer.Capture.Answered -> {
                val file = File(captureFile)
                file.parentFile?.mkdirs()
                file.writeBytes(result.png)
                println("kotlinmcui-web: capture written to ${file.absolutePath} (${file.length()} B, browser ${result.session})")
                exitProcess(0)
            }
            is WebServer.Capture.NoClient -> {
                println("kotlinmcui-web: no browser connected within ${result.waitedMillis / 1000}s")
                exitProcess(1)
            }
            is WebServer.Capture.Unanswered -> {
                println("kotlinmcui-web: browser ${result.session} connected but did not answer within ${result.waitedMillis / 1000}s")
                exitProcess(1)
            }
        }
    }

    // The accept loop runs on a daemon thread, so the process would otherwise end here.
    CountDownLatch(1).await()
}
