package cn.heyread.app.viewmodel

import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.account.FollowedActivityPage
import cn.heyread.app.data.account.FollowedUser
import cn.heyread.app.data.account.FollowedUsersPage
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
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
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicFollowFilterViewModelTest {
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
  fun guestAndRestoringSessionDoNotRequestFollowingData() =
    runTest(dispatcher) {
      var rosterCalls = 0
      var activityCalls = 0
      val viewModel =
        viewModel(
          loadFollowing = { _, _, _ ->
            activityCalls++
            activityPage("activity")
          },
          loadFollowedUsers = {
            rosterCalls++
            FollowedUsersPage(emptyList(), false)
          },
        )

      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      viewModel.ensureFollowingLoaded()
      viewModel.bindAccount("reader-a", revision = 1, loadingSession = true)
      viewModel.ensureFollowingLoaded()
      runCurrent()

      assertEquals(0, rosterCalls)
      assertEquals(0, activityCalls)
      viewModel.bindAccount("reader-a", revision = 1, loadingSession = false)
      viewModel.ensureFollowingLoaded()
      runCurrent()
      assertEquals(1, rosterCalls)
      assertEquals(1, activityCalls)
    }

  @Test
  fun authorMomentsArticlesAndPaginationKeepIndependentCaches() =
    runTest(dispatcher) {
      val calls = mutableListOf<Triple<String, DestinationFeedType, Int>>()
      val loader = DestinationLoader { target, offset, _, lastValue, _, type, _, _, _, _ ->
        calls += Triple(target.id, type, offset)
        if (offset > 0) assertEquals("${target.id}-${type.name}-cursor", lastValue)
        DestinationPage(
          DestinationData(target.name),
          listOf(story("${target.id}-${type.name}-$offset")),
          offset + 30,
          "${target.id}-${type.name}-cursor",
          hasMore = offset == 0,
        )
      }
      val viewModel =
        viewModel(
          loadFollowedUsers = {
            FollowedUsersPage(listOf(user("author-a"), user("author-b")), false)
          },
          destinationLoader = loader,
        )
      showFollowing(viewModel)

      viewModel.selectAuthor("author-a")
      runCurrent()
      assertEquals(DestinationType.USER, viewModel.state.value.selectedAuthorContent?.target?.type)
      assertEquals(
        DestinationFeedType.MOMENTS,
        viewModel.state.value.selectedAuthorContent?.feedType,
      )

      viewModel.loadMoreSelectedAuthor()
      runCurrent()
      viewModel.selectAuthorFeedType(DestinationFeedType.ARTICLES)
      runCurrent()
      assertEquals(
        DestinationFeedType.ARTICLES,
        viewModel.state.value.selectedAuthorContent?.feedType,
      )
      viewModel.selectAuthorFeedType(DestinationFeedType.MOMENTS)
      runCurrent()
      assertEquals(
        listOf("author-a-MOMENTS-0", "author-a-MOMENTS-30"),
        viewModel.state.value.selectedAuthorContent?.stories?.map { it.id },
      )

      viewModel.selectAuthor("author-b")
      runCurrent()
      viewModel.selectAuthor("author-a")
      runCurrent()
      assertEquals(
        DestinationFeedType.MOMENTS,
        viewModel.state.value.selectedAuthorContent?.feedType,
      )
      assertEquals(4, calls.size)
      assertEquals(
        listOf(
          Triple("author-a", DestinationFeedType.MOMENTS, 0),
          Triple("author-a", DestinationFeedType.MOMENTS, 30),
          Triple("author-a", DestinationFeedType.ARTICLES, 0),
          Triple("author-b", DestinationFeedType.MOMENTS, 0),
        ),
        calls,
      )
    }

  @Test
  fun lateRosterResponseAndSessionExpiryCannotCrossAccountRevision() =
    runTest(dispatcher) {
      val oldRoster = CompletableDeferred<Unit>()
      var expiredCallbacks = 0
      val rosterCalls = mutableListOf<String>()
      val viewModel =
        viewModel(
          onSessionExpired = { expiredCallbacks++ },
          loadFollowedUsers = { userId ->
            rosterCalls += userId
            if (userId == "reader-a") {
              withContext(NonCancellable) {
                oldRoster.await()
                throw AccountSessionExpiredException()
              }
            }
            FollowedUsersPage(listOf(user("reader-b-author")), false)
          },
        )
      showFollowing(viewModel, accountId = "reader-a", revision = 1)
      assertTrue(viewModel.state.value.followedUsers.loading)

      viewModel.bindAccount("reader-b", revision = 2, loadingSession = false)
      viewModel.ensureFollowingLoaded()
      runCurrent()
      assertEquals(
        listOf("reader-b-author"),
        viewModel.state.value.followedUsers.users.map { it.userId },
      )
      oldRoster.complete(Unit)
      runCurrent()

      assertEquals(listOf("reader-a", "reader-b"), rosterCalls)
      assertEquals(0, expiredCallbacks)
      assertEquals("reader-b", viewModel.state.value.accountUserId)
      assertEquals(
        listOf("reader-b-author"),
        viewModel.state.value.followedUsers.users.map { it.userId },
      )
    }

  @Test
  fun lateAuthorResponseAfterAccountSwitchCannotReplaceNewAccountOrClearItsUnread() =
    runTest(dispatcher) {
      val oldAuthor = CompletableDeferred<Unit>()
      val viewModel =
        viewModel(
          loadFollowedUsers = { account ->
            FollowedUsersPage(
              listOf(
                user(
                  "shared-author",
                  unread = if (account == "reader-b") 7 else 3,
                  name = if (account == "reader-b") "Account B author" else "Account A author",
                )
              ),
              false,
            )
          },
          destinationLoader =
            DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
              if (target.id == "shared-author" && target.name == "Account A author") {
                withContext(NonCancellable) { oldAuthor.await() }
              }
              authorPage(target, listOf(story(target.name)))
            },
        )
      showFollowing(viewModel, accountId = "reader-a", revision = 1)
      viewModel.selectAuthor("shared-author")
      runCurrent()

      viewModel.bindAccount("reader-b", revision = 2, loadingSession = false)
      viewModel.ensureFollowingLoaded()
      runCurrent()
      viewModel.selectAuthor("shared-author")
      runCurrent()
      oldAuthor.complete(Unit)
      runCurrent()

      assertEquals("reader-b", viewModel.state.value.accountUserId)
      assertEquals("shared-author", viewModel.state.value.selectedAuthor?.userId)
      assertEquals(0, viewModel.state.value.selectedAuthor?.unread)
      assertEquals(
        listOf("Account B author"),
        viewModel.state.value.selectedAuthorContent?.stories?.map { it.id },
      )
    }

  @Test
  fun hiddenModeCancelsAuthorRequestAndDoesNotClearUnreadOnLateResult() =
    runTest(dispatcher) {
      val release = CompletableDeferred<Unit>()
      val viewModel =
        viewModel(
          loadFollowedUsers = { FollowedUsersPage(listOf(user("author-a", unread = 5)), false) },
          destinationLoader =
            DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
              withContext(NonCancellable) { release.await() }
              authorPage(target, listOf(story("late-content")))
            },
        )
      showFollowing(viewModel)
      viewModel.selectAuthor("author-a")
      runCurrent()

      viewModel.selectMode(DynamicFeedMode.RECOMMENDED)
      release.complete(Unit)
      runCurrent()

      assertEquals(5, viewModel.state.value.followedUsers.users.single().unread)
      assertEquals(DynamicFeedMode.RECOMMENDED, viewModel.state.value.mode)
      assertTrue(viewModel.state.value.selectedAuthorContent?.stories.isNullOrEmpty())
    }

  @Test
  fun successfulAuthorReadClearsUnreadButErrorAndCaptchaKeepIt() =
    runTest(dispatcher) {
      val challenge =
        CaptchaRequiredException(
          CaptchaChallenge("/bbs/app/profile/events", mapOf("userid" to "author-c"), "199251710")
        )
      val viewModel =
        viewModel(
          loadFollowedUsers = {
            FollowedUsersPage(
              listOf(
                user("success", unread = 2),
                user("failure", unread = 3),
                user("challenge", unread = 4),
              ),
              false,
            )
          },
          destinationLoader =
            DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
              when (target.id) {
                "failure" -> throw IOException("temporary read failure")
                "challenge" -> throw challenge
                else -> authorPage(target, listOf(story("success")))
              }
            },
        )
      showFollowing(viewModel)

      viewModel.selectAuthor("success")
      runCurrent()
      assertEquals(0, viewModel.state.value.selectedAuthor?.unread)

      viewModel.selectAuthor("failure")
      runCurrent()
      assertEquals(3, viewModel.state.value.selectedAuthor?.unread)
      assertTrue(viewModel.state.value.selectedAuthorContent?.error?.contains("temporary") == true)

      viewModel.selectAuthor("challenge")
      runCurrent()
      assertEquals(4, viewModel.state.value.selectedAuthor?.unread)
      assertTrue(viewModel.state.value.selectedAuthorContent?.captchaRequired == true)
    }

  @Test
  fun refreshedRosterUnreadIsNotClearedByAnOlderAuthorCompletion() =
    runTest(dispatcher) {
      var rosterCalls = 0
      val releaseAuthor = CompletableDeferred<Unit>()
      val viewModel =
        viewModel(
          loadFollowedUsers = {
            rosterCalls++
            FollowedUsersPage(
              listOf(user("author-a", unread = if (rosterCalls == 1) 2 else 9)),
              false,
            )
          },
          destinationLoader =
            DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
              withContext(NonCancellable) { releaseAuthor.await() }
              authorPage(target, listOf(story("old-completion")))
            },
        )
      showFollowing(viewModel)
      viewModel.selectAuthor("author-a")
      runCurrent()
      viewModel.refreshFollowedUsers()
      runCurrent()
      assertEquals(9, viewModel.state.value.selectedAuthor?.unread)

      releaseAuthor.complete(Unit)
      runCurrent()

      assertEquals(9, viewModel.state.value.followedUsers.users.single().unread)
      assertEquals(9, viewModel.state.value.selectedAuthor?.unread)
    }

  @Test
  fun failedRosterRefreshPreservesExistingUnreadMarkers() =
    runTest(dispatcher) {
      var calls = 0
      val viewModel =
        viewModel(
          loadFollowedUsers = {
            if (++calls == 1) FollowedUsersPage(listOf(user("author-a", unread = 6)), false)
            else throw IOException("roster unavailable")
          }
        )
      showFollowing(viewModel)

      viewModel.refreshFollowedUsers()
      runCurrent()

      assertEquals(6, viewModel.state.value.followedUsers.users.single().unread)
      assertTrue(viewModel.state.value.followedUsers.error?.isNotBlank() == true)
    }

  @Test
  fun authorScopesAreBoundedAndEvictionForcesANewLoad() =
    runTest(dispatcher) {
      var authorLoads = 0
      val users = (0..8).map { user("author-$it") }
      val viewModel =
        viewModel(
          loadFollowedUsers = { FollowedUsersPage(users, false) },
          destinationLoader =
            DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
              authorLoads++
              authorPage(target, listOf(story(target.id)))
            },
        )
      showFollowing(viewModel)

      users.forEach { author ->
        viewModel.selectAuthor(author.userId)
        runCurrent()
      }
      assertEquals("author-0", viewModel.state.value.evictedAuthorId)
      assertEquals(9, authorLoads)

      viewModel.selectAuthor("author-0")
      runCurrent()
      assertEquals(10, authorLoads)
      assertEquals("author-0", viewModel.state.value.selectedAuthor?.userId)
    }

  @Test
  fun staleCaptchaCallbackFromPreviousAuthorCannotSubmitProofToNextAuthor() =
    runTest(dispatcher) {
      val receivedProofs = mutableListOf<Pair<String, CaptchaProof?>>()
      val viewModel =
        viewModel(
          loadFollowedUsers = {
            FollowedUsersPage(listOf(user("author-a"), user("author-b")), false)
          },
          destinationLoader =
            DestinationLoader { target, _, _, _, _, _, _, _, suppliedChallenge, proof ->
              if (suppliedChallenge == null) throw captcha(target.id)
              receivedProofs += target.id to proof
              authorPage(target, listOf(story("verified-${target.id}")))
            },
        )
      showFollowing(viewModel)
      viewModel.selectAuthor("author-a")
      runCurrent()
      val authorAToken = viewModel.state.value.selectedAuthorScopeToken!!
      viewModel.openSelectedAuthorCaptcha(authorAToken, "author-a", DestinationFeedType.MOMENTS)
      runCurrent()
      val authorAChallenge = viewModel.state.value.selectedAuthorContent!!.captchaChallengeId!!
      val authorAAttempt = viewModel.state.value.selectedAuthorContent!!.captchaAttemptId!!

      viewModel.selectAuthor("author-b")
      runCurrent()
      val authorBToken = viewModel.state.value.selectedAuthorScopeToken!!
      viewModel.openSelectedAuthorCaptcha(authorBToken, "author-b", DestinationFeedType.MOMENTS)
      runCurrent()
      val authorBChallenge = viewModel.state.value.selectedAuthorContent!!.captchaChallengeId!!
      val authorBAttempt = viewModel.state.value.selectedAuthorContent!!.captchaAttemptId!!
      assertEquals(authorAChallenge, authorBChallenge)
      assertEquals(authorAAttempt, authorBAttempt)

      viewModel.onSelectedAuthorCaptchaCallback(
        authorAToken,
        "author-a",
        DestinationFeedType.MOMENTS,
        authorAChallenge,
        authorAAttempt,
        CaptchaCallbackResult.Completed(CaptchaProof("old-ticket", "old-rand")),
      )
      runCurrent()
      assertTrue(receivedProofs.isEmpty())

      viewModel.selectAuthorFeedType(DestinationFeedType.ARTICLES)
      runCurrent()
      viewModel.onSelectedAuthorCaptchaCallback(
        authorBToken,
        "author-b",
        DestinationFeedType.MOMENTS,
        authorBChallenge,
        authorBAttempt,
        CaptchaCallbackResult.Completed(CaptchaProof("stale-type-ticket", "stale-type-rand")),
      )
      runCurrent()
      assertTrue(receivedProofs.isEmpty())

      viewModel.openSelectedAuthorCaptcha(authorBToken, "author-b", DestinationFeedType.ARTICLES)
      runCurrent()
      val articleContent = viewModel.state.value.selectedAuthorContent!!
      val articleChallenge = articleContent.captchaChallengeId!!
      val articleAttempt = articleContent.captchaAttemptId!!

      val proof = CaptchaProof("b-ticket", "b-rand")
      viewModel.onSelectedAuthorCaptchaCallback(
        authorBToken,
        "author-b",
        DestinationFeedType.ARTICLES,
        articleChallenge,
        articleAttempt,
        CaptchaCallbackResult.Completed(proof),
      )
      runCurrent()
      assertEquals(listOf("author-b" to proof), receivedProofs)
    }

  @Test
  fun canceledAppendCanBeManuallyContinuedWithItsSavedCursor() =
    runTest(dispatcher) {
      val appendStarted = CompletableDeferred<Unit>()
      val releaseAppend = CompletableDeferred<Unit>()
      val offsets = mutableListOf<Pair<Int, String?>>()
      val viewModel =
        viewModel(
          loadFollowedUsers = { FollowedUsersPage(listOf(user("author-a")), false) },
          destinationLoader =
            DestinationLoader { target, offset, _, lastValue, _, type, _, _, _, _ ->
              offsets += offset to lastValue
              if (offset == 30 && offsets.count { it.first == 30 } == 1) {
                appendStarted.complete(Unit)
                withContext(NonCancellable) { releaseAppend.await() }
              }
              DestinationPage(
                DestinationData(target.name),
                listOf(story("${type.name}-$offset")),
                offset + 30,
                "cursor-$offset",
                hasMore = offset == 0,
              )
            },
        )
      showFollowing(viewModel)
      viewModel.selectAuthor("author-a")
      runCurrent()
      viewModel.loadMoreSelectedAuthor()
      runCurrent()
      appendStarted.await()

      viewModel.selectMode(DynamicFeedMode.RECOMMENDED)
      releaseAppend.complete(Unit)
      runCurrent()
      viewModel.selectMode(DynamicFeedMode.FOLLOWING)
      runCurrent()
      viewModel.selectAuthor("author-a")
      runCurrent()
      viewModel.loadMoreSelectedAuthor()
      runCurrent()

      assertEquals(listOf(0 to "", 30 to "cursor-0", 30 to "cursor-0"), offsets)
    }

  private fun TestScope.showFollowing(
    viewModel: DynamicFeedViewModel,
    accountId: String = "reader-a",
    revision: Long = 1,
  ) {
    viewModel.bindAccount(accountId, revision, loadingSession = false)
    viewModel.selectMode(DynamicFeedMode.FOLLOWING)
    viewModel.ensureFollowingLoaded()
    runCurrent()
  }

  private fun viewModel(
    loadFollowing: suspend (String, Int, String?) -> FollowedActivityPage = { _, _, _ ->
      FollowedActivityPage(emptyList(), 0, "")
    },
    onSessionExpired: () -> Unit = {},
    loadFollowedUsers: suspend (String) -> FollowedUsersPage = {
      FollowedUsersPage(emptyList(), false)
    },
    destinationLoader: DestinationLoader = DestinationLoader { target, _, _, _, _, _, _, _, _, _ ->
      authorPage(target, emptyList())
    },
  ) =
    DynamicFeedViewModel(
      loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
      loadFollowing = loadFollowing,
      onSessionExpired = onSessionExpired,
      loadFollowedUsers = loadFollowedUsers,
      destinationLoader = destinationLoader,
    )

  private fun activityPage(id: String) = FollowedActivityPage(listOf(story(id)), 1, "")

  private fun authorPage(target: DestinationTarget, stories: List<Story>) =
    DestinationPage(
      data = DestinationData(target.name),
      stories = stories,
      nextOffset = 30,
      lastValue = "cursor-0",
      hasMore = true,
    )

  private fun captcha(userId: String) =
    CaptchaRequiredException(
      CaptchaChallenge(
        "/bbs/app/profile/events",
        mapOf("userid" to userId, "list_type" to "moment", "lastval" to ""),
        "199251710",
      )
    )

  private fun user(userId: String, unread: Int = 0, name: String = "Account $userId author") =
    FollowedUser(userId, name, null, unread)

  private fun story(id: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title = id,
      excerpt = id,
      body = id,
      author = id,
      date = "date",
    )
}
