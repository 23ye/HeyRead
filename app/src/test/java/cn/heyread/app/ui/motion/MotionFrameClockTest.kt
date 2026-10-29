package cn.heyread.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MotionFrameClockTest {
  @Test
  fun productionSharedTweenUsesElapsedTimeAcrossRefreshModesAndDrops() = runTest {
    val sixty = sampleAt(regularFrames(intervalNanos = 16_666_667L))
    val ninety = sampleAt(regularFrames(intervalNanos = 11_111_111L))
    val oneTwenty = sampleAt(regularFrames(intervalNanos = 8_333_333L))
    val oneFortyFour = sampleAt(regularFrames(intervalNanos = 6_944_444L))
    val sixtyToOneTwenty =
        sampleAt(variableFrames(firstIntervalNanos = 16_666_667L, switchAt = 100_000_000L))
    val oneTwentyToSixty =
        sampleAt(variableFrames(firstIntervalNanos = 8_333_333L, switchAt = 100_000_000L))
    val droppedFrame =
        sampleAt(
            listOf(
                0L,
                16_666_667L,
                33_333_334L,
                50_000_001L,
                83_333_335L,
                125_000_001L,
                260_000_000L,
                350_000_000L,
                520_000_000L,
            ))

    listOf(ninety, oneTwenty, oneFortyFour, sixtyToOneTwenty, oneTwentyToSixty, droppedFrame)
        .forEach { sample ->
          assertEquals(
              "same frame time must produce the same tween progress",
              sixty.midpoint,
              sample.midpoint,
              0.0001f)
          assertEquals("all frame patterns finish at the same time", 1f, sample.finalValue, 0f)
          assertTrue("the production tween completes at 520ms", sample.completed)
        }
    assertEquals(1f, sixty.finalValue, 0f)
    assertTrue(sixty.completed)
  }

  @Test
  fun cancellingAnAnimationStopsAtItsLastDeliveredFrame() = runTest {
    val clock = InjectedFrameClock()
    val progress = Animatable(0f)
    val animation = launch {
      withContext(clock) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(MotionTokens.SHARED, easing = MotionTokens.EMPHASIZED),
        )
      }
    }
    runCurrent()
    clock.sendFrame(0L)
    runCurrent()
    clock.sendFrame(100_000_000L)
    runCurrent()
    animation.cancelAndJoin()

    assertTrue(animation.isCancelled)
    assertTrue("cancellation cannot force the animation to its endpoint", progress.value < 1f)
  }

  private data class AnimationSample(
      val midpoint: Float,
      val finalValue: Float,
      val completed: Boolean
  )

  private suspend fun TestScope.sampleAt(frameTimesNanos: List<Long>): AnimationSample {
    val clock = InjectedFrameClock()
    val progress = Animatable(0f)
    val animation = launch {
      withContext(clock) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(MotionTokens.SHARED, easing = MotionTokens.EMPHASIZED),
        )
      }
    }
    runCurrent()
    var midpoint = 0f
    frameTimesNanos.forEach { frameTime ->
      clock.sendFrame(frameTime)
      runCurrent()
      if (frameTime == 260_000_000L) midpoint = progress.value
    }
    return AnimationSample(midpoint, progress.value, animation.isCompleted)
  }

  private fun regularFrames(intervalNanos: Long): List<Long> =
      buildList {
            add(0L)
            var next = intervalNanos
            while (next < 520_000_000L) {
              add(next)
              next += intervalNanos
            }
            add(260_000_000L)
            add(520_000_000L)
          }
          .distinct()
          .sorted()

  private fun variableFrames(firstIntervalNanos: Long, switchAt: Long): List<Long> =
      buildList {
            add(0L)
            var next = firstIntervalNanos
            while (next < switchAt) {
              add(next)
              next += firstIntervalNanos
            }
            val laterInterval = if (firstIntervalNanos == 16_666_667L) 8_333_333L else 16_666_667L
            while (next < 520_000_000L) {
              add(next)
              next += laterInterval
            }
            add(260_000_000L)
            add(520_000_000L)
          }
          .distinct()
          .sorted()
}

private class InjectedFrameClock : MonotonicFrameClock {
  private val pending = Channel<PendingFrame<*>>(Channel.UNLIMITED)

  override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
      suspendCancellableCoroutine { continuation ->
        pending.trySend(PendingFrame(onFrame, continuation))
      }

  suspend fun sendFrame(frameTimeNanos: Long) {
    pending.receive().deliver(frameTimeNanos)
  }

  private class PendingFrame<R>(
      private val onFrame: (Long) -> R,
      private val continuation: CancellableContinuation<R>,
  ) {
    fun deliver(frameTimeNanos: Long) {
      if (continuation.isActive) continuation.resume(onFrame(frameTimeNanos))
    }
  }
}
