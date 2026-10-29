package cn.heyread.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationPage
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.LocalSharedCoverIsDetail
import cn.heyread.app.ui.motion.LocalSharedCoverOriginId
import cn.heyread.app.ui.motion.SharedCoverCoordinator
import cn.heyread.app.ui.screens.DestinationScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.DestinationViewModel
import cn.heyread.app.viewmodel.UiState
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileTabRenderTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val viewModelStore = ViewModelStore()
  private val generatedFiles = mutableListOf<File>()
  private var imageLoader: ImageLoader? = null

  @After
  fun cleanFixture() {
    compose.activityRule.scenario.onActivity { viewModelStore.clear() }
    imageLoader?.shutdown()
    imageLoader = null
    generatedFiles.forEach(File::delete)
    generatedFiles.clear()
  }

  @Test
  fun restoredProfileTabsKeepClippedRoundedCoversWithMotionEnabled() {
    exerciseProfileTabs(motionEnabled = true)
  }

  @Test
  fun restoredProfileTabsKeepClippedRoundedCoversWithMotionDisabled() {
    exerciseProfileTabs(motionEnabled = false)
  }

  private fun exerciseProfileTabs(motionEnabled: Boolean) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val moments = createStories(context, "moment", isPortrait = true)
    val articles = createStories(context, "article", isPortrait = false)
    val assetsByUrl = (moments + articles).associate { it.asset.url to it.asset }
    val allGeneratedColors = (moments + articles).map { it.asset.color }
    val loader = profileLoader(moments, articles)
    val viewModel = DestinationViewModel(loader)
    compose.activityRule.scenario.onActivity {
      viewModelStore.put("profile-tab-render-$motionEnabled", viewModel)
    }
    val testImageLoader = syntheticImageLoader(context, assetsByUrl)
    imageLoader = testImageLoader
    val target = DestinationTarget(DestinationType.USER, PROFILE_ID, "Ginbtii")
    val coordinator =
      if (motionEnabled) {
        SharedCoverCoordinator().apply { setCurrentEntry(PROFILE_ENTRY_ID) }
      } else null

    compose.setContent {
      CompositionLocalProvider(
        LocalImageLoader provides testImageLoader,
        LocalSharedCoverCoordinator provides coordinator,
        LocalSharedCoverEntryId provides PROFILE_ENTRY_ID,
        LocalSharedCoverOriginId provides PROFILE_ENTRY_ID,
        LocalSharedCoverIsDetail provides false,
      ) {
        HeyReadTheme("light", motionEnabled = motionEnabled) {
          Box(Modifier.fillMaxSize()) {
            DestinationScreen(
              target = target,
              viewModel = viewModel,
              ui = UiState(noImages = false),
              onBack = {},
              onOpenStory = {},
              onOpenSection = {},
            )
          }
        }
      }
    }

    waitForFeed(viewModel, DestinationFeedType.MOMENTS)
    compose.onNodeWithTag("page-top-bar").assertIsDisplayed()
    compose.onNodeWithTag("destination-user-tabs").assertIsDisplayed()
    scrollToStory("overlap")
    val initialMomentTop = storyTop("overlap")
    verifyFeedPixels(
      moments,
      moments.last { it.story.id == "overlap" },
      "v140-${motionLabel(motionEnabled)}-moment-initial",
      allGeneratedColors,
    )

    var firstArticleTop: Int? = null
    repeat(3) { cycle ->
      selectFeed(viewModel, DestinationFeedType.ARTICLES)
      scrollToStory("overlap")
      val articleTop = storyTop("overlap")
      if (firstArticleTop == null) firstArticleTop = articleTop
      else assertNear("投稿列表位置应独立恢复，第${cycle + 1}次", firstArticleTop!!, articleTop)
      verifyFeedPixels(
        articles,
        articles.last { it.story.id == "overlap" },
        "v140-${motionLabel(motionEnabled)}-article-${cycle + 1}",
        allGeneratedColors,
      )

      selectFeed(viewModel, DestinationFeedType.MOMENTS)
      compose.onNodeWithTag(storyTag("overlap")).assertIsDisplayed()
      assertNear("动态列表位置应在页签来回切换后恢复", initialMomentTop, storyTop("overlap"))
      verifyFeedPixels(
        moments,
        moments.last { it.story.id == "overlap" },
        "v140-${motionLabel(motionEnabled)}-moment-${cycle + 1}",
        allGeneratedColors,
      )
    }
  }

  private fun selectFeed(viewModel: DestinationViewModel, feedType: DestinationFeedType) {
    val tabTag =
      if (feedType == DestinationFeedType.MOMENTS) "destination-tab-moments"
      else "destination-tab-articles"
    compose.onNodeWithTag(tabTag).performClick()
    waitForFeed(viewModel, feedType)
    compose.waitForIdle()
    compose.onNodeWithTag("page-top-bar").assertIsDisplayed()
    compose.onNodeWithTag("destination-user-tabs").assertIsDisplayed()
  }

  private fun waitForFeed(viewModel: DestinationViewModel, feedType: DestinationFeedType) {
    compose.waitUntil(10_000) {
      val state = viewModel.state.value
      val feed = state.feeds[feedType]
      state.feedType == feedType && feed?.initialized == true && !feed.loading
    }
    compose.waitForIdle()
  }

  private fun scrollToStory(id: String) {
    val tag = storyTag(id)
    for (attempt in 0 until 16) {
      compose.waitForIdle()
      if (areTabsPinned() && isStoryFullyVisible(tag)) break
      compose.onRoot().performTouchInput {
        swipeUp(startY = height * 0.62f, endY = height * 0.38f, durationMillis = 460)
      }
    }
    compose.waitForIdle()
    assertTrue("Real touch scrolling should pin the profile tabs", areTabsPinned())
    assertTrue("Real touch scrolling should fully reveal $tag below the tabs", isStoryFullyVisible(tag))
    assertTabsArePinned()
  }

  private fun areTabsPinned(): Boolean {
    val topBar = compose.onNodeWithTag("page-top-bar").fetchSemanticsNode().boundsInRoot
    val tabs = compose.onNodeWithTag("destination-user-tabs").fetchSemanticsNode().boundsInRoot
    val tolerancePx = with(compose.density) { 20.dp.toPx() }
    return abs(tabs.top - topBar.bottom) <= tolerancePx
  }

  private fun isStoryFullyVisible(tag: String): Boolean =
    runCatching {
      val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
      val tabs = compose.onNodeWithTag("destination-user-tabs").fetchSemanticsNode().boundsInRoot
      val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
      row.top >= tabs.bottom && row.bottom <= root.bottom
    }.getOrDefault(false)

  private fun assertTabsArePinned() {
    val topBar = compose.onNodeWithTag("page-top-bar").fetchSemanticsNode().boundsInRoot
    val tabs = compose.onNodeWithTag("destination-user-tabs").fetchSemanticsNode().boundsInRoot
    val tolerancePx = with(compose.density) { 20.dp.toPx() }
    assertTrue(
      "The sticky profile tabs should sit directly below the fixed top bar: topBar=$topBar tabs=$tabs",
      abs(tabs.top - topBar.bottom) <= tolerancePx,
    )
  }

  private fun storyTop(id: String): Int =
    compose.onNodeWithTag(storyTag(id)).fetchSemanticsNode().boundsInRoot.top.roundToInt()

  private fun verifyFeedPixels(
    feed: List<GeneratedStory>,
    marker: GeneratedStory,
    screenshotName: String,
    allGeneratedColors: List<Int>,
  ) {
    val topBarBounds = compose.onNodeWithTag("page-top-bar").fetchSemanticsNode().boundsInRoot
    val tabBounds = compose.onNodeWithTag("destination-user-tabs").fetchSemanticsNode().boundsInRoot
    val colors = feed.map { it.asset.color }
    try {
      compose.waitUntil(10_000) {
        val frame = compose.onRoot().captureToImage().asAndroidBitmap()
        val rendered = findColorBounds(frame, colors).containsKey(marker.asset.color)
        frame.recycle()
        rendered
      }
    } catch (failure: Throwable) {
      saveFrame(compose.onRoot().captureToImage().asAndroidBitmap(), "$screenshotName-failure")
      throw failure
    }
    val frame = compose.onRoot().captureToImage().asAndroidBitmap()
    saveFrame(frame, screenshotName)
    val renderedBounds = findColorBounds(frame, colors)
    assertNoGeneratedPixels("fixed top bar", frame, topBarBounds, allGeneratedColors)
    assertNoGeneratedPixels("sticky profile tabs", frame, tabBounds, allGeneratedColors)

    feed.forEach { row ->
      val bounds = renderedBounds[row.asset.color] ?: return@forEach
      val rowTag = storyTag(row.story.id)
      if (runCatching { compose.onNodeWithTag(rowTag).fetchSemanticsNode() }.isFailure) return@forEach
      val rowWidthDp = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.width / compose.density.density
      val coverWidthDp = if (rowWidthDp < 300f) rowWidthDp else 104f
      val coverHeightDp = if (rowWidthDp < 300f) 122f else 82f
      val maxWidthPx = with(compose.density) { coverWidthDp.dp.toPx() }.roundToInt() + 6
      val maxHeightPx = with(compose.density) { coverHeightDp.dp.toPx() }.roundToInt() + 6
      assertTrue(
        "${row.story.id} image escaped its cover width: rendered=$bounds maxWidth=$maxWidthPx",
        bounds.width <= maxWidthPx,
      )
      assertTrue(
        "${row.story.id} image escaped its cover height: rendered=$bounds maxHeight=$maxHeightPx",
        bounds.height <= maxHeightPx,
      )
    }

    val markerPixels = renderedBounds[marker.asset.color]
    assertNotNull("The overlapping story should render its current tab's local PNG", markerPixels)
    val pixels = requireNotNull(markerPixels)
    val markerRow = compose.onNodeWithTag(storyTag("overlap")).fetchSemanticsNode().boundsInRoot
    assertTrue("The marker image must stay inside its story row", pixels.left >= markerRow.left - 2f)
    assertTrue("The marker image must stay inside its story row", pixels.right <= markerRow.right + 2f)
    assertRoundedTopAndBottom(frame, marker.asset.color, pixels)
  }

  private fun saveFrame(frame: Bitmap, name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    File(context.cacheDir, "$name.png").outputStream().use { output ->
      frame.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
  }

  private fun motionLabel(enabled: Boolean) = if (enabled) "motion-on" else "motion-off"

  private fun assertNoGeneratedPixels(label: String, frame: Bitmap, bounds: Rect, colors: List<Int>) {
    val left = bounds.left.toInt().coerceIn(0, frame.width)
    val right = bounds.right.toInt().coerceIn(0, frame.width)
    val top = bounds.top.toInt().coerceIn(0, frame.height)
    val bottom = bounds.bottom.toInt().coerceIn(0, frame.height)
    var found = 0
    for (y in top until bottom) {
      for (x in left until right) {
        val pixel = frame.getPixel(x, y)
        if (colors.any { closeColor(pixel, it) }) found++
      }
    }
    assertEquals("Generated cover pixels must not draw over the $label", 0, found)
  }

  private fun assertRoundedTopAndBottom(frame: Bitmap, color: Int, bounds: PixelBounds) {
    val centerY = (bounds.top + bounds.bottom) / 2
    val middleSpan = colorSpanOnRow(frame, color, centerY, bounds.left, bounds.right)
    val topSpan = colorSpanOnRow(frame, color, bounds.top, bounds.left, bounds.right)
    val bottomSpan = colorSpanOnRow(frame, color, bounds.bottom, bounds.left, bounds.right)
    assertTrue("The cover's top edge should retain rounded corners", topSpan < middleSpan * 0.95f)
    assertTrue("The cover's bottom edge should retain rounded corners", bottomSpan < middleSpan * 0.95f)
  }

  private fun colorSpanOnRow(
    bitmap: Bitmap,
    color: Int,
    y: Int,
    left: Int,
    right: Int,
  ): Int {
    var first = -1
    var last = -1
    for (x in left.coerceAtLeast(0)..right.coerceAtMost(bitmap.width - 1)) {
      if (closeColor(bitmap.getPixel(x, y.coerceIn(0, bitmap.height - 1)), color)) {
        if (first < 0) first = x
        last = x
      }
    }
    return if (first < 0) 0 else last - first + 1
  }

  private fun findColorBounds(bitmap: Bitmap, colors: List<Int>): Map<Int, PixelBounds> {
    val bounds = colors.associateWith { MutablePixelBounds(bitmap.width, bitmap.height) }
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        val pixel = bitmap.getPixel(x, y)
        colors.firstOrNull { closeColor(pixel, it) }?.let { bounds.getValue(it).include(x, y) }
      }
    }
    return bounds.mapNotNull { (color, pixels) -> pixels.build()?.let { color to it } }.toMap()
  }

  private fun closeColor(pixel: Int, color: Int): Boolean =
    abs(Color.red(pixel) - Color.red(color)) < 24 &&
      abs(Color.green(pixel) - Color.green(color)) < 24 &&
      abs(Color.blue(pixel) - Color.blue(color)) < 24

  private fun createStories(
    context: Context,
    prefix: String,
    isPortrait: Boolean,
  ): List<GeneratedStory> {
    val colors =
      if (isPortrait) {
        listOf(
          Color.rgb(234, 38, 88),
          Color.rgb(20, 183, 118),
          Color.rgb(48, 112, 238),
          Color.rgb(244, 157, 28),
          Color.rgb(152, 65, 219),
          Color.rgb(0, 166, 190),
          Color.rgb(207, 61, 43),
        )
      } else {
        listOf(
          Color.rgb(18, 151, 99),
          Color.rgb(232, 53, 83),
          Color.rgb(31, 128, 217),
          Color.rgb(195, 131, 22),
          Color.rgb(109, 57, 202),
          Color.rgb(226, 91, 23),
          Color.rgb(47, 168, 166),
        )
      }
    val ids = listOf("$prefix-0", "$prefix-1", "$prefix-2", "overlap", "$prefix-4", "$prefix-5", "$prefix-6")
    return ids.mapIndexed { index, id ->
      val file = File.createTempFile("profile-tab-$prefix-", ".png", context.cacheDir)
      generatedFiles += file
      val width = if (isPortrait) 96 else 520
      val height = if (isPortrait) 520 else 96
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(colors[index])
      FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
      bitmap.recycle()
      val url = "https://profile-tab-render.invalid/$prefix-$index.png"
      GeneratedStory(
        story =
          Story(
            id = id,
            source = Source.HEYBOX,
            title = "$prefix 标题 $index",
            excerpt = "$prefix 合成摘要 $index，用于检查图片区域和正文卡片边界。",
            body = "synthetic",
            author = "Ginbtii",
            date = "今天",
            image = url,
          ),
        asset = CoverAsset(url, file, colors[index]),
      )
    }
  }

  private fun profileLoader(
    moments: List<GeneratedStory>,
    articles: List<GeneratedStory>,
  ): DestinationLoader {
    val target = DestinationTarget(DestinationType.USER, PROFILE_ID, "Ginbtii")
    val profile = AuthorProfile("Ginbtii", userId = PROFILE_ID)
    val data = DestinationData("Ginbtii", profile = profile)
    return DestinationLoader { requestedTarget, offset, _, _, _, feedType, _, _, _, _ ->
      assertEquals(target, requestedTarget)
      val rows = if (feedType == DestinationFeedType.MOMENTS) moments else articles
      DestinationPage(
        data = data,
        stories = rows.map { it.story },
        nextOffset = offset + rows.size,
        lastValue = if (feedType == DestinationFeedType.MOMENTS) "moment-end" else null,
        hasMore = false,
      )
    }
  }

  private fun syntheticImageLoader(
    context: Context,
    assets: Map<String, CoverAsset>,
  ): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
              val asset = assets[data.toString()] ?: return null
              return object : Fetcher {
                override suspend fun fetch(): FetchResult =
                  withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(asset.file.absolutePath)
                      ?: error("Synthetic cover PNG could not be decoded")
                    DrawableResult(BitmapDrawable(context.resources, bitmap), false, DataSource.DISK)
                  }
              }
            }
          }
        )
      }
      .build()

  private fun storyTag(id: String) = "story-row-${Source.HEYBOX}-$id"

  private fun assertNear(message: String, expected: Int, actual: Int) {
    assertTrue("$message: expected=$expected actual=$actual", abs(expected - actual) <= 4)
  }

  private data class CoverAsset(val url: String, val file: File, val color: Int)

  private data class GeneratedStory(val story: Story, val asset: CoverAsset)

  private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int = right - left + 1
    val height: Int = bottom - top + 1
  }

  private class MutablePixelBounds(private val width: Int, private val height: Int) {
    private var left = width
    private var top = height
    private var right = -1
    private var bottom = -1

    fun include(x: Int, y: Int) {
      left = minOf(left, x)
      top = minOf(top, y)
      right = maxOf(right, x)
      bottom = maxOf(bottom, y)
    }

    fun build(): PixelBounds? =
      if (right < left || bottom < top) null else PixelBounds(left, top, right, bottom)
  }

  private companion object {
    const val PROFILE_ID = "84375298"
    const val PROFILE_ENTRY_ID = "synthetic-user-profile-entry"
  }
}
