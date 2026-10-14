package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.logger.dslLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Where an asset id becomes pixels.
 *
 * Two places are searched, in the order that makes a page look right without a game: the classpath
 * first (the framework's own icon and anything a mod ships as a resource), then the jars of the game
 * installation the host points at - the client jar for vanilla textures and items, and every jar in
 * the `mods` directory for what mods add. Without a game directory the second search finds nothing,
 * every asset the game owns is answered with a miss, and the client draws its placeholder.
 *
 * Ids are the framework's `namespace:path`. A path that already names a directory is taken as it is
 * (`minecraft:textures/block/dirt.png`); a bare name is an item or a block, which is where the game
 * keeps those textures. Both answers are remembered, hits and misses alike: a frame references the
 * same handful of ids every frame, and a miss must not mean rescanning a jar sixty times a second.
 */
internal object WebAssets {
    private val resolved = HashMap<String, ByteArray>()
    private val missing = HashSet<String>()
    private var jars: List<Path>? = null

    /** The bytes of [id], or null when nothing has it. */
    fun bytes(id: String): ByteArray? {
        resolved[id]?.let { return it }
        if (id in missing) return null
        val found = lookup(id)
        if (found == null) missing += id else resolved[id] = found
        return found
    }

    /** Forget every answer, so a changed installation is picked up. */
    fun clear() {
        resolved.clear()
        missing.clear()
        jars = null
    }

    private fun lookup(id: String): ByteArray? {
        val separator = id.indexOf(':')
        val namespace = if (separator > 0) id.substring(0, separator) else ""
        val path = if (separator > 0) id.substring(separator + 1) else id
        for (candidate in candidates(namespace, path)) {
            javaClass.getResourceAsStream(candidate)?.use { return it.readBytes() }
        }
        for (jar in gameJars()) {
            for (candidate in candidates(namespace, path)) {
                readFrom(jar, candidate.removePrefix("/"))?.let { return it }
            }
        }
        return null
    }

    /**
     * The paths an id can mean, most specific first.
     *
     * A bare name is an item or a block - that is how the framework's own demo pages spell them
     * (`minecraft:diamond`) - while anything that names a directory is a path into `assets`.
     */
    private fun candidates(namespace: String, path: String): List<String> {
        if (namespace.isEmpty()) return listOf("/$path", "/$path.png")
        if (path.contains('/')) return listOf("/assets/$namespace/$path")
        return listOf(
            "/assets/$namespace/textures/item/$path.png",
            "/assets/$namespace/textures/block/$path.png",
            "/assets/$namespace/$path",
            "/assets/$namespace/$path.png",
        )
    }

    private fun gameJars(): List<Path> {
        jars?.let { return it }
        val gameDir = WebMetadata.gameDir
        val found = ArrayList<Path>()
        runCatching {
            val versions = gameDir.resolve("versions")
            if (Files.isDirectory(versions)) {
                Files.list(versions).use { directories ->
                    directories.forEach { version ->
                        if (!Files.isDirectory(version)) return@forEach
                        Files.list(version).use { files ->
                            files.forEach { file -> if (file.isJar()) found.add(file) }
                        }
                    }
                }
            }
            val mods = gameDir.resolve("mods")
            if (Files.isDirectory(mods)) {
                Files.list(mods).use { files -> files.forEach { file -> if (file.isJar()) found.add(file) } }
            }
        }.onFailure { dslLogger.warn("web assets: '${gameDir}' could not be scanned: ${it.message}") }
        if (found.isEmpty()) dslLogger.info("web assets: no game jars under '$gameDir', game textures stay placeholders")
        jars = found
        return found
    }

    private fun Path.isJar(): Boolean = Files.isRegularFile(this) && fileName.toString().endsWith(".jar")

    private fun readFrom(jar: Path, entry: String): ByteArray? = runCatching {
        ZipFile(jar.toFile()).use { zip ->
            zip.getEntry(entry)?.let { found -> zip.getInputStream(found).use { it.readBytes() } }
        }
    }.getOrNull()
}
