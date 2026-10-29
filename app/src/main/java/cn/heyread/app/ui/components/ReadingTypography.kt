package cn.heyread.app.ui.components

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.data.model.ReadingPreferences

val LocalCustomReadingTypeface = compositionLocalOf<Typeface?> { null }
val LocalGlobalReadingFont = compositionLocalOf { ReadingFont.SYSTEM }

@Composable
internal fun ReadingFont.toComposeFontFamily(
  customTypeface: Typeface? = LocalCustomReadingTypeface.current
): FontFamily {
  return remember(this, customTypeface) {
    when (this) {
      ReadingFont.SYSTEM -> FontFamily.Default
      ReadingFont.SANS -> FontFamily.SansSerif
      ReadingFont.SERIF -> FontFamily.Serif
      ReadingFont.MONO -> FontFamily.Monospace
      ReadingFont.CUSTOM -> customTypeface?.let { FontFamily(it) } ?: FontFamily.Default
    }
  }
}

internal fun ReadingFont.toAndroidTypeface(customTypeface: Typeface? = null): Typeface =
  when (this) {
    ReadingFont.SYSTEM -> Typeface.DEFAULT
    ReadingFont.SANS -> Typeface.SANS_SERIF
    ReadingFont.SERIF -> Typeface.SERIF
    ReadingFont.MONO -> Typeface.MONOSPACE
    ReadingFont.CUSTOM -> customTypeface ?: Typeface.DEFAULT
  }

internal val ReadingPreferences.bodyFontSize: TextUnit
  get() = fontSizeSp.sp

internal val ReadingPreferences.bodyLineHeight: TextUnit
  get() = (fontSizeSp * lineHeightMultiplier).sp

internal val ReadingPreferences.paragraphBottomSpacing: androidx.compose.ui.unit.Dp
  get() = (paragraphSpacingDp - BASE_ITEM_SPACING_DP).dp

internal val ReadingPreferences.scaledListSpacing: androidx.compose.ui.unit.Dp
  get() = (paragraphSpacingDp * (BASE_LIST_SPACING_DP / DEFAULT_PARAGRAPH_SPACING_DP)).dp

internal val ReadingPreferences.quoteFontSize: TextUnit
  get() = (BASE_QUOTE_FONT_SP * fontSizeSp / DEFAULT_FONT_SIZE_SP).sp

internal val ReadingPreferences.quoteLineHeight: TextUnit
  get() =
      (BASE_QUOTE_LINE_HEIGHT_SP * fontSizeSp / DEFAULT_FONT_SIZE_SP * lineHeightMultiplier /
              DEFAULT_LINE_HEIGHT_MULTIPLIER)
          .sp

internal val ReadingPreferences.quoteBlockSpacing: androidx.compose.ui.unit.Dp
  get() = (BASE_QUOTE_BLOCK_SPACING_DP * paragraphSpacingDp / DEFAULT_PARAGRAPH_SPACING_DP).dp

/** Splits only blank-line paragraph boundaries; single newlines and indentation remain intact. */
internal fun splitReaderParagraphs(body: String): List<String> =
    body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\\n[\\t ]*\\n+"))
        .filter(String::isNotBlank)

private const val DEFAULT_FONT_SIZE_SP = 17f
private const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 30f / 17f
private const val DEFAULT_PARAGRAPH_SPACING_DP = 20f
private const val BASE_ITEM_SPACING_DP = 8f
private const val BASE_LIST_SPACING_DP = 8f
private const val BASE_QUOTE_FONT_SP = 14f
private const val BASE_QUOTE_LINE_HEIGHT_SP = 22f
private const val BASE_QUOTE_BLOCK_SPACING_DP = 3f
