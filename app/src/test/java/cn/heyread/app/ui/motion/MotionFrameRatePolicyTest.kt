package cn.heyread.app.ui.motion

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionFrameRatePolicyTest {
  @Test
  fun selectsFastestRefreshRateOnlyAtTheCurrentResolution() {
    val selected =
        chooseWindowRefreshRate(
            listOf(
                DisplayModeRate(1080, 2400, 60f),
                DisplayModeRate(1080, 2400, 90f),
                DisplayModeRate(1080, 2400, 144f),
                DisplayModeRate(1440, 3200, 165f),
                DisplayModeRate(1080, 2400, Float.NaN),
                DisplayModeRate(1080, 2400, 0f),
            ),
            currentWidth = 1080,
            currentHeight = 2400,
        )

    assertEquals(144f, selected!!, 0f)
    assertNull(chooseWindowRefreshRate(listOf(DisplayModeRate(1440, 3200, 120f)), 1080, 2400))
  }

  @Test
  fun overlappingAndCancelledLeasesApplyOnceAndRestoreAfterTheLastOwner() {
    val events = mutableListOf<String>()
    val gate =
        MotionFrameRateLeaseGate(
            {
              events += "high"
              true
            },
            { events += "restore" })
    gate.setAvailable(true)

    val first: Closeable = gate.acquire()
    val second = gate.acquire()
    first.close()
    first.close()
    assertEquals(listOf("high"), events)

    gate.setAvailable(false)
    gate.setAvailable(true)
    second.close()
    assertEquals(listOf("high", "restore", "high", "restore"), events)
  }

  @Test
  fun unavailableHostNeverAppliesHintAndAReattachedHostResumesOnlyActiveLease() {
    val events = mutableListOf<String>()
    val gate =
        MotionFrameRateLeaseGate(
            {
              events += "high"
              true
            },
            { events += "restore" })
    val lease = gate.acquire()
    assertEquals(emptyList<String>(), events)

    gate.setAvailable(true)
    assertEquals(listOf("high"), events)
    gate.setAvailable(false)
    assertEquals(listOf("high", "restore"), events)
    gate.setAvailable(true)
    assertEquals(listOf("high", "restore", "high"), events)
    lease.close()
    assertEquals(listOf("high", "restore", "high", "restore"), events)
  }

  @Test
  fun failedPlatformHintDoesNotClaimOwnershipOrRunRestore() {
    val events = mutableListOf<String>()
    val gate =
        MotionFrameRateLeaseGate(
            {
              events += "try"
              false
            },
            { events += "restore" })
    gate.setAvailable(true)
    gate.acquire().close()
    assertEquals(listOf("try"), events)
  }

  @Test
  fun aStaleOwnerCannotReleaseANewerRegistrationGeneration() {
    val events = mutableListOf<String>()
    val gate =
        MotionFrameRateLeaseGate(
            {
              events += "high"
              true
            },
            { events += "restore" })
    gate.setAvailable(true)
    val stale = gate.acquire()
    gate.clear()
    gate.setAvailable(true)
    val current = gate.acquire()

    stale.close()
    assertEquals(listOf("high", "restore", "high"), events)
    current.close()
    assertEquals(listOf("high", "restore", "high", "restore"), events)
  }
}
