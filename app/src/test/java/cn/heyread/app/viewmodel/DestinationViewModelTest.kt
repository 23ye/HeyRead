package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaChallenge
import cn.heyread.app.data.remote.CaptchaProof
import cn.heyread.app.data.remote.CaptchaRequiredException
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationPage
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.data.remote.UserProfileStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationViewModelTest {
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
    fun staleRefreshAndOldDestinationResponsesCannotReplaceCurrentState() =
        runTest(dispatcher) {
            val a = DestinationTarget(DestinationType.SECTION, "a", "A")
            val b = DestinationTarget(DestinationType.HASHTAG, "b", "B")
            val gates = mutableMapOf<String, CompletableDeferred<DestinationPage>>()
            val calls = mutableMapOf<String, Int>()
            val loader = DestinationLoader { target, offset, _, _, _, _, _, _, _, _ ->
                val base = "${target.id}:$offset"
                val index = calls.getOrDefault(base, 0)
                calls[base] = index + 1
                withContext(NonCancellable) { gates.getValue("$base:$index").await() }
            }
            val vm = DestinationViewModel(loader)

            gates["a:0:0"] = CompletableDeferred()
            vm.show(a)
            runCurrent()
            gates["a:0:1"] = CompletableDeferred()
            vm.refresh()
            runCurrent()
            gates["a:0:0"]!!.complete(page("old-refresh"))
            gates["a:0:1"]!!.complete(page("new-refresh"))
            runCurrent()
            assertEquals(listOf("new-refresh"), vm.state.value.stories.map { it.id })

            gates["a:0:2"] = CompletableDeferred()
            vm.refresh()
            runCurrent()
            gates["b:0:0"] = CompletableDeferred()
            vm.show(b)
            runCurrent()
            gates["a:0:2"]!!.complete(page("late-a"))
            gates["b:0:0"]!!.complete(page("b-result"))
            runCurrent()
            assertEquals(b, vm.state.value.target)
            assertEquals(listOf("b-result"), vm.state.value.stories.map { it.id })
        }

    @Test
    fun failedPageKeepsCursorForRetryAndDuplicateOnlyPageStops() =
        runTest(dispatcher) {
            val target = DestinationTarget(DestinationType.SECTION, "s", "Section")
            val offsets = mutableListOf<Int>()
            var failPage = true
            val loader = DestinationLoader { _, offset, _, _, _, _, _, _, _, _ ->
                offsets += offset
                when (offset) {
                    0 ->
                        DestinationPage(
                            DestinationData("User"),
                            listOf(story("same")),
                            30,
                            hasMore = true,
                        )
                    else ->
                        if (failPage) {
                            failPage = false
                            error("temporary")
                        } else
                            DestinationPage(
                                DestinationData("User"),
                                listOf(story("same")),
                                60,
                                hasMore = true,
                            )
                }
            }
            val vm = DestinationViewModel(loader)
            vm.show(target)
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertTrue(vm.state.value.error?.contains("temporary") == true)
            assertEquals(30, offsets.last())
            vm.retry()
            advanceUntilIdle()
            assertEquals(listOf(0, 30, 30), offsets)
            assertEquals(listOf("same"), vm.state.value.stories.map { it.id })
            assertFalse(vm.state.value.hasMore)
        }

    @Test
    fun switchingTabsKeepsSeparateCursorsAndCanceledAppendCannotLeaveTabBusy() =
        runTest(dispatcher) {
            val target = DestinationTarget(DestinationType.USER, "u", "User")
            val oldMomentPage = CompletableDeferred<DestinationPage>()
            val newMomentPage = CompletableDeferred<DestinationPage>()
            var momentContinuationCalls = 0
            val requestedCursors = mutableListOf<String?>()
            val loader = DestinationLoader { _, _, _, cursor, _, feed, _, _, _, _ ->
                when (feed) {
                    DestinationFeedType.MOMENTS -> {
                        requestedCursors += cursor
                        if (cursor.isNullOrEmpty())
                            page("moment-1").copy(nextOffset = 0, lastValue = "1791693318.357")
                        else {
                            momentContinuationCalls++
                            withContext(NonCancellable) {
                                if (momentContinuationCalls == 1) oldMomentPage.await()
                                else newMomentPage.await()
                            }
                        }
                    }
                    DestinationFeedType.ARTICLES ->
                        DestinationPage(
                            DestinationData("User"),
                            listOf(story("article-1")),
                            30,
                            hasMore = true,
                        )
                }
            }
            val vm = DestinationViewModel(loader)
            vm.show(target)
            advanceUntilIdle()
            assertEquals(
                "1791693318.357",
                vm.state.value.feeds[DestinationFeedType.MOMENTS]?.lastValue,
            )

            vm.loadMore()
            vm.loadMore()
            runCurrent()
            assertTrue(vm.state.value.loadingMore)
            assertEquals(2, requestedCursors.size)
            vm.selectFeed(DestinationFeedType.ARTICLES)
            advanceUntilIdle()
            assertEquals(listOf("article-1"), vm.state.value.stories.map { it.id })

            vm.selectFeed(DestinationFeedType.MOMENTS)
            assertFalse(vm.state.value.loadingMore)
            assertEquals(listOf("moment-1"), vm.state.value.stories.map { it.id })
            vm.loadMore()
            runCurrent()
            assertEquals(listOf("", "1791693318.357", "1791693318.357"), requestedCursors)

            oldMomentPage.complete(page("late-moment").copy(nextOffset = 0, lastValue = "late"))
            newMomentPage.complete(
                page("moment-2").copy(nextOffset = 0, lastValue = "1791079982.084")
            )
            advanceUntilIdle()
            assertEquals(listOf("moment-1", "moment-2"), vm.state.value.stories.map { it.id })
            assertEquals(
                "1791079982.084",
                vm.state.value.feeds[DestinationFeedType.MOMENTS]?.lastValue,
            )
            assertEquals(30, vm.state.value.feeds[DestinationFeedType.ARTICLES]?.offset)
        }

    @Test
    fun sharedProfileLoadsOnceAcrossTabsAndExplicitRefreshReloadsIt() =
        runTest(dispatcher) {
            val target = DestinationTarget(DestinationType.USER, "u", "User")
            val calls = mutableListOf<Pair<Boolean, Boolean>>()
            val profile = cn.heyread.app.data.model.AuthorProfile("User", userId = "u")
            val loader =
                DestinationLoader { _, offset, _, _, knownProfile, feed, reloadHeader, _, _, _ ->
                    calls += reloadHeader to (knownProfile != null)
                    DestinationPage(
                        DestinationData(
                            "User",
                            profile = profile,
                            profileStats = UserProfileStats(1, 2, 3),
                        ),
                        listOf(story("${feed.name}-$offset-${calls.size}")),
                        if (feed == DestinationFeedType.ARTICLES) offset + 30 else 0,
                        lastValue =
                            if (feed == DestinationFeedType.MOMENTS) "cursor-${calls.size}"
                            else null,
                        hasMore = true,
                    )
                }
            val vm = DestinationViewModel(loader)
            vm.show(target)
            advanceUntilIdle()
            vm.selectFeed(DestinationFeedType.ARTICLES)
            advanceUntilIdle()
            assertEquals(listOf(true to false, false to true), calls)
            assertEquals(3L, vm.state.value.data?.profileStats?.receivedLikesAndFavorites)

            vm.refresh()
            advanceUntilIdle()
            assertEquals(true to false, calls.last())
            assertEquals(3L, vm.state.value.data?.profileStats?.receivedLikesAndFavorites)
        }

    @Test
    fun captchaPausesRequestsInvalidatesOldCallbacksAndResumesOriginalFeedOnce() =
        runTest(dispatcher) {
            val target = DestinationTarget(DestinationType.USER, "u", "User")
            val pageReady = CompletableDeferred<DestinationPage>()
            val resumeStarted = CompletableDeferred<Unit>()
            val challenge =
                CaptchaChallenge(
                    "/bbs/app/profile/events",
                    mapOf("userid" to "u", "list_type" to "moment", "dw" to "304", "lastval" to ""),
                    "199251710",
                )
            val profile = cn.heyread.app.data.model.AuthorProfile("User", userId = "u")
            val partial =
                DestinationData("User", profile = profile, profileStats = UserProfileStats(1, 2, 3))
            var calls = 0
            var receivedChallenge: CaptchaChallenge? = null
            var receivedProof: CaptchaProof? = null
            val loader = DestinationLoader { _, _, _, _, _, _, _, _, suppliedChallenge, proof ->
                calls++
                if (calls == 1) throw CaptchaRequiredException(challenge, partial)
                receivedChallenge = suppliedChallenge
                receivedProof = proof
                resumeStarted.complete(Unit)
                pageReady.await()
            }
            val vm = DestinationViewModel(loader)
            vm.show(target)
            advanceUntilIdle()
            assertTrue(vm.state.value.captchaRequired)
            assertEquals(partial.profileStats, vm.state.value.data?.profileStats)

            vm.refresh()
            vm.retry()
            vm.loadMore()
            vm.selectFeed(DestinationFeedType.ARTICLES)
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(vm.state.value.captchaRequired)
            assertTrue(vm.state.value.stories.isEmpty())

            val challengeId = vm.state.value.captchaChallengeId!!
            vm.openCaptchaDialog()
            val oldAttempt = vm.state.value.captchaAttemptId!!
            vm.closeCaptchaDialog()
            val proof = CaptchaProof("once-only-ticket", "once-only-rand")
            vm.onCaptchaCallback(challengeId, oldAttempt, CaptchaCallbackResult.Completed(proof))
            advanceUntilIdle()
            assertEquals(1, calls)

            vm.openCaptchaDialog()
            val currentAttempt = vm.state.value.captchaAttemptId!!
            vm.onCaptchaCallback(challengeId, oldAttempt, CaptchaCallbackResult.Completed(proof))
            assertEquals(1, calls)
            vm.onCaptchaCallback(
                challengeId,
                currentAttempt,
                CaptchaCallbackResult.Completed(proof),
            )
            runCurrent()
            resumeStarted.await()
            assertEquals(DestinationFeedType.MOMENTS, vm.state.value.feedType)
            assertTrue(vm.state.value.captchaVerifying)
            vm.onCaptchaCallback(challengeId, currentAttempt, CaptchaCallbackResult.Cancelled)
            vm.selectFeed(DestinationFeedType.ARTICLES)
            assertEquals(DestinationFeedType.MOMENTS, vm.state.value.feedType)
            assertEquals(2, calls)

            pageReady.complete(page("verified-story").copy(hasMore = false))
            advanceUntilIdle()
            assertFalse(vm.state.value.captchaRequired)
            assertFalse(vm.state.value.captchaVerifying)
            assertEquals(listOf("verified-story"), vm.state.value.stories.map { it.id })
            assertEquals(challenge, receivedChallenge)
            assertEquals(proof, receivedProof)
        }

    @Test
    fun paginatedCaptchaKeepsCursorAndStopsWhenTheProofGetsAnotherChallenge() =
        runTest(dispatcher) {
            val target = DestinationTarget(DestinationType.USER, "u-page", "User")
            val cursors = mutableListOf<String?>()
            val challenges = mutableListOf<CaptchaChallenge?>()
            val proofs = mutableListOf<CaptchaProof?>()
            var calls = 0
            val loader = DestinationLoader { _, _, _, cursor, _, _, _, _, challenge, proof ->
                calls++
                cursors += cursor
                challenges += challenge
                proofs += proof
                when (calls) {
                    1 -> page("first").copy(nextOffset = 0, lastValue = "1791693318.357")
                    2 ->
                        throw CaptchaRequiredException(
                            CaptchaChallenge(
                                "/bbs/app/profile/events",
                                mapOf(
                                    "userid" to "u-page",
                                    "list_type" to "moment",
                                    "dw" to "304",
                                    "lastval" to "1791693318.357",
                                ),
                                "199251710",
                            )
                        )
                    3 ->
                        throw CaptchaRequiredException(
                            CaptchaChallenge(
                                "/bbs/app/profile/events",
                                mapOf(
                                    "userid" to "u-page",
                                    "list_type" to "moment",
                                    "dw" to "304",
                                    "lastval" to "1791693318.357",
                                ),
                                "199251710",
                            )
                        )
                    else -> page("second").copy(nextOffset = 0, lastValue = "1791079982.084")
                }
            }
            val vm = DestinationViewModel(loader)
            vm.show(target)
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("first"), vm.state.value.stories.map { it.id })
            assertEquals(
                "1791693318.357",
                vm.state.value.feeds[DestinationFeedType.MOMENTS]?.lastValue,
            )
            assertTrue(vm.state.value.captchaRequired)
            assertEquals(2, calls)

            vm.openCaptchaDialog()
            val firstChallenge = vm.state.value.captchaChallengeId!!
            val firstAttempt = vm.state.value.captchaAttemptId!!
            val firstProof = CaptchaProof("first-ticket", "first-rand")
            vm.onCaptchaCallback(
                firstChallenge,
                firstAttempt,
                CaptchaCallbackResult.Completed(firstProof),
            )
            advanceUntilIdle()

            assertEquals(3, calls)
            assertTrue(vm.state.value.captchaRequired)
            assertEquals(listOf("first"), vm.state.value.stories.map { it.id })
            assertEquals(
                "1791693318.357",
                vm.state.value.feeds[DestinationFeedType.MOMENTS]?.lastValue,
            )
            assertEquals(listOf("", "1791693318.357", "1791693318.357"), cursors)
            assertNull(challenges[0])
            assertTrue(challenges[2] != null)
            assertEquals(firstProof, proofs[2])

            vm.openCaptchaDialog()
            val secondChallenge = vm.state.value.captchaChallengeId!!
            val secondAttempt = vm.state.value.captchaAttemptId!!
            val secondProof = CaptchaProof("second-ticket", "second-rand")
            assertNotEquals(firstChallenge, secondChallenge)
            vm.onCaptchaCallback(
                firstChallenge,
                firstAttempt,
                CaptchaCallbackResult.Completed(CaptchaProof("stale-ticket", "stale-rand")),
            )
            assertEquals(3, calls)
            vm.onCaptchaCallback(
                secondChallenge,
                secondAttempt,
                CaptchaCallbackResult.Completed(secondProof),
            )
            advanceUntilIdle()

            assertEquals(4, calls)
            assertEquals(listOf("first", "second"), vm.state.value.stories.map { it.id })
            assertEquals("1791693318.357", cursors[2])
            assertEquals("1791693318.357", cursors[3])
            assertEquals(secondProof, proofs[3])
            assertEquals(
                "1791079982.084",
                vm.state.value.feeds[DestinationFeedType.MOMENTS]?.lastValue,
            )
            assertFalse(vm.state.value.captchaRequired)
        }

    @Test
    fun switchingDestinationInvalidatesCaptchaWindowCallback() =
        runTest(dispatcher) {
            val firstTarget = DestinationTarget(DestinationType.USER, "first", "First")
            val secondTarget = DestinationTarget(DestinationType.USER, "second", "Second")
            var calls = 0
            val loader = DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
                calls++
                if (target == firstTarget)
                    throw CaptchaRequiredException(
                        CaptchaChallenge("/events", mapOf("userid" to "first"), "199251710")
                    )
                page("second-user")
            }
            val vm = DestinationViewModel(loader)
            vm.show(firstTarget)
            advanceUntilIdle()
            vm.openCaptchaDialog()
            val oldChallenge = vm.state.value.captchaChallengeId!!
            val oldAttempt = vm.state.value.captchaAttemptId!!

            vm.show(secondTarget)
            advanceUntilIdle()
            vm.onCaptchaCallback(
                oldChallenge,
                oldAttempt,
                CaptchaCallbackResult.Completed(CaptchaProof("late-ticket", "late-rand")),
            )
            advanceUntilIdle()

            assertEquals(secondTarget, vm.state.value.target)
            assertFalse(vm.state.value.captchaRequired)
            assertEquals(2, calls)
        }

    private fun page(id: String) =
        DestinationPage(DestinationData(id), listOf(story(id)), 1, hasMore = true)

    private fun story(id: String) =
        Story(
            id = id,
            source = Source.HEYBOX,
            title = id,
            excerpt = "",
            body = "",
            author = "Author",
            date = "",
        )
}
