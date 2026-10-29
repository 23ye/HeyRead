package cn.heyread.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.navigation.NavBackStackEntry
import cn.heyread.app.ui.motion.CoverCoordinateTransform
import cn.heyread.app.ui.motion.LocalCoverCoordinateTransforms
import cn.heyread.app.ui.motion.LocalPageLayerTranslationX
import cn.heyread.app.ui.motion.MotionFrameRateHint
import cn.heyread.app.ui.motion.MotionTokens

private val rootOrder = mapOf("discover" to 0, "games" to 1, "dynamic" to 2, "mine" to 3)

internal data class PageMotionPolicy(
  val rootChange: Boolean,
  val moveMillis: Int,
  val fadeMillis: Int,
  val direction: Int,
  val enterFraction: Int,
  val exitFraction: Int,
)

internal data class PageTransitionRecord(
  val fromRoute: String?,
  val toRoute: String?,
  val isPop: Boolean,
)

internal val LocalPageTransitionRecords =
  compositionLocalOf<Map<String, PageTransitionRecord>> { emptyMap() }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.recordTransition(
  records: MutableMap<String, PageTransitionRecord>?,
  isPop: Boolean,
) {
  if (records == null) return
  val record =
    PageTransitionRecord(initialState.destination.route, targetState.destination.route, isPop)
  records[initialState.id] = record
  records[targetState.id] = record
  while (records.size > 32) {
    val stale = records.keys.firstOrNull { it != initialState.id && it != targetState.id } ?: break
    records.remove(stale)
  }
}

internal fun pageMotionPolicy(
  fromRoute: String?,
  toRoute: String?,
  isRtl: Boolean,
  motionEnabled: Boolean,
  isPop: Boolean,
): PageMotionPolicy {
  val fromTab = rootOrder[fromRoute]
  val toTab = rootOrder[toRoute]
  val rootChange = fromTab != null && toTab != null
  val orderDirection = if (toTab == null || fromTab == null || toTab >= fromTab) 1 else -1
  val direction = orderDirection * if (isRtl) -1 else 1
  val moveMillis =
    if (motionEnabled) {
      if (rootChange) MotionTokens.ROOT else MotionTokens.PAGE
    } else 0
  val fadeMillis =
    if (motionEnabled) {
      if (rootChange) MotionTokens.TAB_FADE else MotionTokens.CHILD_FADE
    } else 0
  val enterFraction = if (isPop && !rootChange) 4 else 1
  val exitFraction =
    when {
      rootChange -> 1
      isPop -> 1
      else -> 4
    }
  return PageMotionPolicy(
    rootChange,
    moveMillis,
    fadeMillis,
    direction,
    enterFraction,
    exitFraction,
  )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.policy(
  motionEnabled: Boolean,
  isRtl: Boolean,
  isPop: Boolean,
) =
  pageMotionPolicy(
    initialState.destination.route,
    targetState.destination.route,
    isRtl,
    motionEnabled,
    isPop,
  )

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(
  motionEnabled: Boolean,
  isRtl: Boolean = false,
  records: MutableMap<String, PageTransitionRecord>? = null,
): EnterTransition {
  if (!motionEnabled) return EnterTransition.None
  recordTransition(records, isPop = false)
  val policy = policy(motionEnabled, isRtl, isPop = false)
  return fadeIn(tween(policy.fadeMillis, easing = MotionTokens.EMPHASIZED))
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(
  motionEnabled: Boolean,
  isRtl: Boolean = false,
  records: MutableMap<String, PageTransitionRecord>? = null,
): ExitTransition {
  if (!motionEnabled) return ExitTransition.None
  recordTransition(records, isPop = false)
  val policy = policy(motionEnabled, isRtl, isPop = false)
  return fadeOut(tween(policy.fadeMillis, easing = MotionTokens.EMPHASIZED))
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopEnter(
  motionEnabled: Boolean,
  isRtl: Boolean = false,
  records: MutableMap<String, PageTransitionRecord>? = null,
): EnterTransition {
  if (!motionEnabled) return EnterTransition.None
  recordTransition(records, isPop = true)
  val policy = policy(motionEnabled, isRtl, isPop = true)
  return fadeIn(tween(policy.fadeMillis, easing = MotionTokens.EMPHASIZED))
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopExit(
  motionEnabled: Boolean,
  isRtl: Boolean = false,
  records: MutableMap<String, PageTransitionRecord>? = null,
): ExitTransition {
  if (!motionEnabled) return ExitTransition.None
  recordTransition(records, isPop = true)
  val policy = policy(motionEnabled, isRtl, isPop = true)
  return fadeOut(tween(policy.fadeMillis, easing = MotionTokens.EMPHASIZED))
}

@androidx.compose.runtime.Composable
internal fun PageMotionContainer(
  modifier: Modifier = Modifier,
  visibility: AnimatedVisibilityScope,
  route: String?,
  currentRoute: String?,
  previousRoute: String?,
  isCurrentPage: Boolean,
  motionEnabled: Boolean,
  isRtl: Boolean,
  transitionRecord: PageTransitionRecord?,
  content: @Composable () -> Unit,
) {
  val zeroTranslation = remember { mutableFloatStateOf(0f) }
  val parentTransforms = LocalCoverCoordinateTransforms.current
  val settled =
    visibility.transition.currentState == EnterExitState.Visible &&
      visibility.transition.targetState == EnterExitState.Visible
  val animate = motionEnabled && !settled
  MotionFrameRateHint(animate && visibility.transition.isRunning)
  val layerModifier: Modifier
  val translationState: androidx.compose.runtime.State<Float>
  if (animate) {
    val entering = visibility.transition.targetState == EnterExitState.Visible
    val record = transitionRecord ?: PageTransitionRecord(previousRoute, currentRoute, false)
    val offsets = pageLayerPolicy(record, entering, isRtl)
    val x = if (entering) offsets.enterFraction else offsets.exitFraction
    val fraction =
      visibility.transition.animateFloat(
        transitionSpec = { tween(MotionTokens.PAGE, easing = MotionTokens.EMPHASIZED) },
        label = "page-layer-progress",
      ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
      }
    var pageWidthPx by remember { mutableFloatStateOf(0f) }
    translationState =
      remember(fraction, x) { derivedStateOf { pageWidthPx * x * (1f - fraction.value) } }
    layerModifier =
      Modifier.onSizeChanged { pageWidthPx = it.width.toFloat() }
        .graphicsLayer { translationX = size.width * x * (1f - fraction.value) }
  } else {
    translationState = zeroTranslation
    layerModifier = Modifier
  }
  val childTransforms =
    remember(parentTransforms, translationState, animate) {
      if (animate) {
        derivedStateOf {
          parentTransforms.value + CoverCoordinateTransform(translationX = translationState.value)
        }
      } else parentTransforms
    }
  CompositionLocalProvider(
    LocalPageLayerTranslationX provides translationState,
    LocalCoverCoordinateTransforms provides childTransforms,
  ) {
    Box(modifier.fillMaxSize().then(layerModifier)) { content() }
  }
}

private data class LayerOffsets(val enterFraction: Float, val exitFraction: Float)

private fun pageLayerPolicy(
  record: PageTransitionRecord,
  entering: Boolean,
  isRtl: Boolean,
): LayerOffsets {
  val fromRoot = rootOrder[record.fromRoute]
  val toRoot = rootOrder[record.toRoute]
  val mirror = if (isRtl) -1f else 1f
  if (fromRoot != null && toRoot != null && fromRoot != toRoot) {
    val direction = if (toRoot > fromRoot) 1f else -1f
    return LayerOffsets(direction * mirror, -direction * mirror)
  }
  val incoming = if (record.isPop) -0.25f * mirror else 1f * mirror
  val outgoing = if (record.isPop) 1f * mirror else -0.25f * mirror
  return if (entering) LayerOffsets(incoming, outgoing) else LayerOffsets(incoming, outgoing)
}
