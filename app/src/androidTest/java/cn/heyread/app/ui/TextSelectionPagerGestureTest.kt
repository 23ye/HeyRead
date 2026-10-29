package cn.heyread.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.ui.components.CommentThreadCard
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.screens.DetailPagerPages
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSelectionPagerGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val articleText =
        "prefix START alpha beta gamma delta epsilon zeta eta theta END suffix"
    private val commentText = "prefix COMMENT alpha beta\nsecond gamma REPLYEND suffix"
    private val nestedCommentText = "prefix NESTED alpha beta\nsecond gamma CHILDEND suffix"

    @Test
    fun articleHandleCanExtendAcrossAutoWrappedLineAndCopyExactRangeWithoutPaging() {
        showPager(initialPage = 0)

        compose.onNodeWithTag("article-page-body").assertIsDisplayed()
        dragEndHandleAcrossLine(
            node = compose.onNodeWithTag("article-paragraph-text"),
            content = articleText,
            startToken = "START",
            endToken = "END",
            overshootThenShrink = true,
        )
        runCatching { saveTargetCacheScreenshot("v124-selection-auto.png") }
        val expected =
            articleText.substring(articleText.indexOf("START"), articleText.indexOf("END") + 3)
        val copied = copySelection()
        assertEquals("copied selection text: <$copied>", expected, copied)
        compose.onNodeWithTag("article-page-body").assertIsDisplayed()
        compose.onNodeWithTag("article-tab-body").assertIsSelected()

        val tailTopBeforeScroll =
            compose
                .onNodeWithTag("selection-body-tail-measured")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        compose.onNodeWithTag("article-page-body").performTouchInput {
            swipeUp(startY = height * 0.82f, endY = height * 0.34f, durationMillis = 420)
        }
        compose.waitForIdle()
        val tailTopAfterScroll =
            compose
                .onNodeWithTag("selection-body-tail-measured")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue(
            "the production body LazyColumn should scroll vertically",
            tailTopAfterScroll < tailTopBeforeScroll,
        )
        compose.onNodeWithTag("article-tab-body").assertIsSelected()

        swipePagerToOtherPage("article-page-body")
        compose.onNodeWithTag("article-page-comments").assertIsDisplayed()
    }

    @Test
    fun commentHandleCanExtendAcrossHardLineWithoutPagingOrReplying() {
        val replyTargets = mutableStateListOf<Pair<String, Boolean>>()
        showPager(initialPage = 1, replyTargets = replyTargets)

        compose.onNodeWithTag("article-page-comments").assertIsDisplayed()
        dragEndHandleAcrossLine(
            node = compose.onNodeWithText(commentText, useUnmergedTree = true),
            content = commentText,
            startToken = "COMMENT",
            endToken = "REPLYEND",
        )
        runCatching { saveTargetCacheScreenshot("v124-selection-main.png") }
        val expected =
            commentText.substring(
                commentText.indexOf("COMMENT"),
                commentText.indexOf("REPLYEND") + 8,
            )
        val copied = copySelection()
        assertEquals("copied selection text: <$copied>", expected, copied)
        compose.onNodeWithTag("article-page-comments").assertIsDisplayed()
        compose.onNodeWithTag("article-tab-comments").assertIsSelected()
        compose.runOnIdle {
            assertTrue("selecting text must not trigger reply", replyTargets.isEmpty())
        }

        compose.onNodeWithText(commentText, useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("synthetic-comment" to false), replyTargets.toList())
        }
    }

    @Test
    fun nestedCommentHandleCanExtendAcrossHardLineAndKeepItsReplyTarget() {
        val replyTargets = mutableStateListOf<Pair<String, Boolean>>()
        val nested =
            RemoteComment(
                id = "synthetic-child",
                author = "测试楼中楼作者",
                text = nestedCommentText,
                createdAt = 1,
                likes = 0,
                replyTo = "测试评论者",
                rootCommentId = "synthetic-comment",
            )
        showPager(initialPage = 1, replyTargets = replyTargets, nestedReplies = listOf(nested))

        dragEndHandleAcrossLine(
            node = compose.onNodeWithText(nestedCommentText, useUnmergedTree = true),
            content = nestedCommentText,
            startToken = "NESTED",
            endToken = "CHILDEND",
        )
        runCatching { saveTargetCacheScreenshot("v124-selection-nested.png") }
        val expected =
            nestedCommentText.substring(
                nestedCommentText.indexOf("NESTED"),
                nestedCommentText.indexOf("CHILDEND") + 8,
            )
        val copied = copySelection()
        assertEquals("copied selection text: <$copied>", expected, copied)
        compose.onNodeWithTag("article-page-comments").assertIsDisplayed()
        compose.onNodeWithTag("article-tab-comments").assertIsSelected()
        compose.runOnIdle {
            assertTrue("selecting nested text must not trigger reply", replyTargets.isEmpty())
        }

        compose.onNodeWithText(nestedCommentText, useUnmergedTree = true).performTouchInput {
            click()
        }
        compose.runOnIdle { assertEquals(listOf("synthetic-child" to true), replyTargets.toList()) }
    }

    private fun showPager(
        initialPage: Int,
        replyTargets: MutableList<Pair<String, Boolean>> = mutableStateListOf(),
        nestedReplies: List<RemoteComment> = emptyList(),
    ) {
        compose.setContent {
            val state = rememberPagerState(initialPage = initialPage, pageCount = { 2 })
            HeyReadTheme("light") {
                DetailPagerPages(
                    state = state,
                    pageCount = 2,
                    routeActive = true,
                    motionEnabled = true,
                    commentLabel = "评论",
                    modifier = Modifier.fillMaxSize().testTag("selection-pager"),
                    bodyContent = {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "selection-body-paragraph") {
                                Column(Modifier.width(180.dp)) {
                                    ArticleBlockContent(
                                        block = ArticleBlock.Paragraph(articleText),
                                        noImages = true,
                                        onOpenImage = {},
                                    )
                                }
                            }
                            items(12, key = { "selection-body-tail-$it" }) { index ->
                                Text(
                                    "合成后续段落 $index。正文详情使用可滚动列表承载。".repeat(2),
                                    modifier =
                                        when (index) {
                                            4 -> Modifier.testTag("selection-body-tail-measured")
                                            11 -> Modifier.testTag("selection-body-tail")
                                            else -> Modifier
                                        },
                                )
                            }
                        }
                    },
                    commentsContent = {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "selection-comment-root") {
                                CommentThreadCard(
                                    comment =
                                        RemoteComment(
                                            id = "synthetic-comment",
                                            author = "测试评论者",
                                            text = commentText,
                                            createdAt = 0,
                                            likes = 0,
                                        ),
                                    replies = nestedReplies,
                                    replyLoading = false,
                                    replyHasMore = false,
                                    replyError = null,
                                    noImages = true,
                                    onReply = { comment, isNested ->
                                        replyTargets.add(comment.id to isNested)
                                    },
                                    onLoadReplies = {},
                                    onMoreReplies = {},
                                    onRetryReplies = {},
                                )
                            }
                            items(12, key = { "selection-comment-tail-$it" }) { index ->
                                CommentThreadCard(
                                    comment =
                                        RemoteComment(
                                            id = "synthetic-filler-$index",
                                            author = "合成评论者",
                                            text = "后续合成评论 $index。用于覆盖真实评论列表的纵向滚动。",
                                            createdAt = index.toLong(),
                                            likes = 0,
                                        ),
                                    replies = emptyList(),
                                    replyLoading = false,
                                    replyHasMore = false,
                                    replyError = null,
                                    noImages = true,
                                    onReply = { comment, isNested ->
                                        replyTargets.add(comment.id to isNested)
                                    },
                                    onLoadReplies = {},
                                    onMoreReplies = {},
                                    onRetryReplies = {},
                                )
                            }
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun dragEndHandleAcrossLine(
        node: SemanticsNodeInteraction,
        content: String,
        startToken: String,
        endToken: String,
        overshootThenShrink: Boolean = false,
    ) {
        val layout = textLayout(node)
        val start = content.indexOf(startToken)
        val end = content.indexOf(endToken) + endToken.length
        assertTrue(start >= 0 && end > start)
        assertTrue(
            "selection endpoints must lie on distinct rendered lines",
            layout.getLineForOffset(start) != layout.getLineForOffset(end),
        )

        node.performTouchInput {
            longClick(layout.getBoundingBox(start + startToken.length / 2).center)
        }
        compose.waitForIdle()

        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val density = compose.density
        val startCaret = layout.getCursorRect(start + startToken.length)
        val endCaret = layout.getCursorRect(end)
        val handleOffsetPx = with(density) { 18.dp.toPx() }
        // GetTextLayoutResult is local to this Text; the paragraph's trailing bottom padding keeps
        // the same top-left origin, and LayoutCoordinates handles Compose/window/system-bar
        // offsets.
        val from =
            coordinates.localToScreen(Offset(startCaret.left, startCaret.bottom + handleOffsetPx))
        val lastCharacter = layout.getBoundingBox(end - 1)
        val endInsideLastCharacterPx = with(density) { 2.dp.toPx() }
        val to =
            coordinates.localToScreen(
                Offset(
                    minOf(endCaret.left, lastCharacter.right) - endInsideLastCharacterPx,
                    endCaret.bottom + handleOffsetPx,
                )
            )
        val beyondEnd =
            if (overshootThenShrink) {
                assertEquals(
                    "the correction target should remain on the final text line",
                    layout.getLineForOffset(end),
                    layout.getLineForOffset(content.length),
                )
                val suffixCaret = layout.getCursorRect(content.length)
                coordinates.localToScreen(
                    Offset(suffixCaret.left, suffixCaret.bottom + handleOffsetPx)
                )
            } else {
                null
            }
        injectDrag(from, to, shrinkFrom = beyondEnd)
        compose.waitForIdle()
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("target the actual production Text layout", 1, results.size)
        return results.single()
    }

    private fun injectDrag(from: Offset, to: Offset, shrinkFrom: Offset? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val downTime = SystemClock.uptimeMillis()
        inject(automation, downTime, MotionEvent.ACTION_DOWN, from)
        fun moveAlong(start: Offset, end: Offset) {
            val steps = 12
            repeat(steps) { step ->
                val fraction = (step + 1).toFloat() / steps
                val point =
                    Offset(
                        start.x + (end.x - start.x) * fraction,
                        start.y + (end.y - start.y) * fraction,
                    )
                inject(automation, downTime, MotionEvent.ACTION_MOVE, point)
                SystemClock.sleep(18)
            }
        }
        shrinkFrom?.let { moveAlong(from, it) }
        moveAlong(shrinkFrom ?: from, to)
        inject(automation, downTime, MotionEvent.ACTION_UP, to)
        instrumentation.waitForIdleSync()
    }

    private fun inject(automation: UiAutomation, downTime: Long, action: Int, position: Offset) {
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                position.x,
                position.y,
                0,
            )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(
                "physical pointer event must reach the Android window",
                automation.injectInputEvent(event, true),
            )
        } finally {
            event.recycle()
        }
    }

    private fun swipePagerToOtherPage(currentPageTag: String) {
        compose.onNodeWithTag(currentPageTag).performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    private fun copySelection(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val oldServiceInfo = automation.serviceInfo
        automation.serviceInfo =
            AccessibilityServiceInfo().apply {
                eventTypes = oldServiceInfo.eventTypes
                feedbackType = oldServiceInfo.feedbackType
                notificationTimeout = oldServiceInfo.notificationTimeout
                packageNames = oldServiceInfo.packageNames
                flags =
                    oldServiceInfo.flags or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        try {
            instrumentation.waitForIdleSync()
            val copyNode =
                automation.windows
                    .asSequence()
                    .flatMap { window -> window.root?.let(::walkAccessibilityNodes).orEmpty() }
                    .firstOrNull { it.text?.toString() in setOf("Copy", "复制") }
            assertNotNull("Android's native selection toolbar must expose Copy", copyNode)
            assertTrue(
                "the native Copy action must be clickable",
                clickAccessibilityAction(copyNode!!),
            )
            instrumentation.waitForIdleSync()
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip
            return clip?.getItemAt(0)?.coerceToText(compose.activity)?.toString().orEmpty()
        } finally {
            automation.serviceInfo = oldServiceInfo
        }
    }

    private fun clickAccessibilityAction(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        var current: android.view.accessibility.AccessibilityNodeInfo? = node
        while (current != null) {
            if (
                current.isClickable &&
                    current.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                    )
            )
                return true
            current = current.parent
        }
        return false
    }

    private fun walkAccessibilityNodes(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): Sequence<android.view.accessibility.AccessibilityNodeInfo> = sequence {
        yield(node)
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child -> yieldAll(walkAccessibilityNodes(child)) }
        }
    }
}
