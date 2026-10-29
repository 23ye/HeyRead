package cn.heyread.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletSplitPaneTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun actualTabletWidthStartsAtOneToTwoAndDragReflowsTextAtBothLimits() {
        val mainLayout = AtomicReference<TextMeasure?>(null)
        val detailLayout = AtomicReference<TextMeasure?>(null)
        val availableWidth = AtomicReference<Float?>(null)
        val fraction = mutableFloatStateOf(SPLIT_PANE_DEFAULT_LEFT_FRACTION)
        val body =
            "Adaptive reading text must reflow naturally as the available pane width changes. "
                .repeat(16)

        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                SideEffect { availableWidth.set(maxWidth.value) }
                AdaptiveSplitPane(
                    modifier = Modifier.fillMaxSize(),
                    savedLeftFraction = fraction.floatValue,
                    paneOpen = false,
                    bottomBarVisible = false,
                    onFractionChanged = { fraction.floatValue = it },
                    mainContent = { modifier, _ ->
                        MeasuredPaneText(modifier.testTag("tablet-main"), body, mainLayout)
                    },
                    detailContent = { modifier, _, _ ->
                        MeasuredPaneText(modifier.testTag("tablet-detail"), body, detailLayout)
                    },
                    bottomBar = { Box(it) },
                )
            }
        }
        compose.waitForIdle()

        val windowWidth = requireNotNull(availableWidth.get())
        val windowWidthPx = windowWidth * compose.density.density
        assertTrue(
            "run this production-layout regression on the configured tablet",
            windowWidth >= 1200f,
        )
        assertPaneGeometry(windowWidth)
        val initialMainBounds =
            compose.onNodeWithTag("tablet-main", useUnmergedTree = true).getBoundsInRoot()
        val initialDetailBounds =
            compose.onNodeWithTag("tablet-detail", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(
            "default main and detail panes use a one-to-two width ratio",
            0.5f,
            (initialMainBounds.right - initialMainBounds.left).value /
                (initialDetailBounds.right - initialDetailBounds.left).value,
            0.01f,
        )
        val initialMain = requireNotNull(mainLayout.get())
        val initialDetail = requireNotNull(detailLayout.get())
        assertTrue("initial main text has multiple real layout lines", initialMain.lineCount > 1)
        assertTrue(
            "initial detail text has multiple real layout lines",
            initialDetail.lineCount > 1,
        )

        val handle = compose.onNodeWithTag("split-pane-divider")
        handle.performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x - windowWidthPx * 0.20f), delayMillis = 300)
            up()
        }
        compose.waitForIdle()
        assertPaneGeometry(windowWidth)
        assertEquals(
            "left drag reaches the real minimum allocation",
            clampSplitFraction(windowWidth, SPLIT_PANE_DIVIDER_DP, 0f),
            fraction.floatValue,
            0.002f,
        )
        val atMinimum = requireNotNull(mainLayout.get())
        assertTrue(
            "narrow main text wraps to more lines",
            atMinimum.lineCount > initialMain.lineCount,
        )
        assertTrue(
            "narrow main first line contains fewer characters",
            atMinimum.firstLineCharacters < initialMain.firstLineCharacters,
        )

        compose.onNodeWithTag("split-pane-divider").performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x + windowWidthPx * 0.60f), delayMillis = 450)
            up()
        }
        compose.waitForIdle()
        assertPaneGeometry(windowWidth)
        assertEquals(
            "right drag reaches the real maximum allocation",
            clampSplitFraction(windowWidth, SPLIT_PANE_DIVIDER_DP, 1f),
            fraction.floatValue,
            0.002f,
        )
        val atMaximum = requireNotNull(mainLayout.get())
        val narrowDetail = requireNotNull(detailLayout.get())
        assertTrue(
            "expanded main text wraps to fewer lines",
            atMaximum.lineCount < initialMain.lineCount,
        )
        assertTrue(
            "expanded main first line contains more characters",
            atMaximum.firstLineCharacters > initialMain.firstLineCharacters,
        )
        assertTrue(
            "narrow detail text wraps to more lines",
            narrowDetail.lineCount > initialDetail.lineCount,
        )
        assertTrue(
            "narrow detail first line contains fewer characters",
            narrowDetail.firstLineCharacters < initialDetail.firstLineCharacters,
        )
    }

    @Test
    fun dividerSemanticsSetProgressAndRestoreDefaultRatio() {
        val fraction = mutableFloatStateOf(0.55f)
        val width = AtomicReference<Float?>(null)
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                SideEffect { width.set(maxWidth.value) }
                AdaptiveSplitPane(
                    modifier = Modifier.fillMaxSize(),
                    savedLeftFraction = fraction.floatValue,
                    paneOpen = false,
                    bottomBarVisible = false,
                    onFractionChanged = { fraction.floatValue = it },
                    mainContent = { modifier, _ -> Box(modifier.testTag("semantic-main")) },
                    detailContent = { modifier, _, _ -> Box(modifier.testTag("semantic-detail")) },
                    bottomBar = { Box(it) },
                )
            }
        }
        compose.waitForIdle()
        val windowWidth = requireNotNull(width.get())
        assertTrue(
            "run this production-layout regression on the configured tablet",
            windowWidth >= 1200f,
        )

        compose.onNodeWithTag("split-pane-divider").performSemanticsAction(
            SemanticsActions.SetProgress
        ) { action ->
            assertTrue("semantic progress action is handled", action(0.62f))
        }
        compose.waitForIdle()
        assertEquals(0.62f, fraction.floatValue, 0.001f)
        assertPaneGeometryForSemanticCase(windowWidth, "semantic-main", "semantic-detail")

        val customActions =
            compose
                .onNodeWithTag("split-pane-divider")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        val restore = customActions.single { it.label == "恢复默认左右比例" }
        compose.runOnIdle { assertTrue("restore action reports success", restore.action()) }
        compose.waitForIdle()
        assertEquals(SPLIT_PANE_DEFAULT_LEFT_FRACTION, fraction.floatValue, 0.001f)
        assertPaneGeometryForSemanticCase(windowWidth, "semantic-main", "semantic-detail")

        val range =
            compose
                .onNodeWithTag("split-pane-divider")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertTrue("divider exposes its current bounded fraction", range is ProgressBarRangeInfo)
    }

    @Test
    fun savedFractionIsReclampedWhenTheAvailableWidthChanges() {
        val width = mutableFloatStateOf(1200f)
        val fraction = mutableFloatStateOf(0.99f)
        val measuredWidth = AtomicReference<Float?>(null)
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.width(width.floatValue.dp)) {
                    SideEffect { measuredWidth.set(maxWidth.value) }
                    AdaptiveSplitPane(
                        modifier = Modifier.fillMaxSize(),
                        savedLeftFraction = fraction.floatValue,
                        paneOpen = false,
                        bottomBarVisible = false,
                        onFractionChanged = { fraction.floatValue = it },
                        mainContent = { modifier, _ -> Box(modifier.testTag("resize-main")) },
                        detailContent = { modifier, _, _ ->
                            Box(modifier.testTag("resize-detail"))
                        },
                        bottomBar = { Box(it) },
                    )
                }
            }
        }
        compose.waitForIdle()

        val wideWidth = requireNotNull(measuredWidth.get())
        assertEquals(1200f, wideWidth, 1f)
        assertPaneGeometryForSemanticCase(wideWidth, "resize-main", "resize-detail")
        val wideMaximum = clampSplitFraction(wideWidth, SPLIT_PANE_DIVIDER_DP, 1f)
        assertEquals(wideMaximum, fraction.floatValue, 0.001f)

        compose.runOnIdle { width.floatValue = 840f }
        compose.waitForIdle()
        val narrowWidth = requireNotNull(measuredWidth.get())
        assertEquals(840f, narrowWidth, 1f)
        val narrowMaximum = clampSplitFraction(narrowWidth, SPLIT_PANE_DIVIDER_DP, 1f)
        assertEquals(narrowMaximum, fraction.floatValue, 0.001f)
        assertTrue("width change reclamps the retained fraction", fraction.floatValue < wideMaximum)
        assertPaneGeometryForSemanticCase(narrowWidth, "resize-main", "resize-detail")
    }

    private fun assertPaneGeometry(availableWidth: Float) {
        val main = compose.onNodeWithTag("tablet-main", useUnmergedTree = true).getBoundsInRoot()
        val detail =
            compose.onNodeWithTag("tablet-detail", useUnmergedTree = true).getBoundsInRoot()
        assertPaneSizesAndGap(availableWidth, main.left, main.right, detail.left, detail.right)
        assertHandleBoundsAndAlignment(main.right, detail.left)
    }

    private fun assertPaneGeometryForSemanticCase(
        availableWidth: Float,
        mainTag: String,
        detailTag: String,
    ) {
        val main = compose.onNodeWithTag(mainTag, useUnmergedTree = true).getBoundsInRoot()
        val detail = compose.onNodeWithTag(detailTag, useUnmergedTree = true).getBoundsInRoot()
        assertPaneSizesAndGap(availableWidth, main.left, main.right, detail.left, detail.right)
        assertHandleBoundsAndAlignment(main.right, detail.left)
    }

    private fun assertPaneSizesAndGap(
        availableWidth: Float,
        mainLeft: androidx.compose.ui.unit.Dp,
        mainRight: androidx.compose.ui.unit.Dp,
        detailLeft: androidx.compose.ui.unit.Dp,
        detailRight: androidx.compose.ui.unit.Dp,
    ) {
        val mainWidth = mainRight - mainLeft
        val detailWidth = detailRight - detailLeft
        val gap = detailLeft - mainRight
        assertTrue(
            "main pane remains at least a quarter of the full width",
            mainWidth.value >= availableWidth * 0.25f - 1f,
        )
        assertTrue(
            "detail pane remains at least a quarter of the full width",
            detailWidth.value >= availableWidth * 0.25f - 1f,
        )
        assertEquals("divider gap is measured separately", SPLIT_PANE_DIVIDER_DP, gap.value, 1f)
        assertEquals(
            "pane widths and gap conserve the measured window width",
            availableWidth,
            mainWidth.value + gap.value + detailWidth.value,
            0.5f,
        )
    }

    private fun assertHandleBoundsAndAlignment(
        mainRight: androidx.compose.ui.unit.Dp,
        detailLeft: androidx.compose.ui.unit.Dp,
    ) {
        val bounds = compose.onNodeWithTag("split-pane-divider").getBoundsInRoot()
        assertEquals(48f, bounds.right.value - bounds.left.value, 0.5f)
        assertEquals(72f, bounds.bottom.value - bounds.top.value, 0.5f)
        assertEquals(
            "handle center aligns to the divider gap",
            (mainRight.value + detailLeft.value) / 2f,
            (bounds.left.value + bounds.right.value) / 2f,
            1f,
        )
    }

    @Composable
    private fun MeasuredPaneText(
        modifier: Modifier,
        text: String,
        result: AtomicReference<TextMeasure?>,
    ) {
        Text(
            text = text,
            modifier = modifier,
            fontSize = 16.sp,
            onTextLayout = { layout ->
                result.set(TextMeasure(layout.lineCount, layout.getLineEnd(0, visibleEnd = true)))
            },
        )
    }

    private data class TextMeasure(val lineCount: Int, val firstLineCharacters: Int)
}
