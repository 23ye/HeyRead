package cn.heyread.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingScope
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.components.ContentBlockingCardActions
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.ContentBlockingFeedbackHost
import cn.heyread.app.ui.components.LocalContentBlockingCardActions
import cn.heyread.app.ui.components.LocalContentBlockingContext
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.screens.HomeStoryCard
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingCardsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var fixture: Fixture? = null

  @After
  fun closeIsolatedStore() {
    fixture?.let { current ->
      compose.activityRule.scenario.onActivity { current.owner.clear() }
      current.storeScope.cancel()
    }
    fixture = null
  }

  @Test
  fun undoSnackbarExpiresWithoutRemovingTheSavedRule() {
    val current = createFixture()
    awaitReady(current)
    compose.setContent {
      val ui by current.viewModel.state.collectAsState()
      val snackbarHostState = remember { SnackbarHostState() }
      HeyReadTheme("light", motionEnabled = false) {
        CompositionLocalProvider(LocalAccessibilityManager provides null) {
          Box(Modifier.fillMaxSize()) {
            ContentBlockingFeedbackHost(
              ui = ui,
              hostState = snackbarHostState,
              onAction = current.viewModel::onContentBlockingAction,
              onDismiss = current.viewModel::dismissContentBlockingFeedback,
            )
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
          }
        }
      }
    }

    current.viewModel.onContentBlockingAction(ContentBlockingAction.AddKeywords("keep this rule"))
    compose.waitUntil(5_000) {
      !current.viewModel.state.value.contentBlockingBusy &&
        current.viewModel.state.value.contentBlockingUndo.isNotEmpty()
    }
    compose.onNodeWithText("撤销").assertIsDisplayed()
    compose.mainClock.advanceTimeBy(5_000)
    compose.waitUntil(8_000) {
      runCatching { compose.onNodeWithText("撤销").fetchSemanticsNode() }.isFailure
    }
    assertEquals(
      listOf("keep this rule"),
      current.viewModel.state.value.contentBlocking.rules.map { it.value },
    )
    compose.waitUntil(3_000) { current.viewModel.state.value.contentBlockingUndo.isEmpty() }
    assertEquals(1, current.viewModel.state.value.contentBlocking.rules.size)
  }

  @Test
  fun longPressOpensBlockingWithoutOpeningCardAndTapAndScrollRemainAvailable() {
    val current = createFixture()
    awaitReady(current)
    val stories = (0..11).map { story("card-$it", "block card $it") }
    val openCount = AtomicInteger()
    showCards(current, stories, openCount)

    compose.onNodeWithTag("home-card-A-card-0").performTouchInput { longClick() }
    compose.onNodeWithTag("content-blocking-quick-add-dialog").assertIsDisplayed()
    assertEquals(0, openCount.get())
    compose.onNodeWithText("取消").performClick()
    assertEquals(0, current.viewModel.state.value.contentBlocking.rules.size)

    compose.onNodeWithTag("blocking-card-list").performTouchInput { swipeUp() }
    compose.waitUntil(5_000) {
      runCatching { compose.onNodeWithTag("home-card-A-card-11").fetchSemanticsNode() }.isSuccess
    }
    compose.onNodeWithTag("home-card-A-card-11").performClick()
    assertEquals(1, openCount.get())
    compose.onNodeWithTag("home-card-A-card-11").performTouchInput { longClick() }
    compose.onNodeWithTag("content-blocking-quick-add-dialog").assertIsDisplayed()
    assertEquals("Long press must not also open the article", 1, openCount.get())
    compose.onNodeWithTag("content-blocking-quick-confirm").performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.state.value.contentBlockingBusy &&
        current.viewModel.state.value.contentBlocking.rules.singleOrNull()?.let {
          it.kind == ContentBlockKind.KEYWORD && it.value == "block card 11"
        } == true
    }
    compose.waitUntil(5_000) {
      runCatching { compose.onNodeWithTag("home-card-A-card-11").fetchSemanticsNode() }.isFailure
    }
    compose.onNodeWithTag("home-card-A-card-10").assertIsDisplayed()
    assertEquals(1, openCount.get())
  }

  private fun showCards(
    current: Fixture,
    stories: List<Story>,
    openCount: AtomicInteger,
  ) {
    compose.setContent {
      val ui by current.viewModel.state.collectAsState()
      val context =
        ContentBlockingContext(
          config = ui.contentBlocking,
          ready = ui.contentBlockingLoaded,
          loading = ui.contentBlockingLoading,
          error = ui.contentBlockingError,
          sessionKey = "synthetic-session",
        )
      val actions =
        ContentBlockingCardActions(
          ui = ui,
          sessionKey = "synthetic-session",
          onAction = current.viewModel::onContentBlockingAction,
        )
      val snackbarHostState = remember { SnackbarHostState() }
      HeyReadTheme("light", motionEnabled = false) {
        CompositionLocalProvider(
          LocalContentBlockingContext provides context,
          LocalContentBlockingCardActions provides actions,
        ) {
          val blocking =
            rememberContentBlockingList(
              raw = stories,
              scope = ContentBlockingScope.FEED,
              identity = "test-cards",
              isCurrentPage = true,
            )
          Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("blocking-card-list")) {
              items(blocking.items, key = { it.id }) { story ->
                HomeStoryCard(
                  story = story,
                  layout = HomeLayout.A,
                  featured = false,
                  noImages = true,
                  onOpen = { openCount.incrementAndGet() },
                )
              }
            }
            ContentBlockingFeedbackHost(
              ui = ui,
              hostState = snackbarHostState,
              onAction = current.viewModel::onContentBlockingAction,
              onDismiss = current.viewModel::dismissContentBlockingFeedback,
            )
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
          }
        }
      }
    }
  }

  private fun createFixture(): Fixture {
    val isolatedRoot = temporaryFolder.newFolder("content-blocking-cards")
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
    val store = LibraryStore(context, fontsDirectory, dataStore)
    val owner = ViewModelStore()
    lateinit var appViewModel: AppViewModel
    compose.activityRule.scenario.onActivity {
      appViewModel = AppViewModel(store)
      owner.put("content-blocking-cards", appViewModel)
    }
    return Fixture(appViewModel, owner, storeScope).also { fixture = it }
  }

  private fun awaitReady(current: Fixture) {
    compose.waitUntil(10_000) { !current.viewModel.state.value.contentBlockingLoading }
    assertTrue(current.viewModel.state.value.contentBlockingLoaded)
  }

  private data class Fixture(
    val viewModel: AppViewModel,
    val owner: ViewModelStore,
    val storeScope: CoroutineScope,
  )

  private fun story(id: String, title: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title = title,
      excerpt = "excerpt",
      body = "body",
      author = "writer",
      date = "today",
    )
}
