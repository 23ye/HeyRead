package cn.heyread.app.data.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextTest {
    @Test
    fun paragraphsAndSeparatorsStaySeparateAndRebuildExactly() {
        val original = "First paragraph.\r\nSecond 😀 paragraph.\nThird paragraph."

        val fragments = TranslationText.split(original, maxCodeUnits = 100)
        val translatable = fragments.filter { it.text.isNotEmpty() }

        assertEquals(3, translatable.size)
        assertTrue(translatable.none { '\n' in it.text || '\r' in it.text })
        assertTrue(fragments.drop(1).any { it.prefix == "\r\n" })
        assertTrue(fragments.drop(1).any { it.prefix == "\n" })
        assertEquals(original, fragments.joinToString("") { it.prefix + it.text })
    }

    @Test
    fun longTextIsBoundedWithoutCuttingSurrogatePairsOrDroppingWhitespace() {
        val original = ("word 😀 ".repeat(500)) + "\nend"

        val fragments = TranslationText.split(original, maxCodeUnits = 64)

        assertTrue(fragments.filter { it.text.isNotEmpty() }.all { it.text.length <= 64 })
        assertTrue(fragments.none { it.text.endsWithHighSurrogate() })
        assertEquals(original, fragments.joinToString("") { it.prefix + it.text })
    }

    @Test
    fun emptyAndWhitespaceOnlyInputsDoNotCreateTranslatableContent() {
        assertTrue(TranslationText.split("").isEmpty())
        assertFalse(TranslationText.split(" \n ").any { it.text.isNotBlank() })
    }

    private fun String.endsWithHighSurrogate(): Boolean = isNotEmpty() && last().isHighSurrogate()
}
