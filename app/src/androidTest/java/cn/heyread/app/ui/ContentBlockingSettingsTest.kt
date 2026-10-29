package cn.heyread.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsCategoryScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingSettingsTest {
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
  fun allRuleKindsAndScopesPersistWhenTheProductionViewModelReopens() {
    val current = createFixture()
    showSettings(current)
    awaitReady(current)
    compose.onNodeWithTag("content-blocking-scope-note").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-enabled").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        !current.viewModel.value.state.value.contentBlocking.enabled
    }
    compose.onNodeWithTag("content-blocking-favorites").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        current.viewModel.value.state.value.contentBlocking.applyToFavorites
    }
    compose.onNodeWithTag("content-blocking-history").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        current.viewModel.value.state.value.contentBlocking.applyToHistory
    }

    compose
      .onNodeWithTag("content-blocking-keyword-input")
      .performScrollTo()
      .performTextInput("alpha\nbeta")
    compose.onNodeWithTag("content-blocking-add").performScrollTo().performClick()
    awaitRules(current, 2)
    assertEquals(
      listOf("alpha", "beta"),
      current.viewModel.value.state.value.contentBlocking.rules.map { it.value },
    )

    addIdentity(current, ContentBlockKind.AUTHOR, "00042", "作者备注")
    addIdentity(current, ContentBlockKind.SECTION, "7", "分区备注")
    addIdentity(current, ContentBlockKind.TOPIC, "9", "话题备注")
    val beforeReopen = current.viewModel.value.state.value.contentBlocking
    assertEquals(listOf("alpha", "beta", "42", "7", "9"), beforeReopen.rules.map { it.value })
    assertTrue(
      beforeReopen.rules.drop(2).all { it.source == cn.heyread.app.data.model.Source.HEYBOX }
    )
    assertTrue(beforeReopen.rules.take(2).all { it.source == null })

    current.viewModel.value.onContentBlockingAction(
      ContentBlockingAction.AddIdentity(ContentBlockKind.AUTHOR, "not-decimal")
    )
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        current.viewModel.value.state.value.contentBlockingError != null
    }
    assertTrue(current.viewModel.value.state.value.contentBlockingLoaded)
    assertEquals(beforeReopen, current.viewModel.value.state.value.contentBlocking)
    current.viewModel.value.onContentBlockingAction(ContentBlockingAction.Reload)
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        current.viewModel.value.state.value.contentBlockingError == null
    }
    assertTrue(current.viewModel.value.state.value.contentBlockingLoaded)

    compose.activityRule.scenario.onActivity {
      current.owner.clear()
      val reopened = AppViewModel(current.newStore())
      current.owner.put("content-blocking-settings", reopened)
      current.viewModel.value = reopened
    }
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingLoading &&
        current.viewModel.value.state.value.contentBlocking == beforeReopen
    }
    compose.onNodeWithTag("content-blocking-kind-author").performScrollTo().performClick()
    compose.onNodeWithText("作者备注").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-scope-note").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun editDisableDeleteAndClearRequireExplicitConfirmation() {
    val current = createFixture()
    showSettings(current)
    awaitReady(current)
    compose.onNodeWithTag("content-blocking-enabled").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        !current.viewModel.value.state.value.contentBlocking.enabled
    }
    compose.onNodeWithTag("content-blocking-keyword-input").performTextInput("first\nsecond")
    compose.onNodeWithTag("content-blocking-add").performScrollTo().performClick()
    awaitRules(current, 2)
    compose.onNodeWithTag("content-blocking-favorites").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        current.viewModel.value.state.value.contentBlocking.applyToFavorites
    }
    compose.onNodeWithTag("content-blocking-history").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      current.viewModel.value.state.value.contentBlocking.applyToFavorites &&
        current.viewModel.value.state.value.contentBlocking.applyToHistory
    }

    val firstRuleId = current.viewModel.value.state.value.contentBlocking.rules.first().id
    compose.onNodeWithTag("content-blocking-edit-$firstRuleId").performScrollTo().performClick()
    val editInput = compose.onNodeWithTag("content-blocking-edit-input")
    editInput.performTextClearance()
    editInput.performTextInput("edited")
    compose.onNodeWithTag("motion-alert-confirm").performClick()
    compose.waitUntil(5_000) {
      current.viewModel.value.state.value.contentBlocking.rules.any { it.value == "edited" }
    }
    compose
      .onNodeWithTag("content-blocking-rule-enabled-$firstRuleId")
      .performScrollTo()
      .performClick()
    compose.waitUntil(5_000) {
      current.viewModel.value.state.value.contentBlocking.rules
        .firstOrNull { it.id == firstRuleId }
        ?.enabled == false
    }

    val secondRuleId = current.viewModel.value.state.value.contentBlocking.rules.last().id
    compose.onNodeWithTag("content-blocking-remove-$secondRuleId").performScrollTo().performClick()
    compose.onNodeWithText("取消").performClick()
    assertEquals(2, current.viewModel.value.state.value.contentBlocking.rules.size)
    compose.onNodeWithTag("content-blocking-remove-$secondRuleId").performScrollTo().performClick()
    compose.onNodeWithTag("motion-alert-confirm").performClick()
    compose.waitUntil(5_000) { current.viewModel.value.state.value.contentBlocking.rules.size == 1 }

    compose.onNodeWithTag("content-blocking-clear").performScrollTo().performClick()
    compose.onNodeWithText("取消").performClick()
    assertEquals(1, current.viewModel.value.state.value.contentBlocking.rules.size)
    compose.onNodeWithTag("content-blocking-clear").performScrollTo().performClick()
    compose.onNodeWithTag("motion-alert-confirm").performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.value.state.value.contentBlockingBusy &&
        current.viewModel.value.state.value.contentBlocking.rules.isEmpty()
    }
    val cleared = current.viewModel.value.state.value.contentBlocking
    assertTrue(cleared.applyToFavorites)
    assertTrue(cleared.applyToHistory)
    assertFalse(cleared.enabled)
  }

  @Test
  fun fourTypeChipsWrapAndKeepTouchTargetsAtNarrowWidthAndLargeText() {
    val current = createFixture()
    awaitReady(current)
    compose.setContent {
      val viewModel = current.viewModel.value
      val ui by viewModel.state.collectAsState()
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
        HeyReadTheme("light", motionEnabled = false) {
          Box(Modifier.width(210.dp).fillMaxHeight()) {
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
    }
    listOf("keyword", "author", "section", "topic").forEach { kind ->
      val chip = compose.onNodeWithTag("content-blocking-kind-$kind").performScrollTo()
      chip.assertIsDisplayed()
      val bounds = chip.fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Each rule type chip keeps a 48dp target",
        bounds.height >= compose.density.density * 48f,
      )
    }
    compose.onNodeWithTag("content-blocking-scope-note").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-kind-author").performScrollTo().performClick()
    compose.onNodeWithTag("content-blocking-id-input").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun malformedStoredRulesRemainVisibleAsErrorAndRetryReadsTheRepairedConfig() {
    val current = createFixture(initialConfig = "not-json")
    showSettings(current)
    compose.waitUntil(10_000) {
      val state = current.viewModel.value.state.value
      state.contentBlockingError != null && !state.contentBlockingLoading
    }
    assertFalse(current.viewModel.value.state.value.contentBlockingLoaded)
    compose.onNodeWithTag("content-blocking-error").performScrollTo().assertIsDisplayed()

    val repairedConfig =
      """{"version":1,"enabled":true,"applyToFavorites":false,"applyToHistory":true,"rules":[{"id":"00000000-0000-0000-0000-000000000001","kind":"KEYWORD","value":"recovered","label":"","enabled":true,"source":null}]}"""
    runBlocking {
      current.dataStore.edit {
        it[stringPreferencesKey("content_blocking_config")] = repairedConfig
      }
    }
    compose.onNodeWithTag("content-blocking-retry").performScrollTo().performClick()
    compose.waitUntil(10_000) {
      val state = current.viewModel.value.state.value
      !state.contentBlockingLoading &&
        !state.contentBlockingBusy &&
        state.contentBlockingError == null &&
        state.contentBlockingLoaded &&
        state.contentBlocking.rules.singleOrNull()?.value == "recovered"
    }
    compose.onNodeWithText("recovered").performScrollTo().assertIsDisplayed()
  }

  private fun addIdentity(current: Fixture, kind: ContentBlockKind, id: String, label: String) {
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
    awaitRules(current, expectedCount)
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

  private fun createFixture(initialConfig: String? = null): Fixture {
    val isolatedRoot = temporaryFolder.newFolder("content-blocking")
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
    if (initialConfig != null) {
      runBlocking {
        dataStore.edit { it[stringPreferencesKey("content_blocking_config")] = initialConfig }
      }
    }
    val newStore = {
      val fontsDirectory = File(isolatedRoot, "reading-fonts").apply { mkdirs() }
      LibraryStore(context, fontsDirectory, dataStore)
    }
    val owner = ViewModelStore()
    lateinit var appViewModel: AppViewModel
    compose.activityRule.scenario.onActivity {
      appViewModel = AppViewModel(newStore())
      owner.put("content-blocking-settings", appViewModel)
    }
    return Fixture(mutableStateOf(appViewModel), owner, storeScope, dataStore, newStore).also {
      fixture = it
    }
  }

  private fun awaitReady(current: Fixture) {
    compose.waitUntil(10_000) { !current.viewModel.value.state.value.contentBlockingLoading }
    assertEquals(null, current.viewModel.value.state.value.contentBlockingError)
    assertTrue(current.viewModel.value.state.value.contentBlockingLoaded)
  }

  private fun awaitRules(current: Fixture, count: Int) {
    compose.waitUntil(10_000) {
      val state = current.viewModel.value.state.value
      !state.contentBlockingBusy && state.contentBlocking.rules.size == count
    }
  }

  private data class Fixture(
    val viewModel: androidx.compose.runtime.MutableState<AppViewModel>,
    val owner: ViewModelStore,
    val storeScope: CoroutineScope,
    val dataStore: DataStore<Preferences>,
    val newStore: () -> LibraryStore,
  )
}
