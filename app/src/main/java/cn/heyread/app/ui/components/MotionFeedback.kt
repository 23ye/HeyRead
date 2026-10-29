package cn.heyread.app.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import cn.heyread.app.ui.motion.LocalMotionEnabled
import cn.heyread.app.ui.motion.MotionFrameRateHint
import cn.heyread.app.ui.motion.MotionTokens

@Composable
fun MotionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
  val motionEnabled = LocalMotionEnabled.current && enabled
  if (!motionEnabled) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
    return
  }
  val source = remember { MutableInteractionSource() }
  val pressed by source.collectIsPressedAsState()
  val transition = updateTransition(pressed, label = "button-press-scale")
  val scale =
      transition.animateFloat(
          transitionSpec = {
            tween(
                if (targetState) MotionTokens.PRESS_DOWN else MotionTokens.PRESS_UP,
                easing = MotionTokens.STANDARD,
            )
          },
          label = "button-press-scale",
      ) { isPressed ->
        if (isPressed) 0.975f else 1f
      }
  MotionFrameRateHint(transition.isRunning)
  Button(
      onClick = onClick,
      enabled = enabled,
      interactionSource = source,
      modifier =
          modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
          },
      content = content,
  )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MotionAlertDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
    onConfirmAttempt: (() -> Boolean)? = null,
    confirmTestTag: String = "motion-alert-confirm",
    dismissTestTag: String? = null,
    confirmEnabled: Boolean = true,
) {
  val motionEnabled = LocalMotionEnabled.current
  var handled by remember { mutableStateOf(false) }
  LaunchedEffect(visible) { if (visible) handled = false }
  if (!motionEnabled) {
    if (visible) {
      AlertDialog(
          modifier = modifier.testTag("motion-alert-dialog"),
          onDismissRequest = {
            if (!handled) {
              handled = true
              onDismiss()
            }
          },
          title = { Text(title) },
          text = { content?.invoke() ?: Text(message) },
          confirmButton = {
            TextButton(
                enabled = !handled && confirmEnabled,
                modifier = Modifier.testTag(confirmTestTag),
                onClick = {
                  if (!handled) {
                    if (onConfirmAttempt == null) {
                      handled = true
                      onDismiss()
                      onConfirm()
                    } else if (onConfirmAttempt()) {
                      handled = true
                      onDismiss()
                    }
                  }
                },
            ) {
              Text(confirmLabel)
            }
          },
          dismissButton = {
            TextButton(
                modifier = if (dismissTestTag == null) Modifier else Modifier.testTag(dismissTestTag),
                onClick = {
                  if (!handled) {
                    handled = true
                    onDismiss()
                  }
                },
                enabled = !handled) {
                  Text(dismissLabel)
                }
          },
      )
    }
    return
  }
  val transition = updateTransition(visible, label = "confirmation-dialog")
  val scale =
      transition.animateFloat(
          transitionSpec = {
            tween(
                if (motionEnabled)
                    if (targetState) MotionTokens.DIALOG_IN else MotionTokens.DIALOG_OUT
                else 0,
                easing = if (targetState) MotionTokens.EMPHASIZED else MotionTokens.STANDARD,
            )
          },
          label = "confirmation-dialog-scale",
      ) { shown ->
        if (shown) 1f else 0.975f
      }
  val alpha =
      transition.animateFloat(
          transitionSpec = {
            tween(
                if (motionEnabled)
                    if (targetState) MotionTokens.DIALOG_IN else MotionTokens.DIALOG_OUT
                else 0)
          },
          label = "confirmation-dialog-alpha",
      ) { shown ->
        if (shown) 1f else 0f
      }
  if (transition.currentState || transition.targetState) {
    AlertDialog(
        modifier =
            modifier
                .graphicsLayer {
                  scaleX = scale.value
                  scaleY = scale.value
                  this.alpha = alpha.value
                }
                .testTag("motion-alert-dialog"),
        onDismissRequest = {
          if (visible && !handled) {
            handled = true
            onDismiss()
          }
        },
        title = {
          MotionFrameRateHint(transition.isRunning)
          Text(title)
        },
        text = { content?.invoke() ?: Text(message) },
        confirmButton = {
          TextButton(
              enabled = visible && !handled && confirmEnabled,
              modifier = Modifier.testTag(confirmTestTag),
              onClick = {
                if (visible && !handled) {
                  if (onConfirmAttempt == null) {
                    handled = true
                    onDismiss()
                    onConfirm()
                  } else if (onConfirmAttempt()) {
                    handled = true
                    onDismiss()
                  }
                }
              },
          ) {
            Text(confirmLabel)
          }
        },
        dismissButton = {
          TextButton(
              modifier = if (dismissTestTag == null) Modifier else Modifier.testTag(dismissTestTag),
              enabled = visible && !handled,
              onClick = {
                if (visible && !handled) {
                  handled = true
                  onDismiss()
                }
              },
          ) {
            Text(dismissLabel)
          }
        },
    )
  }
}
