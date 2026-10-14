package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.Environment
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What this backend can say about the process it runs in.
 *
 * A web backend has no game to ask: the working directory is the installation directory, and the
 * game version is whatever the host put in [GAME_VERSION_PROPERTY] (empty when the process is a
 * plain JVM). The loader is read off the classpath rather than reported by the host, so that a
 * backend hosted by a game and one hosted by nothing report the same thing about themselves.
 *
 * [environment] is [Environment.CLIENT] because a browser is somebody looking at a screen. It is
 * *not* what makes this backend usable on a dedicated server: that is the fact that nothing here
 * needs a client, and it is why a server-side host can publish this backend and get a UI at all.
 */
internal object WebMetadata : DslBackendMetadata {
    const val GAME_VERSION_PROPERTY = "kotlinmcuiweb.gameVersion"

    private const val STANDALONE = "web"

    override val gameDir: Path get() = Paths.get(System.getProperty("user.dir") ?: ".")

    override val configDir: Path get() = gameDir.resolve("config")

    override val gameVersion: String get() = System.getProperty(GAME_VERSION_PROPERTY).orEmpty().ifEmpty { STANDALONE }

    override val gameLoader: String get() = detectedLoader

    override val environment: Environment get() = Environment.CLIENT

    private val detectedLoader: String by lazy {
        when {
            present("net.fabricmc.loader.api.FabricLoader") -> "fabric"
            present("net.neoforged.fml.ModList") -> "neoforge"
            present("net.minecraftforge.fml.ModList") -> "forge"
            else -> STANDALONE
        }
    }

    private fun present(className: String) =
        runCatching { Class.forName(className, false, javaClass.classLoader) }.isSuccess
}
