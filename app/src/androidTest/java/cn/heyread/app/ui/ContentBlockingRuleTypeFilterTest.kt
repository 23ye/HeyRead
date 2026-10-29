package cn.heyread.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsCategoryScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingRuleTypeFilterTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var fixture: Fixture? = null

  @After
  fun clearIsolatedViewModelAndStore() {
    fixture?.let { current ->
      compose.activityRule.scenario.onActivity { current.owner.clear() }
      current.storeScope.cancel()
    }
    fixture = null
  }

  @Test
  fun eachKindShowsOnlyItsRulesAndSwitchingKindsPreservesTheSavedConfig() {
    val current = createFixture()
    showSettings(current)
    awaitReady(current)

    compose
      .onNodeWithTag("content-blocking-keyword-input")
      .performScrollTo()
      .performTextInput("keyword-rule")
    compose.onNodeWithTag("content-blocking-add").performScrollTo().performClick()
    awaitRuleCount(current, 1)

    addIdentity(current, ContentBlockKind.AUTHOR, "1001", "author-rule")
    addIdentity(current, ContentBlockKind.SECTION, "2002", "section-rule")
    addIdentity(current, ContentBlockKind.TOPIC, "3003", "topic-rule")

    val originalConfig = current.viewModel.value.state.value.contentBlocking
    assertEquals(4, originalConfig.rules.size)
    val configKey = stringPreferencesKey("content_blocking_config")
    val originalBytes = runBlocking { current.dataStore.data.first()[configKey] }

    ContentBlockKind.entries.forEach { kind ->
      compose
        .onNodeWithTag("content-blocking-kind-${kind.name.lowercase()}")
        .performScrollTo()
        .performClick()
      val expected = originalConfig.rules.single { it.kind == kind }
      compose
        .onNodeWithTag("content-blocking-rule-count")
        .performScrollTo()
        .assertIsDisplayed()
      compose
        .onNodeWithText("${kind.label}规则（1）")
        .assertIsDisplayed()
      compose.onNodeWithText("清空全部（4）").performScrollTo().assertIsDisplayed()
      compose
        .onNodeWithTag("content-blocking-rule-${expected.id}")
        .performScrollTo()
        .assertIsDisplayed()
      originalConfig.rules.filter { it.kind != kind }.forEach { hidden ->
        compose.onNodeWithTag("content-blocking-rule-${hidden.id}").assertDoesNotExist()
      }
      assertEquals(originalConfig, current.viewModel.value.state.value.contentBlocking)
    }
    assertEquals(originalBytes, runBlocking { current.dataStore.data.first()[configKey] })

    val topicRule = originalConfig.rules.single { it.kind == ContentBlockKind.TOPIC }
    compose
      .onNodeWithTag("content-blocking-remove-${topicRule.id}")
      .performScrollTo()
      .performClick()
    compose.onNodeWithTag("motion-alert-confirm").performClick()
    awaitRuleCount(current, 3)
    compose.onNodeWithTag("content-blocking-kind-topic").performScrollTo().performClick()
    compose.onNodeWithText("话题规则（0）").performScrollTo().assertIsDisplayed()
    compose
      .onNodeWithText("当前没有话题规则（0）。切换类型不会修改已保存规则。")
      .performScrollTo()
      .assertIsDisplayed()
    assertTrue(
      current.viewModel.value.state.value.contentBlocking.rules.all {
        it.kind != ContentBlockKind.TOPIC
      }
    )
  }

  private fun addIdentity(
      current: Fixture,
      kind: ContentBlockKind,
      id: String,
      label: String,
  ) {
    compose
      .onNodeWithTag("content-blocking-kind-${kind.name.lowercase()}")
      .performScrollTo()
      .performClick()
    val idInput = compose.onNodeWithTag("content-blocking-id-input").performScrollTo()
    idInput.performTextClearance()
    idInput.performTextInput(id)
    val labelInput = compose.onNodeWithTag("content-blocking-label-input").performScrollTo()
    labelInput.performTextClearance()
    labelInput.performTextInput(label)
    val expectedCount = current.viewModel.value.state.value.contentBlocking.rules.size + 1
    compose.onNodeWithTag("content-blocking-add").performScrollTo().performClick()
    compose.onNodeWithTag("content-blocking-link-confirm").performScrollTo().performClick()
    awaitRuleCount(current, expectedCount)
  }

  private fun showSettings(current: Fixture) {
    compose.setContent {
      val viewModel = current.viewModel.value
      val ui by viewModel.state.collectAsState()
      HeyReadTheme("light", motionEnabled = false) {
        SettingsCategoryScreen(
          category = SettingsCategory.ContentBlocking,
          ui = ui,
          gatewayValue = "",
          advanced = false,
          onGatewayChange = {},
          onAdvancedChange = {},
          onThemeChange = {},
          onHomeLayoutChange = {},
          onAccentChange = {},
          onMotionChange = {},
          onNoImagesChange = {},
          onGatewaySave = {},
          onClearCache = {},
          onOpenStory = {},
          onClearHistory = {},
          onBack = {},
          isCurrentPage = true,
          scrollState = rememberScrollState(),
          onContentBlockingAction = viewModel::onContentBlockingAction,
        )
      }
    }
  }

  private fun createFixture(): Fixture {
    val isolatedRoot = temporaryFolder.newFolder("content-blocking-filter")
    val base = InstrumentationRegistry.getInstrumentation().context
    val context =
      object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
      }
    val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = storeScope) {
        File(isolatedRoot, "content-blocking.preferences_pb")
      }
    val fontsDirectory = File(isolatedRoot, "reading-fonts").apply { mkdirs() }
    val owner = ViewModelStore()
    lateinit var appViewModel: AppViewModel
    compose.activityRule.scenario.onActivity {
      appViewModel = AppViewModel(LibraryStore(context, fontsDirectory, dataStore))
      owner.put("content-blocking-filter", appViewModel)
    }
    return Fixture(mutableStateOf(appViewModel), owner, storeScope, dataStore).also { fixture = it }
  }

  private fun awaitReady(current: Fixture) {
    compose.waitUntil(10_000) { !current.viewModel.value.state.value.contentBlockingLoading }
    assertEquals(null, current.viewModel.value.state.value.contentBlockingError)
  }

  private fun awaitRuleCount(current: Fixture, count: Int) {
    compose.waitUntil(5_000) {
      val state = current.viewModel.value.state.value
      !state.contentBlockingBusy && state.contentBlocking.rules.size == count
    }
  }

  private data class Fixture(
      val viewModel: androidx.compose.runtime.MutableState<AppViewModel>,
      val owner: ViewModelStore,
      val storeScope: CoroutineScope,
      val dataStore: DataStore<Preferences>,
  )
}
