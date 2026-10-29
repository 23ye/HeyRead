package cn.heyread.app.data.translation

import cn.heyread.app.data.model.Source

/** A translated fragment preceded by source whitespace that must remain unchanged. */
data class TranslationFragment(val prefix: String, val text: String)

/** Splits at source paragraph breaks and bounded word boundaries without losing separators. */
object TranslationText {
    fun split(text: String, maxCodeUnits: Int = 1_800): List<TranslationFragment> {
        require(maxCodeUnits >= 2)
        if (text.isEmpty()) return emptyList()

        val fragments = mutableListOf<TranslationFragment>()
        var cursor = 0
        while (cursor < text.length) {
            val prefixStart = cursor
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            val prefix = text.substring(prefixStart, cursor)
            if (cursor == text.length) {
                fragments += TranslationFragment(prefix, "")
                break
            }

            var end = minOf(cursor + maxCodeUnits, text.length)
            if (
                end < text.length &&
                    end > cursor &&
                    text[end - 1].isHighSurrogate() &&
                    text[end].isLowSurrogate()
            )
                end--
            if (end == cursor) end = minOf(cursor + 2, text.length)

            val paragraphBreak =
                text.indexOfAny(charArrayOf('\r', '\n'), cursor).takeIf { it > cursor && it < end }
            if (paragraphBreak != null) {
                end = paragraphBreak
            } else if (end < text.length) {
                val minimumBreak = (cursor + maxCodeUnits / 2).coerceAtMost(end - 1)
                for (candidate in end - 1 downTo minimumBreak) {
                    if (text[candidate].isWhitespace()) {
                        end = candidate
                        break
                    }
                }
            }

            fragments += TranslationFragment(prefix, text.substring(cursor, end))
            cursor = end
        }
        return fragments
    }
}

object SteamTranslationPolicy {
    private const val EMPTY_STEAM_BODY = "请打开原文查看公告。"

    fun canTranslate(source: Source, body: String): Boolean =
        source == Source.STEAM && body.isNotBlank() && body.trim() != EMPTY_STEAM_BODY
}
