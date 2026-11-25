package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.local.RecordsTestRule
import cn.heyread.app.data.local.encodeHistoryArray
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppViewModelHistoryGenerationTest {
  @get:Rule val recordsRule = RecordsTestRule()

  private var owner: ViewModelStore? = null

  @After
  fun closeViewModel() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { owner?.clear() }
    owner = null
  }

  @Test
  fun migrationWaitDoesNotBlockDemoFeedAndPublishesHistoryFlowAfterRelease() = runBlocking {
    val seed = story("migration-seed")
    putLegacy(seed)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val records = recordsRule.newRepository(recordsRule.preferences) {
      entered.complete(Unit)
      release.await()
    }
    val viewModel = createViewModel(records)
    withTimeout(5_000) { entered.await() }

    selectDemo(viewModel)
    awaitState(viewModel) {
      it.discoverFeed.source == Source.DEMO && it.discoverFeed.items.size >= 3 && !it.discoverFeed.loading
    }
    assertTrue(viewModel.state.value.historyLoading)
    assertTrue(viewModel.state.value.history.isEmpty())

    release.complete(Unit)
    awaitState(viewModel) { !it.historyLoading && it.history.firstOrNull()?.id == seed.id }
    assertEquals(seed, viewModel.state.value.history.first())

    val opened =
        story("opened-runtime").copy(
            detailBlocks = listOf(ArticleBlock.Paragraph("runtime-only body")),
            feedVideo = ArticleBlock.Video("https://invalid.example/short"),
        )
    InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.opened(opened) }
    awaitState(viewModel) { it.history.firstOrNull()?.id == opened.id }
    val persisted = viewModel.state.value.history.first()
    assertEquals(null, persisted.feedVideo)
    assertEquals(null, persisted.detailBlocks)
    assertEquals(opened, viewModel.state.value.knownStories["${Source.DEMO}:${opened.id}"])
    InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.clearHistory() }
    awaitState(viewModel) { !it.historyBusy && it.history.isEmpty() }
  }

  @Test
  fun migrationFailureShowsRetryAndKeepsFeedUsable() = runBlocking {
    val seed = story("retry-seed")
    putLegacy(seed)
    val failOnce = AtomicBoolean(true)
    val records = recordsRule.newRepository(recordsRule.preferences) {
      if (failOnce.getAndSet(false)) error("synthetic migration failure")
    }
    val viewModel = createViewModel(records)
    selectDemo(viewModel)
    awaitState(viewModel) { it.historyError != null }
    assertTrue(viewModel.state.value.history.isEmpty())
    awaitState(viewModel) {
      it.discoverFeed.source == Source.DEMO && it.discoverFeed.items.size >= 3 && !it.discoverFeed.loading
    }

    InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.retryHistory() }
    awaitState(viewModel) { !it.historyLoading && it.history.firstOrNull()?.id == seed.id }
    assertEquals(null, viewModel.state.value.historyError)
  }

  private suspend fun putLegacy(story: Story) {
    recordsRule.preferences.updateData { current ->
      current.toMutablePreferences().apply {
        this[stringPreferencesKey("history")] =
            encodeHistoryArray(listOf(story))
      }
    }
  }

  private fun createViewModel(records: cn.heyread.app.data.local.records.LocalRecordsStore): AppViewModel {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = LibraryStore(context, recordsRule.preferences, records)
    val newOwner = ViewModelStore()
    owner = newOwner
    lateinit var viewModel: AppViewModel
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      viewModel = AppViewModel(store)
      newOwner.put("room-history-test", viewModel)
    }
    return viewModel
  }

  private fun selectDemo(viewModel: AppViewModel) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
    }
  }

  private suspend fun awaitState(viewModel: AppViewModel, predicate: (UiState) -> Boolean) {
    withTimeout(10_000) { while (!predicate(viewModel.state.value)) delay(25) }
  }

  private fun story(id: String) =
      Story(id, Source.DEMO, "Synthetic $id", "Excerpt", "Body", "Author", "date")
}
