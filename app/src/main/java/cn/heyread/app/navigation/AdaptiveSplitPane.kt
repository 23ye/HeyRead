package cn.heyread.app.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cn.heyread.app.ui.motion.CoverCoordinateTransform
import cn.heyread.app.ui.motion.LocalCoverCoordinateTransforms
import cn.heyread.app.ui.motion.MotionFrameRateHint
import cn.heyread.app.ui.motion.MotionTokens

@Composable
internal fun AdaptiveSplitPane(
    modifier: Modifier = Modifier,
    savedLeftFraction: Float,
    paneOpen: Boolean,
    bottomBarVisible: Boolean,
    motionEnabled: Boolean = true,
    onFractionChanged: (Float) -> Unit,
    onResizeStarted: () -> Unit = {},
    onResizeFinished: () -> Unit = {},
    onWindowWidthChanged: () -> Unit = {},
    mainContent: @Composable (Modifier, Boolean) -> Unit,
    detailContent:
        @Composable
        (modifier: Modifier, paneVisible: Boolean, isDualPane: Boolean) -> Unit,
    bottomBar: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableWidth = maxWidth.value
        val viewportWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        var previousAvailableWidth by remember { mutableFloatStateOf(availableWidth) }
        SideEffect {
            if (previousAvailableWidth != availableWidth) {
                previousAvailableWidth = availableWidth
                onWindowWidthChanged()
            }
        }
        val isDualPane = availableWidth >= SPLIT_PANE_BREAKPOINT_DP
        val dividerWidth = SPLIT_PANE_DIVIDER_DP.coerceAtMost(availableWidth)
        var fraction by remember {
            mutableFloatStateOf(
                if (isDualPane) clampSplitFraction(availableWidth, dividerWidth, savedLeftFraction)
                else savedLeftFraction.takeIf(Float::isFinite) ?: SPLIT_PANE_DEFAULT_LEFT_FRACTION
            )
        }
        LaunchedEffect(availableWidth, dividerWidth, isDualPane) {
            if (isDualPane) fraction = clampSplitFraction(availableWidth, dividerWidth, fraction)
        }
        val widths = splitPaneWidths(availableWidth, dividerWidth, fraction)
        val rootVisible = isDualPane || !paneOpen
        val detailVisible = isDualPane || paneOpen
        val showBottomBar = bottomBarVisible && (isDualPane || !paneOpen)
        val animatePhonePane = motionEnabled && !isDualPane
        val phoneTransition =
            if (animatePhonePane) remember { Animatable(if (paneOpen) 1f else 0f) } else null
        var previousPaneOpen by remember { mutableStateOf(paneOpen) }
        var paneAnimationActive by remember { mutableStateOf(false) }
        SideEffect { previousPaneOpen = paneOpen }
        LaunchedEffect(phoneTransition, paneOpen) {
            val progress = phoneTransition ?: return@LaunchedEffect
            paneAnimationActive = progress.value != if (paneOpen) 1f else 0f
            try {
                progress.animateTo(
                    if (paneOpen) 1f else 0f,
                    tween(MotionTokens.PAGE, easing = MotionTokens.EMPHASIZED),
                )
            } finally {
                paneAnimationActive = false
            }
        }
        MotionFrameRateHint(phoneTransition?.isRunning == true)
        val transitionStarted = animatePhonePane && paneOpen != previousPaneOpen
        val transitioning =
            animatePhonePane &&
                (paneAnimationActive || phoneTransition?.isRunning == true || transitionStarted)
        val parentTransforms = LocalCoverCoordinateTransforms.current
        val rootTransforms =
            if (animatePhonePane) {
                remember(parentTransforms, phoneTransition, viewportWidthPx) {
                    derivedStateOf {
                        parentTransforms.value +
                            CoverCoordinateTransform(
                                translationX =
                                    -(phoneTransition?.value ?: 0f) * viewportWidthPx * 0.25f
                            )
                    }
                }
            } else parentTransforms
        val detailTransforms =
            if (animatePhonePane) {
                remember(parentTransforms, phoneTransition, viewportWidthPx) {
                    derivedStateOf {
                        parentTransforms.value +
                            CoverCoordinateTransform(
                                translationX =
                                    (1f - (phoneTransition?.value ?: 0f)) * viewportWidthPx * 0.25f
                            )
                    }
                }
            } else parentTransforms
        SideEffect { onFractionChanged(fraction) }
        Layout(
            content = {
                CompositionLocalProvider(LocalCoverCoordinateTransforms provides rootTransforms) {
                    Box(
                        Modifier.fillMaxSize()
                            .then(
                                if (animatePhonePane)
                                    Modifier.graphicsLayer {
                                        translationX =
                                            -size.width * (phoneTransition?.value ?: 0f) * 0.25f
                                    }
                                else Modifier
                            )
                    ) {
                        mainContent(Modifier.fillMaxSize(), rootVisible)
                    }
                }
                CompositionLocalProvider(LocalCoverCoordinateTransforms provides detailTransforms) {
                    Box(
                        Modifier.fillMaxSize()
                            .then(
                                if (animatePhonePane)
                                    Modifier.graphicsLayer {
                                        translationX =
                                            size.width *
                                                (1f - (phoneTransition?.value ?: 0f)) *
                                                0.25f
                                    }
                                else Modifier
                            )
                    ) {
                        detailContent(Modifier.fillMaxSize(), detailVisible, isDualPane)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (isDualPane) {
                        SplitPaneDivider(
                            availableWidth = availableWidth,
                            dividerWidth = dividerWidth,
                            fraction = fraction,
                            onFractionChange = { fraction = it },
                            onResizeStarted = onResizeStarted,
                            onResizeFinished = onResizeFinished,
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) { bottomBar(Modifier.fillMaxSize()) }
            },
            modifier = Modifier.fillMaxSize().testTag("adaptive-pane-layout"),
        ) { measurables, constraints ->
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val gapPx = (if (isDualPane) widths.divider else 0f).dp.roundToPx()
            val leftPx = if (isDualPane) widths.left.dp.roundToPx() else widthPx
            val rightPx = if (isDualPane) (widthPx - leftPx - gapPx).coerceAtLeast(0) else widthPx
            val barHeightPx = if (showBottomBar) 80.dp.roundToPx() else 0
            val rootHeight = (heightPx - if (showBottomBar) barHeightPx else 0).coerceAtLeast(0)
            val root = measurables[0].measure(Constraints.fixed(leftPx, rootHeight))
            val detail = measurables[1].measure(Constraints.fixed(rightPx, heightPx))
            val divider =
                measurables[2].measure(
                    Constraints.fixed(
                        if (isDualPane) 48.dp.roundToPx() else 0,
                        if (isDualPane) heightPx else 0,
                    )
                )
            val barWidth = if (isDualPane) leftPx else widthPx
            val bar = measurables[3].measure(Constraints.fixed(barWidth, barHeightPx))
            layout(widthPx, heightPx) {
                if (isDualPane || rootVisible || transitioning) root.place(0, 0, zIndex = 0f)
                if (isDualPane || detailVisible || transitioning)
                    detail.place(
                        if (isDualPane) leftPx + gapPx else 0,
                        0,
                        zIndex = if (isDualPane) 0f else 1f,
                    )
                if (isDualPane)
                    divider.place(leftPx + gapPx / 2 - divider.width / 2, 0, zIndex = 2f)
                if (showBottomBar) bar.place(0, heightPx - barHeightPx, zIndex = 3f)
            }
        }
    }
}

@Composable
internal fun SplitPaneDivider(
    availableWidth: Float,
    dividerWidth: Float,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    onResizeStarted: () -> Unit,
    onResizeFinished: () -> Unit,
) {
    val currentFraction by rememberUpdatedState(fraction)
    Box(Modifier.fillMaxSize().testTag("split-pane-divider-slot")) {
        Box(
            Modifier.align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Box(
            Modifier.align(Alignment.Center)
                .requiredSize(48.dp, 72.dp)
                .pointerInput(availableWidth, dividerWidth) {
                    detectDragGestures(
                        onDragStart = { onResizeStarted() },
                        onDragEnd = { onResizeFinished() },
                        onDragCancel = { onResizeFinished() },
                    ) { change, dragAmount ->
                        change.consume()
                        onFractionChange(
                            adjustSplitFractionForDrag(
                                availableWidth,
                                dividerWidth,
                                currentFraction,
                                dragAmount.x / density,
                            )
                        )
                    }
                }
                .semantics {
                    contentDescription = "调整左右栏宽度"
                    val paneWidth = (availableWidth - dividerWidth).coerceAtLeast(1f)
                    val minimum =
                        (availableWidth * SPLIT_PANE_MIN_FRACTION / paneWidth).coerceAtMost(0.5f)
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimum..(1f - minimum))
                    setProgress { requested ->
                        onResizeStarted()
                        onFractionChange(
                            clampSplitFraction(availableWidth, dividerWidth, requested)
                        )
                        onResizeFinished()
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction("恢复默认左右比例") {
                                onResizeStarted()
                                onFractionChange(
                                    clampSplitFraction(
                                        availableWidth,
                                        dividerWidth,
                                        SPLIT_PANE_DEFAULT_LEFT_FRACTION,
                                    )
                                )
                                onResizeFinished()
                                true
                            }
                        )
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = 0.025f
                    val next =
                        when (event.key) {
                            Key.DirectionLeft -> fraction - step
                            Key.DirectionRight -> fraction + step
                            Key.Home -> SPLIT_PANE_DEFAULT_LEFT_FRACTION
                            else -> return@onKeyEvent false
                        }
                    onResizeStarted()
                    onFractionChange(clampSplitFraction(availableWidth, dividerWidth, next))
                    onResizeFinished()
                    true
                }
                .focusable()
                .testTag("split-pane-divider")
        )
        Box(
            Modifier.align(Alignment.Center)
                .width(5.dp)
                .heightIn(min = 20.dp, max = 32.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}
