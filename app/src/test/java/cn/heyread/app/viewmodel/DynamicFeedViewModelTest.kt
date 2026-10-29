package cn.heyread.app.viewmodel

import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.account.FollowedActivityPage
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicFeedViewModelTest {
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
  fun recommendationsHaveIndependentThirtyItemCursorAndSurviveModeSwitch() =
    runTest(dispatcher) {
      val calls = mutableListOf<Pair<Int, Int>>()
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { offset, width ->
            calls += offset to width
            StoryPage(
              when (offset) {
                0 -> listOf(story("first"), story("duplicate"))
                else -> listOf(story("duplicate"), story("second"))
              },
              hasMore = true,
            )
          }
        )

      viewModel.ensureLoaded(displayWidth = 500)
      runCurrent()
      assertEquals(listOf(0 to 500), calls)
      assertEquals(
        listOf("first", "duplicate"),
        viewModel.state.value.recommendations.items.map { it.id },
      )

      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      viewModel.selectMode(DynamicFeedMode.RECOMMENDED)
      viewModel.ensureLoaded(displayWidth = 500)
      runCurrent()
      assertEquals(1, calls.size)

      viewModel.loadMore(displayWidth = 620)
      runCurrent()
      assertEquals(listOf(0 to 500, 30 to 620), calls)
      assertEquals(
        listOf("first", "duplicate", "second"),
        viewModel.state.value.recommendations.items.map { it.id },
      )
      assertEquals(1, viewModel.state.value.recommendations.page)
      assertTrue(viewModel.state.value.recommendations.hasMore)
    }

  @Test
  fun followingModeDoesNotIssueOrBorrowRecommendationRequests() =
    runTest(dispatcher) {
      var calls = 0
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ ->
            calls++
            StoryPage(listOf(story("recommendation")), hasMore = false)
          }
        )

      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      assertEquals(0, calls)
      assertTrue(viewModel.state.value.recommendations.items.isEmpty())
      assertFalse(viewModel.state.value.recommendations.hasRequested)

      viewModel.selectMode(DynamicFeedMode.RECOMMENDED)
      runCurrent()
      assertEquals(1, calls)
      assertEquals(
        listOf("recommendation"),
        viewModel.state.value.recommendations.items.map { it.id },
      )
    }

  @Test
  fun followFeedRequiresRestoredAccountAndKeepsItsCursorAndItemsIsolated() =
    runTest(dispatcher) {
      val followedCalls = mutableListOf<Triple<String, Int, String?>>()
      val recommendationCalls = mutableListOf<Int>()
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { offset, _ ->
            recommendationCalls += offset
            StoryPage(listOf(story("recommendation-$offset")), hasMore = false)
          },
          loadFollowing = { userId, offset, lastValue ->
            followedCalls += Triple(userId, offset, lastValue)
            FollowedActivityPage(
              stories = listOf(story("follow-$offset")),
              rawCount = 30,
              lastValue = if (offset == 0) "cursor/one" else "",
            )
          },
        )

      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      assertTrue(followedCalls.isEmpty())

      viewModel.bindAccount("user-a", revision = 1, loadingSession = false)
      viewModel.ensureFollowingLoaded()
      runCurrent()
      assertEquals(listOf(Triple("user-a", 0, null)), followedCalls)
      assertEquals(listOf("follow-0"), viewModel.state.value.following.items.map { it.id })

      viewModel.loadMoreFollowing()
      runCurrent()
      assertEquals(Triple("user-a", 30, "cursor/one"), followedCalls.last())
      assertEquals(
        listOf("follow-0", "follow-30"),
        viewModel.state.value.following.items.map { it.id },
      )

      viewModel.selectMode(DynamicFeedMode.RECOMMENDED)
      runCurrent()
      assertEquals(listOf(0), recommendationCalls)
      assertEquals(
        listOf("follow-0", "follow-30"),
        viewModel.state.value.following.items.map { it.id },
      )

      viewModel.bindAccount("user-b", revision = 2, loadingSession = false)
      runCurrent()
      assertTrue(viewModel.state.value.following.items.isEmpty())
      assertEquals(2, followedCalls.size)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      assertEquals(Triple("user-b", 0, null), followedCalls.last())
      assertEquals(listOf("follow-0"), viewModel.state.value.following.items.map { it.id })
      assertEquals(
        listOf("recommendation-0"),
        viewModel.state.value.recommendations.items.map { it.id },
      )
    }

  @Test
  fun unparseableNonEmptyMomentsAreNotReportedAsAnEmptyFollowingFeed() =
    runTest(dispatcher) {
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
          loadFollowing = { _, _, _ -> FollowedActivityPage(emptyList(), 30, "") },
        )
      viewModel.bindAccount("user-a", revision = 1, loadingSession = false)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()

      assertTrue(viewModel.state.value.following.items.isEmpty())
      assertEquals("关注动态包含暂不支持的内容，暂时无法显示。", viewModel.state.value.following.error)
      assertFalse(viewModel.state.value.following.hasMore)
    }

  @Test
  fun presentationDropsPreviousAccountFollowingItemsBeforeBindingEffectRuns() {
    val state =
      DynamicFeedState(
        recommendations = FeedPageState(Source.HEYBOX, items = listOf(story("public"))),
        following = DynamicActivityFeedState(items = listOf(story("private-a"))),
        accountUserId = "user-a",
        sessionRevision = 1,
        sessionRestoring = false,
      )

    val presented = state.forAccountPresentation("user-b", revision = 2, loadingSession = false)

    assertTrue(presented.following.items.isEmpty())
    assertEquals("user-b", presented.accountUserId)
    assertEquals(2, presented.sessionRevision)
    assertEquals(listOf("public"), presented.recommendations.items.map { it.id })
  }

  @Test
  fun lateFollowingSessionExpiryFromPreviousAccountCannotAffectNewAccount() =
    runTest(dispatcher) {
      val releaseOldAccount = CompletableDeferred<Unit>()
      var expiredCalls = 0
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
          loadFollowing = { userId, _, _ ->
            if (userId == "user-a") {
              withContext(NonCancellable) {
                releaseOldAccount.await()
                throw AccountSessionExpiredException()
              }
            }
            FollowedActivityPage(listOf(story("follow-user-b")), 1, "")
          },
          onSessionExpired = { expiredCalls++ },
        )

      viewModel.bindAccount("user-a", revision = 1, loadingSession = false)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      viewModel.bindAccount("user-b", revision = 2, loadingSession = false)
      viewModel.ensureFollowingLoaded()
      runCurrent()
      assertEquals(listOf("follow-user-b"), viewModel.state.value.following.items.map { it.id })

      releaseOldAccount.complete(Unit)
      runCurrent()
      assertEquals(listOf("follow-user-b"), viewModel.state.value.following.items.map { it.id })
      assertEquals(0, expiredCalls)
      assertEquals("user-b", viewModel.state.value.accountUserId)
    }

  @Test
  fun lateFollowingSuccessFromPreviousAccountCannotReplaceNewAccountItems() =
    runTest(dispatcher) {
      val releaseOldAccount = CompletableDeferred<Unit>()
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
          loadFollowing = { userId, _, _ ->
            if (userId == "user-a") {
              withContext(NonCancellable) {
                releaseOldAccount.await()
                FollowedActivityPage(listOf(story("stale-user-a")), 1, "")
              }
            } else {
              FollowedActivityPage(listOf(story("current-user-b")), 1, "")
            }
          },
        )

      viewModel.bindAccount("user-a", revision = 1, loadingSession = false)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      viewModel.bindAccount("user-b", revision = 2, loadingSession = false)
      viewModel.ensureFollowingLoaded()
      runCurrent()
      releaseOldAccount.complete(Unit)
      runCurrent()

      assertEquals(listOf("current-user-b"), viewModel.state.value.following.items.map { it.id })
    }

  @Test
  fun lateRecommendationRefreshCannotReplaceNewerResponse() =
    runTest(dispatcher) {
      val releaseOldRefresh = CompletableDeferred<Unit>()
      var calls = 0
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ ->
            when (++calls) {
              1 -> StoryPage(listOf(story("initial")), hasMore = true)
              2 -> {
                withContext(NonCancellable) { releaseOldRefresh.await() }
                StoryPage(listOf(story("old-refresh")), hasMore = true)
              }
              else -> StoryPage(listOf(story("new-refresh")), hasMore = true)
            }
          }
        )

      viewModel.ensureLoaded()
      runCurrent()
      viewModel.refresh()
      runCurrent()
      viewModel.refresh()
      runCurrent()
      assertEquals(listOf("new-refresh"), viewModel.state.value.recommendations.items.map { it.id })

      releaseOldRefresh.complete(Unit)
      runCurrent()
      assertEquals(listOf("new-refresh"), viewModel.state.value.recommendations.items.map { it.id })
      assertFalse(viewModel.state.value.recommendations.refreshing)
    }

  @Test
  fun recommendationAppendRetryKeepsOffsetWhileRefreshRetryRestartsAtZero() =
    runTest(dispatcher) {
      val offsets = mutableListOf<Int>()
      var pageThirtyCalls = 0
      var refreshCalls = 0
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { offset, _ ->
            offsets += offset
            when (offset) {
              30 ->
                if (++pageThirtyCalls == 1) throw IOException("append failure")
                else StoryPage(listOf(story("second")), hasMore = false)
              0 -> {
                refreshCalls++
                when (refreshCalls) {
                  1 -> StoryPage(listOf(story("first")), hasMore = true)
                  2 -> throw IOException("refresh failure")
                  else -> StoryPage(listOf(story("refreshed")), hasMore = false)
                }
              }
              else -> error("unexpected offset $offset")
            }
          }
        )

      viewModel.ensureLoaded()
      runCurrent()
      viewModel.loadMore()
      runCurrent()
      assertEquals(30, viewModel.state.value.recommendationRetryOffset)
      assertEquals(0, viewModel.state.value.recommendations.page)

      viewModel.retryRecommendations()
      runCurrent()
      assertEquals(listOf(0, 30, 30), offsets)
      assertEquals(
        listOf("first", "second"),
        viewModel.state.value.recommendations.items.map { it.id },
      )
      assertEquals(null, viewModel.state.value.recommendationRetryOffset)

      viewModel.refresh()
      runCurrent()
      assertEquals(0, viewModel.state.value.recommendationRetryOffset)
      assertEquals(
        listOf("first", "second"),
        viewModel.state.value.recommendations.items.map { it.id },
      )
      viewModel.retryRecommendations()
      runCurrent()
      assertEquals(listOf(0, 30, 30, 0, 0), offsets)
      assertEquals(listOf("refreshed"), viewModel.state.value.recommendations.items.map { it.id })
    }

  @Test
  fun leavingAndReenteringFollowingCanLoadAgainWhileCanceledRequestFinishesLate() =
    runTest(dispatcher) {
      val releaseOldRequest = CompletableDeferred<Unit>()
      var calls = 0
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
          loadFollowing = { _, _, _ ->
            if (++calls == 1) {
              withContext(NonCancellable) { releaseOldRequest.await() }
              FollowedActivityPage(listOf(story("stale")), 1, "")
            } else {
              FollowedActivityPage(listOf(story("current")), 1, "")
            }
          },
        )
      viewModel.bindAccount("user-a", revision = 1, loadingSession = false)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      viewModel.selectMode(DynamicFeedMode.RECOMMENDED)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      assertEquals(listOf("current"), viewModel.state.value.following.items.map { it.id })
      assertFalse(viewModel.state.value.following.loading)

      releaseOldRequest.complete(Unit)
      runCurrent()
      assertEquals(listOf("current"), viewModel.state.value.following.items.map { it.id })
      assertFalse(viewModel.state.value.following.loading)
    }

  @Test
  fun failedPageRetriesSameOffsetAndEmptyCursorStillAdvancesByOffset() =
    runTest(dispatcher) {
      val calls = mutableListOf<Pair<Int, String?>>()
      var pageThirtyCalls = 0
      val viewModel =
        DynamicFeedViewModel(
          loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
          loadFollowing = { _, offset, lastValue ->
            calls += offset to lastValue
            when (offset) {
              0 -> FollowedActivityPage(listOf(story("first")), 30, "opaque/cursor+1")
              30 ->
                if (++pageThirtyCalls == 1) throw IOException("temporary failure")
                else FollowedActivityPage(listOf(story("second")), 30, "")
              else -> FollowedActivityPage(listOf(story("third")), 30, null)
            }
          },
        )
      viewModel.bindAccount("user-a", revision = 1, loadingSession = false)
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      viewModel.loadMoreFollowing()
      runCurrent()
      assertEquals(30, viewModel.state.value.following.nextOffset)
      assertEquals(30, viewModel.state.value.following.retryOffset)
      assertEquals("opaque/cursor+1", viewModel.state.value.following.lastValue)

      viewModel.retryFollowing()
      runCurrent()
      assertEquals(30 to "opaque/cursor+1", calls.last())
      assertEquals(60, viewModel.state.value.following.nextOffset)
      assertEquals("", viewModel.state.value.following.lastValue)
      viewModel.loadMoreFollowing()
      runCurrent()
      assertEquals(60 to "", calls.last())
      assertEquals(
        listOf("first", "second", "third"),
        viewModel.state.value.following.items.map { it.id },
      )
    }

  private fun story(id: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title = id,
      excerpt = id,
      body = id,
      author = "author",
      date = "date",
    )
}
