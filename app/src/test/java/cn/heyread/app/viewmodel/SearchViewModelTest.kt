package cn.heyread.app.viewmodel

import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.local.SearchHistoryStore
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaChallenge
import cn.heyread.app.data.remote.CaptchaProof
import cn.heyread.app.data.remote.CaptchaRequiredException
import cn.heyread.app.data.remote.OnlineSearchBatch
import cn.heyread.app.data.remote.OnlineSearchLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun explicitSubmitAndPaginationFailureRetryKeepQueryAndCursor() =
        runTest(dispatcher) {
            val calls = mutableListOf<Triple<String, Int, String?>>()
            var failSecond = true
            val loader = OnlineSearchLoader { query, _, offset, cursor, _, _ ->
                calls += Triple(query, offset, cursor)
                if (offset == 20 && failSecond) {
                    failSecond = false
                    error("network")
                }
                batch(if (offset == 0) "first" else "second", offset + 20, true)
            }
            val history = MemorySearchHistory()
            val vm = SearchViewModel(loader, history)
            vm.setQuery("  query  ")
            runCurrent()
            assertTrue(calls.isEmpty())
            vm.submit()
            runCurrent()
            assertEquals("query", calls.single().first)
            assertEquals("query", history.entries.single().query)
            vm.loadMore()
            runCurrent()
            assertEquals(20, calls.last().second)
            assertEquals("network", vm.state.value.error)
            vm.retry()
            runCurrent()
            assertEquals(20, calls.last().second)
            assertEquals(listOf("first", "second"), vm.state.value.items.map { it.id })
        }

    @Test
    fun captchaRestoresTheSameTopicBatchAndPreservesExistingResults() =
        runTest(dispatcher) {
            val scope = SearchScope(SearchScopeType.HASHTAG, "55", "topic")
            val calls = mutableListOf<Triple<Int, String?, Boolean>>()
            var challengePending = true
            val loader = OnlineSearchLoader { _, _, offset, cursor, challenge, proof ->
                calls += Triple(offset, cursor, proof != null)
                if (offset == 29 && challengePending && challenge == null) {
                    challengePending = false
                    throw CaptchaRequiredException(
                        CaptchaChallenge(
                            "/bbs/app/hashtag/feeds",
                            mapOf("hashtag_id" to "55"),
                            "2061805035",
                        )
                    )
                }
                if (offset == 0) batch("initial", 29, true, "cursor-a")
                else batch("next", 58, true, "cursor-b")
            }
            val vm = SearchViewModel(loader, MemorySearchHistory(), scope)
            vm.setQuery("find")
            vm.submit()
            runCurrent()
            vm.loadMore()
            runCurrent()
            assertEquals(listOf("initial"), vm.state.value.items.map { it.id })
            assertTrue(vm.state.value.captchaRequired)
            vm.openCaptchaDialog()
            val challengeId = vm.state.value.captchaChallengeId!!
            val attemptId = vm.state.value.captchaAttemptId!!
            vm.onCaptchaCallback(
                challengeId,
                attemptId,
                CaptchaCallbackResult.Completed(CaptchaProof("ticket", "rand")),
            )
            runCurrent()
            assertEquals(listOf("initial", "next"), vm.state.value.items.map { it.id })
            assertEquals(29, calls.last().first)
            assertEquals("cursor-a", calls.last().second)
            assertTrue(calls.last().third)
        }

    @Test
    fun staleResponseCannotReplaceNewQuery() =
        runTest(dispatcher) {
            val old = CompletableDeferred<OnlineSearchBatch>()
            val loader = OnlineSearchLoader { query, _, _, _, _, _ ->
                if (query == "old") old.await() else batch("new", 20, false)
            }
            val vm = SearchViewModel(loader, MemorySearchHistory())
            vm.setQuery("old")
            vm.submit()
            runCurrent()
            vm.setQuery("new")
            vm.submit()
            runCurrent()
            old.complete(batch("old-result", 20, false))
            runCurrent()
            assertEquals(listOf("new"), vm.state.value.items.map { it.id })
        }

    @Test
    fun unmatchedHashtagBatchCountsCheckedIdsAndRepeatedPageStops() =
        runTest(dispatcher) {
            var calls = 0
            val loader = OnlineSearchLoader { _, _, offset, _, _, _ ->
                calls++
                if (calls == 1)
                    OnlineSearchBatch(
                        emptyList(),
                        2,
                        listOf("raw-a", "raw-b"),
                        offset + 2,
                        true,
                        "c1",
                    )
                else
                    OnlineSearchBatch(
                        emptyList(),
                        2,
                        listOf("raw-a", "raw-b"),
                        offset + 2,
                        true,
                        "c2",
                    )
            }
            val vm =
                SearchViewModel(
                    loader,
                    MemorySearchHistory(),
                    SearchScope(SearchScopeType.HASHTAG, "55", "topic"),
                )
            vm.setQuery("no match")
            vm.submit()
            runCurrent()
            assertEquals(2, vm.state.value.checkedCount)
            assertTrue(vm.state.value.hasMore)
            vm.loadMore()
            runCurrent()
            assertEquals(2, vm.state.value.checkedCount)
            assertFalse(vm.state.value.hasMore)
        }

    @Test
    fun failedCaptchaReplayAllowsASeparateNewAttempt() =
        runTest(dispatcher) {
            var proofCount = 0
            val challenge = CaptchaChallenge("/search", emptyMap(), "199251710")
            val loader = OnlineSearchLoader { _, _, _, _, incomingChallenge, proof ->
                if (incomingChallenge == null) throw CaptchaRequiredException(challenge)
                proofCount++
                if (proofCount == 1) error("temporary failure")
                batch("done", 20, false)
            }
            val vm = SearchViewModel(loader, MemorySearchHistory())
            vm.setQuery("query")
            vm.submit()
            runCurrent()
            vm.openCaptchaDialog()
            val challengeId = vm.state.value.captchaChallengeId!!
            val firstAttempt = vm.state.value.captchaAttemptId!!
            vm.onCaptchaCallback(
                challengeId,
                firstAttempt,
                CaptchaCallbackResult.Completed(CaptchaProof("ticket-a", "rand-a")),
            )
            runCurrent()
            assertTrue(vm.state.value.captchaRequired)
            assertFalse(vm.state.value.captchaVerifying)
            vm.openCaptchaDialog()
            val secondAttempt = vm.state.value.captchaAttemptId!!
            assertTrue(secondAttempt > firstAttempt)
            vm.onCaptchaCallback(
                challengeId,
                secondAttempt,
                CaptchaCallbackResult.Completed(CaptchaProof("ticket-b", "rand-b")),
            )
            runCurrent()
            assertEquals(listOf("done"), vm.state.value.items.map { it.id })
        }

    @Test
    fun accountRevisionChangeCancelsOldResultsButFirstBindDoesNot() =
        runTest(dispatcher) {
            val response = CompletableDeferred<OnlineSearchBatch>()
            var calls = 0
            val vm =
                SearchViewModel(
                    OnlineSearchLoader { _, _, _, _, _, _ ->
                        calls++
                        response.await()
                    },
                    MemorySearchHistory(),
                )
            vm.bindSession(1)
            vm.setQuery("query")
            vm.submit()
            runCurrent()
            assertEquals(1, calls)
            vm.bindSession(2)
            response.complete(batch("stale", 20, false))
            runCurrent()
            assertTrue(vm.state.value.items.isEmpty())
            assertEquals("", vm.state.value.submittedQuery)
        }

    private fun batch(id: String, next: Int, more: Boolean, cursor: String? = null) =
        OnlineSearchBatch(
            listOf(Story(id, Source.HEYBOX, id, id, id, "author", "today")),
            1,
            listOf(id),
            next,
            more,
            cursor,
        )

    private class MemorySearchHistory : SearchHistoryStore {
        val entries = mutableListOf<SearchHistoryEntry>()

        override suspend fun searchHistory() = entries.toList()

        override suspend fun recordSearch(entry: SearchHistoryEntry) {
            entries.removeAll { it.key == entry.key }
            entries.add(0, entry)
        }

        override suspend fun removeSearch(entry: SearchHistoryEntry) {
            entries.removeAll { it.key == entry.key }
        }

        override suspend fun clearSearchHistory() {
            entries.clear()
        }
    }
}
