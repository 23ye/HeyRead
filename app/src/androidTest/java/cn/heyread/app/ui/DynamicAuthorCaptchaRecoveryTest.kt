package cn.heyread.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.FollowedActivityPage
import cn.heyread.app.data.account.FollowedUser
import cn.heyread.app.data.account.FollowedUsersPage
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.StoryPage
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaProof
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationRepository
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.screens.DynamicFeedScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.DynamicFeedMode
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.UiState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicAuthorCaptchaRecoveryTest {
  @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private var viewModelStore: ViewModelStore? = null

  @After
  fun clearViewModels() {
    compose.activityRule.scenario.onActivity { viewModelStore?.clear() }
    viewModelStore = null
  }

  @Test
  fun inAppProofUsesNestedAppIdFailureCanBeRetriedAndNoProofFailureStaysOrdinary() {
    val requests = CopyOnWriteArrayList<Request>()
    val eventCalls = AtomicInteger()
    val repository =
      DestinationRepository(
        execute = { request ->
          requests += request
          when (request.url.encodedPath) {
            "/bbs/app/profile/events" ->
              when (eventCalls.getAndIncrement()) {
                0 -> momentsResponse("old-story", "此前内容")
                1,
                4 -> """{"status":"show_captcha","result":{"appid":2061805035}}"""
                2,
                5 -> """{"status":"failed","msg":"synthetic failure"}"""
                3 -> momentsResponse("verified-story", "再次读取成功")
                else -> error("Unexpected synthetic events request")
              }
            else -> error("Dynamic author should only request public events")
          }
        }
      )
    val rosterRequests = AtomicInteger()
    val viewModel =
      DynamicFeedViewModel(
        loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
        loadFollowing = { _, _, _ -> FollowedActivityPage(emptyList(), 0, null) },
        loadFollowedUsers = {
          rosterRequests.incrementAndGet()
          FollowedUsersPage(
            listOf(
              FollowedUser("author-1", "作者甲", "https://images.example/author-1.jpg", unread = 1)
            ),
            hasMore = false,
          )
        },
        destinationLoader = repository,
      )
    attach(viewModel)
    viewModel.bindAccount("reader-1", revision = 1, loadingSession = false)
    viewModel.selectMode(DynamicFeedMode.FOLLOWING)
    showScreen(viewModel)
    compose.waitUntil(5_000) { viewModel.state.value.followedUsers.users.size == 1 }
    compose.onNodeWithTag("dynamic-followed-user-author-1").performClick()
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.stories?.any { it.id == "old-story" } == true
    }
    compose.waitUntil(5_000) {
      viewModel.state.value.followedUsers.users.singleOrNull()?.unread == 0
    }
    compose.onNodeWithTag("home-card-A-old-story").assertIsDisplayed()

    compose.runOnIdle { viewModel.refreshFollowedUsers() }
    compose.waitUntil(5_000) {
      viewModel.state.value.followedUsers.revision >= 2 &&
        viewModel.state.value.followedUsers.users.singleOrNull()?.unread == 1
    }
    compose.runOnIdle { viewModel.refreshSelectedAuthor() }
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.captchaRequired == true
    }
    val initialCaptcha = requireNotNull(viewModel.state.value.selectedAuthorContent)
    val initialChallengeId = requireNotNull(initialCaptcha.captchaChallengeId)
    assertEquals("2061805035", initialCaptcha.captchaAppId)
    assertEquals("old-story", viewModel.state.value.selectedAuthorContent?.stories?.single()?.id)
    compose.onNodeWithText("打开验证").assertIsDisplayed()

    val firstAttempt = completeInAppVerification(viewModel, "first-ticket", "first-rand")
    compose.waitUntil(5_000) {
      val state = viewModel.state.value.selectedAuthorContent
      state?.captchaRequired == true &&
        state.captchaChallengeId != initialChallengeId &&
        state.captchaMessage?.contains("验证后读取仍失败(status=failed)") == true
    }
    val retryCaptcha = requireNotNull(viewModel.state.value.selectedAuthorContent)
    val retryChallengeId = requireNotNull(retryCaptcha.captchaChallengeId)
    assertEquals("2061805035", retryCaptcha.captchaAppId)
    assertEquals("old-story", retryCaptcha.stories.single().id)
    assertEquals(1, viewModel.state.value.followedUsers.users.single().unread)
    compose.onNodeWithText("验证后读取仍失败(status=failed)，请重新验证或稍后重试").assertIsDisplayed()
    compose.onNodeWithText("重新验证").assertIsDisplayed()
    compose.onNodeWithTag("home-card-A-old-story").assertIsDisplayed()
    compose
      .onNodeWithTag("dynamic-followed-user-unread-author-1", useUnmergedTree = true)
      .assertDoesNotExist()
    compose
      .onNodeWithContentDescription("作者甲，已选中，有新动态", useUnmergedTree = true)
      .assertDoesNotExist()

    val requestsBeforeStaleCallback = requests.size
    compose.runOnIdle {
      val active = viewModel.state.value
      val token = requireNotNull(active.selectedAuthorScopeToken)
      viewModel.onSelectedAuthorCaptchaCallback(
        token,
        "author-1",
        DestinationFeedType.MOMENTS,
        initialChallengeId,
        firstAttempt.second,
        CaptchaCallbackResult.Completed(CaptchaProof("stale-ticket", "stale-rand")),
      )
    }
    compose.waitForIdle()
    assertEquals(requestsBeforeStaleCallback, requests.size)
    assertEquals(retryChallengeId, viewModel.state.value.selectedAuthorContent?.captchaChallengeId)
    assertEquals(1, viewModel.state.value.followedUsers.users.single().unread)

    completeInAppVerification(viewModel, "second-ticket", "second-rand")
    compose.waitUntil(5_000) {
      val state = viewModel.state.value
      state.selectedAuthorContent?.captchaRequired == false &&
        state.selectedAuthorContent?.stories?.any { it.id == "verified-story" } == true &&
        state.followedUsers.users.singleOrNull()?.unread == 0
    }
    compose.onNodeWithTag("home-card-A-verified-story").assertIsDisplayed()
    compose
      .onNodeWithTag("dynamic-followed-user-unread-author-1", useUnmergedTree = true)
      .assertDoesNotExist()

    compose.runOnIdle { viewModel.refreshFollowedUsers() }
    compose.waitUntil(5_000) {
      viewModel.state.value.followedUsers.revision >= 3 &&
        viewModel.state.value.followedUsers.users.singleOrNull()?.unread == 1
    }
    compose.runOnIdle { viewModel.refreshSelectedAuthor() }
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.captchaRequired == true
    }
    compose.runOnIdle { viewModel.retrySelectedAuthorAfterOfficialVerification() }
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.error != null &&
        viewModel.state.value.selectedAuthorContent?.captchaRequired == false
    }
    assertNull(viewModel.state.value.selectedAuthorContent?.captchaMessage)
    assertFalse(viewModel.state.value.selectedAuthorContent?.captchaRequired == true)
    assertEquals(1, viewModel.state.value.followedUsers.users.single().unread)
    compose
      .onNodeWithTag("dynamic-followed-user-unread-author-1", useUnmergedTree = true)
      .assertDoesNotExist()
    compose
      .onNodeWithContentDescription("作者甲，已选中，有新动态", useUnmergedTree = true)
      .assertDoesNotExist()
    val ordinaryError = requireNotNull(viewModel.state.value.selectedAuthorContent?.error)
    compose.onNodeWithText(ordinaryError).performScrollTo().assertIsDisplayed()

    val eventRequests = requests.filter { it.url.encodedPath == "/bbs/app/profile/events" }
    assertEquals(6, eventRequests.size)
    assertEquals("2061805035", initialCaptcha.captchaAppId)
    assertEquals("reader-1", viewModel.state.value.accountUserId)
    for (request in eventRequests) {
      assertEquals("author-1", request.url.queryParameter("userid"))
      assertEquals("moment", request.url.queryParameter("list_type"))
      assertEquals("304", request.url.queryParameter("dw"))
      assertEquals("web", request.url.queryParameter("os_type"))
      assertEquals("heybox", request.url.queryParameter("app"))
      assertEquals("web", request.url.queryParameter("x_client_type"))
      assertEquals("Mac", request.url.queryParameter("x_os_type"))
      assertEquals("heybox", request.url.queryParameter("x_app"))
      assertEquals("999.0.4", request.url.queryParameter("version"))
      assertNull(request.header("Cookie"))
    }
    assertEquals("", eventRequests[1].url.queryParameter("lastval"))
    assertEquals("confirm", eventRequests[2].url.queryParameter("captcha_type"))
    assertEquals("first-ticket", eventRequests[2].url.queryParameter("ticket"))
    assertEquals("first-rand", eventRequests[2].url.queryParameter("randstr"))
    assertEquals("confirm", eventRequests[3].url.queryParameter("captcha_type"))
    assertEquals("second-ticket", eventRequests[3].url.queryParameter("ticket"))
    assertEquals("second-rand", eventRequests[3].url.queryParameter("randstr"))
    assertNull(eventRequests[5].url.queryParameter("captcha_type"))
    assertNull(eventRequests[5].url.queryParameter("ticket"))
    assertNotEquals(
      eventRequests[2].url.queryParameter("hkey"),
      eventRequests[3].url.queryParameter("hkey"),
    )
    assertTrue(rosterRequests.get() >= 3)
  }

  private fun completeInAppVerification(
    viewModel: DynamicFeedViewModel,
    ticket: String,
    randstr: String,
  ): Pair<Long, Long> {
    lateinit var identifiers: Pair<Long, Long>
    var scope: Triple<Long, String, DestinationFeedType>? = null
    var challengeId: Long? = null
    compose.runOnIdle {
      val state = viewModel.state.value
      val scopeToken = requireNotNull(state.selectedAuthorScopeToken)
      val userId = requireNotNull(state.selectedAuthor).userId
      val feedType = state.selectedAuthorFeedType
      val currentChallengeId = requireNotNull(state.selectedAuthorContent?.captchaChallengeId)
      scope = Triple(scopeToken, userId, feedType)
      challengeId = currentChallengeId
      viewModel.openSelectedAuthorCaptcha(scopeToken, userId, feedType)
    }
    val (scopeToken, userId, feedType) = requireNotNull(scope)
    val currentChallengeId = requireNotNull(challengeId)
    compose.waitUntil(5_000) {
      val state = viewModel.state.value
      state.selectedAuthorContent?.captchaChallengeId == currentChallengeId &&
        state.selectedAuthorContent?.captchaAttemptId != null
    }
    val attemptId = requireNotNull(viewModel.state.value.selectedAuthorContent?.captchaAttemptId)
    identifiers = currentChallengeId to attemptId
    compose.runOnIdle {
      viewModel.onSelectedAuthorCaptchaCallback(
        scopeToken,
        userId,
        feedType,
        currentChallengeId,
        attemptId,
        CaptchaCallbackResult.Completed(CaptchaProof(ticket, randstr)),
      )
    }
    return identifiers
  }

  private fun momentsResponse(id: String, title: String) =
    """{"status":"ok","result":{"moments":[{"link":{"linkid":"$id","title":"$title","description":"合成验证内容","user":{"userid":"author-1","username":"作者甲"}}}],"lastval":""}}"""

  private fun showScreen(viewModel: DynamicFeedViewModel) {
    compose.setContent {
      val state by viewModel.state.collectAsState()
      CompositionLocalProvider(LocalPaneResizeEpoch provides 0) {
        HeyReadTheme("light", motionEnabled = false) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DynamicFeedScreen(
              state = state,
              ui = UiState(homeLayout = HomeLayout.A, noImages = true),
              viewModel = viewModel,
              isCurrentPage = true,
              onLogin = {},
              onOpenStory = {},
            )
          }
        }
      }
    }
  }

  private fun attach(viewModel: DynamicFeedViewModel) {
    val owner = ViewModelStore()
    viewModelStore = owner
    compose.activityRule.scenario.onActivity { owner.put("dynamic-author-captcha", viewModel) }
  }
}
