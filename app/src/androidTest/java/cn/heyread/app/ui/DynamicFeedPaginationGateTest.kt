package cn.heyread.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.screens.DynamicFeedScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.UiState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicFeedPaginationGateTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var viewModelStore: ViewModelStore? = null

    @After
    fun clearViewModels() {
        compose.activityRule.scenario.onActivity { viewModelStore?.clear() }
        viewModelStore = null
    }

    @Test
    fun layoutAndPaneReflowSuppressAutopagingUntilRealListDrag() {
        val calls = CopyOnWriteArrayList<Pair<Int, Int>>()
        val viewModel =
            DynamicFeedViewModel(
                loadRecommendations = { offset, displayWidth ->
                    calls.add(offset to displayWidth)
                    if (offset == 0) StoryPage(recommendationStories(), hasMore = true)
                    else StoryPage(emptyList(), hasMore = false)
                }
            )
        val owner = ViewModelStore()
        viewModelStore = owner
        compose.activityRule.scenario.onActivity { owner.put("dynamic-pagination", viewModel) }

        val displayWidth = 390 - 56
        viewModel.ensureLoaded(displayWidth)
        compose.waitUntil(5_000) {
            !viewModel.state.value.recommendations.loading &&
                viewModel.state.value.recommendations.items.size == 30 &&
                viewModel.state.value.recommendations.hasMore
        }

        var ui by mutableStateOf(UiState(homeLayout = HomeLayout.A, noImages = true))
        var exposeHasMore by mutableStateOf(false)
        var paneResizeEpoch by mutableIntStateOf(0)
        var paneWidth by mutableIntStateOf(390)
        compose.setContent {
            val actualState by viewModel.state.collectAsState()
            val presentationState =
                if (exposeHasMore) actualState
                else
                    actualState.copy(
                        recommendations = actualState.recommendations.copy(hasMore = false)
                    )
            CompositionLocalProvider(LocalPaneResizeEpoch provides paneResizeEpoch) {
                HeyReadTheme("light", motionEnabled = false) {
                    Box(Modifier.width(paneWidth.dp).height(520.dp).testTag("dynamic-test-pane")) {
                        DynamicFeedScreen(
                            state = presentationState,
                            ui = ui,
                            viewModel = viewModel,
                            isCurrentPage = true,
                            onLogin = {},
                            onOpenStory = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Park the fully loaded first page at the bottom while the screen's presentation says there
        // is no next page. The real ViewModel already holds hasMore=true; expose it together with
        // the
        // layout/width/resize update so the test exercises the reflow pagination race.
        scrollRecommendationsToStory(29)
        compose.onNodeWithTag("home-card-A-dynamic-29").assertIsDisplayed()
        compose.runOnIdle {
            ui = ui.copy(homeLayout = HomeLayout.B)
            paneWidth = 360
            paneResizeEpoch = 1
            exposeHasMore = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("dynamic-recommend-list").assertIsDisplayed()
        scrollRecommendationsToStory(29)
        compose.onNodeWithTag("home-card-B-dynamic-29").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(
            "layout/width/pane reflow must not request offset 30",
            listOf(0 to displayWidth),
            calls.toList(),
        )

        compose.onNodeWithTag("dynamic-recommend-list").performTouchInput {
            swipeDown(startY = height * 0.72f, endY = height * 0.9f, durationMillis = 420)
        }
        scrollRecommendationsToStory(29)
        compose.waitUntil(5_000) { calls.any { (offset, _) -> offset == 30 } }
        assertEquals(listOf(0, 30), calls.map { it.first }.toList())
    }

    private fun scrollRecommendationsToStory(storyIndex: Int) {
        // The DynamicFeedScreen's actual LazyColumn has one source header before its story items.
        // Scrolling by index composes the target item before asking for its card semantics.
        compose
            .onNode(
                hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("dynamic-recommend-list")),
                useUnmergedTree = true,
            )
            .performScrollToIndex(storyIndex + 1)
    }

    private fun recommendationStories(): List<Story> =
        (0 until 30).map { index ->
            Story(
                id = "dynamic-$index",
                source = Source.HEYBOX,
                title = "合成动态 $index",
                excerpt = "布局门控测试使用的公开合成内容。",
                body = "",
                author = "合成作者",
                date = "2026-10-26",
            )
        }
}
