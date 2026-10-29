package cn.heyread.app.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedCoverPendingReturnTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun temporarilyInvisibleReturnTargetRecoversFromSameRegistrationAndCurrentGeometry() {
    compose.mainClock.autoAdvance = false
    val coordinator = SharedCoverCoordinator()
    val identity = "demo:story:https://images.invalid/story.png"
    val originId = "root-entry"
    val detailEntryId = "detail-entry"
    val painter = ColorPainter(Color.Magenta)
    val originalRect = Rect(18f, 46f, 138f, 126f)
    val restoredRect = Rect(24f, 58f, 152f, 144f)
    val sourceCoordinatesVisible = androidx.compose.runtime.mutableStateOf(true)
    val sourceCoordinatesRect = androidx.compose.runtime.mutableStateOf(originalRect)
    val sourceProviderReads = AtomicInteger()
    val sourceOwner = Any()
    val sourceRegistration =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = originId,
            isDetail = false,
            rect = originalRect,
            painter = painter,
            animated = false,
            failed = false,
            rectProvider = {
              sourceProviderReads.incrementAndGet()
              sourceCoordinatesRect.value
            },
            visualRectProvider = {
              sourceProviderReads.incrementAndGet()
              sourceCoordinatesRect.value
            },
            owner = sourceOwner,
            attachedProvider = {
              sourceProviderReads.incrementAndGet()
              true
            },
            visibleProvider = {
              sourceProviderReads.incrementAndGet()
              sourceCoordinatesVisible.value
            },
            canonicalVisibleProvider = {
              sourceProviderReads.incrementAndGet()
              sourceCoordinatesVisible.value
            },
        )
    val detailRegistration =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = detailEntryId,
            isDetail = true,
            rect = Rect(110f, 80f, 390f, 260f),
            painter = painter,
            animated = false,
            failed = false,
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { true },
            canonicalVisibleProvider = { true },
        )

    compose.setContent { SharedCoverOverlayHost(coordinator) }
    compose.runOnIdle {
      coordinator.register(sourceRegistration)
      assertTrue(coordinator.begin(identity, originId, originId))
      coordinator.bindExpectedTarget(detailEntryId)
      coordinator.register(detailRegistration)
      val forwardGeneration = coordinator.transaction!!.generation
      coordinator.finish(forwardGeneration)

      assertTrue(coordinator.beginReturn(identity, originId, detailEntryId))
      sourceCoordinatesVisible.value = false
      coordinator.register(sourceRegistration.copy(rect = originalRect))
      val pending = coordinator.transaction
      assertNotNull("a hidden but attached source remains a pending return target", pending)
      assertEquals(SharedCoverDirection.Return, pending?.direction)
      assertEquals(false, pending?.targetReady)
    }

    compose.runOnIdle {
      // The same LayoutCoordinates-backed providers become visible at their settled location.
      sourceCoordinatesRect.value = restoredRect
      sourceCoordinatesVisible.value = true
    }
    for (frame in 0 until 48) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
      if (coordinator.transaction?.targetReady == true) break
    }

    compose.runOnIdle {
      val recovered = coordinator.transaction
      assertNotNull("the pending return survives until its source is placed", recovered)
      assertEquals(SharedCoverDirection.Return, recovered?.direction)
      assertTrue(
          "the current source geometry becomes the return target", recovered?.targetReady == true)
      assertEquals(restoredRect, recovered?.targetRect)
    }
    val readsAtReady = sourceProviderReads.get()
    repeat(4) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle {
      assertEquals(
          "ready return stops sampling its endpoint while the overlay animates",
          readsAtReady,
          sourceProviderReads.get(),
      )
      coordinator.cancel()
      assertEquals(null, coordinator.transaction)
    }
  }

  @Test
  fun hiddenPaneRequiresNewPlacementRevisionBeforeUsingTargetGeometry() {
    compose.mainClock.autoAdvance = false
    val coordinator = SharedCoverCoordinator()
    val identity = "demo:placement:https://images.invalid/story.png"
    val originId = "root-entry"
    val detailEntryId = "detail-entry"
    val painter = ColorPainter(Color.Magenta)
    val visible = androidx.compose.runtime.mutableStateOf(true)
    val paneActive = androidx.compose.runtime.mutableStateOf(true)
    val placementRevision = AtomicLong(2L)
    val staleRect = Rect(18f, 46f, 138f, 126f)
    val restoredRect = Rect(38f, 66f, 166f, 152f)
    val sourceOwner = Any()
    val sourceRegistration =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = originId,
            isDetail = false,
            rect = staleRect,
            painter = painter,
            animated = false,
            failed = false,
            rectProvider = { if (visible.value) restoredRect else staleRect },
            owner = sourceOwner,
            attachedProvider = { true },
            visibleProvider = { visible.value },
            canonicalVisibleProvider = { visible.value },
            placementRevisionProvider = { placementRevision.get() },
            paneActiveProvider = { paneActive.value },
        )
    val detailRegistration =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = detailEntryId,
            isDetail = true,
            rect = Rect(110f, 80f, 390f, 260f),
            painter = painter,
            animated = false,
            failed = false,
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { true },
            canonicalVisibleProvider = { true },
        )
    compose.setContent { SharedCoverOverlayHost(coordinator) }
    compose.runOnIdle {
      coordinator.register(sourceRegistration)
      assertTrue(coordinator.begin(identity, originId, originId))
      coordinator.bindExpectedTarget(detailEntryId)
      coordinator.register(detailRegistration)
      coordinator.finish(coordinator.transaction!!.generation)

      visible.value = false
      paneActive.value = false
      placementRevision.set(3L)
      assertTrue(coordinator.beginReturn(identity, originId, detailEntryId))
      coordinator.register(sourceRegistration)
      assertEquals(SharedCoverDirection.Return, coordinator.transaction?.direction)
    }

    compose.runOnIdle { paneActive.value = true }
    repeat(3) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle {
      assertEquals(
          "activating a hidden pane does not reuse its stale placement at revision 3",
          false,
          coordinator.transaction?.targetReady,
      )
      assertNull(coordinator.transaction?.targetRect)
    }

    compose.runOnIdle {
      visible.value = true
      placementRevision.set(4L)
    }
    for (frame in 0 until 8) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
      if (coordinator.transaction?.targetReady == true) break
    }
    compose.runOnIdle {
      assertTrue(
          "the same endpoint becomes fresh after placement revision 4",
          coordinator.transaction?.targetReady == true)
      assertEquals(
          "the target uses current measured coordinates",
          restoredRect,
          coordinator.transaction?.targetRect)
      coordinator.cancel()
    }
  }

  @Test
  fun pendingInvisibleTargetTimesOutAndReleasesItsSampling() {
    compose.mainClock.autoAdvance = false
    val coordinator = SharedCoverCoordinator()
    val identity = "demo:timeout:https://images.invalid/story.png"
    val originId = "root-entry"
    val detailEntryId = "detail-entry"
    val painter = ColorPainter(Color.Magenta)
    val targetReads = AtomicInteger()
    val invisible = androidx.compose.runtime.mutableStateOf(false)
    val rect = Rect(20f, 42f, 140f, 124f)
    val source =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = originId,
            isDetail = false,
            rect = rect,
            painter = painter,
            animated = false,
            failed = false,
            rectProvider = {
              targetReads.incrementAndGet()
              rect
            },
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { invisible.value },
            canonicalVisibleProvider = { invisible.value },
        )
    val detail =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = detailEntryId,
            isDetail = true,
            rect = Rect(110f, 80f, 390f, 260f),
            painter = painter,
            animated = false,
            failed = false,
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { true },
            canonicalVisibleProvider = { true },
        )

    compose.setContent { SharedCoverOverlayHost(coordinator) }
    compose.runOnIdle {
      coordinator.register(
          source.copy(visibleProvider = { true }, canonicalVisibleProvider = { true }))
      assertTrue(coordinator.begin(identity, originId, originId))
      coordinator.bindExpectedTarget(detailEntryId)
      coordinator.register(detail)
      coordinator.finish(coordinator.transaction!!.generation)
      assertTrue(coordinator.beginReturn(identity, originId, detailEntryId))
      coordinator.register(source)
      assertEquals(SharedCoverDirection.Return, coordinator.transaction?.direction)
    }
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    compose.runOnIdle {
      assertEquals(
          "the return remains pending during its bounded wait",
          SharedCoverDirection.Return,
          coordinator.transaction?.direction)
    }
    for (frame in 0 until 50) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
      if (coordinator.transaction == null) break
    }
    compose.runOnIdle {
      assertEquals(
          "unrestored target expires without a stale overlay", null, coordinator.transaction)
    }
    val readsAtCancel = targetReads.get()
    repeat(3) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    assertEquals("timeout stops endpoint sampling", readsAtCancel, targetReads.get())
  }

  @Test
  fun cancellingPendingReturnStopsEndpointSamplingImmediately() {
    compose.mainClock.autoAdvance = false
    val coordinator = SharedCoverCoordinator()
    val identity = "demo:cancel:https://images.invalid/story.png"
    val originId = "root-entry"
    val detailEntryId = "detail-entry"
    val painter = ColorPainter(Color.Magenta)
    val targetReads = AtomicInteger()
    val rect = Rect(20f, 42f, 140f, 124f)
    val source =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = originId,
            isDetail = false,
            rect = rect,
            painter = painter,
            animated = false,
            failed = false,
            rectProvider = {
              targetReads.incrementAndGet()
              rect
            },
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { false },
            canonicalVisibleProvider = { false },
        )
    val detail =
        SharedCoverRegistration(
            identity = identity,
            originId = originId,
            entryId = detailEntryId,
            isDetail = true,
            rect = Rect(110f, 80f, 390f, 260f),
            painter = painter,
            animated = false,
            failed = false,
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { true },
            canonicalVisibleProvider = { true },
        )
    compose.setContent { SharedCoverOverlayHost(coordinator) }
    compose.runOnIdle {
      coordinator.register(
          source.copy(visibleProvider = { true }, canonicalVisibleProvider = { true }))
      assertTrue(coordinator.begin(identity, originId, originId))
      coordinator.bindExpectedTarget(detailEntryId)
      coordinator.register(detail)
      coordinator.finish(coordinator.transaction!!.generation)
      assertTrue(coordinator.beginReturn(identity, originId, detailEntryId))
      coordinator.register(source)
      assertEquals(SharedCoverDirection.Return, coordinator.transaction?.direction)
    }
    repeat(4) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle { coordinator.cancel() }
    val readsAtCancel = targetReads.get()
    repeat(5) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    assertNull(coordinator.transaction)
    assertEquals("cancel stops endpoint sampling", readsAtCancel, targetReads.get())
  }

  @Test
  fun failedAndAnimatedSourceTargetsFallBackWithoutAStaleReturn() {
    listOf("failed", "animated").forEach { failureMode ->
      val coordinator = SharedCoverCoordinator()
      val identity = "demo:$failureMode:https://images.invalid/story.png"
      val originId = "root-entry"
      val detailEntryId = "detail-entry"
      val painter = ColorPainter(Color.Magenta)
      val source =
          SharedCoverRegistration(
              identity = identity,
              originId = originId,
              entryId = originId,
              isDetail = false,
              rect = Rect(20f, 42f, 140f, 124f),
              painter = painter,
              animated = false,
              failed = false,
              owner = Any(),
              attachedProvider = { true },
              visibleProvider = { true },
              canonicalVisibleProvider = { true },
          )
      val detail =
          SharedCoverRegistration(
              identity = identity,
              originId = originId,
              entryId = detailEntryId,
              isDetail = true,
              rect = Rect(110f, 80f, 390f, 260f),
              painter = painter,
              animated = false,
              failed = false,
              owner = Any(),
              attachedProvider = { true },
              visibleProvider = { true },
              canonicalVisibleProvider = { true },
          )
      coordinator.register(source)
      assertTrue(
          "$failureMode source starts forward", coordinator.begin(identity, originId, originId))
      coordinator.bindExpectedTarget(detailEntryId)
      coordinator.register(detail)
      coordinator.finish(coordinator.transaction!!.generation)
      assertTrue(
          "$failureMode case starts paired return",
          coordinator.beginReturn(identity, originId, detailEntryId))
      coordinator.register(
          source.copy(
              failed = failureMode == "failed",
              animated = failureMode == "animated",
          ))
      assertNull("$failureMode source cancels the paired overlay", coordinator.transaction)
    }
  }

  @Test
  fun steamAnnouncementIdsRemainDistinctWhileSamePostUrlChangesCancelReturn() {
    val coordinator = SharedCoverCoordinator()
    val originId = "root-entry"
    val detailEntryId = "detail-entry"
    val postIdentity = "story-cover:$originId:STEAM:570:announcementA:https://images.invalid/a.jpg"
    val otherPostIdentity =
        "story-cover:$originId:STEAM:570:announcementB:https://images.invalid/b.jpg"
    val refreshedMediaIdentity =
        "story-cover:$originId:STEAM:570:announcementA:https://images.invalid/a-new.jpg"
    val painter = ColorPainter(Color.Magenta)
    val source =
        SharedCoverRegistration(
            identity = postIdentity,
            originId = originId,
            entryId = originId,
            isDetail = false,
            rect = Rect(18f, 46f, 138f, 126f),
            painter = painter,
            animated = false,
            failed = false,
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { true },
            canonicalVisibleProvider = { true },
        )
    val detail =
        SharedCoverRegistration(
            identity = postIdentity,
            originId = originId,
            entryId = detailEntryId,
            isDetail = true,
            rect = Rect(110f, 80f, 390f, 260f),
            painter = painter,
            animated = false,
            failed = false,
            owner = Any(),
            attachedProvider = { true },
            visibleProvider = { true },
            canonicalVisibleProvider = { true },
        )
    coordinator.register(source)
    assertTrue(coordinator.begin(postIdentity, originId, originId))
    coordinator.bindExpectedTarget(detailEntryId)
    coordinator.register(detail)
    coordinator.finish(coordinator.transaction!!.generation)
    assertTrue(coordinator.beginReturn(postIdentity, originId, detailEntryId))

    coordinator.register(source.copy(identity = otherPostIdentity))
    assertEquals(
        "a different Steam announcement must not cancel this post's pending return",
        SharedCoverDirection.Return,
        coordinator.transaction?.direction,
    )

    coordinator.register(source.copy(identity = refreshedMediaIdentity))
    assertNull(
        "a new media URL for the same Steam post invalidates its frozen painter",
        coordinator.transaction)
  }
}
