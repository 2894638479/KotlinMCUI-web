package io.github.u2894638479.kotlinmcuiweb

import io.github.u2894638479.kotlinmcui.context.DslContext
import io.github.u2894638479.kotlinmcui.entry.DslEntryPage
import io.github.u2894638479.kotlinmcui.test.TestAnimationPage
import io.github.u2894638479.kotlinmcui.test.TestBackendRenderPage
import io.github.u2894638479.kotlinmcui.test.TestColorPage
import io.github.u2894638479.kotlinmcui.test.TestImagePage
import io.github.u2894638479.kotlinmcui.test.TestLayoutPage
import io.github.u2894638479.kotlinmcui.test.TestScrollPage
import io.github.u2894638479.kotlinmcui.test.TestTextPage
import io.github.u2894638479.kotlinmcui.test.TestTransformPage

/** The names a command line can ask for, in the order the help text lists them. */
internal val PAGE_NAMES = listOf(
    "entry", "color", "render", "transform", "text", "scroll", "animation", "layout", "image",
)

/**
 * The page a name stands for.
 *
 * It is a context function rather than a table of `DslFunction` values on purpose: a `DslFunction`
 * carries the DSL context, and Kotlin only propagates a context type into a lambda in argument
 * position - a `when` branch or a stored function reference would not have one. Calling the page
 * here, inside the context, is the shape that works.
 */
context(ctx: DslContext)
internal fun pageNamed(name: String) {
    when (name) {
        "entry" -> DslEntryPage()
        "color" -> TestColorPage()
        "render" -> TestBackendRenderPage()
        "transform" -> TestTransformPage()
        "text" -> TestTextPage()
        "scroll" -> TestScrollPage()
        "animation" -> TestAnimationPage()
        "layout" -> TestLayoutPage()
        "image" -> TestImagePage()
        else -> error("unknown page '$name'; try ${PAGE_NAMES.joinToString(", ")}")
    }
}
