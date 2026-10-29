package cn.heyread.app.navigation

import cn.heyread.app.ui.motion.MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMotionPolicyTest {
  @Test
  fun rootTabsFollowIndexWithFullWidthMoveAndIndependentFade() {
    val forward = pageMotionPolicy("discover", "games", false, true, isPop = false)
    assertTrue(forward.rootChange)
    assertEquals(520, forward.moveMillis)
    assertEquals(180, forward.fadeMillis)
    assertEquals(1, forward.direction)
    assertEquals(1, forward.enterFraction)
    assertEquals(1, forward.exitFraction)

    val backward = pageMotionPolicy("dynamic", "games", false, true, isPop = false)
    assertEquals(-1, backward.direction)
    assertEquals(-1, pageMotionPolicy("discover", "games", true, true, false).direction)
  }

  @Test
  fun detailAndPopUseQuarterParallaxAndReducedMotionZerosBothClocks() {
    val push = pageMotionPolicy("discover", "detail/HEYBOX/1", false, true, isPop = false)
    assertFalse(push.rootChange)
    assertEquals(MotionTokens.PAGE, push.moveMillis)
    assertEquals(220, push.fadeMillis)
    assertEquals(1, push.enterFraction)
    assertEquals(4, push.exitFraction)

    val pop = pageMotionPolicy("detail/HEYBOX/1", "discover", false, true, isPop = true)
    assertEquals(4, pop.enterFraction)
    assertEquals(1, pop.exitFraction)

    val disabled = pageMotionPolicy("discover", "games", false, false, isPop = false)
    assertEquals(0, disabled.moveMillis)
    assertEquals(0, disabled.fadeMillis)
  }
}
