package cn.heyread.app.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.FeedTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletParallelNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var testViewModelStore: ViewModelStore? = null

    @After
    fun clearTestViewModel() {
        compose.activityRule.scenario.onActivity { testViewModelStore?.clear() }
        testViewModelStore = null
    }

    @Test
    fun productionNavigationStartsEmptyOpensReplacesDeduplicatesAndRootTabClears() {
        val viewModel = prepareDemoViewModel()
        compose.setContent {
            Box(Modifier.width(1280.dp).fillMaxHeight()) { HeyReadNavigation(viewModel) }
        }
        awaitTag("adaptive-pane-layout")
        compose.onNodeWithTag("adaptive-pane-layout").assertExists()
        val layoutWidth =
            compose.onNodeWithTag("adaptive-pane-layout").getBoundsInRoot().let {
                it.right.value - it.left.value
            }
        assertTrue("the test exercises a wide tablet content area", layoutWidth >= 1200f)
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()
        compose.onNodeWithTag("detail-cover-demo-1").assertDoesNotExist()

        val firstCard = cardTag(viewModel, "demo-1")
        val secondCard = cardTag(viewModel, "demo-2")
        compose.onNodeWithTag(firstCard).performScrollTo().performClick()
        awaitTag("detail-cover-demo-1")
        compose.onNodeWithTag("detail-cover-demo-1").assertExists()
        compose.onNodeWithTag(firstCard).assertExists()

        compose.onNodeWithTag(secondCard).performScrollTo().performClick()
        awaitTag("detail-cover-demo-2")
        compose.onNodeWithTag("detail-cover-demo-2").assertExists()
        compose.onNodeWithTag("detail-cover-demo-1").assertDoesNotExist()
        compose.onNodeWithTag(secondCard).performClick()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()
        compose.onNodeWithTag("detail-cover-demo-2").assertDoesNotExist()

        compose.onNodeWithTag(firstCard).performScrollTo().performClick()
        awaitTag("detail-cover-demo-1")
        compose.onNodeWithTag("root-tab-mine").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-title").assertTextEquals("我的")
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()
        compose.onNodeWithTag("detail-cover-demo-1").assertDoesNotExist()
    }

    @Test
    fun productionNavigationKeepsOpenEntryAndListScrollAcrossNarrowWidthRoundTrip() {
        val viewModel = prepareDemoViewModel()
        val width = mutableFloatStateOf(1280f)
        compose.setContent {
            Box(Modifier.width(width.floatValue.dp).fillMaxHeight()) {
                HeyReadNavigation(viewModel)
            }
        }
        awaitTag("adaptive-pane-layout")
        val initialWideContentWidth =
            compose.onNodeWithTag("adaptive-pane-layout").getBoundsInRoot().let {
                it.right.value - it.left.value
            }
        assertTrue(
            "the test exercises a wide tablet content area",
            initialWideContentWidth >= 1200f,
        )

        val secondCard = cardTag(viewModel, "demo-2")
        compose.onNodeWithTag(secondCard).performScrollTo().assertIsDisplayed()
        val listCardTopBeforeOpen = compose.onNodeWithTag(secondCard).getBoundsInRoot().top.value
        compose.onNodeWithTag(secondCard).performClick()
        awaitTag("detail-cover-demo-2")
        compose.onNodeWithTag("detail-cover-demo-2").assertExists()

        compose.runOnIdle { width.floatValue = 411f }
        compose.waitForIdle()
        val narrowContentWidth =
            compose.onNodeWithTag("adaptive-pane-layout").getBoundsInRoot().let {
                it.right.value - it.left.value
            }
        assertTrue(
            "the simulated phone width crosses below the split-pane breakpoint",
            narrowContentWidth < SPLIT_PANE_BREAKPOINT_DP,
        )
        compose.onNodeWithTag("detail-cover-demo-2").assertExists()
        compose.onNodeWithTag("split-pane-divider").assertDoesNotExist()
        compose.onNodeWithTag(secondCard).assertDoesNotExist()

        compose.runOnIdle { width.floatValue = 1280f }
        compose.waitForIdle()
        compose.onNodeWithTag("detail-cover-demo-2").assertExists()
        compose.onNodeWithTag(secondCard).assertIsDisplayed()
        val returnedWideContentWidth =
            compose.onNodeWithTag("adaptive-pane-layout").getBoundsInRoot().let {
                it.right.value - it.left.value
            }
        assertEquals(
            "wide usable width returns to its original value",
            initialWideContentWidth,
            returnedWideContentWidth,
            2f,
        )
        val listCardTopAfterReturn = compose.onNodeWithTag(secondCard).getBoundsInRoot().top.value
        assertEquals(
            "the left list restores its scroll position",
            listCardTopBeforeOpen,
            listCardTopAfterReturn,
            2f,
        )
    }

    private fun prepareDemoViewModel(): AppViewModel {
        val instrumentationContext: Context =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val owner = ViewModelStore()
        testViewModelStore = owner
        val viewModel = AppViewModel(LibraryStore(instrumentationContext))
        owner.put("tablet-parallel-navigation", viewModel)
        viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
        compose.waitUntil(10_000) {
            val feed = viewModel.state.value.discoverFeed
            feed.source == Source.DEMO && feed.items.size == 3 && !feed.loading
        }
        viewModel.setMotionEnabled(false)
        viewModel.setHomeLayout(HomeLayout.A)
        viewModel.settings(noImages = true)
        compose.waitUntil(10_000) {
            val state = viewModel.state.value
            !state.motionEnabled && state.homeLayout == HomeLayout.A && state.noImages
        }
        assertEquals(Source.DEMO, viewModel.state.value.discoverFeed.source)
        return viewModel
    }

    private fun cardTag(viewModel: AppViewModel, storyId: String): String =
        "home-card-${viewModel.state.value.homeLayout.name}-$storyId"

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
