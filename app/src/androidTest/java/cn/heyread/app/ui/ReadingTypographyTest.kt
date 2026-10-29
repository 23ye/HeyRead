package cn.heyread.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.ImportedReadingFont
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.data.model.ReadingPreferences
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsCategoryScreen
import cn.heyread.app.ui.screens.SettingsOverviewScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.UiState
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingTypographyTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun importedFontRowsSelectAndForwardImportAndRemovalActions() {
    val id = "0123456789abcdef".repeat(4)
    val font = ImportedReadingFont(id, "Synthetic Font")
    val ui = mutableStateOf(UiState(importedFonts = listOf(font)))
    var importRequests = 0
    val removed = mutableListOf<String>()
    val selected = mutableListOf<ReadingPreferences>()
    compose.setContent {
      HeyReadTheme("light") {
        SettingsCategoryScreen(
          category = SettingsCategory.Appearance,
          ui = ui.value,
          gatewayValue = "",
          advanced = false,
          onGatewayChange = {},
          onAdvancedChange = {},
          onThemeChange = {},
          onHomeLayoutChange = {},
          onAccentChange = {},
          onMotionChange = {},
          onNoImagesChange = {},
          onGatewaySave = {},
          onClearCache = {},
          onOpenStory = {},
          onClearHistory = {},
          onBack = {},
          onReadingPreferencesChange = {
            selected += it
            ui.value = ui.value.copy(readingPreferences = it)
          },
          onImportFont = { importRequests++ },
          onRemoveFont = removed::add,
        )
      }
    }

    val fontRow = compose.onNodeWithTag("settings-reading-font-custom-$id").performScrollTo()
    fontRow.performClick().assertIsSelected()
    assertEquals(1, selected.size)
    assertEquals(ReadingFont.CUSTOM, selected.single().font)
    assertEquals(id, selected.single().customFontId)
    assertEquals(ReadingPreferences.DEFAULT_FONT_SIZE_SP, selected.single().fontSizeSp)

    compose.onNodeWithTag("settings-reading-font-import").performScrollTo().performClick()
    compose.onNodeWithTag("settings-reading-font-remove-$id").performScrollTo().performClick()
    compose.onNodeWithTag("motion-alert-dialog").assertIsDisplayed()
    assertTrue("removal waits for explicit confirmation", removed.isEmpty())
    compose.onNodeWithText("取消").performClick()
    compose.onNodeWithTag("motion-alert-dialog").assertDoesNotExist()
    compose.onNodeWithTag("settings-reading-font-remove-$id").performScrollTo().performClick()
    compose.onNodeWithTag("motion-alert-confirm").performClick()
    assertEquals(1, importRequests)
    assertEquals(listOf(id), removed)
    compose.onNodeWithTag("settings-reading-reset").performScrollTo().performClick()
    assertEquals(ReadingPreferences(), ui.value.readingPreferences)
    assertEquals(listOf(font), ui.value.importedFonts)
  }

  @Test
  fun slidersPreviewDuringDragAndCommitOnlyOnReleaseOrSemanticAdjustment() {
    val ui =
      mutableStateOf(
        UiState(
          theme = "dark",
          homeLayout = HomeLayout.C,
          noImages = true,
          gateway = "https://kept.example",
          readingPreferences = ReadingPreferences(),
        )
      )
    val commits = mutableListOf<ReadingPreferences>()
    compose.setContent {
      HeyReadTheme("light") {
        SettingsCategoryScreen(
          category = SettingsCategory.Appearance,
          ui = ui.value,
          gatewayValue = "",
          advanced = false,
          onGatewayChange = {},
          onAdvancedChange = {},
          onThemeChange = {},
          onHomeLayoutChange = {},
          onAccentChange = {},
          onMotionChange = {},
          onNoImagesChange = {},
          onGatewaySave = {},
          onClearCache = {},
          onOpenStory = {},
          onClearHistory = {},
          onBack = {},
          onReadingPreferencesChange = {
            commits += it
            ui.value = ui.value.copy(readingPreferences = it)
          },
        )
      }
    }

    val lineHeight = compose.onNodeWithTag("settings-reading-line-height-slider")
    val initialLineHeightStep =
      lineHeight.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    assertTrue(initialLineHeightStep in 1.2f..2.4f)
    assertEquals(
      initialLineHeightStep,
      1.2f + kotlin.math.round((initialLineHeightStep - 1.2f) * 10f) / 10f,
      0.001f,
    )
    assertEquals(30f / 17f, ui.value.readingPreferences.lineHeightMultiplier, 0.0001f)
    compose.onNodeWithText("1.76 ×").assertIsDisplayed()
    val initialPreview =
      compose.onAllNodesWithTag("article-paragraph-text", useUnmergedTree = true)[0]
    assertEquals(30f, textLayout(initialPreview).layoutInput.style.lineHeight.value, 0.01f)
    val fontSize = compose.onNodeWithTag("settings-reading-font-size-slider").performScrollTo()
    fontSize.performTouchInput {
      down(Offset(width * 0.25f, height / 2f))
      moveTo(Offset(width * 0.82f, height / 2f), delayMillis = 250)
    }
    compose.waitForIdle()
    assertTrue(
      "the slider reports its live position before release",
      fontSize.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current >
        ReadingPreferences.DEFAULT_FONT_SIZE_SP,
    )
    val preview = compose.onAllNodesWithTag("article-paragraph-text", useUnmergedTree = true)[0]
    val previewSizeDuringDrag = textLayout(preview).layoutInput.style.fontSize.value
    assertTrue(previewSizeDuringDrag > ReadingPreferences.DEFAULT_FONT_SIZE_SP)
    assertTrue("a drag does not write the durable preference callback", commits.isEmpty())

    fontSize.performTouchInput { up() }
    compose.waitForIdle()
    assertEquals(1, commits.size)
    assertTrue(ui.value.readingPreferences.fontSizeSp > ReadingPreferences.DEFAULT_FONT_SIZE_SP)
    assertEquals(previewSizeDuringDrag, textLayout(preview).layoutInput.style.fontSize.value, 0.01f)

    val beforeSemanticSet = commits.size
    compose
      .onNodeWithTag("settings-reading-line-height-slider")
      .performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
        assertTrue(setProgress(2f))
      }
    compose.waitForIdle()
    assertEquals(beforeSemanticSet + 1, commits.size)
    assertEquals(2f, ui.value.readingPreferences.lineHeightMultiplier, 0.001f)

    val beforeSpacingSet = commits.size
    compose
      .onNodeWithTag("settings-reading-paragraph-spacing-slider")
      .performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
        assertTrue(setProgress(30f))
      }
    compose.waitForIdle()
    assertEquals(beforeSpacingSet + 1, commits.size)
    assertEquals(30f, ui.value.readingPreferences.paragraphSpacingDp, 0.001f)
    assertEquals("dark", ui.value.theme)
    assertEquals(HomeLayout.C, ui.value.homeLayout)
    assertTrue(ui.value.noImages)
    assertEquals("https://kept.example", ui.value.gateway)
  }

  @Test
  fun cancellingSliderGestureDoesNotCommitTheTemporaryPreview() {
    val ui = mutableStateOf(UiState(readingPreferences = ReadingPreferences()))
    val commits = mutableListOf<ReadingPreferences>()
    compose.setContent {
      HeyReadTheme("light") {
        SettingsCategoryScreen(
          category = SettingsCategory.Appearance,
          ui = ui.value,
          gatewayValue = "",
          advanced = false,
          onGatewayChange = {},
          onAdvancedChange = {},
          onThemeChange = {},
          onHomeLayoutChange = {},
          onAccentChange = {},
          onMotionChange = {},
          onNoImagesChange = {},
          onGatewaySave = {},
          onClearCache = {},
          onOpenStory = {},
          onClearHistory = {},
          onBack = {},
          onReadingPreferencesChange = commits::add,
        )
      }
    }

    val slider = compose.onNodeWithTag("settings-reading-font-size-slider").performScrollTo()
    slider.performTouchInput {
      down(Offset(width * 0.25f, height / 2f))
      moveTo(Offset(width * 0.9f, height / 2f), delayMillis = 200)
      cancel()
    }
    compose.waitForIdle()
    assertTrue("cancelled drag is not committed", commits.isEmpty())
    assertEquals(ReadingPreferences(), ui.value.readingPreferences)
    val preview = compose.onAllNodesWithTag("article-paragraph-text", useUnmergedTree = true)[0]
    assertEquals(
      ReadingPreferences.DEFAULT_FONT_SIZE_SP,
      textLayout(preview).layoutInput.style.fontSize.value,
      0.01f,
    )
  }

  @Test
  fun articleAndPreviewUseSameTypographyAndParagraphGapAt210DpAndLargeFontScale() {
    val preferences =
      ReadingPreferences(
        fontSizeSp = 22f,
        lineHeightMultiplier = 1.6f,
        paragraphSpacingDp = 30f,
        font = ReadingFont.SERIF,
      )
    val firstText = "正文预览覆盖中文与 English，字号、行高与字体会直接使用当前正文设置。"
    val secondText = "每一段都保留清楚的间距与舒适的阅读节奏。调整字号后，文本也会按可用宽度重新换行。"
    val listText = "列表正文使用同一字号、行高和字体，继续保留缩进与标记。"
    val quoteText = "引用文字也会按正文偏好同比调整，较大字号配合紧凑行高仍可读。"
    val quotePreferences =
      ReadingPreferences(
        fontSizeSp = 26f,
        lineHeightMultiplier = 1.2f,
        paragraphSpacingDp = 20f,
        font = ReadingFont.SANS,
      )
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
        HeyReadTheme("light") {
          Row(Modifier.width(420.dp).fillMaxHeight()) {
            Column(
              Modifier.width(210.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              ArticleBlockContent(
                ArticleBlock.Paragraph(firstText),
                noImages = true,
                onOpenImage = {},
                readingPreferences = preferences,
              )
              ArticleBlockContent(
                ArticleBlock.Paragraph(secondText),
                noImages = true,
                onOpenImage = {},
                readingPreferences = preferences,
              )
              ArticleBlockContent(
                ArticleBlock.ListItem(listText, depth = 0, marker = "•"),
                noImages = true,
                onOpenImage = {},
                readingPreferences = preferences,
              )
              ArticleBlockContent(
                ArticleBlock.Quote(listOf(ArticleBlock.Paragraph(quoteText))),
                noImages = true,
                onOpenImage = {},
                readingPreferences = quotePreferences,
              )
            }
            Box(Modifier.width(210.dp).fillMaxHeight()) {
              SettingsCategoryScreen(
                category = SettingsCategory.Appearance,
                ui = UiState(readingPreferences = preferences),
                gatewayValue = "",
                advanced = false,
                onGatewayChange = {},
                onAdvancedChange = {},
                onThemeChange = {},
                onHomeLayoutChange = {},
                onAccentChange = {},
                onMotionChange = {},
                onNoImagesChange = {},
                onGatewaySave = {},
                onClearCache = {},
                onOpenStory = {},
                onClearHistory = {},
                onBack = {},
              )
            }
          }
        }
      }
    }

    val bodyFirst = compose.onAllNodesWithText(firstText, useUnmergedTree = true)
    val bodySecond = compose.onAllNodesWithText(secondText, useUnmergedTree = true)
    val bodyList = compose.onAllNodesWithText(listText, useUnmergedTree = true)
    assertEquals(2, bodyFirst.fetchSemanticsNodes().size)
    assertEquals(2, bodySecond.fetchSemanticsNodes().size)
    assertEquals(2, bodyList.fetchSemanticsNodes().size)
    val actualFirst = bodyFirst[0]
    val actualSecond = bodySecond[0]
    val actualList = bodyList[0]
    val previewFirst = bodyFirst[1]
    val previewSecond = bodySecond[1]
    val previewList = bodyList[1]
    compose.onNodeWithTag("settings-typography-preview").performScrollTo()
    previewFirst.performScrollTo()
    previewSecond.performScrollTo()

    val actualStyle = textLayout(actualFirst).layoutInput.style
    val actualFirstLayout = textLayout(actualFirst)
    val previewStyle = textLayout(previewFirst).layoutInput.style
    val previewFirstLayout = textLayout(previewFirst)
    val actualListStyle = textLayout(actualList).layoutInput.style
    val previewListStyle = textLayout(previewList).layoutInput.style
    assertEquals(22f, actualStyle.fontSize.value, 0.01f)
    assertEquals(35.2f, actualStyle.lineHeight.value, 0.01f)
    assertEquals(FontFamily.Serif, actualStyle.fontFamily)
    assertEquals(actualStyle.fontSize, previewStyle.fontSize)
    assertEquals(actualStyle.lineHeight, previewStyle.lineHeight)
    assertEquals(actualStyle.fontFamily, previewStyle.fontFamily)
    assertEquals(actualStyle.fontSize, actualListStyle.fontSize)
    assertEquals(actualListStyle.fontSize, previewListStyle.fontSize)
    assertEquals(actualListStyle.lineHeight, previewListStyle.lineHeight)
    assertEquals(actualListStyle.fontFamily, previewListStyle.fontFamily)
    val densityScale = compose.density.density
    val expectedGapPx = 30f * densityScale
    val actualTextGap =
      actualSecond.fetchSemanticsNode().positionInRoot.y -
        (actualFirst.fetchSemanticsNode().positionInRoot.y + actualFirstLayout.size.height)
    val previewTextGap =
      previewSecond.fetchSemanticsNode().positionInRoot.y -
        (previewFirst.fetchSemanticsNode().positionInRoot.y + previewFirstLayout.size.height)
    assertEquals(expectedGapPx, actualTextGap, 2f)
    assertEquals(actualTextGap, previewTextGap, 2f)
    val quoteNode = compose.onNodeWithTag("article-quote-paragraph-text", useUnmergedTree = true)
    quoteNode.performScrollTo()
    val quoteStyle = textLayout(quoteNode).layoutInput.style
    assertEquals(26f * 14f / 17f, quoteStyle.fontSize.value, 0.02f)
    assertEquals(22f * 26f / 17f * 1.2f / (30f / 17f), quoteStyle.lineHeight.value, 0.02f)
    assertEquals(FontFamily.SansSerif, quoteStyle.fontFamily)
    quoteNode.assertTextEquals(quoteText)
  }

  @Test
  fun avatarLoadsRealSyntheticPixelsAndSwitchesToTheNewAccountImage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val firstUrl = "https://avatars.invalid/first.png"
    val secondUrl = "https://avatars.invalid/second.png"
    val firstColor = Color(0xFF176B87)
    val secondColor = Color(0xFF9A4E24)
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val loader =
      syntheticAvatarLoader(
        context,
        requested,
        mapOf(firstUrl to firstColor, secondUrl to secondColor),
      )
    val account =
      mutableStateOf(accountState("user-1", "First Reader", " //avatars.invalid/first.png "))
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          SettingsOverviewScreen(
            account = account.value,
            ui = UiState(),
            onLogin = {},
            onLogout = {},
            onOpenFavorites = {},
            onAccountHistory = {},
            onCategory = {},
          )
        }
      }
    }

    compose.waitUntil(5_000) { requested.contains(firstUrl) }
    compose.waitUntil(5_000) { avatarCenterPixel() == firstColor }
    assertEquals(firstColor, avatarCenterPixel())
    compose.runOnIdle { account.value = accountState("user-2", "Second Reader", secondUrl) }
    compose.waitUntil(5_000) { requested.contains(secondUrl) }
    compose.waitUntil(5_000) { avatarCenterPixel() == secondColor }
    assertEquals(secondColor, avatarCenterPixel())
    assertTrue(requested.contains(firstUrl) && requested.contains(secondUrl))
  }

  @Test
  fun failedAvatarFallsBackAndNoImageModeMakesNoAvatarRequest() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val failedUrl = "https://avatars.invalid/failure.png"
    val noImageUrl = "https://avatars.invalid/hidden.png"
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val loader =
      syntheticAvatarLoader(
        context,
        requested,
        mapOf(noImageUrl to Color(0xFF267A39)),
        failures = setOf(failedUrl),
      )
    val account = mutableStateOf(accountState("user-3", "Error Reader", failedUrl))
    val ui = mutableStateOf(UiState(noImages = false))
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          SettingsOverviewScreen(
            account = account.value,
            ui = ui.value,
            onLogin = {},
            onLogout = {},
            onOpenFavorites = {},
            onAccountHistory = {},
            onCategory = {},
          )
        }
      }
    }

    compose.waitUntil(5_000) { requested.contains(failedUrl) }
    compose
      .onNodeWithTag("settings-account-avatar-fallback", useUnmergedTree = true)
      .assertIsDisplayed()
    compose.onNodeWithText("E", useUnmergedTree = true).assertIsDisplayed()
    compose.runOnIdle {
      account.value = accountState("user-4", "No Image Reader", noImageUrl)
      ui.value = ui.value.copy(noImages = true)
    }
    compose
      .onNodeWithTag("settings-account-avatar-image", useUnmergedTree = true)
      .assertDoesNotExist()
    compose
      .onNodeWithTag("settings-account-avatar-fallback", useUnmergedTree = true)
      .assertIsDisplayed()
    compose.onNodeWithText("N", useUnmergedTree = true).assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(failedUrl), requested.toList()) }

    compose.runOnIdle {
      account.value =
        accountState("user-5", "Insecure Reader", "http://avatars.invalid/insecure.png")
      ui.value = ui.value.copy(noImages = false)
    }
    compose
      .onNodeWithTag("settings-account-avatar-image", useUnmergedTree = true)
      .assertDoesNotExist()
    compose
      .onNodeWithTag("settings-account-avatar-fallback", useUnmergedTree = true)
      .assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(failedUrl), requested.toList()) }
  }

  private fun textLayout(
    node: androidx.compose.ui.test.SemanticsNodeInteraction
  ): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
    assertEquals(1, results.size)
    return results.single()
  }

  private fun avatarCenterPixel(): Color {
    val image =
      compose
        .onNodeWithTag("settings-account-avatar-image", useUnmergedTree = true)
        .captureToImage()
    return image.toPixelMap()[image.width / 2, image.height / 2]
  }

  private fun accountState(userId: String, nickname: String, avatar: String) =
    AccountUiState(
      session = AccountSession("pkey=synthetic", userId, nickname, avatar),
      loadingSession = false,
    )

  private fun syntheticAvatarLoader(
    context: Context,
    requested: MutableList<String>,
    colors: Map<String, Color>,
    failures: Set<String> = emptySet(),
  ): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
              val url = data.toString()
              val color = colors[url] ?: if (url in failures) Color.White else return null
              return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                  requested += url
                  if (url in failures) throw java.io.IOException("synthetic avatar failure")
                  val bytes = syntheticPng(color)
                  val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                  return DrawableResult(
                    BitmapDrawable(context.resources, bitmap),
                    false,
                    DataSource.MEMORY,
                  )
                }
              }
            }
          }
        )
      }
      .build()

  private fun syntheticPng(color: Color): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
      Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color.toArgb())
        compress(Bitmap.CompressFormat.PNG, 100, output)
        recycle()
      }
      output.toByteArray()
    }
}
