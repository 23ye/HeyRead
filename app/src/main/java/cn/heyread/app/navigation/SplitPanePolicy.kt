package cn.heyread.app.navigation

internal const val SPLIT_PANE_BREAKPOINT_DP = 840f
internal const val SPLIT_PANE_DIVIDER_DP = 12f
internal const val SPLIT_PANE_MIN_FRACTION = 0.25f
internal const val SPLIT_PANE_DEFAULT_LEFT_FRACTION = 1f / 3f

internal data class SplitPaneWidths(val left: Float, val divider: Float, val right: Float) {
  val total: Float
    get() = left + divider + right
}

internal fun splitPaneWidths(
    availableWidth: Float,
    dividerWidth: Float = SPLIT_PANE_DIVIDER_DP,
    leftFraction: Float = SPLIT_PANE_DEFAULT_LEFT_FRACTION,
): SplitPaneWidths {
  val safeWidth = availableWidth.coerceAtLeast(0f)
  val safeDivider = dividerWidth.coerceIn(0f, safeWidth)
  val panesWidth = (safeWidth - safeDivider).coerceAtLeast(0f)
  if (panesWidth == 0f) return SplitPaneWidths(0f, safeDivider, 0f)
  val minFraction = (safeWidth * SPLIT_PANE_MIN_FRACTION / panesWidth).coerceAtMost(0.5f)
  val fraction =
      leftFraction.takeIf(Float::isFinite)?.coerceIn(minFraction, 1f - minFraction)
          ?: SPLIT_PANE_DEFAULT_LEFT_FRACTION.coerceIn(minFraction, 1f - minFraction)
  val left = panesWidth * fraction
  return SplitPaneWidths(left, safeDivider, panesWidth - left)
}

internal fun clampSplitFraction(
    availableWidth: Float,
    dividerWidth: Float,
    proposed: Float
): Float {
  val panesWidth = (availableWidth - dividerWidth).coerceAtLeast(0f)
  if (panesWidth == 0f) return SPLIT_PANE_DEFAULT_LEFT_FRACTION
  val minFraction = (availableWidth * SPLIT_PANE_MIN_FRACTION / panesWidth).coerceAtMost(0.5f)
  val finite = proposed.takeIf(Float::isFinite) ?: SPLIT_PANE_DEFAULT_LEFT_FRACTION
  return finite.coerceIn(minFraction, 1f - minFraction)
}

internal fun adjustSplitFractionForDrag(
    availableWidth: Float,
    dividerWidth: Float,
    currentFraction: Float,
    deltaDp: Float,
): Float {
  val panesWidth = (availableWidth - dividerWidth).coerceAtLeast(0f)
  if (panesWidth == 0f) return SPLIT_PANE_DEFAULT_LEFT_FRACTION
  return clampSplitFraction(
      availableWidth,
      dividerWidth,
      currentFraction + deltaDp / panesWidth,
  )
}
