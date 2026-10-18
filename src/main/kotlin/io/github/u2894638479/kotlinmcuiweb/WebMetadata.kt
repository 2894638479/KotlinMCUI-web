package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslPlatformMetadata
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
 * [environment] is [Environment.NONE]: nothing here needs a game side. The surface is a browser, and
 * a browser may be looking at a dedicated server's UI just as well as a client's - so the runtime
 * must not initialise either game entry point on this backend's behalf.
 */
internal object WebMetadata : DslPlatformMetadata {
    const val GAME_VERSION_PROPERTY = "kotlinmcuiweb.gameVersion"

    /**
     * Where the assets of a game installation are looked for.
     *
     * The working directory by default, which is the installation when a host starts this backend
     * inside one. `-Dkotlinmcuiweb.gameDir=` points it somewhere else, which is what a standalone
     * server wants: it runs from its own directory but can still serve the textures of an install.
     */
    const val GAME_DIR_PROPERTY = "kotlinmcuiweb.gameDir"

    private const val STANDALONE = "web"

    override val gameDir: Path get() = System.getProperty(GAME_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.dir") ?: ".")

    override val configDir: Path get() = gameDir.resolve("config")

    override val gameVersion: String get() = System.getProperty(GAME_VERSION_PROPERTY).orEmpty().ifEmpty { STANDALONE }

    override val gameLoader: String get() = detectedLoader

    override val environment: Environment get() = Environment.NONE

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
