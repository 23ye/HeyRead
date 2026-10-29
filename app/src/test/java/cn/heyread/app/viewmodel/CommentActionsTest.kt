package cn.heyread.app.viewmodel

import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountRequestExecutor
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.model.AuthorProfile
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentActionsTest {
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
    fun duplicateCommentSupportLocksAndFailureKeepsServerState() =
        runTest(dispatcher) {
            var requests = 0
            val pending = CompletableDeferred<String>()
            val repository =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor {
                            requests++
                            pending.await()
                        }
                )
            val vm = CommentActionsViewModel(repository)
            vm.bindSession("account", 1)
            val comment =
                RemoteComment(
                    "comment-1",
                    "Author",
                    "text",
                    0,
                    4,
                    isSupported = false,
                    supportCount = 4,
                )
            vm.syncComments(listOf(comment))
            vm.support(comment, story)
            vm.support(comment, story)
            runCurrent()
            assertEquals(1, requests)
            assertTrue(vm.stateFor(comment.id).busy)
            vm.syncComments(listOf(comment.copy(supportCount = 99, isSupported = true)))
            assertEquals(4, vm.stateFor(comment.id).count)
            pending.completeExceptionally(IOException("write outcome unknown"))
            runCurrent()
            assertEquals(false, vm.stateFor(comment.id).supported)
            assertEquals(4, vm.stateFor(comment.id).count)
            assertNotNull(vm.stateFor(comment.id).error)
            assertFalse(vm.stateFor(comment.id).busy)
        }

    @Test
    fun acknowledgedFollowCannotBeOverwrittenByAnOldSeedAndCanRefresh() =
        runTest(dispatcher) {
            val requests = mutableListOf<Request>()
            val write = CompletableDeferred<String>()
            var refresh = CompletableDeferred<String>()
            val repo =
                AccountRepository(
                    requestExecutor =
                        AccountRequestExecutor { request ->
                            requests += request
                            if (request.method == "POST") write.await() else refresh.await()
                        }
                )
            val vm = AuthorFollowViewModel(repo)
            vm.bindSession("viewer", 9)
            val author = AuthorProfile("Author", userId = "target")
            vm.seedFromArticle(author, false)
            vm.toggle(author, story)
            runCurrent()
            assertTrue(vm.stateFor("target").busy)
            vm.seedFromArticle(author, false)
            write.complete("""{"status":"ok"}""")
            runCurrent()
            assertEquals(true, vm.stateFor("target").followed)
            vm.seedFromArticle(author, false)
            assertEquals(true, vm.stateFor("target").followed)
            refresh.complete(
                """{"status":"ok","result":{"link":{"poster":{"userid":"target"}},"current_user":{"follow_status":2}}}"""
            )
            vm.refresh(author, story)
            runCurrent()
            assertEquals(false, vm.stateFor("target").followed)
            assertEquals("/bbs/app/profile/follow/user", requests.first().url.encodedPath)
            assertTrue(requests.any { it.url.encodedPath == "/bbs/app/link/web/view" })
        }

    @Test
    fun oldSessionCommentResponseCannotRestorePrivateActionState() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<String>()
            val repo =
                AccountRepository(requestExecutor = AccountRequestExecutor { pending.await() })
            val vm = CommentActionsViewModel(repo)
            vm.bindSession("account-a", 1)
            val comment =
                RemoteComment(
                    "comment-session",
                    "Author",
                    "text",
                    0,
                    1,
                    isSupported = false,
                    supportCount = 1,
                )
            vm.syncComments(listOf(comment))
            vm.support(comment, story)
            runCurrent()
            vm.bindSession("account-b", 2)
            pending.complete("""{"status":"ok"}""")
            runCurrent()
            assertTrue(vm.states.value.isEmpty())
        }

    private val story =
        Story(
            id = "post-1",
            source = Source.HEYBOX,
            title = "Fixture",
            excerpt = "",
            body = "",
            author = "Author",
            date = "2026-10-17",
            hSrc = "fixture",
        )
}
