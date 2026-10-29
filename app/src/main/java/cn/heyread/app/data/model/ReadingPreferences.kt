package cn.heyread.app.data.model

/**
 * The selected font applies to app UI and article text.
 * Numeric typography applies only to article body text.
 */
data class ReadingPreferences(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val lineHeightMultiplier: Float = DEFAULT_LINE_HEIGHT_MULTIPLIER,
    val paragraphSpacingDp: Float = DEFAULT_PARAGRAPH_SPACING_DP,
    val font: ReadingFont = ReadingFont.SYSTEM,
    val customFontId: String? = null,
) {
  fun normalized(): ReadingPreferences =
      copy(
          fontSizeSp =
              fontSizeSp.normalized(DEFAULT_FONT_SIZE_SP, MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
          lineHeightMultiplier =
              lineHeightMultiplier.normalized(
                  DEFAULT_LINE_HEIGHT_MULTIPLIER,
                  MIN_LINE_HEIGHT_MULTIPLIER,
                  MAX_LINE_HEIGHT_MULTIPLIER,
              ),
          paragraphSpacingDp =
              paragraphSpacingDp.normalized(
                  DEFAULT_PARAGRAPH_SPACING_DP,
                  MIN_PARAGRAPH_SPACING_DP,
                  MAX_PARAGRAPH_SPACING_DP,
              ),
          customFontId =
              if (font == ReadingFont.CUSTOM && customFontId.isValidReadingFontId()) customFontId
              else null,
          font =
              if (font == ReadingFont.CUSTOM && !customFontId.isValidReadingFontId())
                  ReadingFont.SYSTEM
              else font,
      )

  private fun Float.normalized(default: Float, minimum: Float, maximum: Float): Float =
      if (!isFinite()) default else coerceIn(minimum, maximum)

  companion object {
    const val DEFAULT_FONT_SIZE_SP = 17f
    const val MIN_FONT_SIZE_SP = 14f
    const val MAX_FONT_SIZE_SP = 26f
    const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 30f / 17f
    const val MIN_LINE_HEIGHT_MULTIPLIER = 1.2f
    const val MAX_LINE_HEIGHT_MULTIPLIER = 2.4f
    const val DEFAULT_PARAGRAPH_SPACING_DP = 20f
    const val MIN_PARAGRAPH_SPACING_DP = 8f
    const val MAX_PARAGRAPH_SPACING_DP = 40f
  }
}

internal fun String?.isValidReadingFontId(): Boolean =
    this != null && matches(Regex("[0-9a-f]{64}"))

data class ImportedReadingFont(val id: String, val displayName: String) {
  fun normalized(): ImportedReadingFont? =
      if (id.isValidReadingFontId())
          copy(
              displayName =
                  displayName.trim().filter { it.code >= 0x20 }.take(48).ifBlank { "导入字体" })
      else null
}

enum class ReadingFont(val storedValue: String) {
  SYSTEM("system"),
  SANS("sans"),
  SERIF("serif"),
  MONO("mono"),
  CUSTOM("custom");

  companion object {
    fun fromStoredValue(value: String?): ReadingFont = parseStoredValue(value).first

    fun parseStoredValue(value: String?): Pair<ReadingFont, String?> {
      if (value?.startsWith("custom:") == true) {
        val id = value.removePrefix("custom:")
        return if (id.isValidReadingFontId()) CUSTOM to id else SYSTEM to null
      }
      return (entries.firstOrNull { it != CUSTOM && it.storedValue == value } ?: SYSTEM) to null
    }

    fun storedValue(font: ReadingFont, customFontId: String?): String =
        when {
          font != CUSTOM -> font.storedValue
          customFontId.isValidReadingFontId() -> "custom:$customFontId"
          else -> SYSTEM.storedValue
        }
  }
}
