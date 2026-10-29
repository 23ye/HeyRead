package cn.heyread.app.ui.motion

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PressFeedbackTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun pressScalesWhileHeldAndDispatchesOnlyOneClickOnRelease() {
    compose.mainClock.autoAdvance = false
    var clicks = 0
    var renderedView: View? = null
    compose.setContent {
      val localView = LocalView.current
      SideEffect { renderedView = localView }
      HeyReadTheme("light", motionEnabled = true) {
        Box(Modifier.size(180.dp).pressScaleClick { clicks++ }.testTag("press-target"))
      }
    }
    compose.mainClock.advanceTimeByFrame()
    val platformView = requireNotNull(renderedView)
    var originalFrameRate = 0f
    if (Build.VERSION.SDK_INT >= 35) {
      compose.runOnIdle { originalFrameRate = platformView.requestedFrameRate }
    }
    val target = compose.onNodeWithTag("press-target")
    val widthBefore = target.fetchSemanticsNode().boundsInRoot.width

    target.performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(70)
    if (Build.VERSION.SDK_INT >= 35) {
      assertEquals(
          "the production press animation requests HIGH while scaling",
          View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
          platformView.requestedFrameRate,
          0f,
      )
    }
    compose.mainClock.advanceTimeBy(100)
    if (Build.VERSION.SDK_INT >= 35) {
      assertEquals(
          "a completed press animation releases HIGH while the pointer remains down",
          originalFrameRate,
          platformView.requestedFrameRate,
          0f,
      )
    }
    val widthPressed = target.fetchSemanticsNode().boundsInRoot.width
    assertTrue("pressed target should visibly scale down", widthPressed < widthBefore)
    target.performTouchInput { up() }
    compose.mainClock.advanceTimeBy(220)
    assertEquals(widthBefore, target.fetchSemanticsNode().boundsInRoot.width, 1f)
    assertEquals(1, clicks)
  }
}
