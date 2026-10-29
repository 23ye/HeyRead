package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.SteamGame
import cn.heyread.app.data.remote.SteamGameSearchSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamGameSearchViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun queryOnlySearchesAfterExplicitSubmitAndClearResetsState() =
    runTest(dispatcher) {
      val calls = mutableListOf<String>()
      val viewModel =
        SteamGameSearchViewModel(
          SteamGameSearchSource { query ->
            calls += query
            listOf(SteamGame(1145350, "Hades II"))
          }
        )

      viewModel.updateQuery("Hades")
      runCurrent()
      assertTrue(calls.isEmpty())
      viewModel.submit()
      runCurrent()
      assertEquals(listOf("Hades"), calls)
      assertEquals(listOf(SteamGame(1145350, "Hades II")), viewModel.state.value.results)
      assertTrue(viewModel.state.value.hasSearched)

      viewModel.clear()
      assertEquals(SteamGameSearchState(), viewModel.state.value)
      assertEquals(listOf("Hades"), calls)
    }

  @Test
  fun lateOldSuccessCannotReplaceNewQueryResults() =
    runTest(dispatcher) {
      val oldResult = CompletableDeferred<List<SteamGame>>()
      val oldStarted = CompletableDeferred<Unit>()
      val source = SteamGameSearchSource { query ->
        if (query == "old") {
          oldStarted.complete(Unit)
          withContext(NonCancellable) { oldResult.await() }
        } else listOf(SteamGame(123, "New result"))
      }
      val viewModel = SteamGameSearchViewModel(source)

      viewModel.updateQuery("old")
      viewModel.submit()
      runCurrent()
      oldStarted.await()
      viewModel.updateQuery("new")
      viewModel.submit()
      runCurrent()
      assertEquals(listOf(SteamGame(123, "New result")), viewModel.state.value.results)

      oldResult.complete(listOf(SteamGame(456, "Stale result")))
      runCurrent()
      assertEquals("new", viewModel.state.value.query)
      assertEquals(listOf(SteamGame(123, "New result")), viewModel.state.value.results)
      assertNull(viewModel.state.value.error)
    }

  @Test
  fun pauseCancelsOutstandingSearchWithoutClearingExistingQuery() =
    runTest(dispatcher) {
      var cancelled = false
      val viewModel =
        SteamGameSearchViewModel(
          SteamGameSearchSource {
            try {
              awaitCancellation()
            } finally {
              cancelled = true
            }
          }
        )

      viewModel.updateQuery("Hades")
      viewModel.submit()
      runCurrent()
      assertTrue(viewModel.state.value.loading)
      viewModel.pause()
      runCurrent()

      assertTrue(cancelled)
      assertFalse(viewModel.state.value.loading)
      assertEquals("Hades", viewModel.state.value.query)
      assertTrue(viewModel.state.value.results.isEmpty())
    }

  @Test
  fun failedSearchIsRetryableAndBlankSubmitHasExplicitError() =
    runTest(dispatcher) {
      var attempts = 0
      val viewModel =
        SteamGameSearchViewModel(
          SteamGameSearchSource {
            attempts++
            if (attempts == 1) error("offline")
            listOf(SteamGame(367520, "Hollow Knight"))
          }
        )

      viewModel.submit()
      assertEquals("请输入游戏名称或AppID。", viewModel.state.value.error)
      assertFalse(viewModel.state.value.loading)

      viewModel.updateQuery("367520")
      viewModel.submit()
      runCurrent()
      assertEquals("offline", viewModel.state.value.error)
      assertTrue(viewModel.state.value.hasSearched)

      viewModel.submit()
      runCurrent()
      assertEquals(2, attempts)
      assertEquals(367520, viewModel.state.value.results.single().appId)
      assertNull(viewModel.state.value.error)
    }
}
