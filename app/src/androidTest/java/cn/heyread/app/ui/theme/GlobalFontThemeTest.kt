package cn.heyread.app.ui.theme

import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ReadingPreferences
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.components.MotionAlertDialog
import cn.heyread.app.ui.components.RichText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalFontThemeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun globalFontReachesMaterialTypographyAndProductionTextComponents() {
    val fontFamily = syntheticFontFamily("synthetic-a.ttf")
    val styleTags = (0 until 15).map { "global-typography-$it" }
    compose.setContent {
      HeyReadTheme("light", "classic", false, {}, fontFamily) {
        val typography = MaterialTheme.typography
        val styles =
          listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
            typography.titleMedium,
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
          )
        Column(Modifier.verticalScroll(rememberScrollState())) {
          styles.forEachIndexed { index, style ->
            Text("AAA", Modifier.testTag(styleTags[index]), style = style)
          }
          Text("Default AAA", Modifier.testTag("global-default-text"))
          RichText("Rich AAA", Modifier.testTag("global-rich-text"), noImages = true)
          OutlinedTextField(
            value = "AAA",
            onValueChange = {},
            modifier = Modifier.testTag("global-input"),
          )
        }
        MotionAlertDialog(
          visible = true,
          onDismiss = {},
          title = "Dialog title AAA",
          message = "Dialog message AAA",
          confirmLabel = "确定",
          onConfirm = {},
          dismissLabel = "取消",
        )
      }
    }
    compose.waitForIdle()

    (styleTags + listOf("global-default-text", "global-rich-text", "global-input"))
      .forEach { tag ->
        val layout = textLayout(compose.onNodeWithTag(tag, useUnmergedTree = true))
        assertEquals("$tag inherits the selected application font", fontFamily, layout.layoutInput.style.fontFamily)
      }
    assertEquals(
      fontFamily,
      textLayout(compose.onNodeWithText("Dialog title AAA")).layoutInput.style.fontFamily,
    )
    assertEquals(
      fontFamily,
      textLayout(compose.onNodeWithText("Dialog message AAA")).layoutInput.style.fontFamily,
    )
  }

  @Test
  fun changingGlobalFontPreservesTypographyMetricsAndBodyNumbersStayLocal() {
    val customFamily = syntheticFontFamily("synthetic-a.ttf")
    val systemFamily = FontFamily.Default
    val activeFamily = mutableStateOf(customFamily)
    val readingPreferences = mutableStateOf(ReadingPreferences())
    val styleTags = (0 until 15).map { "global-metrics-$it" }
    compose.setContent {
      HeyReadTheme("light", "classic", false, {}, activeFamily.value) {
        val typography = MaterialTheme.typography
        val styles =
          listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
            typography.titleMedium,
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
          )
        Column(Modifier.verticalScroll(rememberScrollState())) {
          styles.forEachIndexed { index, style ->
            Text("AAA", Modifier.testTag(styleTags[index]), style = style)
          }
          Text("AAAA", Modifier.testTag("global-interface-metrics"), style = typography.bodyMedium)
          ArticleBlockContent(
            block = ArticleBlock.Paragraph("AAA paragraph"),
            noImages = true,
            onOpenImage = {},
            readingPreferences = readingPreferences.value,
          )
        }
      }
    }
    compose.waitForIdle()
    val initialMetrics = styleTags.map { metrics(textLayout(compose.onNodeWithTag(it, useUnmergedTree = true))) }
    val initialInterfaceMetrics = metrics(textLayout(compose.onNodeWithTag("global-interface-metrics", useUnmergedTree = true)))
    val customPixels = glyphPixels("global-interface-metrics")

    compose.runOnIdle { activeFamily.value = systemFamily }
    compose.waitForIdle()
    assertEquals(
      "font switching changes only font family, not any Material3 size/line-height/weight/spacing",
      initialMetrics,
      styleTags.map { metrics(textLayout(compose.onNodeWithTag(it, useUnmergedTree = true))) },
    )
    val systemPixels = glyphPixels("global-interface-metrics")
    assertNotEquals("the supplied synthetic typeface is actually rendered", customPixels, systemPixels)

    compose.runOnIdle {
      readingPreferences.value =
        ReadingPreferences(fontSizeSp = 26f, lineHeightMultiplier = 1.2f, paragraphSpacingDp = 40f)
    }
    compose.waitForIdle()
    assertEquals(
      "body-only numeric preferences do not alter global interface typography",
      initialInterfaceMetrics,
      metrics(textLayout(compose.onNodeWithTag("global-interface-metrics", useUnmergedTree = true))),
    )
    val articleStyle =
      textLayout(compose.onNodeWithTag("article-paragraph-text", useUnmergedTree = true)).layoutInput.style
    assertEquals(26f, articleStyle.fontSize.value, 0.01f)
    assertEquals(31.2f, articleStyle.lineHeight.value, 0.02f)
  }

  private data class TypographyMetrics(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight?,
    val letterSpacing: TextUnit,
  )

  private fun metrics(layout: TextLayoutResult) =
    TypographyMetrics(
      layout.layoutInput.style.fontSize,
      layout.layoutInput.style.lineHeight,
      layout.layoutInput.style.fontWeight,
      layout.layoutInput.style.letterSpacing,
    )

  private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
    assertEquals(1, results.size)
    return results.single()
  }

  private fun glyphPixels(tag: String): List<Int> {
    val image = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().captureToImage().toPixelMap()
    return buildList(image.width * image.height) {
      for (y in 0 until image.height) for (x in 0 until image.width) add(image[x, y].hashCode())
    }
  }

  private fun syntheticFontFamily(assetName: String): FontFamily {
    val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    return runBlocking(Dispatchers.IO) {
      val fontFile = File.createTempFile("global-font-test-", ".ttf", targetContext.cacheDir)
      try {
        instrumentationContext.assets.open("fonts/$assetName").use { input ->
          fontFile.outputStream().use { output -> input.copyTo(output) }
        }
        FontFamily(Typeface.Builder(fontFile).build())
      } finally {
        fontFile.delete()
      }
    }
  }
}
