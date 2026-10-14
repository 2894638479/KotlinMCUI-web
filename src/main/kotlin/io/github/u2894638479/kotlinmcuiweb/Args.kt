package io.github.u2894638479.kotlinmcuiweb

/** `--name value` or `--name=value`, whichever a caller (or Gradle) produced. */
internal fun option(args: Array<String>, name: String): String? {
    val prefix = "$name="
    args.firstOrNull { it.startsWith(prefix) }?.let { return it.substring(prefix.length) }
    val at = args.indexOf(name)
    return if (at >= 0 && at + 1 < args.size) args[at + 1] else null
}
