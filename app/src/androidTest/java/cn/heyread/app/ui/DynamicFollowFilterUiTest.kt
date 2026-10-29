package cn.heyread.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.FollowedActivityPage
import cn.heyread.app.data.account.FollowedUser
import cn.heyread.app.data.account.FollowedUsersPage
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationPage
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.screens.DynamicFeedScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.DynamicFeedMode
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.UiState
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicFollowFilterUiTest {
  @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private var viewModelStore: ViewModelStore? = null

  @After
  fun clearViewModels() {
    compose.activityRule.scenario.onActivity { viewModelStore?.clear() }
    viewModelStore = null
  }

  @Test
  fun authorSelectionShowsPublicMomentsAndArticlesAndClearsUnreadOnlyAfterSuccess() {
    val authorCalls = CopyOnWriteArrayList<Pair<String, DestinationFeedType>>()
    val viewModel = createViewModel(authorCalls)
    attach(viewModel)
    viewModel.bindAccount("reader-1", revision = 1, loadingSession = false)
    viewModel.selectMode(DynamicFeedMode.FOLLOWING)
    compose.waitUntil(5_000) { viewModel.state.value.followedUsers.users.size == 2 }

    compose.setContent {
      val state by viewModel.state.collectAsState()
      CompositionLocalProvider(LocalPaneResizeEpoch provides 0) {
        HeyReadTheme("light", motionEnabled = false) {
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
    compose.waitForIdle()
    saveScreenshot("follow-light")

    compose
      .onNodeWithTag("dynamic-followed-user-unread-author-1", useUnmergedTree = true)
      .assertDoesNotExist()
    compose.onNodeWithContentDescription("作者甲，有新动态", useUnmergedTree = true).assertDoesNotExist()
    assertEquals(
      1,
      viewModel.state.value.followedUsers.users.first { it.userId == "author-1" }.unread,
    )
    compose.onNodeWithTag("dynamic-followed-user-author-1").performClick()
    compose.waitUntil(5_000) {
      val state = viewModel.state.value
      (state.selectedAuthorContent?.successfulLoadId ?: 0L) > 0L &&
        state.followedUsers.users.firstOrNull { it.userId == "author-1" }?.unread == 0
    }
    compose.onNodeWithTag("dynamic-author-feed-heading").assertIsDisplayed()
    compose.onNodeWithTag("dynamic-author-type-moments").assertIsDisplayed()
    compose.onNodeWithTag("home-card-A-author-moments").assertIsDisplayed()
    compose
      .onNodeWithTag("dynamic-followed-user-unread-author-1", useUnmergedTree = true)
      .assertDoesNotExist()
    compose.waitForIdle()
    SystemClock.sleep(450)
    compose.waitForIdle()
    saveScreenshot("follow-author-light")

    compose.onNodeWithTag("dynamic-author-type-articles").performClick()
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.feedType == DestinationFeedType.ARTICLES &&
        viewModel.state.value.selectedAuthorContent?.stories?.any { it.id == "author-articles" } ==
          true
    }
    compose.onNodeWithTag("home-card-A-author-articles").assertIsDisplayed()
    assertEquals(
      listOf(DestinationFeedType.MOMENTS, DestinationFeedType.ARTICLES),
      authorCalls.map { it.second }.toList(),
    )

    compose.onNodeWithTag("dynamic-followed-user-all").performClick()
    compose.onNodeWithTag("dynamic-follow-source").assertIsDisplayed()
    compose.onNodeWithTag("home-card-A-following-all").assertIsDisplayed()
  }

  @Test
  fun recommendationHidesFollowedUsersAndNoImagesDoesNotMountAvatarRequests() {
    val viewModel = createViewModel(CopyOnWriteArrayList())
    attach(viewModel)
    viewModel.bindAccount("reader-1", revision = 1, loadingSession = false)
    viewModel.selectMode(DynamicFeedMode.FOLLOWING)
    compose.waitUntil(5_000) { viewModel.state.value.followedUsers.users.size == 2 }

    compose.setContent {
      val state by viewModel.state.collectAsState()
      CompositionLocalProvider(LocalPaneResizeEpoch provides 0) {
        HeyReadTheme("light", motionEnabled = false) {
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
    compose.waitForIdle()
    compose.onNodeWithTag("dynamic-followed-user-author-1").assertIsDisplayed()
    compose.onNodeWithContentDescription("作者甲 头像", useUnmergedTree = true).assertDoesNotExist()

    compose.onNodeWithTag("dynamic-mode-recommended").performClick()
    compose.onNodeWithTag("dynamic-followed-users-header").assertDoesNotExist()
    compose.onNodeWithTag("dynamic-mode-following").performClick()
    compose.onNodeWithTag("dynamic-followed-users-header").assertIsDisplayed()
  }

  @Test
  fun horizontalSwipeSelectsFarAuthorAndRestoresEachFeedPosition() {
    val viewModel = createScrollingViewModel()
    attach(viewModel)
    viewModel.bindAccount("reader-1", revision = 1, loadingSession = false)
    viewModel.selectMode(DynamicFeedMode.FOLLOWING)
    compose.waitUntil(5_000) { viewModel.state.value.followedUsers.users.size == 8 }

    showScreen(viewModel)
    compose.waitForIdle()

    compose.onNodeWithTag("dynamic-followed-user-row").performTouchInput {
      repeat(3) { swipeLeft() }
    }
    compose.onNodeWithTag("dynamic-followed-user-author-8").assertIsDisplayed().performClick()
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.successfulLoadId?.let { it > 0L } == true
    }
    compose.onNodeWithTag("dynamic-followed-user-author-8").assertIsSelected()

    scrollFollowingTo(7)
    compose.onNodeWithTag("home-card-A-author-8-moments-5").assertIsDisplayed()
    compose.runOnIdle { viewModel.selectAuthorFeedType(DestinationFeedType.ARTICLES) }
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.feedType == DestinationFeedType.ARTICLES &&
        viewModel.state.value.selectedAuthorContent?.stories?.size == 12
    }
    scrollFollowingTo(8)
    compose.onNodeWithTag("home-card-A-author-8-articles-6").assertIsDisplayed()

    compose.runOnIdle { viewModel.selectAuthorFeedType(DestinationFeedType.MOMENTS) }
    compose.waitForIdle()
    compose.onNodeWithTag("home-card-A-author-8-moments-5").assertIsDisplayed()

    compose.runOnIdle { viewModel.selectAuthor("author-1") }
    compose.waitUntil(5_000) {
      viewModel.state.value.selectedAuthorContent?.successfulLoadId?.let { it > 0L } == true
    }
    compose.runOnIdle { viewModel.selectAuthor("author-8") }
    compose.waitForIdle()
    compose.onNodeWithTag("home-card-A-author-8-moments-5").assertIsDisplayed()

    compose.runOnIdle { viewModel.selectAuthor(null) }
    scrollFollowingTo(6)
    compose.onNodeWithTag("home-card-A-following-5").assertIsDisplayed()
    compose.onNodeWithTag("dynamic-mode-recommended").performClick()
    compose.waitUntil(5_000) { viewModel.state.value.recommendations.items.size == 12 }
    scrollRecommendationsTo(3)
    compose.onNodeWithTag("home-card-A-recommended-3").assertIsDisplayed()

    compose.onNodeWithTag("dynamic-mode-following").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag("home-card-A-following-5").assertIsDisplayed()
    compose.onNodeWithTag("dynamic-mode-recommended").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag("home-card-A-recommended-3").assertIsDisplayed()
    compose.onNodeWithTag("dynamic-mode-following").performClick()
    scrollFollowingTo(0)
    compose.onNodeWithTag("dynamic-followed-user-author-8").assertIsDisplayed()
    compose.onNodeWithTag("dynamic-followed-user-author-8").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag("home-card-A-author-8-moments-5").assertIsDisplayed()
    compose.runOnIdle { viewModel.selectAuthorFeedType(DestinationFeedType.ARTICLES) }
    compose.waitForIdle()
    compose.onNodeWithTag("home-card-A-author-8-articles-6").assertIsDisplayed()
  }

  @Test
  fun narrowLargeTextDarkThemeKeepsFilterAndAvatarTargetsUsable() {
    val viewModel = createViewModel(CopyOnWriteArrayList())
    attach(viewModel)
    viewModel.bindAccount("reader-1", revision = 1, loadingSession = false)
    viewModel.selectMode(DynamicFeedMode.FOLLOWING)
    compose.waitUntil(5_000) { viewModel.state.value.followedUsers.users.size == 2 }

    compose.setContent {
      val state by viewModel.state.collectAsState()
      val density = LocalDensity.current
      CompositionLocalProvider(
        LocalPaneResizeEpoch provides 0,
        androidx.compose.ui.platform.LocalDensity provides Density(density.density, 1.3f),
      ) {
        HeyReadTheme("dark", motionEnabled = false) {
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Surface(
              modifier = Modifier.requiredWidth(210.dp).height(760.dp),
              color = MaterialTheme.colorScheme.background,
            ) {
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
    compose.waitForIdle()

    val screenBounds = compose.onNodeWithTag("dynamic-feed-screen").getBoundsInRoot()
    val screenWidthDp = (screenBounds.right - screenBounds.left).value
    assertEquals(210f, screenWidthDp, 0.5f)
    compose.onNodeWithTag("dynamic-followed-user-all").assertHeightIsAtLeast(48.dp)
    compose.onNodeWithTag("dynamic-followed-user-author-1").assertHeightIsAtLeast(48.dp)
    compose.onNodeWithTag("dynamic-followed-users-header").assertIsDisplayed()
    saveScreenshot("follow-dark-210dp-font130")
    compose.onNodeWithTag("dynamic-mode-following").performTouchInput { swipeLeft() }
    val recommendedBounds =
      compose
        .onNodeWithTag("dynamic-mode-recommended")
        .assertIsDisplayed()
        .fetchSemanticsNode()
        .boundsInRoot
    val densityScale = compose.activity.resources.displayMetrics.density
    assertTrue(
      "FilterChip visible height should remain the normal 32dp design height",
      recommendedBounds.height >= 32f * densityScale,
    )
    compose.onRoot().performTouchInput {
      click(Offset(recommendedBounds.center.x, recommendedBounds.top - 7f * densityScale))
    }
    compose.onNodeWithTag("dynamic-mode-recommended").assertIsSelected()
    compose.onNodeWithTag("dynamic-followed-users-header").assertDoesNotExist()
  }

  private fun createViewModel(
    authorCalls: MutableList<Pair<String, DestinationFeedType>>
  ): DynamicFeedViewModel {
    val authorLoader = DestinationLoader { target, offset, _, lastValue, _, feedType, _, _, _, _ ->
      authorCalls += target.id to feedType
      val story =
        when (feedType) {
          DestinationFeedType.MOMENTS -> authorStory("author-moments")
          DestinationFeedType.ARTICLES -> authorStory("author-articles")
        }
      DestinationPage(
        data = DestinationData(target.name, target.iconUrl),
        stories = listOf(story),
        nextOffset = if (feedType == DestinationFeedType.ARTICLES) offset + 30 else 0,
        lastValue = if (feedType == DestinationFeedType.MOMENTS) "next-$lastValue" else null,
        hasMore = false,
      )
    }
    return DynamicFeedViewModel(
      loadRecommendations = { _, _ -> StoryPage(emptyList(), hasMore = false) },
      loadFollowing = { _, _, _ ->
        FollowedActivityPage(listOf(authorStory("following-all")), 1, null)
      },
      loadFollowedUsers = {
        FollowedUsersPage(
          users =
            listOf(
              FollowedUser("author-1", "作者甲", "https://images.example/author-1.jpg", unread = 1),
              FollowedUser("author-2", "作者乙", "https://images.example/author-2.jpg", unread = 0),
            ),
          hasMore = true,
        )
      },
      destinationLoader = authorLoader,
    )
  }

  private fun createScrollingViewModel(): DynamicFeedViewModel {
    val users =
      (1..8).map { index ->
        FollowedUser(
          "author-$index",
          "作者$index",
          "https://images.example/author-$index.jpg",
          unread = 0,
        )
      }
    val authorLoader = DestinationLoader { target, _, _, _, _, feedType, _, _, _, _ ->
      val scope = if (feedType == DestinationFeedType.MOMENTS) "moments" else "articles"
      DestinationPage(
        data = DestinationData(target.name, target.iconUrl),
        stories = (1..12).map { authorStory("${target.id}-$scope-$it") },
        nextOffset = 30,
        lastValue = "next",
        hasMore = false,
      )
    }
    return DynamicFeedViewModel(
      loadRecommendations = { _, _ ->
        StoryPage((1..12).map { authorStory("recommended-$it") }, hasMore = false)
      },
      loadFollowing = { _, _, _ ->
        FollowedActivityPage((1..12).map { authorStory("following-$it") }, 12, null)
      },
      loadFollowedUsers = { FollowedUsersPage(users, hasMore = true) },
      destinationLoader = authorLoader,
    )
  }

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

  private fun scrollFollowingTo(index: Int) {
    compose
      .onNode(
        hasScrollToIndexAction() and
          hasAnyAncestor(hasTestTag("dynamic-follow-list")) and
          !hasTestTag("dynamic-followed-user-row") and
          !hasAnyAncestor(hasTestTag("dynamic-followed-user-row")),
        useUnmergedTree = true,
      )
      .performScrollToIndex(index)
  }

  private fun scrollRecommendationsTo(index: Int) {
    compose
      .onNode(
        hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("dynamic-recommend-list")),
        useUnmergedTree = true,
      )
      .performScrollToIndex(index)
  }

  private fun saveScreenshot(name: String) {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "dynamic-$name.png")
    FileOutputStream(file).use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output) }
  }

  private fun authorStory(id: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title =
        when (id) {
          "following-all" -> "关注用户的新动态"
          "author-moments" -> "分享今天的游戏体验"
          "author-articles" -> "我的游戏推荐"
          else -> id
        },
      excerpt = "合成作者内容",
      body = "",
      author = "作者甲",
      date = "2026-10-27",
    )

  private fun attach(viewModel: DynamicFeedViewModel) {
    val owner = ViewModelStore()
    viewModelStore = owner
    compose.activityRule.scenario.onActivity { owner.put("dynamic-follow-filter", viewModel) }
  }
}
