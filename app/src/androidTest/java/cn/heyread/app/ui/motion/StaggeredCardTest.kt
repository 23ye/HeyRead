package cn.heyread.app.ui.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.saveTargetCacheScreenshot
import cn.heyread.app.ui.screens.FeedSkeletonCards
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaggeredCardTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun revealMovesOnLayerWithoutRecomposingOrRelayingOutContent() {
    compose.mainClock.autoAdvance = false
    var compositionCount = 0
    var measureCount = 0
    var placeCount = 0
    var drawCount = 0
    compose.setContent {
      HeyReadTheme("light", motionEnabled = true) {
        val seen = remember { mutableStateListOf<String>() }
        StaggeredCard("only", FirstVisibleBatch(listOf("only"), true), seen, enabled = true) {
          androidx.compose.foundation.layout.Box(
              Modifier.fillMaxWidth()
                  .height(80.dp)
                  .layout { measurable, constraints ->
                    measureCount++
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                      placeCount++
                      placeable.place(0, 0)
                    }
                  }
                  .drawWithContent {
                    drawCount++
                    drawContent()
                  }
                  .background(Color.Red)
                  .testTag("layer-only-content"))
          androidx.compose.runtime.SideEffect { compositionCount++ }
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    compose.runOnIdle {}
    val initial = compose.onRoot().captureToImage()
    val initialCompositions = compositionCount
    val initialMeasures = measureCount
    val initialPlaces = placeCount
    val initialDraws = drawCount
    compose.mainClock.advanceTimeBy(180)
    assertEquals(
        "animation frames do not recompose static card content",
        initialCompositions,
        compositionCount)
    assertEquals(
        "animation frames do not measure static card content", initialMeasures, measureCount)
    assertEquals("animation frames do not place static card content", initialPlaces, placeCount)
    assertEquals("animation frames only recompose the graphics layer", initialDraws, drawCount)
    val moving = compose.onRoot().captureToImage()
    assertTrue("the real content layer visibly advances", imageDifference(initial, moving) > 0)
    compose.mainClock.advanceTimeBy(400)
    compose.waitForIdle()
  }

  @Test
  fun disablingMidRevealSettlesWithoutRebuildingContentOrReplayingOnEnable() {
    compose.mainClock.autoAdvance = false
    val enabled = mutableStateOf(true)
    var rememberedToken: Any? = null
    compose.setContent {
      HeyReadTheme("light", motionEnabled = enabled.value) {
        val seen = remember { mutableStateListOf<String>() }
        StaggeredCard(
            "toggle",
            FirstVisibleBatch(listOf("toggle"), ready = true),
            seen,
            enabled = enabled.value,
        ) {
          val token = remember { Any() }
          androidx.compose.runtime.SideEffect { rememberedToken = token }
          androidx.compose.foundation.layout.Box(
              Modifier.fillMaxWidth()
                  .height(80.dp)
                  .background(Color.Magenta)
                  .testTag("toggle-content"))
        }
      }
    }
    compose.mainClock.advanceTimeBy(100)
    val beforeDisable = compose.onRoot().captureToImage()
    val tokenBeforeDisable = rememberedToken
    compose.runOnIdle { enabled.value = false }
    compose.mainClock.advanceTimeBy(500)
    val disabledImage = compose.onRoot().captureToImage()
    compose.runOnIdle { enabled.value = true }
    compose.mainClock.advanceTimeBy(200)
    val reenabledImage = compose.onRoot().captureToImage()
    assertTrue(
        "disabling settles the partial reveal immediately",
        imageDifference(beforeDisable, disabledImage) > 0)
    assertEquals(
        "the stable content slot keeps its remembered state", tokenBeforeDisable, rememberedToken)
    assertEquals(
        "reenabling does not replay an already settled reveal",
        0,
        imageDifference(disabledImage, reenabledImage))
  }

  @Test
  fun firstVisibleCardsEnterAtDifferentTimesAndRemainBoundedToTheCapturedBatch() {
    compose.mainClock.autoAdvance = false
    val rows = mutableStateListOf<String>().apply { addAll((0 until 8).map { "story-$it" }) }
    lateinit var seenKeys: SnapshotStateList<String>
    var capturedBatch = FirstVisibleBatch(emptyList(), ready = false)
    compose.setContent {
      HeyReadTheme("light", motionEnabled = true) {
        StaggeredList(rows) { seen, batch ->
          seenKeys = seen
          capturedBatch = batch
        }
      }
    }
    var frameCount = 0
    while (!capturedBatch.ready && frameCount < 10) {
      compose.mainClock.advanceTimeByFrame()
      frameCount++
    }
    assertTrue("the initial visible batch should be captured", capturedBatch.ready)
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("story-0").assertExists()
    compose.onNodeWithTag("story-1").assertExists()
    val firstFrames = mutableListOf<Int>()
    val secondFrames = mutableListOf<Int>()
    val firstAreas = mutableListOf<Int>()
    val secondAreas = mutableListOf<Int>()
    repeat(15) {
      compose.mainClock.advanceTimeBy(16)
      compose.waitForIdle()
      val frame = compose.onRoot().captureToImage()
      firstFrames += coloredTop(frame, Color.Red)
      secondFrames += coloredTop(frame, Color.Green)
      firstAreas += coloredPixelCount(frame, Color.Red)
      secondAreas += coloredPixelCount(frame, Color.Green)
    }
    val firstStartIndex = firstFrames.indexOfFirst { it >= 0 }
    val secondStartIndex = secondFrames.indexOfFirst { it >= 0 }
    System.out.println("stagger frame samples first=$firstFrames second=$secondFrames")
    assertTrue("the first item should render during its entry", firstStartIndex >= 0)
    assertTrue("the next stagger slot should render during its entry", secondStartIndex >= 0)
    assertTrue(
        "the first visible item should begin rendering before the next stagger slot",
        firstStartIndex < secondStartIndex,
    )
    val diagonalMid = compose.onRoot().captureToImage()
    saveTargetCacheScreenshot("v115-diagonal-mid.png")

    compose.mainClock.advanceTimeBy(500)
    compose.waitForIdle()
    val settledImage = compose.onRoot().captureToImage()
    saveTargetCacheScreenshot("v115-diagonal-final.png")
    val settledFirstTop = coloredTop(settledImage, Color.Red)
    val settledSecondTop = coloredTop(settledImage, Color.Green)
    assertTrue(
        "the first clip grows through intermediate pixel areas",
        firstAreas.filter { it > 0 }.distinct().size > 1,
    )
    assertTrue(
        "the delayed second clip also grows through intermediate areas",
        secondAreas.filter { it > 0 }.distinct().size > 1,
    )
    assertTrue(
        "the first card reveals more real pixels before settling",
        coloredPixelCount(diagonalMid, Color.Red) < coloredPixelCount(settledImage, Color.Red),
    )
    assertTrue("settled rows should keep their vertical order", settledSecondTop > settledFirstTop)
    assertEquals("the first captured viewport animates six items", 6, capturedBatch.keys.size)
    assertTrue(
        "visible non-batch items may be marked as already settled",
        seenKeys.size >= capturedBatch.keys.size)
    compose.runOnIdle {
      rows.remove("story-0")
      rows.remove("story-1")
      rows.add("story-8")
    }
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("story-8").assertExists()
    val newStoryTop = coloredTop(compose.onRoot().captureToImage(), Color.Magenta)
    assertTrue("a refreshed non-batch story should be visible immediately", newStoryTop >= 0)
    compose.mainClock.advanceTimeBy(300)
    compose.waitForIdle()
    val settledStoryTop = coloredTop(compose.onRoot().captureToImage(), Color.Magenta)
    assertEquals(
        "refreshed IDs outside the captured first batch appear immediately",
        newStoryTop,
        settledStoryTop,
    )
    assertTrue("the captured animation batch stays bounded", capturedBatch.keys.size <= 6)
  }

  @Test
  fun initialLoadingSkeletonPausesForLifecycleAndBecomesStaticWithReducedMotion() {
    compose.mainClock.autoAdvance = false
    val owner = TestLifecycleOwner()
    compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
    val motionEnabled = mutableStateOf(true)
    compose.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        HeyReadTheme("light", motionEnabled = motionEnabled.value) {
          FeedSkeletonCards(motion = motionEnabled.value, isCurrentPage = true)
        }
      }
    }
    compose.mainClock.advanceTimeBy(120)
    val first = compose.onRoot().captureToImage()
    compose.mainClock.advanceTimeBy(425)
    val middle = compose.onRoot().captureToImage()
    saveTargetCacheScreenshot("v115-skeleton.png")
    assertTrue("foreground skeleton animates its painted alpha", imageDifference(first, middle) > 0)
    compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
    compose.mainClock.advanceTimeBy(1000)
    val background = compose.onRoot().captureToImage()
    compose.mainClock.advanceTimeBy(1000)
    val backgroundAgain = compose.onRoot().captureToImage()
    assertEquals(
        "skeleton animation stops on lifecycle pause",
        0,
        imageDifference(background, backgroundAgain),
    )
    compose.runOnIdle { motionEnabled.value = false }
    compose.mainClock.advanceTimeBy(500)
    val reducedA = compose.onRoot().captureToImage()
    compose.mainClock.advanceTimeBy(500)
    val reducedB = compose.onRoot().captureToImage()
    assertEquals("reduced motion keeps a static skeleton", 0, imageDifference(reducedA, reducedB))
  }
}

@Composable
private fun StaggeredList(
    items: List<String>,
    onSeen: (SnapshotStateList<String>, FirstVisibleBatch) -> Unit,
) {
  val listState = rememberLazyListState()
  val keys = items.toSet()
  val batch = rememberFirstVisibleBatch("test-list", listState, keys)
  val seen = remember { mutableStateListOf<String>() }
  onSeen(seen, batch)
  Box(Modifier.fillMaxSize().background(Color.White)) {
    LazyColumn(state = listState) {
      items(items, key = { it }) { key ->
        StaggeredCard(key, batch, seen, enabled = true) {
          Box(Modifier.fillMaxWidth().height(56.dp).background(storyColor(key)).testTag(key))
        }
      }
    }
  }
}

private fun storyColor(key: String): Color =
    when (key) {
      "story-0" -> Color.Red
      "story-1" -> Color.Green
      "story-8" -> Color.Magenta
      else -> Color.Blue
    }

private fun coloredTop(bitmap: ImageBitmap, color: Color): Int {
  val pixels = bitmap.toPixelMap()
  val x = pixels.width / 2
  return (0 until pixels.height).firstOrNull { y ->
    val pixel = pixels[x, y]
    when (color) {
      Color.Red -> pixel.red > 0.2f && pixel.green < 0.8f && pixel.blue < 0.8f
      Color.Green -> pixel.green > 0.2f && pixel.red < 0.8f && pixel.blue < 0.8f
      Color.Magenta -> pixel.red > 0.2f && pixel.blue > 0.2f && pixel.green < 0.8f
      else -> false
    }
  } ?: -1
}

private fun coloredPixelCount(bitmap: ImageBitmap, color: Color): Int {
  val pixels = bitmap.toPixelMap()
  var count = 0
  for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
    val pixel = pixels[x, y]
    if ((color == Color.Red && pixel.red > 0.2f && pixel.green < 0.8f && pixel.blue < 0.8f) ||
        (color == Color.Green && pixel.green > 0.2f && pixel.red < 0.8f && pixel.blue < 0.8f))
        count++
  }
  return count
}

private class TestLifecycleOwner : LifecycleOwner {
  val registry = LifecycleRegistry(this)
  override val lifecycle: Lifecycle = registry
}

private fun imageDifference(first: ImageBitmap, second: ImageBitmap): Int {
  val firstPixels = first.toPixelMap()
  val secondPixels = second.toPixelMap()
  var changed = 0
  for (y in 0 until minOf(firstPixels.height, secondPixels.height)) {
    for (x in 0 until minOf(firstPixels.width, secondPixels.width)) {
      if (firstPixels[x, y] != secondPixels[x, y]) changed++
    }
  }
  return changed
}
