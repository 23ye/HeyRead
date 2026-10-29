package cn.heyread.app.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletSettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val temporaryFolder = TemporaryFolder()

    private var testViewModelStore: ViewModelStore? = null
    private var testDataStoreScope: CoroutineScope? = null

    @After
    fun clearViewModel() {
        compose.activityRule.scenario.onActivity { testViewModelStore?.clear() }
        testViewModelStore = null
        testDataStoreScope?.cancel()
        testDataStoreScope = null
    }

    @Test
    fun wideMineKeepsOverviewBesideOneReplaceableSettingsDetailAndBackReturnsToOverview() {
        val viewModel = prepareDemoViewModel()
        compose.setContent {
            Box(Modifier.width(1280.dp).fillMaxHeight()) { HeyReadNavigation(viewModel) }
        }
        awaitTag("adaptive-pane-layout")
        compose.onNodeWithTag("root-tab-mine").performClick()
        awaitTag("mine-settings-root")
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()

        clickOverviewCategory("settings-category-appearance")
        awaitTag("settings-detail-appearance")
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.onNodeWithTag("settings-detail-appearance").assertIsDisplayed()

        clickOverviewCategory("settings-category-data")
        awaitTag("settings-detail-data")
        compose.waitForIdle()
        compose.onNodeWithTag("settings-detail-appearance").assertDoesNotExist()
        compose.onNodeWithTag("settings-detail-data").assertExists()

        clickOverviewCategory("settings-category-data")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.onNodeWithTag("settings-detail-data").assertDoesNotExist()
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()
    }

    @Test
    fun settingsRouteAndPerCategoryAdvancedGatewayDraftSurviveWidthChangesAndCategorySwitches() {
        val viewModel = prepareDemoViewModel()
        val width = mutableFloatStateOf(1280f)
        compose.setContent {
            Box(Modifier.width(width.floatValue.dp).fillMaxHeight()) {
                HeyReadNavigation(viewModel)
            }
        }
        awaitTag("adaptive-pane-layout")
        compose.onNodeWithTag("root-tab-mine").performClick()
        awaitTag("mine-settings-root")
        clickOverviewCategory("settings-category-data")
        awaitTag("settings-detail-data")
        compose.onNodeWithText("高级数据源设置").performScrollTo().performClick()
        val gateway = compose.onNodeWithTag("settings-gateway").performScrollTo()
        gateway.performTextInput("https://draft.example")
        clickOverviewCategory("settings-category-appearance")
        awaitTag("settings-detail-appearance")
        clickOverviewCategory("settings-category-data")
        awaitTag("settings-detail-data")
        assertGatewayDraft("https://draft.example")
        compose.onNodeWithText("收起高级数据源设置").performScrollTo().assertExists()

        compose.runOnIdle { width.floatValue = 411f }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-detail-data").assertIsDisplayed()
        compose.onNodeWithTag("mine-settings-root").assertDoesNotExist()

        compose.runOnIdle { width.floatValue = 1280f }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-detail-data").assertIsDisplayed()
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.onNodeWithTag("settings-gateway").performScrollTo().assertExists()
        compose.onAllNodesWithTag("settings-category-appearance")[0].assertExists()
        compose.onAllNodesWithTag("settings-category-data")[0].assertExists()
    }

    @Test
    fun rapidAnimatedCategoryReentryKeepsSaveableDraftAndReturnsToOverview() {
        val viewModel = prepareDemoViewModel(motionEnabled = true)
        compose.setContent {
            Box(Modifier.width(1280.dp).fillMaxHeight()) { HeyReadNavigation(viewModel) }
        }
        awaitTag("adaptive-pane-layout")
        compose.onNodeWithTag("root-tab-mine").performClick()
        awaitTag("mine-settings-root")
        clickOverviewCategory("settings-category-data")
        awaitTag("settings-detail-data")
        compose.onNodeWithText("高级数据源设置").performScrollTo().performClick()
        compose
            .onNodeWithTag("settings-gateway")
            .performScrollTo()
            .performTextInput("https://rapid.example")
        clickOverviewCategory("settings-category-appearance")
        awaitTag("settings-detail-appearance")

        compose.mainClock.autoAdvance = false
        clickOverviewCategory("settings-category-data")
        clickOverviewCategory("settings-category-appearance")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("settings-detail-appearance").assertIsDisplayed()
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        clickOverviewCategory("settings-category-data")
        awaitTag("settings-detail-data")
        assertGatewayDraft("https://rapid.example")
        compose.onNodeWithText("收起高级数据源设置").performScrollTo().assertExists()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("mine-settings-root").assertIsDisplayed()
        compose.onNodeWithText("选择左侧内容").assertIsDisplayed()
        compose.onNodeWithTag("settings-detail-data").assertDoesNotExist()
    }

    private fun prepareDemoViewModel(motionEnabled: Boolean = false): AppViewModel {
        val instrumentationBase: Context = InstrumentationRegistry.getInstrumentation().context
        assertNotEquals(
            "the test store must use the instrumentation package preferences",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
            instrumentationBase.packageName,
        )
        val isolatedRoot = temporaryFolder.newFolder("tablet-settings-context")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        testDataStoreScope = dataStoreScope
        val injectedPreferencesStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                java.io.File(isolatedRoot, "settings.preferences_pb")
            }
        val instrumentationContext =
            object : ContextWrapper(instrumentationBase) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): java.io.File =
                    java.io.File(isolatedRoot, "files").apply { mkdirs() }

                override fun getCacheDir(): java.io.File =
                    java.io.File(isolatedRoot, "cache").apply { mkdirs() }
            }
        val owner = ViewModelStore()
        testViewModelStore = owner
        lateinit var viewModel: AppViewModel
        compose.activityRule.scenario.onActivity {
            viewModel = AppViewModel(LibraryStore(instrumentationContext, injectedPreferencesStore))
            owner.put("tablet-settings-navigation", viewModel)
            viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
        }
        compose.waitUntil(10_000) {
            val state = viewModel.state.value
            state.discoverFeed.source == Source.DEMO &&
                state.discoverFeed.items.size == 3 &&
                !state.discoverFeed.loading
        }
        assertTrue(viewModel.state.value.discoverFeed.items.isNotEmpty())
        compose.activityRule.scenario.onActivity { viewModel.setMotionEnabled(motionEnabled) }
        compose.waitUntil(10_000) { viewModel.state.value.motionEnabled == motionEnabled }
        return viewModel
    }

    private fun assertGatewayDraft(expected: String) {
        val editableText =
            compose
                .onNodeWithTag("settings-gateway")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
        assertEquals(expected, editableText.text)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun clickOverviewCategory(tag: String) {
        compose.onAllNodesWithTag(tag)[0].performScrollTo().performClick()
    }
}
