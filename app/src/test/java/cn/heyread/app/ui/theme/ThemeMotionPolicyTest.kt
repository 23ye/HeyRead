package cn.heyread.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cn.heyread.app.ui.motion.MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMotionPolicyTest {
  @Test
  fun onlyKnownAccentChoicesAreAccepted() {
    assertTrue(isValidAccent("classic"))
    assertTrue(isValidAccent("wallpaper"))
    assertFalse(isValidAccent("orange"))
  }

  @Test
  fun fixedSchemeTextRolesMeetContrastTarget() {
    val schemes =
      listOf(
        classicLight,
        classicDark,
        blueLight,
        blueDark,
        greenLight,
        greenDark,
        purpleLight,
        purpleDark,
      )
    schemes.forEach { scheme ->
      listOf(
          scheme.onPrimary to scheme.primary,
          scheme.onPrimaryContainer to scheme.primaryContainer,
          scheme.onSecondary to scheme.secondary,
          scheme.onSecondaryContainer to scheme.secondaryContainer,
          scheme.onTertiary to scheme.tertiary,
          scheme.onTertiaryContainer to scheme.tertiaryContainer,
          scheme.onError to scheme.error,
          scheme.onErrorContainer to scheme.errorContainer,
          scheme.onBackground to scheme.background,
          scheme.onSurface to scheme.surface,
          scheme.onSurfaceVariant to scheme.surfaceVariant,
          scheme.inverseOnSurface to scheme.inverseSurface,
        )
        .forEach { (foreground, background) ->
          assertTrue(
            "${scheme.primary} contrast=${contrast(foreground, background)}",
            contrast(foreground, background) >= 4.5,
          )
        }
    }
  }

  @Test
  fun motionPolicyStopsExplicitDelayAndAnimationWhenDisabled() {
    assertEquals(520, MotionTokens.duration(true, 1f, MotionTokens.PAGE))
    assertEquals(0, MotionTokens.duration(false, 1f, MotionTokens.PAGE))
    assertEquals(0, MotionTokens.duration(true, 0f, MotionTokens.PAGE))
    assertEquals(275L, MotionTokens.staggerDelay(true, 1f, 5))
    assertEquals(0L, MotionTokens.staggerDelay(false, 1f, 5))
    assertEquals(0L, MotionTokens.staggerDelay(true, 1f, 6))
  }
}

private fun contrast(foreground: Color, background: Color): Double {
  val (lighter, darker) =
    listOf(foreground.luminance().toDouble(), background.luminance().toDouble()).sortedDescending()
  return (lighter + 0.05) / (darker + 0.05)
}
