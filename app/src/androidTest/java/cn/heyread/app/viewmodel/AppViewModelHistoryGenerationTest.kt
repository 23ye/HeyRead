package cn.heyread.app.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.local.encodeHistoryStory
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppViewModelHistoryGenerationTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var owner: ViewModelStore? = null
  private var dataStoreScope: CoroutineScope? = null

  @After
  fun closeTestResources() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { owner?.clear() }
    owner = null
    dataStoreScope?.cancel()
    dataStoreScope = null
  }

  @Test
  fun olderOpenedReadCannotReplaceNewerOpenedHistory() = runBlocking {
    val harness = createHarness()
    awaitDemoReady(harness.viewModel)
    val oldStory = story("opened-a")
    val newStory = story("opened-b")
    val gate = harness.dataStore.delayNextHistoryRead()

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness.viewModel.opened(oldStory)
    }
    val captured = withTimeout(10_000) { gate.captured.await() }
    assertTrue(captured[stringPreferencesKey("history")].orEmpty().contains("opened-a"))

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness.viewModel.opened(newStory)
    }
    awaitState(harness.viewModel) {
      it.history.firstOrNull()?.id == "opened-b" &&
          it.history.any { story -> story.id == "opened-a" }
    }
    releaseAndDrain(gate)

    val history = harness.viewModel.state.value.history
    assertEquals(listOf("opened-b", "opened-a"), history.take(2).map { it.id })
  }

  @Test
  fun olderOpenedReadCannotRestoreHistoryAfterClear() = runBlocking {
    val harness = createHarness()
    awaitDemoReady(harness.viewModel)
    val oldStory = story("opened-before-clear")
    val gate = harness.dataStore.delayNextHistoryRead()

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness.viewModel.opened(oldStory)
    }
    val captured = withTimeout(10_000) { gate.captured.await() }
    assertTrue(captured[stringPreferencesKey("history")].orEmpty().contains("opened-before-clear"))

    InstrumentationRegistry.getInstrumentation().runOnMainSync { harness.viewModel.clearHistory() }
    withTimeout(10_000) {
      while (harness.libraryStore.history().isNotEmpty() ||
          harness.viewModel.state.value.history.isNotEmpty()) delay(25)
    }
    releaseAndDrain(gate)

    assertTrue(harness.viewModel.state.value.history.isEmpty())
    assertTrue(harness.libraryStore.history().isEmpty())
  }

  @Test
  fun initializationSnapshotDoesNotReplaceHistoryOpenedWhileItWasLoading() = runBlocking {
    val seed = story("stored-seed")
    val harness = createHarness(listOf(seed), gatedInitializationHistoryRead = true)
    val captured = withTimeout(10_000) { checkNotNull(harness.initializationGate).captured.await() }
    assertTrue(captured[stringPreferencesKey("history")].orEmpty().contains("stored-seed"))

    val opened = story("opened-during-init")
    InstrumentationRegistry.getInstrumentation().runOnMainSync { harness.viewModel.opened(opened) }
    awaitState(harness.viewModel) {
      it.history.firstOrNull()?.id == "opened-during-init" &&
          it.knownStories.containsKey("${Source.DEMO}:opened-during-init")
    }
    releaseAndDrain(checkNotNull(harness.initializationGate))
    awaitDemoReady(harness.viewModel)

    assertEquals("opened-during-init", harness.viewModel.state.value.history.first().id)
    assertTrue(
        harness.viewModel.state.value.knownStories.containsKey("${Source.DEMO}:opened-during-init"))
  }

  private suspend fun awaitDemoReady(viewModel: AppViewModel) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
    }
    awaitState(viewModel) {
      it.discoverFeed.source == Source.DEMO &&
          it.discoverFeed.items.size >= 3 &&
          !it.discoverFeed.loading
    }
  }

  private suspend fun awaitState(viewModel: AppViewModel, predicate: (UiState) -> Boolean) {
    withTimeout(10_000) { while (!predicate(viewModel.state.value)) delay(25) }
  }

  private suspend fun releaseAndDrain(gate: ReadGate) {
    gate.release.complete(Unit)
    withTimeout(10_000) { gate.emissionCompleted.await() }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    delay(50)
  }

  private fun createHarness(
      initialHistory: List<Story> = emptyList(),
      gatedInitializationHistoryRead: Boolean = false,
  ): Harness {
    val root = temporaryFolder.newFolder("history-generation-${System.nanoTime()}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dataStoreScope = scope
    val baseStore =
        PreferenceDataStoreFactory.create(scope = scope) { File(root, "isolated.preferences_pb") }
    if (initialHistory.isNotEmpty()) {
      val historyJson =
          JSONArray().apply { initialHistory.forEach { put(encodeHistoryStory(it)) } }.toString()
      runBlocking {
        baseStore.updateData { preferences ->
          preferences.toMutablePreferences().apply {
            this[stringPreferencesKey("history")] = historyJson
          }
        }
      }
    }
    val gatedStore = GatedDataStore(baseStore)
    val initializationGate =
        if (gatedInitializationHistoryRead) gatedStore.delayNextHistoryRead() else null
    val libraryStore =
        LibraryStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
            File(root, "fonts").apply { mkdirs() },
            gatedStore,
        )
    val newOwner = ViewModelStore()
    owner = newOwner
    lateinit var viewModel: AppViewModel
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      viewModel = AppViewModel(libraryStore)
      newOwner.put("history-generation-test", viewModel)
    }
    return Harness(viewModel, libraryStore, gatedStore, initializationGate)
  }

  private fun story(id: String) =
      Story(
          id = id,
          source = Source.DEMO,
          title = "Synthetic $id",
          excerpt = "Synthetic excerpt",
          body = "Synthetic body",
          author = "Test author",
          date = "2026-10-23",
      )

  private data class Harness(
      val viewModel: AppViewModel,
      val libraryStore: LibraryStore,
      val dataStore: GatedDataStore,
      val initializationGate: ReadGate?,
  )

  private class ReadGate {
    val captured = CompletableDeferred<Preferences>()
    val release = CompletableDeferred<Unit>()
    val emissionCompleted = CompletableDeferred<Unit>()
  }

  private class GatedDataStore(private val delegate: DataStore<Preferences>) :
      DataStore<Preferences> {
    private val historyGate = AtomicReference<ReadGate?>(null)

    override val data: Flow<Preferences>
      get() {
        val isHistoryRead = isLibraryStoreHistoryRead()
        return flow {
          val snapshot = delegate.data.first()
          if (isHistoryRead) {
            historyGate.getAndSet(null)?.let { gate ->
              gate.captured.complete(snapshot)
              gate.release.await()
              try {
                emit(snapshot)
              } finally {
                gate.emissionCompleted.complete(Unit)
              }
              return@flow
            }
          }
          emit(snapshot)
        }
      }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = delegate.updateData(transform)

    fun delayNextHistoryRead(): ReadGate =
        ReadGate().also { gate ->
          check(historyGate.compareAndSet(null, gate)) { "A history read is already gated" }
        }

    private fun isLibraryStoreHistoryRead(): Boolean {
      val storeClass = LibraryStore::class.java.name
      val historyContinuationPrefix = "$storeClass\$history\$"
      return Thread.currentThread().stackTrace.any { frame ->
        (frame.className == storeClass && frame.methodName == "history") ||
            frame.className.startsWith(historyContinuationPrefix)
      }
    }
  }
}
