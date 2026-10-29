package cn.heyread.app.viewmodel

import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountRequestExecutor
import cn.heyread.app.data.account.FullArticle
import cn.heyread.app.data.account.ReplyTarget
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.local.SearchHistoryStore
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialFlowsTest {
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
    fun searchNewQueryRejectsOldResponseAndRefreshBlocksAppend() =
        runTest(dispatcher) {
            val requests = mutableListOf<Pair<String, Int>>()
            val deferreds =
                mutableMapOf<
                    String,
                    CompletableDeferred<cn.heyread.app.data.remote.OnlineSearchBatch>,
                >()
            val counts = mutableMapOf<String, Int>()
            val repo =
                cn.heyread.app.data.remote.OnlineSearchLoader { query, _, offset, _, _, _ ->
                    requests += query to offset
                    val key = "$query:$offset"
                    val count = counts.getOrDefault(key, 0)
                    counts[key] = count + 1
                    deferreds.getValue("$key:$count").await()
                }
            val history =
                object : SearchHistoryStore {
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
            val vm = SearchViewModel(repo, history)

            deferreds["old:0:0"] = CompletableDeferred()
            vm.setQuery("old")
            vm.submit()
            runCurrent()
            assertEquals(1, requests.size)

            deferreds["new:0:0"] = CompletableDeferred()
            vm.setQuery("new")
            vm.submit()
            runCurrent()
            assertEquals(listOf("old", "new"), requests.map { it.first })
            deferreds.getValue("old:0:0").complete(searchBatch("old-id", 20, hasMore = true))
            deferreds.getValue("new:0:0").complete(searchBatch("new-id", 20, hasMore = true))
            runCurrent()
            assertEquals(listOf("new-id"), vm.state.value.items.map { it.id })

            deferreds["new:20:0"] = CompletableDeferred()
            vm.loadMore()
            runCurrent()
            assertTrue(vm.state.value.loadingMore)
            deferreds["new:0:1"] = CompletableDeferred()
            vm.refresh()
            runCurrent()
            assertTrue(vm.state.value.refreshing)
            val requestCount = requests.size
            vm.loadMore()
            runCurrent()
            assertEquals(requestCount, requests.size)
            deferreds["new:20:0"]?.complete(searchBatch("late-page", 40, hasMore = true))
            deferreds["new:0:1"]?.complete(searchBatch("refreshed", 20, hasMore = true))
            runCurrent()
            assertEquals(listOf("refreshed"), vm.state.value.items.map { it.id })
        }

    @Test
    fun socialActionsReserveDuplicateCommentAndKeepFailedDraft() =
        runTest(dispatcher) {
            val requests = mutableListOf<Request>()
            val repo =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor { request ->
                            requests += request
                            """{"status":"ok"}"""
                        }
                )
            val vm = SocialActionsViewModel(repo)
            vm.bindSession("synthetic-user", 3)
            vm.syncArticle(story, article(liked = false, favorited = false, count = 4))
            vm.draft(story, "synthetic comment")
            var published = 0

            vm.comment(story, "received-code") { published++ }
            vm.comment(story, "received-code") { published++ }
            runCurrent()

            assertEquals(1, requests.size)
            assertEquals(1, published)
            assertEquals("", vm.states.value[SocialActionsViewModel.key(story)]?.commentDraft)

            val failedRepo =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor { """{"status":"failed","msg":"write rejected"}""" }
                )
            val failedVm = SocialActionsViewModel(failedRepo)
            failedVm.bindSession("synthetic-user", 4)
            failedVm.syncArticle(story, article(liked = false, favorited = false, count = 4))
            val failedTarget = ReplyTarget("root-fail", "child-fail", "Reply target")
            failedVm.selectCommentTarget(story, failedTarget)
            failedVm.draft(story, "keep this draft")
            failedVm.comment(story, null) {}
            runCurrent()
            val failed = failedVm.states.value[SocialActionsViewModel.key(story)]
            assertEquals("keep this draft", failed?.commentDraft)
            assertEquals(failedTarget, failed?.commentTarget)
            assertNotNull(failed?.error)
        }

    @Test
    fun replyDraftsStayBoundToTargetsAndSessionSwitchClearsThem() =
        runTest(dispatcher) {
            val requests = mutableListOf<Request>()
            val repo =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor { request ->
                            requests += request
                            """{"status":"ok"}"""
                        }
                )
            val vm = SocialActionsViewModel(repo)
            vm.bindSession("user-a", 1)
            vm.syncArticle(story, article(liked = false, favorited = false, count = 1))
            vm.draft(story, "main draft")
            val targetA = ReplyTarget("root-1", "child-2", "A")
            val targetB = ReplyTarget("root-1", "child-3", "B")
            assertTrue(vm.selectCommentTarget(story, targetA))
            vm.draft(story, "draft for A")
            assertTrue(vm.selectCommentTarget(story, targetB))
            assertEquals("", vm.states.value[SocialActionsViewModel.key(story)]?.commentDraft)
            vm.draft(story, "draft for B")
            assertTrue(vm.selectCommentTarget(story, targetA))
            assertEquals(
                "draft for A",
                vm.states.value[SocialActionsViewModel.key(story)]?.commentDraft,
            )
            vm.comment(story, null) {}
            runCurrent()
            val body = Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("root_id=root-1"))
            assertTrue(body.contains("reply_id=child-2"))
            assertEquals(
                "draft for B",
                run {
                    assertTrue(vm.selectCommentTarget(story, targetB))
                    vm.states.value[SocialActionsViewModel.key(story)]?.commentDraft
                },
            )
            vm.bindSession("user-b", 2)
            assertTrue(vm.states.value.isEmpty())
            assertTrue(vm.selectCommentTarget(story, targetA))
            assertEquals("", vm.states.value[SocialActionsViewModel.key(story)]?.commentDraft)
        }

    @Test
    fun pendingReplyLocksTargetAndFailureKeepsItsDraft() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<String>()
            val requests = mutableListOf<Request>()
            val repo =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor { request ->
                            requests += request
                            pending.await()
                        }
                )
            val vm = SocialActionsViewModel(repo)
            vm.bindSession("synthetic-user", 8)
            vm.syncArticle(story, article(liked = false, favorited = false, count = 1))
            val target = ReplyTarget("root-7", "reply-8", "Synthetic user")
            val other = ReplyTarget("root-9", "reply-10", "Other user")
            vm.selectCommentTarget(story, target)
            vm.draft(story, "reply draft")
            vm.comment(story, "received-code") {}
            runCurrent()
            assertEquals(1, requests.size)
            assertFalse(vm.selectCommentTarget(story, other))
            vm.draft(story, "wrong replacement")
            vm.comment(story, "received-code") {}
            assertEquals(1, requests.size)
            pending.completeExceptionally(IOException("timeout"))
            runCurrent()
            val state = vm.states.value[SocialActionsViewModel.key(story)]
            assertEquals(target, state?.commentTarget)
            assertEquals("reply draft", state?.commentDraft)
            assertTrue(state?.error.orEmpty().contains("发送结果未确认"))
        }

    @Test
    fun lateReplyFromOldSessionCannotReplaceNewSessionDraft() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<String>()
            val repo =
                AccountRepository(requestExecutor = AccountRequestExecutor { pending.await() })
            val vm = SocialActionsViewModel(repo)
            vm.bindSession("old-user", 1)
            vm.syncArticle(story, article(liked = false, favorited = false, count = 1))
            val oldTarget = ReplyTarget("old-root", "old-child", "Old")
            vm.selectCommentTarget(story, oldTarget)
            vm.draft(story, "old private draft")
            vm.comment(story, null) {}
            runCurrent()

            vm.bindSession("new-user", 2)
            vm.syncArticle(story, article(liked = false, favorited = false, count = 1))
            val newTarget = ReplyTarget("new-root", "new-child", "New")
            vm.selectCommentTarget(story, newTarget)
            vm.draft(story, "new private draft")
            pending.complete("""{"status":"ok"}""")
            runCurrent()

            val state = vm.states.value[SocialActionsViewModel.key(story)]
            assertEquals(newTarget, state?.commentTarget)
            assertEquals("new private draft", state?.commentDraft)
        }

    @Test
    fun simultaneousLikeAndFavoriteMergeWithoutReplacingEachOther() =
        runTest(dispatcher) {
            val requests = mutableListOf<Request>()
            val repo =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor { request ->
                            requests += request
                            """{"status":"ok"}"""
                        }
                )
            val vm = SocialActionsViewModel(repo)
            vm.bindSession("synthetic-user", 1)
            vm.syncArticle(story, article(liked = false, favorited = false, count = 8))

            vm.like(story)
            vm.favorite(story) {}
            runCurrent()

            val state = vm.states.value[SocialActionsViewModel.key(story)]
            assertEquals(2, requests.size)
            assertTrue(state?.liked == true)
            assertTrue(state?.favorited == true)
            assertEquals(9, state?.likeCount)
            assertFalse(state?.likeBusy ?: true)
            assertFalse(state?.favoriteBusy ?: true)
        }

    @Test
    fun writeReservationPreventsDuplicateOperationKey() {
        val gate = WriteReservation()
        assertTrue(gate.acquire("post:comment"))
        assertFalse(gate.acquire("post:comment"))
        assertTrue(gate.acquire("post:like"))
        gate.release("post:comment")
        assertTrue(gate.acquire("post:comment"))
        gate.clear()
        assertTrue(gate.acquire("post:comment"))
    }

    private fun article(liked: Boolean, favorited: Boolean, count: Int) =
        FullArticle(
            story = story,
            blocks = listOf(ArticleBlock.Paragraph("synthetic")),
            comments = emptyList(),
            page = 1,
            hasMoreComments = false,
            isLiked = liked,
            isFavorited = favorited,
            likeCount = count,
        )

    private fun searchBatch(id: String, nextOffset: Int, hasMore: Boolean) =
        cn.heyread.app.data.remote.OnlineSearchBatch(
            stories = listOf(story.copy(id = id, title = "Synthetic $id")),
            rawCount = 1,
            rawIds = listOf(id),
            nextOffset = nextOffset,
            hasMore = hasMore,
        )

    private val story =
        Story(
            id = "synthetic-post",
            source = Source.HEYBOX,
            title = "Synthetic post",
            excerpt = "fixture",
            body = "",
            author = "Synthetic author",
            date = "2026-01-01",
            hSrc = "synthetic-source",
        )
}
