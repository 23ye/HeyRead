package cn.heyread.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.screens.DetailInitialSkeleton
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailInitialSkeletonTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun productionDetailSkeletonPulsesOnlyWhenMotionIsEnabledAndPageIsCurrent() {
    compose.mainClock.autoAdvance = false
    val motion = mutableStateOf(true)
    val currentPage = mutableStateOf(true)
    compose.setContent {
      HeyReadTheme("light", motionEnabled = motion.value) {
        Surface(Modifier.fillMaxSize()) { DetailInitialSkeleton(motion.value, currentPage.value) }
      }
    }
    compose.waitForIdle()
    val initial = compose.onNodeWithTag("detail-initial-skeleton").captureToImage()
    compose.mainClock.advanceTimeBy(425)
    compose.mainClock.advanceTimeByFrame()
    val pulsed = compose.onNodeWithTag("detail-initial-skeleton").captureToImage()
    assertTrue(
        "the real skeleton layer changes opacity during its 850ms pulse",
        changedPixels(initial, pulsed) > 100)

    compose.runOnIdle { currentPage.value = false }
    // The page flag takes effect on the next rendered composition frame.
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    val offPageStatic = compose.onNodeWithTag("detail-initial-skeleton").captureToImage()
    compose.mainClock.advanceTimeBy(1_000)
    compose.mainClock.advanceTimeByFrame()
    val offPageStillStatic = compose.onNodeWithTag("detail-initial-skeleton").captureToImage()
    assertTrue(
        "non-current page skeleton has no pulse",
        changedPixels(offPageStatic, offPageStillStatic) == 0)

    compose.runOnIdle {
      currentPage.value = true
      motion.value = false
    }
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    val disabledStatic = compose.onNodeWithTag("detail-initial-skeleton").captureToImage()
    compose.mainClock.advanceTimeBy(1_000)
    compose.mainClock.advanceTimeByFrame()
    val disabledStillStatic = compose.onNodeWithTag("detail-initial-skeleton").captureToImage()
    assertTrue(
        "motion-disabled skeleton is static",
        changedPixels(disabledStatic, disabledStillStatic) == 0)
  }

  private fun changedPixels(first: ImageBitmap, second: ImageBitmap): Int {
    val a = first.toPixelMap()
    val b = second.toPixelMap()
    var changed = 0
    for (y in 0 until minOf(a.height, b.height)) {
      for (x in 0 until minOf(a.width, b.width)) {
        val left = a[x, y]
        val right = b[x, y]
        if (kotlin.math.abs(left.red - right.red) > 0.015f ||
            kotlin.math.abs(left.green - right.green) > 0.015f ||
            kotlin.math.abs(left.blue - right.blue) > 0.015f)
            changed++
      }
    }
    return changed
  }
}
