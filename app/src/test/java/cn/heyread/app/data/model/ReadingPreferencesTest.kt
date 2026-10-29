package cn.heyread.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPreferencesTest {
    @Test
    fun defaultsAndFiniteBoundsAreNormalized() {
        assertEquals(ReadingPreferences(), ReadingPreferences().normalized())
        assertEquals(
            ReadingPreferences(
                fontSizeSp = ReadingPreferences.MIN_FONT_SIZE_SP,
                lineHeightMultiplier = ReadingPreferences.MAX_LINE_HEIGHT_MULTIPLIER,
                paragraphSpacingDp = ReadingPreferences.MIN_PARAGRAPH_SPACING_DP,
                font = ReadingFont.SERIF,
            ),
            ReadingPreferences(
                    fontSizeSp = 10f,
                    lineHeightMultiplier = 3f,
                    paragraphSpacingDp = 0f,
                    font = ReadingFont.SERIF,
                )
                .normalized(),
        )
        assertEquals(
            ReadingPreferences(
                fontSizeSp = ReadingPreferences.MAX_FONT_SIZE_SP,
                lineHeightMultiplier = ReadingPreferences.MIN_LINE_HEIGHT_MULTIPLIER,
                paragraphSpacingDp = ReadingPreferences.MAX_PARAGRAPH_SPACING_DP,
            ),
            ReadingPreferences(
                    fontSizeSp = 40f,
                    lineHeightMultiplier = 0.5f,
                    paragraphSpacingDp = 55f,
                )
                .normalized(),
        )
    }

    @Test
    fun nonFiniteFieldsFallBackIndependentlyAndUnknownFontUsesSystem() {
        assertEquals(
            ReadingPreferences(
                fontSizeSp = ReadingPreferences.DEFAULT_FONT_SIZE_SP,
                lineHeightMultiplier = ReadingPreferences.DEFAULT_LINE_HEIGHT_MULTIPLIER,
                paragraphSpacingDp = ReadingPreferences.DEFAULT_PARAGRAPH_SPACING_DP,
                font = ReadingFont.SERIF,
            ),
            ReadingPreferences(
                    fontSizeSp = Float.NaN,
                    lineHeightMultiplier = Float.POSITIVE_INFINITY,
                    paragraphSpacingDp = Float.NEGATIVE_INFINITY,
                    font = ReadingFont.SERIF,
                )
                .normalized(),
        )
        assertEquals(ReadingFont.SYSTEM, ReadingFont.fromStoredValue("unknown-font"))
        assertEquals(ReadingFont.SYSTEM, ReadingFont.fromStoredValue(null))
    }

    @Test
    fun customFontIdIsValidatedAndRoundTripsThroughStoredValue() {
        val validId = "0123456789abcdef".repeat(4)
        val selected = ReadingPreferences(font = ReadingFont.CUSTOM, customFontId = validId)

        assertEquals(selected, selected.normalized())
        assertEquals(ReadingFont.CUSTOM to validId, ReadingFont.parseStoredValue("custom:$validId"))
        assertEquals("custom:$validId", ReadingFont.storedValue(ReadingFont.CUSTOM, validId))
        assertEquals(ReadingFont.SYSTEM to null, ReadingFont.parseStoredValue("custom:BAD"))
        assertEquals(ReadingFont.SYSTEM to null, ReadingFont.parseStoredValue("custom:short"))
        assertEquals("system", ReadingFont.storedValue(ReadingFont.CUSTOM, null))
    }

    @Test
    fun nonCustomFontClearsCustomIdAndImportedDisplayNameIsSafe() {
        val validId = "fedcba9876543210".repeat(4)

        assertEquals(
            ReadingPreferences(font = ReadingFont.SERIF),
            ReadingPreferences(font = ReadingFont.SERIF, customFontId = validId).normalized(),
        )
        assertEquals(
            ImportedReadingFont(validId, "Friendly Font"),
            ImportedReadingFont(validId, "  Friendly\u0000 Font\n").normalized(),
        )
        assertEquals(null, ImportedReadingFont("../font", "Unsafe").normalized())
    }
}
