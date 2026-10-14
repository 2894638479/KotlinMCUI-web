package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Serve the UI to a browser.
 *
 * `--port`, `--page`, `--width` and `--height`; the URL is printed once the page is built. The
 * process then owns a page and a frame clock, and stops on Ctrl+C.
 */
fun main(args: Array<String>) {
    val page = option(args, "--page") ?: "entry"

    DslRuntime.install { WebMetadata }
    DslRuntime.platformReady { WebBackend }

    val server = WebServer(
        port = (option(args, "--port") ?: "8080").toInt(),
        page = page,
        initialWidth = (option(args, "--width") ?: "1280").toInt(),
        initialHeight = (option(args, "--height") ?: "720").toInt(),
    )
    server.start()

    println("kotlinmcui-web: http://127.0.0.1:${server.boundPort}/  (page '$page', Ctrl+C to stop)")

    // `--capture=<file>` waits for a browser, asks it for a picture of its canvas, writes it and
    // leaves. It is the acceptance check a person would otherwise do by looking at the screen, and it
    // exercises the whole path: page, script, socket, frames, canvas and the capture round trip.
    val captureFile = option(args, "--capture")
    if (captureFile != null) {
        val png = server.capture()
        server.stop()
        if (png == null) {
            println("kotlinmcui-web: no client answered the capture request")
            exitProcess(1)
        }
        val file = File(captureFile)
        file.parentFile?.mkdirs()
        file.writeBytes(png)
        println("kotlinmcui-web: capture written to ${file.absolutePath} (${file.length()} B)")
        exitProcess(0)
    }

    // The accept loop runs on a daemon thread, so the process would otherwise end here.
    CountDownLatch(1).await()
}
