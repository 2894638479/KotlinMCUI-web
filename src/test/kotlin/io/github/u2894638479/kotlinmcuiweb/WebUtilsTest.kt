package io.github.u2894638479.kotlinmcuiweb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Translation, and the convention that goes with it.
 *
 * The framework asks for a translation while it builds the tree and shows the key when it gets
 * nothing, so a key that is missing has to return null rather than an empty string - and a key that
 * exists has to come back as the language file's text, with its arguments already formatted in.
 */
class WebUtilsTest {
    @Test
    fun `a language file is found and a missing key is answered with nothing`() {
        val translated = WebUtils.translate("kotlinmcui.widget.true")
        assertNotNull(translated, "the framework's own language file was not on the classpath")
        assertTrue(translated.isNotEmpty())
        assertTrue(translated != "kotlinmcui.widget.true", "the key came back untranslated")

        assertNull(WebUtils.translate("kotlinmcui.nothing.of.the.sort"))
    }

    @Test
    fun `the language a client reports decides which file is read`() {
        WebUtils.useLanguage("zh-CN")
        assertEquals("开", WebUtils.translate("kotlinmcui.widget.true"))

        WebUtils.useLanguage("en_us")
        val english = WebUtils.translate("kotlinmcui.widget.true")
        assertNotNull(english)
        assertTrue(english != "开", "the language did not change: $english")
    }

    @Test
    fun `arguments are formatted into the text`() {
        val translated = WebUtils.translate("kotlinmcui.test_translate", "a", 7, 1.5f)
        assertNotNull(translated)
        assertTrue(translated.contains("a"), translated)
        assertTrue(translated.contains("7"), translated)
    }
}
