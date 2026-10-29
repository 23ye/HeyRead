package cn.heyread.app.navigation

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.ui.theme.HeyReadTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomBarMotionTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun selectedCapsulesScaleAndFadeInPlaceAndRetargetRapidly() {
    compose.mainClock.autoAdvance = false
    val destination = mutableStateOf("discover")
    var renderedView: View? = null
    compose.setContent {
      val localView = LocalView.current
      SideEffect { renderedView = localView }
      HeyReadTheme("light", motionEnabled = true) {
        Surface(Modifier.fillMaxSize()) {
          androidx.compose.foundation.layout.Column {
            Spacer(Modifier.weight(1f))
            HeyReadBottomBar(destination.value, motionEnabled = true) { destination.value = it }
          }
        }
      }
    }
    compose.waitForIdle()
    val platformView = requireNotNull(renderedView)
    var originalFrameRate = 0f
    if (Build.VERSION.SDK_INT >= 35) {
      compose.runOnIdle { originalFrameRate = platformView.requestedFrameRate }
    }
    val discoverTag = "root-tab-capsule-discover"
    val gamesTag = "root-tab-capsule-games"
    val initialImage = compose.onRoot().captureToImage()
    saveCapture("v1162-bottom-capsule-initial.png", initialImage)
    val oldInitial = capsuleStats(discoverTag, initialImage)
    val iconsBefore = tabBounds("root-tab-icon-discover", "root-tab-icon-games")
    val labelsBefore = tabBounds("root-tab-label-discover", "root-tab-label-games")
    compose.runOnIdle { destination.value = "games" }
    compose.mainClock.advanceTimeBy(65)
    compose.mainClock.advanceTimeByFrame()
    if (Build.VERSION.SDK_INT >= 35) {
      assertEquals(
        "the production bottom-bar transition requests HIGH while moving",
        View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
        platformView.requestedFrameRate,
        0f,
      )
    }
    val earlyImage = compose.onRoot().captureToImage()
    saveCapture("v1162-bottom-capsule-early.png", earlyImage)
    val oldEarly = capsuleStats(discoverTag, earlyImage)
    assertTrue(
      "old capsule background area starts shrinking in place",
      oldEarly.area < oldInitial.area,
    )
    assertTrue("old capsule remains visible early in the cross-fade", oldEarly.area > 0)
    assertTrue("old capsule begins fading", oldEarly.peakOpacity < oldInitial.peakOpacity)

    compose.mainClock.advanceTimeBy(110)
    compose.mainClock.advanceTimeByFrame()
    val middleImage = compose.onRoot().captureToImage()
    saveCapture("v1162-bottom-capsule-middle.png", middleImage)
    val newMiddle = capsuleStats(gamesTag, middleImage)
    assertTrue("new capsule background becomes visible", newMiddle.area > 0)
    assertTrue("new capsule expands in place", newMiddle.width > 0)
    val iconsMiddle = tabBounds("root-tab-icon-discover", "root-tab-icon-games")
    val labelsMiddle = tabBounds("root-tab-label-discover", "root-tab-label-games")
    assertEquals(iconsBefore, iconsMiddle)
    assertEquals(labelsBefore, labelsMiddle)
    compose.mainClock.advanceTimeBy(230)
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeBy(200)
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    if (Build.VERSION.SDK_INT >= 35) {
      assertEquals(
        "the bottom-bar transition restores its prior hint",
        originalFrameRate,
        platformView.requestedFrameRate,
        0f,
      )
    }
    val finalImage = compose.onRoot().captureToImage()
    saveCapture("v1162-bottom-capsule-final.png", finalImage)
    val oldFinal = capsuleStats(discoverTag, finalImage)
    val newFinal = capsuleStats(gamesTag, finalImage)
    assertEquals("old capsule disappears at completion", 0, oldFinal.area)
    assertTrue("new capsule expands beyond its intermediate frame", newFinal.area > newMiddle.area)
    assertTrue("new capsule settles to full opacity", newFinal.peakOpacity > newMiddle.peakOpacity)

    compose.runOnIdle { destination.value = "dynamic" }
    compose.mainClock.advanceTimeBy(90)
    compose.runOnIdle { destination.value = "discover" }
    compose.mainClock.advanceTimeBy(90)
    compose.runOnIdle { destination.value = "games" }
    compose.mainClock.advanceTimeBy(450)
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("root-tab-games").assertIsSelected()
    val rapidFinal = compose.onRoot().captureToImage()
    assertTrue(
      "rapid retargeting settles on the latest destination",
      capsuleStats(gamesTag, rapidFinal).area > 0,
    )
    compose.onNodeWithTag(discoverTag, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun fixedTabsKeepSelectedCapsuleSemanticsAndStableIconSlots() {
    var destination = "discover"
    var navigationCount = 0
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        val current = remember { mutableStateOf(destination) }
        Surface(Modifier.fillMaxSize()) {
          androidx.compose.foundation.layout.Column {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            HeyReadBottomBar(current.value, motionEnabled = false) { target ->
              navigationCount++
              current.value = target
              destination = target
            }
          }
        }
      }
    }
    compose.onNodeWithTag("root-tab-discover").assertIsSelected()
    val discoverCapsule =
      compose
        .onNodeWithTag("root-tab-capsule-discover", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    assertEquals(with(compose.density) { 64.dp.toPx() }, discoverCapsule.width, 1f)
    assertEquals(with(compose.density) { 32.dp.toPx() }, discoverCapsule.height, 1f)
    compose
      .onNodeWithTag("root-tab-indicator-discover", useUnmergedTree = true)
      .assertDoesNotExist()
    val discoverTab = compose.onNodeWithTag("root-tab-discover").fetchSemanticsNode().boundsInRoot
    val rendered = compose.onRoot().captureToImage()
    File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "v1162-bottom-capsule-static.png",
      )
      .outputStream()
      .use {
        check(
          rendered.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        )
      }
    compose.onNodeWithTag("root-tab-discover").performClick()
    assertEquals("reselecting the current root tab is a no-op", 0, navigationCount)
    compose.onNodeWithTag("root-tab-games").performClick()
    compose.onNodeWithTag("root-tab-games").assertIsSelected()
    assertEquals("games", destination)
    assertEquals(1, navigationCount)
    val gamesCapsule =
      compose
        .onNodeWithTag("root-tab-capsule-games", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    val gamesTab = compose.onNodeWithTag("root-tab-games").fetchSemanticsNode().boundsInRoot
    assertEquals("selection never changes tab layout geometry", discoverTab.top, gamesTab.top, 0.5f)
    assertEquals(discoverCapsule.top, gamesCapsule.top, 0.5f)
    compose.onNodeWithTag("root-tab-capsule-games", useUnmergedTree = true).assertExists()
    val iconBounds =
      compose
        .onNodeWithTag("root-tab-icon-games", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    val labelBounds =
      compose
        .onNodeWithTag("root-tab-label-games", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    assertTrue(
      "icon remains inside its fixed capsule slot",
      iconBounds.center.x in gamesCapsule.left..gamesCapsule.right,
    )
    assertTrue("label remains below the icon slot", labelBounds.top >= gamesCapsule.bottom)
  }

  @Test
  fun disablingMotionMidSwitchImmediatelyShowsStaticCurrentSelection() {
    compose.mainClock.autoAdvance = false
    val destination = mutableStateOf("discover")
    val motionEnabled = mutableStateOf(true)
    var renderedView: View? = null
    compose.setContent {
      val localView = LocalView.current
      SideEffect { renderedView = localView }
      HeyReadTheme("light", motionEnabled = motionEnabled.value) {
        Surface(Modifier.fillMaxSize()) {
          androidx.compose.foundation.layout.Column {
            Spacer(Modifier.weight(1f))
            HeyReadBottomBar(destination.value, motionEnabled.value) { destination.value = it }
          }
        }
      }
    }
    compose.waitForIdle()
    val platformView = requireNotNull(renderedView)
    var originalFrameRate = 0f
    if (Build.VERSION.SDK_INT >= 35) {
      compose.runOnIdle { originalFrameRate = platformView.requestedFrameRate }
    }
    compose.onNodeWithTag("root-tab-games").performClick()
    compose.mainClock.advanceTimeBy(120)
    compose.mainClock.advanceTimeByFrame()
    if (Build.VERSION.SDK_INT >= 35) {
      assertEquals(
        "the production bottom-bar transition requests HIGH while moving",
        View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
        platformView.requestedFrameRate,
        0f,
      )
    }
    compose.runOnIdle { motionEnabled.value = false }
    repeat(3) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.waitForIdle()
    if (Build.VERSION.SDK_INT >= 35) {
      assertEquals(
        "disabling motion immediately restores the prior platform preference",
        originalFrameRate,
        platformView.requestedFrameRate,
        0f,
      )
    }
    compose.onNodeWithTag("root-tab-games").assertIsSelected()
    compose.onNodeWithTag("root-tab-capsule-discover", useUnmergedTree = true).assertDoesNotExist()
    val image = compose.onRoot().captureToImage()
    saveCapture("v1162-bottom-capsule-disabled.png", image)
    val settled = capsuleStats("root-tab-capsule-games", image)
    assertTrue(
      "disabled branch shows the selected capsule at static opacity",
      settled.peakOpacity > 0.95f,
    )
  }

  @Test
  fun holdingAnUnselectedTabAddsNoPressBackgroundAndStillNavigatesOnTap() {
    compose.mainClock.autoAdvance = false
    val destination = mutableStateOf("discover")
    var navigations = 0
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        Surface(Modifier.fillMaxSize()) {
          androidx.compose.foundation.layout.Column {
            Spacer(Modifier.weight(1f))
            HeyReadBottomBar(destination.value, motionEnabled = false) {
              navigations++
              destination.value = it
            }
          }
        }
      }
    }
    compose.waitForIdle()
    val games = compose.onNodeWithTag("root-tab-games")
    val beforePress = compose.onRoot().captureToImage()
    games.performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(180)
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    val held = compose.onRoot().captureToImage()
    assertTrue(
      "bottom tabs have no extra pressed rectangle or ripple",
      samePixels(beforePress, held),
    )

    games.performTouchInput { up() }
    compose.waitForIdle()
    assertEquals("tab remains actionable", 1, navigations)
    assertEquals("games", destination.value)
  }

  private fun tabBounds(vararg tags: String): List<androidx.compose.ui.geometry.Rect> =
    tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }

  private data class CapsuleStats(val area: Int, val width: Int, val peakOpacity: Float)

  private fun capsuleStats(
    tag: String,
    capture: androidx.compose.ui.graphics.ImageBitmap,
  ): CapsuleStats {
    if (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
      return CapsuleStats(0, 0, 0f)
    val image = capture.toPixelMap()
    val bounds =
      compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val iconTag = "root-tab-icon-${tag.removePrefix("root-tab-capsule-")}"
    val iconBounds =
      compose.onNodeWithTag(iconTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val left = bounds.left.toInt().coerceIn(0, image.width - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, image.width)
    val top = bounds.top.toInt().coerceIn(0, image.height - 1)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, image.height)
    val background = image[left + (right - left) / 2, (top - 3).coerceAtLeast(0)]
    val target = androidx.compose.ui.graphics.Color(0xFFF8D348)
    val red = target.red - background.red
    val green = target.green - background.green
    val blue = target.blue - background.blue
    val norm = red * red + green * green + blue * blue
    var area = 0
    var minVisibleX = right
    var maxVisibleX = left - 1
    var peakOpacity = 0f
    for (y in top until bottom) {
      for (x in left until right) {
        val sampleX = x + 0.5f
        val sampleY = y + 0.5f
        if (
          sampleX in iconBounds.left..iconBounds.right &&
            sampleY in iconBounds.top..iconBounds.bottom
        )
          continue
        val pixel = image[x, y]
        val opacity =
          ((pixel.red - background.red) * red +
            (pixel.green - background.green) * green +
            (pixel.blue - background.blue) * blue) / norm
        val residual =
          kotlin.math.abs((pixel.red - background.red) - opacity * red) +
            kotlin.math.abs((pixel.green - background.green) - opacity * green) +
            kotlin.math.abs((pixel.blue - background.blue) - opacity * blue)
        if (opacity in 0.1f..1.05f && residual < 0.04f) {
          area++
          minVisibleX = minOf(minVisibleX, x)
          maxVisibleX = maxOf(maxVisibleX, x)
          peakOpacity = maxOf(peakOpacity, opacity.coerceAtMost(1f))
        }
      }
    }
    return CapsuleStats(area, (maxVisibleX - minVisibleX + 1).coerceAtLeast(0), peakOpacity)
  }

  private fun saveCapture(name: String, image: androidx.compose.ui.graphics.ImageBitmap) {
    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
      .outputStream()
      .use {
        check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
      }
  }

  private fun samePixels(
    first: androidx.compose.ui.graphics.ImageBitmap,
    second: androidx.compose.ui.graphics.ImageBitmap,
  ): Boolean {
    val a = first.toPixelMap()
    val b = second.toPixelMap()
    if (a.width != b.width || a.height != b.height) return false
    for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) return false
    return true
  }
}
