package cn.heyread.app.ui.screens

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.ui.components.CommentThreadCard
import cn.heyread.app.ui.components.LocalPaneContentActive
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalFoundationApi::class)
class DetailPagerActivityTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun tabNavigationKeepsBodyAndCommentScrollPositionsIndependent() {
    compose.setContent {
      HeyReadTheme("light") {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val bodyListState = rememberLazyListState()
        val commentsListState = rememberLazyListState()
        DetailPagerPages(
          state = pagerState,
          pageCount = 2,
          routeActive = true,
          motionEnabled = false,
          commentLabel = "评论 · 12",
          modifier = Modifier.fillMaxSize(),
          bodyContent = { ProductionBodyFixture(bodyListState) },
          commentsContent = { ProductionCommentsFixture(commentsListState) },
        )
      }
    }

    compose.onNodeWithTag("article-body-list").performScrollToIndex(9)
    compose.onNodeWithText("正文段落 9", substring = true, useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag("article-tab-comments").performClick()
    compose.onNodeWithTag("article-tab-comments").assertIsSelected()

    compose.onNodeWithTag("article-comments-list").performScrollToIndex(9)
    compose.onNodeWithText("测试评论 9", substring = true, useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag("article-tab-body").performClick()
    compose.onNodeWithTag("article-tab-body").assertIsSelected()
    compose.onNodeWithText("正文段落 9", substring = true, useUnmergedTree = true).assertIsDisplayed()

    compose.onNodeWithTag("article-tab-comments").performClick()
    compose.onNodeWithTag("article-tab-comments").assertIsSelected()
    compose.onNodeWithText("测试评论 9", substring = true, useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun singlePageContentDoesNotExposeAnUnsupportedCommentsTab() {
    compose.setContent {
      HeyReadTheme("light") {
        val pagerState = rememberPagerState(pageCount = { 1 })
        DetailPagerPages(
          state = pagerState,
          pageCount = 1,
          routeActive = true,
          motionEnabled = false,
          commentLabel = "评论",
          modifier = Modifier.fillMaxSize(),
          bodyContent = { androidx.compose.material3.Text("演示正文") },
          commentsContent = { androidx.compose.material3.Text("不应显示的评论") },
        )
      }
    }

    compose.onNodeWithTag("article-tab-comments").assertDoesNotExist()
    compose.onNodeWithText("演示正文").assertExists()
    compose.onNodeWithText("不应显示的评论").assertDoesNotExist()
  }

  @Test
  fun physicalDragTracksFingerAndMovesIndicatorBeforePageChanges() {
    compose.mainClock.autoAdvance = false
    var pagerState: androidx.compose.foundation.pager.PagerState? = null
    compose.setContent {
      HeyReadTheme("light") {
        val state = rememberPagerState(pageCount = { 2 })
        pagerState = state
        DetailPagerPages(
          state = state,
          pageCount = 2,
          routeActive = true,
          motionEnabled = true,
          commentLabel = "评论 · 12",
          modifier = Modifier.fillMaxSize(),
          bodyContent = { ProductionBodyFixture(rememberLazyListState()) },
          commentsContent = { ProductionCommentsFixture(rememberLazyListState()) },
        )
      }
    }
    compose.mainClock.advanceTimeByFrame()

    val bodyNode = pageNode("article-page-body")
    val bodyStart = nodeBounds(bodyNode)
    val pointerDown =
      bodyNode.layoutInfo.coordinates.localToScreen(
        Offset(bodyNode.size.width / 2f, bodyNode.size.height / 2f)
      )
    val indicatorStart = indicatorCenterX()
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    var pointerDownSent = false
    try {
      pointerDownSent = true
      inject(automation, downTime, MotionEvent.ACTION_DOWN, pointerDown)
      compose.mainClock.advanceTimeByFrame()

      val halfway = Offset(pointerDown.x - bodyStart.width * 0.32f, pointerDown.y)
      inject(automation, downTime, MotionEvent.ACTION_MOVE, halfway)
      compose.mainClock.advanceTimeByFrame()
      val bodyDuring = nodeBounds(pageNode("article-page-body"))
      val commentsDuring = nodeBounds(pageNode("article-page-comments"))
      var partialPage = -1
      var partialOffset = 0f
      compose.runOnIdle {
        partialPage = pagerState!!.currentPage
        partialOffset = pagerState!!.currentPageOffsetFraction
      }
      val indicatorDuring = indicatorCenterX()
      assertEquals("current page remains unsettled during a partial drag", 0, partialPage)
      assertTrue(
        "pager exposes a partial offset while the pointer is down",
        partialOffset > 0f && partialOffset < 0.5f,
      )
      assertTrue(
        "body page follows the held pointer",
        bodyDuring.center.x < bodyStart.center.x - 20f,
      )
      assertTrue(
        "adjacent comments page enters the viewport",
        commentsDuring.left < bodyStart.right - 20f,
      )
      assertEquals(
        "the pages remain adjacent while moving",
        bodyDuring.right,
        commentsDuring.left,
        2f,
      )
      assertTrue(
        "indicator follows toward comments before settling",
        indicatorDuring > indicatorStart + 2f,
      )

      // Reverse direction in the same physical press and verify that both pages follow back.
      val reverse = Offset(pointerDown.x - bodyStart.width * 0.12f, pointerDown.y)
      inject(automation, downTime, MotionEvent.ACTION_MOVE, reverse)
      compose.mainClock.advanceTimeByFrame()
      val bodyReversed = nodeBounds(pageNode("article-page-body"))
      val indicatorReversed = indicatorCenterX()
      assertTrue(
        "reversing the held drag moves the body back",
        bodyReversed.center.x > bodyDuring.center.x + 20f,
      )
      assertTrue("indicator reverses with the finger", indicatorReversed < indicatorDuring - 2f)

      val reverseAgain = Offset(pointerDown.x - bodyStart.width * 0.28f, pointerDown.y)
      inject(automation, downTime, MotionEvent.ACTION_MOVE, reverseAgain)
      compose.mainClock.advanceTimeByFrame()
      val bodyAgain = nodeBounds(pageNode("article-page-body"))
      val indicatorAgain = indicatorCenterX()
      assertTrue(
        "same held pointer can reverse repeatedly",
        bodyAgain.center.x < bodyReversed.center.x - 20f,
      )
      assertTrue("indicator follows the second reversal", indicatorAgain > indicatorReversed + 2f)
    } finally {
      if (pointerDownSent) {
        runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, pointerDown) }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
      }
      compose.mainClock.autoAdvance = true
    }
    compose.runOnIdle {
      assertEquals("cancel returns to the original page", 0, pagerState!!.currentPage)
    }
    val indicatorAfterCancel = indicatorCenterX()
    assertEquals("cancel restores the original indicator", indicatorStart, indicatorAfterCancel, 2f)
  }

  @Test
  fun tappingOriginalTabDuringTransitionCancelsThePendingTarget() {
    compose.mainClock.autoAdvance = false
    var pagerState: androidx.compose.foundation.pager.PagerState? = null
    compose.setContent {
      HeyReadTheme("light") {
        val state = rememberPagerState(pageCount = { 2 })
        pagerState = state
        DetailPagerPages(
          state = state,
          pageCount = 2,
          routeActive = true,
          motionEnabled = true,
          commentLabel = "评论 · 12",
          modifier = Modifier.fillMaxSize(),
          bodyContent = { ProductionBodyFixture(rememberLazyListState()) },
          commentsContent = { ProductionCommentsFixture(rememberLazyListState()) },
        )
      }
    }
    compose.mainClock.advanceTimeByFrame()
    try {
      tapTag("article-tab-comments")
      var pageAtInterruption = -1
      var offsetAtInterruption = 0f
      var scrollingAtInterruption = false
      var foundOriginalPageWindow = false
      for (frame in 0 until 8) {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
          pageAtInterruption = pagerState!!.currentPage
          offsetAtInterruption = pagerState!!.currentPageOffsetFraction
          scrollingAtInterruption = pagerState!!.isScrollInProgress
        }
        if (
          pageAtInterruption == 0 &&
            scrollingAtInterruption &&
            offsetAtInterruption > 0f &&
            offsetAtInterruption < 0.5f
        ) {
          foundOriginalPageWindow = true
          break
        }
      }
      assertTrue(
        "found the earliest in-flight window before currentPage changes",
        foundOriginalPageWindow,
      )
      assertEquals("current page remains original before midpoint", 0, pageAtInterruption)
      assertTrue("tab animation is still moving", scrollingAtInterruption)
      assertTrue(
        "tap occurs during the partial transition",
        offsetAtInterruption > 0f && offsetAtInterruption < 0.5f,
      )

      // Tapping the selected body tab while the target animation is in flight must cancel it.
      tapTag("article-tab-body")
      repeat(40) { compose.mainClock.advanceTimeByFrame() }
      compose.runOnIdle { assertEquals(0, pagerState!!.currentPage) }
    } finally {
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun incomingPageCanBeGrabbedAndReversedDuringTabAnimation() {
    compose.mainClock.autoAdvance = false
    val pager = showProductionPager()
    try {
      tapTag("article-tab-comments")
      var offsetBeforeGrab = 0f
      var foundPartialPage = false
      for (frame in 0 until 12) {
        compose.mainClock.advanceTimeByFrame()
        var currentPage = -1
        var scrolling = false
        compose.runOnIdle {
          currentPage = pager.currentPage
          offsetBeforeGrab = pager.currentPageOffsetFraction
          scrolling = pager.isScrollInProgress
        }
        if (currentPage == 0 && scrolling && offsetBeforeGrab in 0.25f..0.48f) {
          foundPartialPage = true
          break
        }
      }
      assertTrue("tab animation exposed a grabbable incoming page", foundPartialPage)

      val root = compose.onRoot().fetchSemanticsNode()
      val localStart =
        Offset(root.size.width * (1f - offsetBeforeGrab / 2f), root.size.height * 0.72f)
      val screenStart = root.layoutInfo.coordinates.localToScreen(localStart)
      val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
      val downTime = SystemClock.uptimeMillis()
      var downSent = false
      try {
        downSent = true
        inject(automation, downTime, MotionEvent.ACTION_DOWN, screenStart)
        val reverseDistance = root.size.width * offsetBeforeGrab * 0.4f
        val reversed = Offset(screenStart.x + reverseDistance, screenStart.y)
        inject(automation, downTime, MotionEvent.ACTION_MOVE, reversed)
        compose.mainClock.advanceTimeByFrame()
        val offsetAfterGrab = pager.currentPageOffsetFraction
        assertTrue(
          "grabbed incoming page follows reversal: before=$offsetBeforeGrab after=$offsetAfterGrab " +
            "downX=${screenStart.x} moveX=${reversed.x} rootWidth=${root.size.width}",
          offsetAfterGrab < offsetBeforeGrab - 0.04f,
        )
      } finally {
        if (downSent)
          runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, screenStart) }
      }
    } finally {
      repeat(40) { compose.mainClock.advanceTimeByFrame() }
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun commentsPageCanDragBackTowardBodyAndCancel() {
    compose.mainClock.autoAdvance = false
    val pager = showProductionPager(initialPage = 1)
    val commentsNode = pageNode("article-page-comments")
    val commentsStart = nodeBounds(commentsNode)
    val pointerDown =
      commentsNode.layoutInfo.coordinates.localToScreen(
        Offset(commentsNode.size.width / 2f, commentsNode.size.height / 2f)
      )
    val indicatorStart = indicatorCenterX()
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    var downSent = false
    try {
      downSent = true
      inject(automation, downTime, MotionEvent.ACTION_DOWN, pointerDown)
      val dragged = Offset(pointerDown.x + commentsStart.width * 0.32f, pointerDown.y)
      inject(automation, downTime, MotionEvent.ACTION_MOVE, dragged)
      compose.mainClock.advanceTimeByFrame()
      val commentsDuring = nodeBounds(pageNode("article-page-comments"))
      val bodyDuring = nodeBounds(pageNode("article-page-body"))
      val indicatorDuring = indicatorCenterX()
      assertEquals(1, pager.currentPage)
      assertTrue(
        "comments page follows rightward drag",
        commentsDuring.center.x > commentsStart.center.x + 20f,
      )
      assertTrue("indicator moves toward the body tab", indicatorDuring < indicatorStart - 2f)
    } finally {
      if (downSent)
        runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, pointerDown) }
      repeat(30) { compose.mainClock.advanceTimeByFrame() }
      compose.mainClock.autoAdvance = true
    }
    compose.runOnIdle { assertEquals("cancel returns to comments", 1, pager.currentPage) }
  }

  @Test
  fun disabledMotionStillTracksManualDrag() {
    compose.mainClock.autoAdvance = false
    showProductionPager(motionEnabled = false)
    val body = pageNode("article-page-body")
    val start = nodeBounds(body)
    val down =
      body.layoutInfo.coordinates.localToScreen(Offset(body.size.width / 2f, body.size.height / 2f))
    val indicatorStart = indicatorCenterX()
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    try {
      inject(automation, downTime, MotionEvent.ACTION_DOWN, down)
      val moved = Offset(down.x - start.width * 0.24f, down.y)
      inject(automation, downTime, MotionEvent.ACTION_MOVE, moved)
      compose.mainClock.advanceTimeByFrame()
      assertTrue(
        "disabled custom motion retains direct manual page movement",
        nodeBounds(pageNode("article-page-body")).center.x < start.center.x - 20f,
      )
      assertTrue(
        "disabled custom motion retains direct indicator movement",
        indicatorCenterX() > indicatorStart + 2f,
      )
    } finally {
      runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, down) }
      repeat(30) { compose.mainClock.advanceTimeByFrame() }
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun hiddenRouteBlocksPagerInputAndSettlesAtOriginalPage() {
    compose.mainClock.autoAdvance = false
    val hiddenPager = showProductionPager(routeActive = false)
    val root = compose.onRoot().fetchSemanticsNode()
    val hiddenDown =
      root.layoutInfo.coordinates.localToScreen(
        Offset(root.size.width / 2f, root.size.height * 0.72f)
      )
    val hiddenAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val hiddenDownTime = SystemClock.uptimeMillis()
    var hiddenDownSent = false
    try {
      hiddenDownSent = true
      inject(hiddenAutomation, hiddenDownTime, MotionEvent.ACTION_DOWN, hiddenDown)
      inject(
        hiddenAutomation,
        hiddenDownTime,
        MotionEvent.ACTION_MOVE,
        Offset(hiddenDown.x - 240f, hiddenDown.y),
      )
      inject(
        hiddenAutomation,
        hiddenDownTime,
        MotionEvent.ACTION_UP,
        Offset(hiddenDown.x - 240f, hiddenDown.y),
      )
      compose.mainClock.advanceTimeByFrame()
      repeat(30) { compose.mainClock.advanceTimeByFrame() }
      assertEquals("hidden route cannot change the pager page", 0, hiddenPager.currentPage)
      assertEquals(
        "hidden route keeps pager offset at zero",
        0f,
        hiddenPager.currentPageOffsetFraction,
        0.001f,
      )
      assertTrue("hidden route does not enter a scroll", !hiddenPager.isScrollInProgress)
    } finally {
      if (hiddenDownSent) {
        runCatching {
          inject(hiddenAutomation, hiddenDownTime, MotionEvent.ACTION_CANCEL, hiddenDown)
        }
      }
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun hiddenPaneBlocksPagerInput() {
    compose.mainClock.autoAdvance = false
    val hiddenPager = showProductionPager(paneActive = false)
    val root = compose.onRoot().fetchSemanticsNode()
    val hiddenDown =
      root.layoutInfo.coordinates.localToScreen(
        Offset(root.size.width / 2f, root.size.height * 0.72f)
      )
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    try {
      inject(automation, downTime, MotionEvent.ACTION_DOWN, hiddenDown)
      inject(
        automation,
        downTime,
        MotionEvent.ACTION_MOVE,
        Offset(hiddenDown.x - 240f, hiddenDown.y),
      )
      inject(automation, downTime, MotionEvent.ACTION_UP, Offset(hiddenDown.x - 240f, hiddenDown.y))
      compose.mainClock.advanceTimeByFrame()
      repeat(30) { compose.mainClock.advanceTimeByFrame() }
      assertEquals("hidden pane cannot change the pager page", 0, hiddenPager.currentPage)
      assertEquals(
        "hidden pane keeps pager offset at zero",
        0f,
        hiddenPager.currentPageOffsetFraction,
        0.001f,
      )
      assertTrue("hidden pane does not enter a scroll", !hiddenPager.isScrollInProgress)
    } finally {
      runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, hiddenDown) }
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun rtlIndicatorFollowsLogicalPageOrder() {
    compose.mainClock.autoAdvance = false
    showProductionPager(layoutDirection = LayoutDirection.Rtl)
    val body = pageNode("article-page-body")
    val start = nodeBounds(body)
    val down =
      body.layoutInfo.coordinates.localToScreen(Offset(body.size.width / 2f, body.size.height / 2f))
    val firstTabX =
      compose.onNodeWithTag("article-tab-body").fetchSemanticsNode().boundsInRoot.center.x
    val secondTabX =
      compose.onNodeWithTag("article-tab-comments").fetchSemanticsNode().boundsInRoot.center.x
    val indicatorStart = indicatorCenterX()
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    var downSent = false
    try {
      downSent = true
      inject(automation, downTime, MotionEvent.ACTION_DOWN, down)
      val moved = Offset(down.x + start.width * 0.32f, down.y)
      inject(automation, downTime, MotionEvent.ACTION_MOVE, moved)
      compose.mainClock.advanceTimeByFrame()
      val indicatorDuring = indicatorCenterX()
      assertTrue(
        "RTL indicator moves toward the logical comments tab",
        (indicatorDuring - indicatorStart) * (secondTabX - firstTabX) > 0f,
      )
    } finally {
      if (downSent) runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, down) }
      repeat(30) { compose.mainClock.advanceTimeByFrame() }
      compose.mainClock.autoAdvance = true
    }
  }

  private fun indicatorCenterX(): Float =
    compose
      .onNodeWithTag("article-detail-indicator", useUnmergedTree = true)
      .fetchSemanticsNode()
      .boundsInRoot
      .center
      .x

  private fun inject(automation: UiAutomation, downTime: Long, action: Int, position: Offset) {
    val event =
      MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    try {
      assertTrue(
        "physical pointer event reaches the Compose window",
        automation.injectInputEvent(event, true),
      )
    } finally {
      event.recycle()
    }
  }

  private fun tapTag(tag: String) {
    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
    val point =
      node.layoutInfo.coordinates.localToScreen(Offset(node.size.width / 2f, node.size.height / 2f))
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    var downSent = false
    try {
      downSent = true
      inject(automation, downTime, MotionEvent.ACTION_DOWN, point)
      inject(automation, downTime, MotionEvent.ACTION_UP, point)
    } finally {
      if (downSent) runCatching { inject(automation, downTime, MotionEvent.ACTION_CANCEL, point) }
    }
  }

  private fun pageNode(tag: String) =
    compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

  private fun nodeBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
    val topLeft = node.layoutInfo.coordinates.localToRoot(Offset.Zero)
    return Rect(topLeft.x, topLeft.y, topLeft.x + node.size.width, topLeft.y + node.size.height)
  }

  private fun showProductionPager(
    initialPage: Int = 0,
    routeActive: Boolean = true,
    motionEnabled: Boolean = true,
    paneActive: Boolean = true,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  ): androidx.compose.foundation.pager.PagerState {
    var state: androidx.compose.foundation.pager.PagerState? = null
    compose.setContent {
      CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalPaneContentActive provides paneActive,
      ) {
        HeyReadTheme("light") {
          val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 2 })
          state = pagerState
          DetailPagerPages(
            state = pagerState,
            pageCount = 2,
            routeActive = routeActive,
            motionEnabled = motionEnabled,
            commentLabel = "评论 · 12",
            modifier = Modifier.fillMaxSize(),
            bodyContent = { ProductionBodyFixture(rememberLazyListState()) },
            commentsContent = { ProductionCommentsFixture(rememberLazyListState()) },
          )
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    return requireNotNull(state)
  }

  @Composable
  private fun ProductionBodyFixture(state: androidx.compose.foundation.lazy.LazyListState) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("article-body-list")) {
      items((0..9).toList(), key = { "body-$it" }) { index ->
        ArticleBlockContent(
          block = ArticleBlock.Paragraph("正文段落 $index " + "可选择的生产正文文本。 ".repeat(5)),
          noImages = true,
          onOpenImage = {},
        )
      }
    }
  }

  @Composable
  private fun ProductionCommentsFixture(state: androidx.compose.foundation.lazy.LazyListState) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("article-comments-list")) {
      items((0..9).toList(), key = { "comment-$it" }) { index ->
        CommentThreadCard(
          comment =
            RemoteComment(
              id = "pager-comment-$index",
              author = "测试作者",
              text = "测试评论 $index " + "保留独立评论列表位置。 ".repeat(4),
              createdAt = 0L,
              likes = 0,
            ),
          replies = emptyList(),
          replyLoading = false,
          replyHasMore = false,
          replyError = null,
          noImages = true,
          onReply = { _, _ -> },
          onLoadReplies = {},
          onMoreReplies = {},
          onRetryReplies = {},
        )
      }
    }
  }
}
