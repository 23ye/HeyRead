package cn.heyread.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPanePolicyTest {
  @Test
  fun defaultRatioAndMinimumWidthsRespectAvailableWidth() {
    val widths = splitPaneWidths(1200f)

    assertEquals(1f / 3f, widths.left / (widths.left + widths.right), 0.001f)
    assertEquals(1200f, widths.total, 0.001f)
    assertTrue(widths.left >= 300f)
    assertTrue(widths.right >= 300f)
  }

  @Test
  fun resizingIsClampedToQuarterOfWholeWindowAndWidthChangesReclamp() {
    val tooFarLeft = adjustSplitFractionForDrag(1200f, 12f, 1f / 3f, -1000f)
    val narrowAfterResize = splitPaneWidths(840f, 12f, tooFarLeft)

    assertTrue(narrowAfterResize.left >= 210f)
    assertTrue(narrowAfterResize.right >= 210f)
    assertEquals(840f, narrowAfterResize.total, 0.001f)
  }

  @Test
  fun invalidFractionFallsBackToDefaultThenClamps() {
    val widths = splitPaneWidths(840f, leftFraction = Float.NaN)

    assertEquals(
        SPLIT_PANE_DEFAULT_LEFT_FRACTION, widths.left / (widths.left + widths.right), 0.001f)
    assertEquals(840f, widths.total, 0.001f)
  }
}
