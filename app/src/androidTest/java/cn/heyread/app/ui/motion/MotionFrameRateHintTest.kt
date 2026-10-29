package cn.heyread.app.ui.motion

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionFrameRateHintTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun api35ComposeViewRequestsHighOnlyWhileOverlappingAnimationsAreActive() {
    assumeTrue("View frame-rate hints require API 35", Build.VERSION.SDK_INT >= 35)
    val firstActive = mutableStateOf(false)
    val secondActive = mutableStateOf(false)
    var renderedView: View? = null
    compose.setContent {
      val localView = LocalView.current
      SideEffect { renderedView = localView }
      MotionFrameRateHint(firstActive.value)
      MotionFrameRateHint(secondActive.value)
    }
    compose.waitForIdle()
    val view = requireNotNull(renderedView)
    val original = view.requestedFrameRate
    try {
      compose.runOnIdle { firstActive.value = true }
      compose.waitForIdle()
      assertEquals(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, view.requestedFrameRate, 0f)

      compose.runOnIdle { secondActive.value = true }
      compose.waitForIdle()
      compose.runOnIdle { firstActive.value = false }
      compose.waitForIdle()
      assertEquals(
          "the second active animation owns the hint",
          View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
          view.requestedFrameRate,
          0f)

      compose.runOnIdle { secondActive.value = false }
      compose.waitForIdle()
      assertEquals(
          "the initial platform preference is restored", original, view.requestedFrameRate, 0f)
    } finally {
      compose.runOnIdle {
        firstActive.value = false
        secondActive.value = false
      }
      compose.waitForIdle()
      view.requestedFrameRate = original
    }
  }

  @Test
  fun dialogUsesAndRestoresItsOwnComposeViewHint() {
    assumeTrue("View frame-rate hints require API 35", Build.VERSION.SDK_INT >= 35)
    val showDialog = mutableStateOf(false)
    val animationActive = mutableStateOf(false)
    var activityView: View? = null
    var dialogView: View? = null
    compose.setContent {
      val localView = LocalView.current
      SideEffect { activityView = localView }
      if (showDialog.value) {
        Dialog(onDismissRequest = { showDialog.value = false }) {
          val dialogLocalView = LocalView.current
          SideEffect { dialogView = dialogLocalView }
          MotionFrameRateHint(animationActive.value)
          Box(Modifier.size(1.dp).testTag("frame-rate-dialog"))
        }
      }
    }
    compose.waitForIdle()
    val activity = requireNotNull(activityView)
    val activityPreference = activity.requestedFrameRate
    compose.runOnIdle { showDialog.value = true }
    compose.onNodeWithTag("frame-rate-dialog").assertExists()
    compose.waitForIdle()
    val dialog = requireNotNull(dialogView)
    val dialogPreference = dialog.requestedFrameRate
    assertEquals(activityPreference, activity.requestedFrameRate, 0f)
    compose.runOnIdle { animationActive.value = true }
    compose.waitForIdle()
    assertEquals(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, dialog.requestedFrameRate, 0f)

    compose.runOnIdle { animationActive.value = false }
    compose.waitForIdle()
    assertEquals(
        "dialog restores its own original preference",
        dialogPreference,
        dialog.requestedFrameRate,
        0f)
    assertEquals(
        "dialog request never changes the activity view",
        activityPreference,
        activity.requestedFrameRate,
        0f)
  }

  @Test
  fun attachedComposeViewRestoresAndReacquiresHintAcrossLifecyclePause() {
    assumeTrue("View frame-rate hints require API 35", Build.VERSION.SDK_INT >= 35)
    var renderedView: View? = null
    compose.setContent {
      val localView = LocalView.current
      SideEffect { renderedView = localView }
      Box(Modifier.size(1.dp))
    }
    compose.waitForIdle()

    compose.runOnIdle {
      val view = requireNotNull(renderedView)
      val original = view.requestedFrameRate
      val owner = TestLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
      val controller = MotionFrameRateHintController(view, owner, window = null)
      val registration = controller.retain(view)
      val lease = controller.acquire()
      try {
        assertEquals(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, view.requestedFrameRate, 0f)

        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(
            "pausing the host restores the previous view hint",
            original,
            view.requestedFrameRate,
            0f)

        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(
            "resuming reapplies the still-active animation hint",
            View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
            view.requestedFrameRate,
            0f)
      } finally {
        lease.close()
        registration.close()
      }
      assertEquals(
          "ending the lease restores the previous view hint", original, view.requestedFrameRate, 0f)
    }
  }

  private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
      get() = registry
  }
}
