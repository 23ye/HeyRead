package cn.heyread.app.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Adapted from MotionShelf Kit v0.2 (MIT); license is bundled at
// assets/licenses/MOTIONSHELF-KIT-MIT.txt.
object MotionTokens {
  const val PAGE = 520
  const val SHARED = 520
  const val ROOT = 520
  const val TAB_FADE = 180
  const val CHILD_FADE = 220
  const val ITEM = 360
  const val STAGGER = 55
  const val PRESS_DOWN = 130
  const val PRESS_UP = 130
  const val DIALOG_IN = 360
  const val DIALOG_OUT = 130
  val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
  val STANDARD = FastOutSlowInEasing

  fun duration(enabled: Boolean, systemScale: Float, normal: Int): Int =
      if (enabled && systemScale > 0f) normal else 0

  fun staggerDelay(enabled: Boolean, systemScale: Float, index: Int): Long =
      if (duration(enabled, systemScale, 1) == 0 || index !in 0..5) 0L else index * STAGGER.toLong()
}

val LocalMotionEnabled = compositionLocalOf { true }
private val ZeroPageLayerTranslation = androidx.compose.runtime.mutableStateOf(0f)
val LocalPageLayerTranslationX =
    compositionLocalOf<androidx.compose.runtime.State<Float>> { ZeroPageLayerTranslation }

@Composable
fun rememberFirstVisibleBatch(
    identity: String,
    listState: LazyListState,
    candidateKeys: Set<String>,
    enabled: Boolean = true,
): FirstVisibleBatch {
  if (!enabled) return FirstVisibleBatch(emptyList(), true, null, emptySet())
  var encodedBatch by rememberSaveable(identity) { mutableStateOf<String?>(null) }
  LaunchedEffect(identity, listState, candidateKeys) {
    if (encodedBatch == null && candidateKeys.isNotEmpty()) {
      val visible =
          snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key.toString() } }
              .map { keys -> keys.filter { it in candidateKeys }.distinct() }
              .first { it.isNotEmpty() }
      if (encodedBatch == null) encodedBatch = visible.take(6).joinToString("\n")
    }
  }
  return FirstVisibleBatch(
      encodedBatch?.split('\n').orEmpty().filter(String::isNotEmpty),
      encodedBatch != null,
      listState,
      candidateKeys,
  )
}

data class FirstVisibleBatch(
    val keys: List<String>,
    val ready: Boolean,
    val listState: LazyListState? = null,
    val candidates: Set<String> = emptySet(),
)

@Composable
fun rememberMotionEnabled(preference: Boolean): Boolean {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  var animatorScale by remember { mutableStateOf(readAnimatorScale(context)) }
  DisposableEffect(context, owner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME)
          animatorScale = readAnimatorScale(context)
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  return preference && animatorScale > 0f
}

internal fun readAnimatorScale(context: Context): Float =
    runCatching {
          Settings.Global.getFloat(
              context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        .getOrDefault(1f)

@Composable
fun Modifier.pressScaleClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
  val motionEnabled = LocalMotionEnabled.current && enabled
  if (!motionEnabled) {
    return this.clickable(enabled = enabled, onClick = onClick)
  }
  val source = remember { MutableInteractionSource() }
  val pressed by source.collectIsPressedAsState()
  val transition = updateTransition(pressed, label = "press-scale")
  val scale =
      transition.animateFloat(
          transitionSpec = {
            tween(
                if (targetState) MotionTokens.PRESS_DOWN else MotionTokens.PRESS_UP,
                easing = MotionTokens.STANDARD,
            )
          },
          label = "press-scale",
      ) { isPressed ->
        if (isPressed) 0.975f else 1f
      }
  MotionFrameRateHint(transition.isRunning)
  return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
      }
      .clickable(
          enabled = enabled,
          interactionSource = source,
          indication = LocalIndication.current,
          onClick = onClick,
      )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PressScaleColumn(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
  val motionEnabled = LocalMotionEnabled.current && enabled
  val interactionSource = if (motionEnabled) remember { MutableInteractionSource() } else null
  val pressed = if (motionEnabled) interactionSource!!.collectIsPressedAsState().value else false
  val transition =
      if (motionEnabled) updateTransition(pressed, label = "press-scale-column") else null
  val scaleState =
      transition?.animateFloat(
          transitionSpec = {
            tween(
                if (targetState) MotionTokens.PRESS_DOWN else MotionTokens.PRESS_UP,
                easing = MotionTokens.STANDARD,
            )
          },
          label = "press-scale-column",
      ) { isPressed ->
        if (isPressed) 0.975f else 1f
      } ?: remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
  transition?.let { MotionFrameRateHint(it.isRunning) }
  var coordinates by remember { androidx.compose.runtime.mutableStateOf<LayoutCoordinates?>(null) }
  val parentTransforms = LocalCoverCoordinateTransforms.current
  val transformStack =
      remember(parentTransforms, scaleState, motionEnabled) {
        if (motionEnabled) {
          derivedStateOf {
            val current = coordinates
            val pivot =
                current
                    ?.takeIf { it.isAttached }
                    ?.let { it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)) }
                    ?: Offset.Zero
            parentTransforms.value +
                CoverCoordinateTransform(
                    scaleX = scaleState.value,
                    scaleY = scaleState.value,
                    pivotInRoot = pivot,
                )
          }
        } else parentTransforms
      }
  val clickModifier =
      if (motionEnabled) {
        modifier
            .onGloballyPositioned { coordinates = it }
            .graphicsLayer {
              scaleX = scaleState.value
              scaleY = scaleState.value
            }
      } else modifier
  val inputModifier =
      if (onLongClick == null) {
        clickModifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
      } else {
        clickModifier.combinedClickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClickLabel = "屏蔽内容",
            onLongClick = onLongClick,
        )
      }
  CompositionLocalProvider(LocalCoverCoordinateTransforms provides transformStack) {
    Column(inputModifier, content = content)
  }
}

fun Modifier.pageInputGate(isCurrent: Boolean): Modifier =
    if (isCurrent) this
    else
        this.semantics { hideFromAccessibility() }
            .pointerInput(Unit) {
              awaitEachGesture {
                do {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
              }
            }

@Composable
fun StaggeredCard(
    key: String,
    batch: FirstVisibleBatch,
    animatedKeys: MutableList<String>,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
  val slot = batch.keys.indexOf(key).takeIf { it in 0..5 } ?: -1
  val shouldAnimate = enabled && slot >= 0 && key !in animatedKeys
  val frameRateController = rememberMotionFrameRateHintController(shouldAnimate)
  val waitingForBatch = enabled && !batch.ready && key in batch.candidates && key !in animatedKeys
  val density = LocalDensity.current
  val hiddenOffsetX = with(density) { 16.dp.toPx() }
  val hiddenOffsetY = with(density) { 12.dp.toPx() }
  var progressAnimator by
      remember(key) {
        mutableStateOf(if (shouldAnimate || waitingForBatch) Animatable(0f) else null)
      }
  val progress = progressAnimator
  if (enabled) {
    LaunchedEffect(key, slot, batch.ready) {
      if (!batch.ready || progress == null) return@LaunchedEffect
      if (slot < 0) {
        progress.snapTo(1f)
        progressAnimator = null
        if (key !in animatedKeys) animatedKeys.add(key)
        return@LaunchedEffect
      }
      kotlinx.coroutines.delay(slot * MotionTokens.STAGGER.toLong())
      val frameRateLease = frameRateController?.acquire()
      try {
        progress.animateTo(1f, tween(MotionTokens.ITEM, easing = MotionTokens.EMPHASIZED))
      } finally {
        frameRateLease?.close()
      }
      progressAnimator = null
      if (key !in animatedKeys) animatedKeys.add(key)
    }
    LaunchedEffect(batch.listState) {
      val state = batch.listState ?: return@LaunchedEffect
      snapshotFlow { state.isScrollInProgress }
          .distinctUntilChanged()
          .collect { scrolling ->
            if (scrolling && ((progress?.value ?: 1f) < 1f)) {
              progress?.stop()
              progress?.snapTo(1f)
              progressAnimator = null
              if (key !in animatedKeys) animatedKeys.add(key)
            }
          }
    }
  } else {
    SideEffect {
      progressAnimator = null
      if (key !in animatedKeys) animatedKeys.add(key)
    }
  }
  val parentTransforms = LocalCoverCoordinateTransforms.current
  val transformStack =
      remember(parentTransforms, progressAnimator, enabled, hiddenOffsetX, hiddenOffsetY) {
        if (enabled && progressAnimator != null) {
          derivedStateOf {
            val currentProgress = progress?.value ?: 1f
            parentTransforms.value +
                CoverCoordinateTransform(
                    translationX = -hiddenOffsetX * (1f - currentProgress),
                    translationY = -hiddenOffsetY * (1f - currentProgress),
                )
          }
        } else parentTransforms
      }
  androidx.compose.foundation.layout.Box(
      Modifier.then(
          if (enabled && (progress != null || waitingForBatch))
              Modifier.graphicsLayer {
                val currentProgress = progress?.value ?: 0f
                alpha = currentProgress
                translationX = -hiddenOffsetX * (1f - currentProgress)
                translationY = -hiddenOffsetY * (1f - currentProgress)
              }
          else Modifier)) {
        CompositionLocalProvider(LocalCoverCoordinateTransforms provides transformStack) {
          content()
        }
      }
}
