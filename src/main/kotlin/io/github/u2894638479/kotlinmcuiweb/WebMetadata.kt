package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.backend.DslPlatformMetadata
import io.github.u2894638479.kotlinmcui.backend.Environment
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What this backend can say about the process it runs in.
 *
 * A web backend has no game to ask: the working directory is the installation directory, the game
 * version is whatever the host put in [GAME_VERSION_PROPERTY] (empty on a plain JVM), and the loader
 * is not guessed - a host that runs under one reports it itself (see [gameLoader]).
 *
 * [environment] is [Environment.STANDALONE]: no game side exists in this process. The surface is a
 * browser, and a browser may be looking at a dedicated server's UI just as well as a client's - so
 * neither side may be claimed on this backend's behalf. Standalone does not mean "load no entries":
 * the registry still loads every entry that declared standalone.
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

    /**
     * Empty: an independent process has no loader hosting it.
     *
     * A host that does run under one - a mod on Fabric, NeoForge or Forge - publishes its own
     * metadata and fills this in where the loader is actually known. Probing the classpath here could
     * only answer for a loader that is *not* hosting this process; the one case where such a probe
     * fires is a main run by hand on a loader's classpath, and that is not a game.
     */
    override val gameLoader: String get() = ""

    override val environment: Environment get() = Environment.STANDALONE
}
