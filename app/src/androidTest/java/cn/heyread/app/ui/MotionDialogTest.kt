package cn.heyread.app.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.components.MotionAlertDialog
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dialogRemainsMountedAndActionsDisableDuringAnimatedExit() {
        compose.mainClock.autoAdvance = false
        var confirmations = 0
        compose.setContent {
            HeyReadTheme("light", motionEnabled = true) {
                var visible by remember { mutableStateOf(false) }
                Button(onClick = { visible = true }) { Text("打开确认") }
                MotionAlertDialog(
                    visible = visible,
                    onDismiss = { visible = false },
                    title = "确认标题",
                    message = "确认说明",
                    confirmLabel = "确认",
                    onConfirm = { confirmations++ },
                    dismissLabel = "取消",
                )
            }
        }
        compose.onNodeWithText("打开确认").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("motion-alert-dialog").assertExists()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(24)

        compose.onNodeWithTag("motion-alert-dialog").assertExists()
        compose.onNodeWithTag("motion-alert-confirm").assertIsNotEnabled()
        assertEquals(0, confirmations)

        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("motion-alert-dialog").assertDoesNotExist()
    }
}
