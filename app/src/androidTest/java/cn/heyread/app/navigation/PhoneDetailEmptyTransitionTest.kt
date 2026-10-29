package cn.heyread.app.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.Source
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.FeedTab
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneDetailEmptyTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val temporaryFolder = TemporaryFolder()

    private var testViewModelStore: ViewModelStore? = null
    private var testDataStoreScope: CoroutineScope? = null

    @After
    fun clearTestState() {
        compose.activityRule.scenario.onActivity { testViewModelStore?.clear() }
        testViewModelStore = null
        testDataStoreScope?.cancel()
        testDataStoreScope = null
    }

    @Test
    fun phoneStoryEnterBackQuickReopenAndSettingsNeverShowTabletEmptyPrompt() {
        val viewModel = prepareDemoViewModel()
        compose.setContent {
            Box(Modifier.width(410.dp).fillMaxHeight()) { HeyReadNavigation(viewModel) }
        }
        awaitTag("adaptive-pane-layout")
        compose.mainClock.autoAdvance = false
        advanceFrames(4)
        val layoutWidth =
            compose.onNodeWithTag("adaptive-pane-layout").fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            "this case must stay below the split-pane breakpoint",
            layoutWidth < 840f * compose.density.density,
        )
        assertNoVisibleEmptyPrompt()

        val card = "home-card-${viewModel.state.value.homeLayout.name}-demo-1"
        compose.onNodeWithTag(card).performScrollTo().performClick()
        assertNoVisibleEmptyPrompt()
        val coverStartX = advanceUntilCoverIsPlaced()
        advanceFramesCheckingPrompt(7)
        val coverMidX =
            compose.onNodeWithTag("detail-cover-demo-1").fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            "the production detail page must move during its real transition",
            kotlin.math.abs(coverStartX - coverMidX) > 1f,
        )

        // Interrupt the first navigation, return to the same card, and open it again promptly.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advanceFramesCheckingPrompt(34)
        compose.onNodeWithTag(card).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(card).performClick()
        advanceFramesCheckingPrompt(36)
        compose.onNodeWithTag("detail-cover-demo-1").assertExists()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advanceFramesCheckingPrompt(36)
        compose.onNodeWithTag(card).assertIsDisplayed()

        compose.onNodeWithTag("root-tab-mine").performClick()
        advanceFramesCheckingPrompt(36)
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.onNodeWithTag("settings-category-appearance").performScrollTo().performClick()
        advanceFramesCheckingPrompt(36)
        compose.onNodeWithTag("settings-detail-appearance").assertIsDisplayed()
    }

    @Test
    fun tabletEmptyPromptSurvivesReturnAndOpenArticleAcrossWideNarrowWidths() {
        val viewModel = prepareDemoViewModel()
        val width = mutableFloatStateOf(1280f)
        compose.setContent {
            Box(Modifier.width(width.floatValue.dp).fillMaxHeight()) {
                HeyReadNavigation(viewModel)
            }
        }
        awaitTag("adaptive-pane-layout")
        advanceFrames(34)
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()

        val card = "home-card-${viewModel.state.value.homeLayout.name}-demo-1"
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(card).performScrollTo().performClick()
        advanceFramesCheckingPrompt(36)
        compose.onNodeWithTag("detail-cover-demo-1").assertExists()

        compose.runOnIdle { width.floatValue = 410f }
        advanceFramesCheckingPrompt(3)
        compose.onNodeWithTag("detail-cover-demo-1").assertExists()
        assertNoPromptNodes()

        compose.runOnIdle { width.floatValue = 1280f }
        advanceFramesCheckingPrompt(3)
        compose.onNodeWithTag("detail-cover-demo-1").assertExists()
        assertNoPromptNodes()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advanceFrames(36)
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()
        compose.onNodeWithTag("detail-cover-demo-1").assertDoesNotExist()
    }

    private fun prepareDemoViewModel(): AppViewModel {
        val instrumentationBase: Context = InstrumentationRegistry.getInstrumentation().context
        val isolatedRoot = temporaryFolder.newFolder("detail-empty-test-context")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        testDataStoreScope = dataStoreScope
        val injectedPreferencesStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                File(isolatedRoot, "settings.preferences_pb")
            }
        val instrumentationContext =
            object : ContextWrapper(instrumentationBase) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }

                override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
            }
        val store =
            LibraryStore(
                instrumentationContext,
                File(isolatedRoot, "reading-fonts"),
                injectedPreferencesStore,
            )
        runBlocking {
            store.setMotionEnabled(true)
            store.setNoImages(true)
        }

        val owner = ViewModelStore()
        testViewModelStore = owner
        lateinit var viewModel: AppViewModel
        compose.activityRule.scenario.onActivity {
            viewModel = AppViewModel(store)
            owner.put("phone-detail-empty-transition", viewModel)
            viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
        }
        compose.waitUntil(10_000) {
            val state = viewModel.state.value
            state.motionEnabled &&
                state.noImages &&
                state.discoverFeed.source == Source.DEMO &&
                state.discoverFeed.items.size == 3 &&
                !state.discoverFeed.loading
        }
        assertEquals(Source.DEMO, viewModel.state.value.discoverFeed.source)
        return viewModel
    }

    private fun advanceFramesCheckingPrompt(frameCount: Int) {
        repeat(frameCount) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertNoVisibleEmptyPrompt()
        }
    }

    private fun advanceUntilCoverIsPlaced(): Float {
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertNoVisibleEmptyPrompt()
            val cover =
                compose
                    .onAllNodesWithTag("detail-cover-demo-1")
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.boundsInRoot
            if (cover != null && cover.width > 1f && cover.height > 1f) return cover.left
        }
        throw AssertionError("the demo article cover did not enter the composed detail page")
    }

    private fun advanceFrames(frameCount: Int) {
        repeat(frameCount) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun assertNoVisibleEmptyPrompt() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val promptNodes = compose.onAllNodesWithText("选择左侧内容").fetchSemanticsNodes()
        val visiblePrompt =
            promptNodes.any { node ->
                val bounds = node.boundsInRoot
                val left = maxOf(root.left, bounds.left)
                val top = maxOf(root.top, bounds.top)
                val right = minOf(root.right, bounds.right)
                val bottom = minOf(root.bottom, bounds.bottom)
                right - left > 0.5f && bottom - top > 0.5f
            }
        assertTrue(
            "the tablet-only empty prompt became visible during a transition frame",
            !visiblePrompt,
        )
    }

    private fun assertNoPromptNodes() {
        assertEquals(0, compose.onAllNodesWithText("选择左侧内容").fetchSemanticsNodes().size)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
