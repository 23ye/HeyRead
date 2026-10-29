package cn.heyread.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.rich.OfficialEmojiCatalog
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

private val OfficialEmojiToken = Regex("\\[[^\\]\\s]{1,96}]")

/** Keeps shortcodes as selectable fallback text and only replaces exact official catalog keys. */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
    color: Color = Color.Unspecified,
    noImages: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
  val effectiveFontFamily = fontFamily ?: LocalTextStyle.current.fontFamily ?: FontFamily.Default
  val context = LocalContext.current
  val density = LocalDensity.current
  val hasTokens = remember(text) { OfficialEmojiToken.containsMatchIn(text) }
  if (!hasTokens || noImages) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        fontFamily = effectiveFontFamily,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
    return
  }
  val catalog by OfficialEmojiCatalog.entries.collectAsState()
  LaunchedEffect(hasTokens, noImages) { OfficialEmojiCatalog.ensureLoaded(context) }

  val mapped = catalog
  var loadedUrls by remember(text) { mutableStateOf(emptySet<String>()) }
  var failedUrls by remember(text) { mutableStateOf(emptySet<String>()) }
  val candidates =
      remember(text, mapped, failedUrls) {
        OfficialEmojiToken.findAll(text)
            .mapNotNull { match ->
              mapped[match.value]
                  ?.takeUnless { it in failedUrls }
                  ?.let { match.range to (match.value to it) }
            }
            .toList()
      }
  val parts = candidates.filter { (_, entry) -> entry.second in loadedUrls }
  val pendingUrls =
      candidates
          .map { it.second.second }
          .filterNot { it in loadedUrls || it in failedUrls }
          .distinct()

  val inline = linkedMapOf<String, InlineTextContent>()
  val annotated = buildAnnotatedString {
    var cursor = 0
    parts.forEachIndexed { index, (range, entry) ->
      val (token, imageUrl) = entry
      append(text.substring(cursor, range.first))
      val id = "official-emoji-$index"
      appendInlineContent(id, alternateText = token)
      val sizeSp = (fontSize.value * 1.18f).sp
      val sizeDp = with(density) { sizeSp.toDp() }
      inline[id] =
          InlineTextContent(Placeholder(sizeSp, sizeSp, PlaceholderVerticalAlign.TextCenter)) {
            AnimatedImage(
                model = remember(imageUrl) { ImageRequest.Builder(context).data(imageUrl).build() },
                contentDescription = token,
                modifier = Modifier.size(sizeDp),
                onState = { state ->
                  if (state is AsyncImagePainter.State.Error) {
                    failedUrls = failedUrls + imageUrl
                  }
                },
            )
          }
      cursor = range.last + 1
    }
    append(text.substring(cursor))
  }
  Box(modifier) {
    if (parts.isEmpty()) {
      Text(
          text = text,
          fontSize = fontSize,
          fontWeight = fontWeight,
          lineHeight = lineHeight,
          fontFamily = effectiveFontFamily,
          color = color,
          maxLines = maxLines,
          overflow = overflow,
      )
    } else {
      Text(
          text = annotated,
          inlineContent = inline,
          fontSize = fontSize,
          fontWeight = fontWeight,
          lineHeight = lineHeight,
          fontFamily = effectiveFontFamily,
          color = color,
          maxLines = maxLines,
          overflow = overflow,
      )
    }
    pendingUrls.forEach { imageUrl ->
      AnimatedImage(
          model = remember(imageUrl) { ImageRequest.Builder(context).data(imageUrl).build() },
          contentDescription = null,
          modifier = Modifier.size(1.dp).alpha(0f),
          onState = {
            if (it is AsyncImagePainter.State.Success) loadedUrls = loadedUrls + imageUrl
            if (it is AsyncImagePainter.State.Error) failedUrls = failedUrls + imageUrl
          },
      )
    }
  }
}
