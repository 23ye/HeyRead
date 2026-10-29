package cn.heyread.app.ui.motion

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedCoverPaneFocusTest {
    @Test
    fun unrelatedLeftRootDoesNotCancelPendingRightPaneDestination() {
        val coordinator = SharedCoverCoordinator()
        val sourceEntry = "right-source-article"
        val targetEntry = "right-target-article"
        val identity = "DEMO|story|cover|$sourceEntry"
        val sourcePainter = ColorPainter(Color.Red)
        coordinator.register(
            registration(
                identity = identity,
                originEntry = sourceEntry,
                entry = sourceEntry,
                detail = false,
                painter = sourcePainter,
                rect = Rect(20f, 40f, 180f, 160f),
            )
        )
        coordinator.setCurrentEntries(setOf("left-root", sourceEntry), focusedEntryId = sourceEntry)

        assertTrue(coordinator.begin(identity, sourceEntry, sourceEntry))
        coordinator.bindExpectedTarget(targetEntry)
        coordinator.setCurrentEntries(setOf("left-root", targetEntry), focusedEntryId = targetEntry)

        val pending = coordinator.transaction
        assertNotNull("the unrelated left root must not cancel the right-side open", pending)
        assertEquals(targetEntry, pending?.pendingEntryId)

        coordinator.register(
            registration(
                identity = identity,
                originEntry = sourceEntry,
                entry = targetEntry,
                detail = true,
                painter = ColorPainter(Color.Blue),
                rect = Rect(220f, 30f, 620f, 430f),
            )
        )
        assertEquals(targetEntry, coordinator.transaction?.targetEntryId)
        assertTrue(coordinator.transaction?.targetReady == true)
    }

    @Test
    fun returningFocusToSourceBeforeTargetRegistrationCancelsAndCanReopen() {
        val coordinator = SharedCoverCoordinator()
        val sourceEntry = "right-source-article"
        val targetEntry = "right-pending-article"
        val identity = "DEMO|story|cover|$sourceEntry"
        val sourcePainter = ColorPainter(Color.Red)
        coordinator.register(
            registration(
                identity = identity,
                originEntry = sourceEntry,
                entry = sourceEntry,
                detail = false,
                painter = sourcePainter,
                rect = Rect(16f, 24f, 136f, 144f),
            )
        )
        coordinator.setCurrentEntries(setOf("left-root", sourceEntry), focusedEntryId = sourceEntry)

        assertTrue(coordinator.begin(identity, sourceEntry, sourceEntry))
        val firstGeneration = requireNotNull(coordinator.transaction).generation
        coordinator.bindExpectedTarget(targetEntry)
        coordinator.setCurrentEntries(setOf("left-root", targetEntry), focusedEntryId = targetEntry)
        assertNotNull(
            "the destination is pending before its cover is registered",
            coordinator.transaction,
        )

        coordinator.setCurrentEntries(setOf("left-root", sourceEntry), focusedEntryId = sourceEntry)
        assertNull(
            "back to the source cancels an unpaired forward transaction",
            coordinator.transaction,
        )

        assertTrue(
            "the retained source can start a new transition",
            coordinator.begin(identity, sourceEntry, sourceEntry),
        )
        val reopened = requireNotNull(coordinator.transaction)
        assertTrue("reopen receives a fresh generation", reopened.generation > firstGeneration)
        assertSame(
            "the source Painter registration remains available",
            sourcePainter,
            reopened.painter,
        )
    }

    @Test
    fun unrelatedFocusedPageCancelsAfterRightPaneTargetIsRegistered() {
        val coordinator = SharedCoverCoordinator()
        val sourceEntry = "right-source-article"
        val targetEntry = "right-target-article"
        val identity = "DEMO|story|cover|$sourceEntry"
        coordinator.register(
            registration(
                identity = identity,
                originEntry = sourceEntry,
                entry = sourceEntry,
                detail = false,
                painter = ColorPainter(Color.Red),
                rect = Rect(32f, 56f, 176f, 200f),
            )
        )
        coordinator.setCurrentEntries(setOf("left-root", sourceEntry), focusedEntryId = sourceEntry)

        assertTrue(coordinator.begin(identity, sourceEntry, sourceEntry))
        coordinator.bindExpectedTarget(targetEntry)
        coordinator.setCurrentEntries(setOf("left-root", targetEntry), focusedEntryId = targetEntry)
        coordinator.register(
            registration(
                identity = identity,
                originEntry = sourceEntry,
                entry = targetEntry,
                detail = true,
                painter = ColorPainter(Color.Blue),
                rect = Rect(240f, 16f, 640f, 416f),
            )
        )
        assertEquals(
            "target registration pairs the transaction",
            targetEntry,
            coordinator.transaction?.targetEntryId,
        )

        coordinator.setCurrentEntries(
            setOf("left-root", "unrelated-right-page"),
            focusedEntryId = "unrelated-right-page",
        )
        assertNull(
            "moving focus outside the source/target pair cancels the transaction",
            coordinator.transaction,
        )
    }

    @Test
    fun firstFocusUpdateToSourceDoesNotLookLikeBackWhenThereWasNoPreviousFocus() {
        val coordinator = SharedCoverCoordinator()
        val sourceEntry = "right-source-article"
        val identity = "DEMO|story|cover|$sourceEntry"
        coordinator.register(
            registration(
                identity = identity,
                originEntry = sourceEntry,
                entry = sourceEntry,
                detail = false,
                painter = ColorPainter(Color.Red),
                rect = Rect(12f, 20f, 132f, 140f),
            )
        )

        assertTrue(coordinator.begin(identity, sourceEntry, sourceEntry))
        val generation = requireNotNull(coordinator.transaction).generation
        coordinator.setCurrentEntries(setOf("left-root", sourceEntry), focusedEntryId = sourceEntry)

        val stillPending = requireNotNull(coordinator.transaction)
        assertEquals(
            "the initial focus settles on the existing source",
            sourceEntry,
            stillPending.sourceEntryId,
        )
        assertEquals(
            "first focus update preserves the active generation",
            generation,
            stillPending.generation,
        )
    }

    private fun registration(
        identity: String,
        originEntry: String,
        entry: String,
        detail: Boolean,
        painter: ColorPainter,
        rect: Rect,
    ) =
        SharedCoverRegistration(
            identity = identity,
            originId = originEntry,
            entryId = entry,
            isDetail = detail,
            rect = rect,
            painter = painter,
            animated = false,
            failed = false,
        )
}
