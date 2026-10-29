package cn.heyread.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.local.SearchHistoryStore
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.OnlineSearchBatch
import cn.heyread.app.data.remote.OnlineSearchLoader
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.ContentBlockingScope
import cn.heyread.app.ui.components.ProvideContentBlockingContext
import cn.heyread.app.ui.screens.FeedScreen
import cn.heyread.app.ui.screens.SearchScreen
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsCategoryScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.FeedPageState
import cn.heyread.app.viewmodel.FeedTab
import cn.heyread.app.viewmodel.SearchViewModel
import cn.heyread.app.viewmodel.UiState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingFeedSearchHistoryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var feedFixture: FeedFixture? = null
  private var searchOwner: ViewModelStore? = null

  @After
  fun clearViewModelsAndStore() {
    feedFixture?.let { fixture ->
      compose.activityRule.scenario.onActivity { fixture.owner.clear() }
      fixture.scope.cancel()
    }
    compose.activityRule.scenario.onActivity { searchOwner?.clear() }
    feedFixture = null
    searchOwner = null
  }

  @Test
  fun feedFiltersOnlyPresentedCardsAndTemporaryShowDoesNotChangeRawFeed() {
    val fixture = createFeedFixture()
    fixture.viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
    val raw = listOf(story("blocked-feed", "blocked title"), story("visible-feed", "visible title"))
    val ui = UiState(discoverFeed = FeedPageState(Source.DEMO, items = raw, hasMore = true))
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ProvideContentBlockingContext(feedFilterContext()) {
          FeedScreen("发现", ui, fixture.viewModel, {}, {}, isCurrentPage = true)
        }
      }
    }

    compose.onNodeWithText("blocked title").assertDoesNotExist()
    compose.onNodeWithText("visible title").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.onNodeWithTag("feed-story-list").performScrollToNode(hasText("blocked title"))
    compose.onNodeWithText("blocked title").assertIsDisplayed()
    compose
      .onNodeWithTag("feed-story-list")
      .performScrollToNode(hasTestTag("content-blocking-temporary-toggle"))
    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.onNodeWithText("已隐藏1条已加载内容").assertIsDisplayed()
    compose.onNodeWithText("blocked title").assertDoesNotExist()
    assertEquals(raw, ui.discoverFeed.items)
    assertEquals(true, ui.discoverFeed.hasMore)
  }

  @Test
  fun searchKeepsRawPageAndAllowsOneExplicitBatchWithFilteredResults() {
    val calls = CopyOnWriteArrayList<Int>()
    val firstPage =
      listOf(
        story("blocked-search", "blocked result"),
        story("visible-search", "visible result"),
        story("blocked-tail-search", "blocked tail"),
      )
    val vm =
      SearchViewModel(
        OnlineSearchLoader { _, _, offset, _, _, _ ->
          calls += offset
          val items = if (offset == 0) firstPage else listOf(story("next-search", "next result"))
          OnlineSearchBatch(
            stories = items,
            rawCount = items.size,
            rawIds = items.map(Story::id),
            nextOffset = offset + items.size,
            hasMore = true,
          )
        },
        EmptySearchHistory,
        SearchScope.GLOBAL,
      )
    searchOwner = ViewModelStore().also { it.put("content-filter-search", vm) }
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ProvideContentBlockingContext(feedFilterContext()) {
          SearchScreen(UiState(noImages = true), vm, {}, {})
        }
      }
    }
    compose.onNodeWithTag("search-query").performTextInput("query")
    compose.onNodeWithTag("search-submit").performClick()
    compose.waitUntil(5_000) { calls.size == 1 }
    compose.onNodeWithText("blocked result").assertDoesNotExist()
    compose.onNodeWithText("blocked tail").assertDoesNotExist()
    compose.onNodeWithText("visible result").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()

    assertEquals(listOf(0), calls.toList())
    compose.onNodeWithText("加载更多").performScrollTo().performClick()
    compose.waitUntil(5_000) { calls.size >= 2 && !vm.state.value.loadingMore }
    assertEquals(listOf(0, 3), calls.toList())
    assertEquals(4, vm.state.value.items.size)
    assertEquals(true, vm.state.value.hasMore)
    compose.onNodeWithText("next result").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun localHistoryFiltersBeforeTakingFiveAndPreservesRawCountAndClearAction() {
    val raw = (1..6).map { story("history-$it", if (it <= 2) "blocked $it" else "history $it") }
    var clearCount = 0
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ProvideContentBlockingContext(historyFilterContext()) {
          SettingsCategoryScreen(
            category = SettingsCategory.LocalHistory,
            ui = UiState(history = raw),
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
            onClearHistory = { clearCount++ },
            onBack = {},
          )
        }
      }
    }

    compose.onNodeWithText("本机阅读历史 · 6").assertIsDisplayed()
    compose.onNodeWithTag("home-card-A-history-1").assertDoesNotExist()
    compose.onNodeWithTag("home-card-A-history-2").assertDoesNotExist()
    compose.onNodeWithTag("home-card-A-history-6").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("settings-history-clear").performScrollTo().performClick()
    assertEquals(1, clearCount)
    assertEquals(6, raw.size)
  }

  private fun feedFilterContext() = filterContext(ContentBlockingScope.FEED)

  private fun historyFilterContext() = filterContext(ContentBlockingScope.HISTORY)

  private fun filterContext(scope: ContentBlockingScope) =
    ContentBlockingContext(
      config =
        ContentBlockingConfig(
          enabled = true,
          applyToHistory = scope == ContentBlockingScope.HISTORY,
          rules = listOf(ContentBlockRule("test-keyword", ContentBlockKind.KEYWORD, "blocked")),
        ),
      ready = true,
      sessionKey = "synthetic-test-session",
    )

  private fun createFeedFixture(): FeedFixture {
    val isolatedRoot = temporaryFolder.newFolder("feed-screen")
    val base = InstrumentationRegistry.getInstrumentation().context
    val context =
      object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
      }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = scope) {
        File(isolatedRoot, "library.preferences_pb")
      }
    val store = LibraryStore(context, File(isolatedRoot, "fonts").apply { mkdirs() }, dataStore)
    val owner = ViewModelStore()
    lateinit var viewModel: AppViewModel
    compose.activityRule.scenario.onActivity {
      viewModel = AppViewModel(store)
      owner.put("content-filter-feed", viewModel)
    }
    return FeedFixture(viewModel, owner, scope).also { feedFixture = it }
  }

  private fun story(id: String, title: String) =
    Story(id, Source.DEMO, title, "summary", "body", "author", "today")

  private data class FeedFixture(
    val viewModel: AppViewModel,
    val owner: ViewModelStore,
    val scope: CoroutineScope,
  )

  private object EmptySearchHistory : SearchHistoryStore {
    override suspend fun searchHistory(): List<SearchHistoryEntry> = emptyList()

    override suspend fun recordSearch(entry: SearchHistoryEntry) = Unit

    override suspend fun removeSearch(entry: SearchHistoryEntry) = Unit

    override suspend fun clearSearchHistory() = Unit
  }
}
