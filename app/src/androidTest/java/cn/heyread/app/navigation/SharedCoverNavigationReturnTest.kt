package cn.heyread.app.navigation

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.Source
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.FeedTab
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedCoverNavigationReturnTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule val temporaryFolder = TemporaryFolder()

  private var testViewModelStore: ViewModelStore? = null
  private var testDataStoreScope: CoroutineScope? = null

  @After
  fun clearTestState() {
    compose.activityRule.scenario.onActivity { testViewModelStore?.clear() }
    testViewModelStore = null
    testDataStoreScope?.cancel()
    testDataStoreScope = null
  }

  @Test
  fun productionPhoneDualHostRestoresReturnOverlayAcrossFiveRoundsAndQuickReopen() {
    compose.mainClock.autoAdvance = false
    val requestedImages = Collections.synchronizedList(mutableListOf<String>())
    val viewModel = prepareDemoViewModel()
    val loader = patternedImageLoader(compose.activity, requestedImages)
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        Box(Modifier.width(410.dp).fillMaxHeight().testTag("shared-return-phone-root")) {
          HeyReadNavigation(viewModel)
        }
      }
    }
    awaitTag("adaptive-pane-layout")
    compose.waitUntil(10_000) {
      viewModel.state.value.discoverFeed.items.size == 3 &&
        viewModel.state.value.discoverFeed.source == Source.DEMO
    }
    val cardTag = "home-card-${viewModel.state.value.homeLayout.name}-demo-1"
    val coverTag = "home-cover-demo-1"
    awaitImagePixels(requestedImages)

    repeat(5) { round ->
      val originalSourceBounds = bounds(coverTag)
      openAndSettle(cardTag)
      val detailBounds = bounds("detail-cover-demo-1")
      assertTrue("round $round loaded the actual detail cover", detailBounds.width > 1f)
      assertPatternPixels("round $round detail source", captureRoot())

      if (round % 2 == 1) compose.onNodeWithTag("page-back").performClick()
      else dispatchSystemBack()
      advanceFrame()
      assertPatternPixels("round $round first frame after system Back", captureRoot())

      val firstOverlay = awaitReturnOverlay(round)
      repeat(8) { advanceFrame() }
      val movingOverlay = returnOverlayBounds()
      val distanceMoved = centerDistance(firstOverlay, movingOverlay)
      val distanceToSourceAtStart = centerDistance(firstOverlay, originalSourceBounds)
      val distanceToSourceAtMidpoint = centerDistance(movingOverlay, originalSourceBounds)
      assertTrue(
        "round $round draws a moving production return overlay (start=$firstOverlay mid=$movingOverlay)",
        distanceMoved > 2f,
      )
      assertTrue(
        "round $round reverse overlay moves toward the original source bounds",
        distanceToSourceAtMidpoint < distanceToSourceAtStart,
      )
      assertPatternPixels("round $round return midpoint", captureRoot())

      repeat(36) { advanceFrame() }
      assertTrue(
        "round $round releases the drawn return overlay",
        compose.onAllNodesWithTag("shared-cover-overlay-return").fetchSemanticsNodes().isEmpty(),
      )
      compose.onNodeWithTag(cardTag).assertIsDisplayed()
      val sourceBounds = bounds(coverTag)
      assertTrue("round $round restores the real source cover", sourceBounds.width > 1f)
      assertPatternPixels("round $round settled source", captureRoot())
    }

    // Reopen while the paired return is still moving, then complete one final real round trip.
    openAndSettle(cardTag)
    dispatchSystemBack()
    advanceFrame()
    assertPatternPixels("quick-reopen return first frame", captureRoot())
    awaitReturnOverlay(5)
    repeat(2) { advanceFrame() }
    val quickSourceAtReopen = unclippedAndVisibleBounds(coverTag)
    assertTrue(
      "quick reopen source cover remains laid out",
      quickSourceAtReopen.unclipped.width > 1f && quickSourceAtReopen.unclipped.height > 1f,
    )
    assertTrue(
      "quick reopen source cover is still clipped by the entering root pane " +
        "(unclipped=${quickSourceAtReopen.unclipped}, visible=${quickSourceAtReopen.visible})",
      quickSourceAtReopen.visible.width < quickSourceAtReopen.unclipped.width * 0.95f,
    )
    compose.onNodeWithTag(cardTag).performSemanticsAction(SemanticsActions.OnClick) { onClick ->
      assertTrue("quick reopen invokes the source card's OnClick action", onClick())
    }
    awaitTag("detail-cover-demo-1")
    val quickForwardFrames = mutableListOf<Rect>()
    repeat(36) {
      compose
        .onAllNodesWithTag("shared-cover-overlay-forward")
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.let { quickForwardFrames += it.boundsInRoot }
      advanceFrame()
    }
    val quickDetailBounds = bounds("detail-cover-demo-1")
    assertTrue("quick fallback opens a real detail cover", quickDetailBounds.width > 1f)
    assertTrue(
      "clipped quick source does not create a false forward shared-cover transaction " +
        "(source=${quickSourceAtReopen.unclipped}/${quickSourceAtReopen.visible}, " +
        "detail=$quickDetailBounds, forwardFrames=${quickForwardFrames.size})",
      quickForwardFrames.isEmpty(),
    )
    dispatchSystemBack()
    advanceFrame()
    assertPatternPixels("quick-reopen final return first frame", captureRoot())
    repeat(36) { advanceFrame() }
    assertTrue(
      "quick fallback leaves no stale return overlay",
      compose.onAllNodesWithTag("shared-cover-overlay-return").fetchSemanticsNodes().isEmpty(),
    )
    assertTrue(
      "quick fallback leaves no stale forward overlay",
      compose.onAllNodesWithTag("shared-cover-overlay-forward").fetchSemanticsNodes().isEmpty(),
    )
    compose.onNodeWithTag(cardTag).assertIsDisplayed()
    val restoredSource = unclippedAndVisibleBounds(coverTag)
    assertTrue(
      "quick fallback restores the full source cover before a real pointer reopen",
      restoredSource.unclipped.width > 1f &&
        kotlin.math.abs(restoredSource.visible.width - restoredSource.unclipped.width) < 1f,
    )
    assertPatternPixels("quick fallback settled source", captureRoot())

    // Once the source is fully visible again, a real pointer open must form a fresh pair.
    compose.onNodeWithTag(cardTag).performScrollTo().performClick()
    awaitTag("detail-cover-demo-1")
    var restoredForwardObserved = false
    repeat(36) {
      if (
        compose.onAllNodesWithTag("shared-cover-overlay-forward").fetchSemanticsNodes().isNotEmpty()
      ) {
        restoredForwardObserved = true
      }
      advanceFrame()
    }
    assertTrue(
      "restored real pointer open draws its forward shared-cover overlay",
      restoredForwardObserved,
    )
    val restoredDetailBounds = bounds("detail-cover-demo-1")
    assertTrue("restored pointer open has a real detail cover", restoredDetailBounds.width > 1f)
    assertPatternPixels("restored pointer open settled detail", captureRoot())
    dispatchSystemBack()
    advanceFrame()
    assertPatternPixels("restored pointer return first frame", captureRoot())
    awaitReturnOverlay(7)
    repeat(36) { advanceFrame() }
    assertTrue(
      "restored pointer roundtrip releases its return overlay",
      compose.onAllNodesWithTag("shared-cover-overlay-return").fetchSemanticsNodes().isEmpty(),
    )
    compose.onNodeWithTag(cardTag).assertIsDisplayed()
    assertPatternPixels("restored pointer roundtrip settled source", captureRoot())
  }

  private fun openAndSettle(cardTag: String) {
    compose.onNodeWithTag(cardTag).performScrollTo().performClick()
    awaitTag("detail-cover-demo-1")
    repeat(36) { advanceFrame() }
    assertPatternPixels("settled detail", captureRoot())
  }

  private fun dispatchSystemBack() {
    compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
  }

  private fun awaitReturnOverlay(round: Int): Rect {
    repeat(48) {
      val node = compose.onAllNodesWithTag("shared-cover-overlay-return").fetchSemanticsNodes()
      if (node.isNotEmpty()) return node.first().boundsInRoot
      advanceFrame()
      if (it % 4 == 0)
        assertPatternPixels("round $round waiting for restored source", captureRoot())
    }
    assertTrue("round $round creates the real return-direction overlay", false)
    return Rect.Zero
  }

  private fun returnOverlayBounds(): Rect =
    compose.onNodeWithTag("shared-cover-overlay-return").fetchSemanticsNode().boundsInRoot

  private fun bounds(tag: String): Rect =
    compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

  private fun unclippedAndVisibleBounds(tag: String): CoverBounds {
    val coordinates =
      compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
    return CoverBounds(
      unclipped =
        coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false),
      visible = coordinates.boundsInRoot(),
    )
  }

  private data class CoverBounds(val unclipped: Rect, val visible: Rect)

  private fun centerDistance(first: Rect, second: Rect): Float {
    val dx = first.center.x - second.center.x
    val dy = first.center.y - second.center.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
  }

  private fun captureRoot(): ImageBitmap = compose.onRoot().captureToImage()

  private fun assertPatternPixels(message: String, image: ImageBitmap) {
    val pixels = image.toPixelMap()
    var matching = 0
    for (y in 0 until pixels.height step 2) {
      for (x in 0 until pixels.width step 2) {
        val color = pixels[x, y]
        if (color.red > 0.72f && color.blue > 0.72f && color.green < 0.38f && color.alpha > 0.7f)
          matching++
      }
    }
    assertTrue("$message keeps the actual synthetic cover visible (pixels=$matching)", matching > 3)
  }

  private fun awaitImagePixels(requestedImages: List<String>) {
    compose.waitUntil(10_000) { requestedImages.isNotEmpty() }
    repeat(24) {
      if (runCatching { assertPatternPixels("loaded DEMO cover", captureRoot()) }.isSuccess) return
      advanceFrame()
    }
    assertPatternPixels("loaded DEMO cover", captureRoot())
  }

  private fun advanceFrame() {
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
  }

  private fun awaitTag(tag: String) {
    repeat(48) {
      if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
      advanceFrame()
    }
    assertTrue("expected production node $tag", false)
  }

  private fun prepareDemoViewModel(): AppViewModel {
    val instrumentationBase: Context = InstrumentationRegistry.getInstrumentation().context
    val isolatedRoot = temporaryFolder.newFolder("shared-return-test-context")
    val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    testDataStoreScope = dataStoreScope
    val injectedPreferencesStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = dataStoreScope) {
        File(isolatedRoot, "settings.preferences_pb")
      }
    val instrumentationContext =
      object : ContextWrapper(instrumentationBase) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
      }
    val store =
      LibraryStore(
        instrumentationContext,
        File(isolatedRoot, "reading-fonts"),
        injectedPreferencesStore,
      )
    kotlinx.coroutines.runBlocking {
      store.setMotionEnabled(true)
      store.setNoImages(false)
    }

    val owner = ViewModelStore()
    testViewModelStore = owner
    lateinit var viewModel: AppViewModel
    compose.activityRule.scenario.onActivity {
      viewModel = AppViewModel(store)
      owner.put("shared-cover-return-test", viewModel)
      viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
    }
    compose.waitUntil(10_000) {
      val state = viewModel.state.value
      state.motionEnabled &&
        !state.noImages &&
        state.discoverFeed.source == Source.DEMO &&
        state.discoverFeed.items.size == 3 &&
        !state.discoverFeed.loading
    }
    assertTrue(viewModel.state.value.discoverFeed.items.all { it.source == Source.DEMO })
    return viewModel
  }

  private fun patternedImageLoader(context: Context, requested: MutableList<String>): ImageLoader {
    val bitmap = patternBitmap()
    return ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
              if (data.scheme != "https") return null
              return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                  requested += data.toString()
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
  }

  private fun patternBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(android.graphics.Color.rgb(25, 40, 55))
    paint.color = android.graphics.Color.rgb(230, 60, 55)
    canvas.drawRect(0f, 0f, 80f, 180f, paint)
    paint.color = android.graphics.Color.rgb(45, 175, 110)
    canvas.drawRect(80f, 0f, 160f, 180f, paint)
    paint.color = android.graphics.Color.rgb(60, 100, 220)
    canvas.drawRect(160f, 0f, 240f, 180f, paint)
    paint.color = android.graphics.Color.rgb(230, 180, 35)
    canvas.drawRect(240f, 0f, 320f, 180f, paint)
    paint.color = android.graphics.Color.MAGENTA
    canvas.drawCircle(160f, 90f, 26f, paint)
    return bitmap
  }
}
