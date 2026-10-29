package cn.heyread.app.ui.motion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.saveTargetCacheScreenshot
import cn.heyread.app.ui.screens.storyCoverSharedIdentity
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedCoverTransitionTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun pendingBackCancelsBeforeTargetAndReopenStartsANewGeneration() {
    val coordinator = SharedCoverCoordinator()
    val identity = "HEYBOX|story|cover|search-entry"
    val painter = BitmapPainter(redBitmap())
    coordinator.register(
      SharedCoverRegistration(
        identity = identity,
        originId = "search-entry",
        entryId = "search-entry",
        isDetail = false,
        rect = Rect(12f, 18f, 92f, 74f),
        painter = painter,
        animated = false,
        failed = false,
      )
    )
    assertTrue(coordinator.begin(identity, "search-entry", "search-entry"))
    val firstGeneration = coordinator.transaction!!.generation
    coordinator.setCurrentEntry("detail-entry")
    assertTrue(
      "pending destination keeps the frozen source until known",
      coordinator.transaction != null,
    )
    coordinator.setCurrentEntry("search-entry")
    assertNull(
      "back before target registration cancels the pending overlay",
      coordinator.transaction,
    )

    assertTrue(
      "reopen can begin from the retained real source registration",
      coordinator.begin(identity, "search-entry", "search-entry"),
    )
    assertTrue(
      "reopen uses a fresh generation",
      coordinator.transaction!!.generation > firstGeneration,
    )
    assertSame(
      "reopened transition retains the same actual Painter",
      painter,
      coordinator.transaction!!.painter,
    )
    coordinator.cancel()
    assertNull("cancel releases the painter", coordinator.transaction)
  }

  @Test
  fun lateOldOwnerRemovalAndFinishCannotClearNewGeneration() {
    val coordinator = SharedCoverCoordinator()
    val identity = "HEYBOX|story|cover|search-entry"
    val oldOwner = Any()
    val newOwner = Any()
    val oldPainter = BitmapPainter(redBitmap())
    val newPainter = BitmapPainter(redBitmap())
    fun registration(owner: Any, painter: Painter) =
      SharedCoverRegistration(
        identity = identity,
        originId = "search-entry",
        entryId = "search-entry",
        isDetail = false,
        rect = Rect(12f, 18f, 92f, 74f),
        painter = painter,
        animated = false,
        failed = false,
        owner = owner,
      )
    coordinator.register(registration(oldOwner, oldPainter))
    assertTrue(coordinator.begin(identity, "search-entry", "search-entry"))
    val oldGeneration = coordinator.transaction!!.generation

    coordinator.register(registration(newOwner, newPainter))
    coordinator.remove(identity, "search-entry", "search-entry", false, oldOwner)
    coordinator.cancel()

    assertTrue(
      "current endpoint survives an old DisposableEffect",
      coordinator.begin(identity, "search-entry", "search-entry"),
    )
    val newGeneration = coordinator.transaction!!
    assertTrue("the replacement owns a fresh transaction", newGeneration.generation > oldGeneration)
    assertSame("the replacement Painter remains registered", newPainter, newGeneration.painter)
    coordinator.finish(oldGeneration)
    assertTrue(
      "late finish from an old animation cannot clear the replacement",
      coordinator.transaction?.generation == newGeneration.generation,
    )
    coordinator.finish(newGeneration.generation)
    assertNull(coordinator.transaction)
  }

  @Test
  fun staleDetailRegistrationCannotClaimAReopenedForwardTarget() {
    val coordinator = SharedCoverCoordinator()
    val identity = "HEYBOX|story|cover|search-entry"
    val sourcePainter = BitmapPainter(redBitmap())
    coordinator.setCurrentEntry("search-entry")
    coordinator.register(
      SharedCoverRegistration(
        identity = identity,
        originId = "search-entry",
        entryId = "search-entry",
        isDetail = false,
        rect = Rect(12f, 18f, 92f, 74f),
        painter = sourcePainter,
        animated = false,
        failed = false,
      )
    )
    assertTrue(coordinator.begin(identity, "search-entry", "search-entry"))
    coordinator.bindExpectedTarget("new-detail-entry")

    coordinator.register(
      SharedCoverRegistration(
        identity = identity,
        originId = "search-entry",
        entryId = "old-exiting-detail",
        isDetail = true,
        rect = Rect(0f, 200f, 400f, 420f),
        painter = sourcePainter,
        animated = false,
        failed = false,
        owner = Any(),
      )
    )
    assertTrue(
      "stale exiting detail cannot claim or cancel the new open",
      coordinator.transaction?.targetEntryId == null,
    )
    assertTrue("new open remains active", coordinator.transaction != null)

    coordinator.register(
      SharedCoverRegistration(
        identity = identity,
        originId = "search-entry",
        entryId = "new-detail-entry",
        isDetail = true,
        rect = Rect(0f, 220f, 420f, 440f),
        painter = sourcePainter,
        animated = false,
        failed = false,
        owner = Any(),
      )
    )
    assertTrue(
      "the explicitly bound destination is paired",
      coordinator.transaction?.targetEntryId == "new-detail-entry",
    )
  }

  @Test
  fun productionPainterOverlayMovesBetweenRegisteredCoverBounds() {
    compose.mainClock.autoAdvance = false
    lateinit var coordinator: SharedCoverCoordinator
    lateinit var painter: Painter
    lateinit var sourceListState: LazyListState
    compose.setContent {
      HeyReadTheme("light", motionEnabled = true) {
        SharedCoverHarness(originId = "search-entry", detailEntryId = "detail-entry") {
          c,
          p,
          state,
          _ ->
          coordinator = c
          painter = p
          sourceListState = state
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    compose.runOnIdle {
      assertTrue(
        "test first scrolls to the loaded cover",
        sourceListState.firstVisibleItemIndex == 4,
      )
    }
    val start = redBounds(compose.onRoot().captureToImage())
    compose.onNodeWithTag("open-shared-cover").performClick()
    repeat(4) {
      if (coordinator.transaction?.targetReady != true) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
      }
    }
    compose.runOnIdle {
      assertTrue(
        "destination bounds are registered before timing the animation",
        coordinator.transaction?.targetReady == true,
      )
      assertSame(
        "the overlay must retain the exact successful Painter",
        painter,
        coordinator.transaction?.painter,
      )
    }
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeBy(260)
    val middleImage = compose.onRoot().captureToImage()
    saveTargetCacheScreenshot("v116-shared-mid.png")
    val middle = redBounds(middleImage)
    assertTrue(
      "production overlay should move during the shared transition",
      middle.top > start.top,
    )
    compose.mainClock.advanceTimeBy(260)
    compose.mainClock.advanceTimeByFrame()
    val endImage = compose.onRoot().captureToImage()
    saveTargetCacheScreenshot("v116-shared-final.png")
    val end = redBounds(endImage)
    assertTrue("overlay should settle at target bounds", end.top > middle.top)
    assertTrue("overlay should grow to detail cover size", end.width > middle.width)
    compose.runOnIdle {
      assertNull("finished generation releases the frozen painter", coordinator.transaction)
    }
  }

  @Test
  fun staleOriginDoesNotStartOverlayAndNavigationContinues() {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      HeyReadTheme("light", motionEnabled = true) {
        SharedCoverHarness(originId = "other-entry", detailEntryId = "detail-entry") { _, _, _, _ ->
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("open-shared-cover").performClick()
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("shared-detail-cover").assertExists()
    val detailBounds =
      compose.onNodeWithTag("shared-detail-cover").fetchSemanticsNode().boundsInRoot
    val detailImage = compose.onNodeWithTag("shared-detail-cover").captureToImage()
    assertTrue(
      "stale origin falls back to the real detail cover position",
      detailBounds.top > 100f && detailBounds.height > 100f && redBounds(detailImage).width > 0,
    )
  }

  @Test
  fun realDetailListSwipeCancelsFrozenPainterAndScrollsNormally() {
    compose.mainClock.autoAdvance = false
    lateinit var coordinator: SharedCoverCoordinator
    lateinit var detailListState: LazyListState
    compose.setContent {
      HeyReadTheme("light", motionEnabled = true) {
        SharedCoverHarness(
          originId = "search-entry",
          detailEntryId = "detail-entry",
          scrollableDetail = true,
        ) { c, _, _, detailState ->
          coordinator = c
          detailListState = detailState
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("open-shared-cover").performClick()
    compose.mainClock.advanceTimeBy(220)
    compose.runOnIdle {
      assertTrue("shared overlay is active before the swipe", coordinator.transaction != null)
    }
    compose.onNodeWithTag("shared-detail-list").performTouchInput { swipeUp() }
    compose.mainClock.advanceTimeByFrame()
    compose.runOnIdle {
      assertTrue("the detail list actually scrolled", detailListState.firstVisibleItemIndex > 0)
      assertNull("a real list drag cancels the overlay immediately", coordinator.transaction)
    }
  }
}

@Composable
private fun SharedCoverHarness(
  originId: String,
  detailEntryId: String,
  scrollableDetail: Boolean = false,
  onState: (SharedCoverCoordinator, Painter, LazyListState, LazyListState) -> Unit,
) {
  var detail by remember { mutableStateOf(false) }
  val coordinator = remember { SharedCoverCoordinator() }
  val identity =
    storyCoverSharedIdentity(
      "HEYBOX",
      "shared-test-story",
      "https://images.example/shared-cover.png",
      originId,
      enabled = true,
    )
  val painter = remember { BitmapPainter(redBitmap()) }
  val sourceListState = rememberLazyListState()
  val detailListState = rememberLazyListState()
  androidx.compose.runtime.LaunchedEffect(sourceListState) { sourceListState.scrollToItem(4) }
  androidx.compose.runtime.SideEffect {
    onState(coordinator, painter, sourceListState, detailListState)
  }
  androidx.compose.runtime.LaunchedEffect(detail) {
    coordinator.setCurrentEntry(if (detail) detailEntryId else "search-entry")
  }
  Box(Modifier.fillMaxSize()) {
    if (!detail) {
      LazyColumn(Modifier.fillMaxSize(), state = sourceListState) {
        items(4) { Spacer(Modifier.height(100.dp)) }
        item {
          registeredCover(
            painter,
            identity,
            "search-entry",
            "search-entry",
            false,
            80.dp,
            56.dp,
            coordinator,
            "shared-source-cover",
          )
        }
        item {
          Button(
            onClick = {
              coordinator.begin(identity, originId, "search-entry")
              coordinator.bindExpectedTarget(detailEntryId)
              detail = true
            },
            modifier = Modifier.testTag("open-shared-cover"),
          ) {
            Text("打开")
          }
        }
        items(20) { Spacer(Modifier.height(100.dp)) }
      }
    } else if (scrollableDetail) {
      cancelSharedCoverOnScroll(detailListState, detailEntryId, coordinator)
      LazyColumn(Modifier.fillMaxSize().testTag("shared-detail-list"), state = detailListState) {
        item { Spacer(Modifier.height(144.dp)) }
        item {
          registeredCover(
            painter,
            identity,
            originId,
            detailEntryId,
            true,
            240.dp,
            160.dp,
            coordinator,
            "shared-detail-cover",
          )
        }
        items(20) { index -> Text("detail-row-$index", Modifier.height(72.dp)) }
      }
    } else {
      androidx.compose.runtime.LaunchedEffect(detail) {
        coordinator.setCurrentEntry(if (detail) detailEntryId else "search-entry")
      }
      Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(144.dp))
        registeredCover(
          painter,
          identity,
          originId,
          detailEntryId,
          true,
          240.dp,
          160.dp,
          coordinator,
          "shared-detail-cover",
        )
      }
    }
    SharedCoverOverlayHost(coordinator)
  }
}

@Composable
private fun registeredCover(
  painter: Painter,
  identity: String?,
  originId: String,
  entryId: String,
  isDetail: Boolean,
  width: androidx.compose.ui.unit.Dp,
  height: androidx.compose.ui.unit.Dp,
  coordinator: SharedCoverCoordinator,
  tag: String,
) {
  val hidden = coordinator.isHidden(identity, originId, entryId, isDetail)
  Image(
    painter = painter,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier =
      Modifier.size(width, height)
        .graphicsLayerCompat(hidden)
        .sharedCoverEndpoint(
          identity,
          originId,
          entryId,
          isDetail,
          painter,
          false,
          false,
          true,
          coordinator,
        )
        .testTag(tag),
  )
}

private fun Modifier.graphicsLayerCompat(hidden: Boolean): Modifier = graphicsLayer {
  alpha = if (hidden) 0f else 1f
}

private data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)

private fun redBitmap(): ImageBitmap =
  ImageBitmap(16, 16).also { bitmap ->
    Canvas(bitmap).drawRect(Rect(0f, 0f, 16f, 16f), Paint().apply { color = Color.Red })
  }

private fun redBounds(bitmap: ImageBitmap): Bounds {
  val pixels = bitmap.toPixelMap()
  var left = pixels.width
  var right = -1
  var top = pixels.height
  var bottom = -1
  for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
    val pixel = pixels[x, y]
    if (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) {
      left = minOf(left, x)
      right = maxOf(right, x)
      top = minOf(top, y)
      bottom = maxOf(bottom, y)
    }
  }
  assertTrue("the real painted cover must be visible", right >= left && bottom >= top)
  return Bounds(left, top, right - left + 1, bottom - top + 1)
}
