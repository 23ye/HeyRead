package cn.heyread.app.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationPage
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.ProvideContentBlockingContext
import cn.heyread.app.ui.screens.AccountLibraryScreen
import cn.heyread.app.ui.screens.DestinationScreen
import cn.heyread.app.ui.screens.DynamicFeedScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.AccountViewModel
import cn.heyread.app.viewmodel.DestinationViewModel
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.UiState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingListSurfacesTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val owner = ViewModelStore()

  @After
  fun clearFixture() {
    compose.activityRule.scenario.onActivity { owner.clear() }
  }

  @Test
  fun dynamicFeedBlocksTailAutoPagingAndManualContinueLoadsOnlyOneRawPage() {
    val offsets = CopyOnWriteArrayList<Int>()
    val vm =
      DynamicFeedViewModel(
        loadRecommendations = { offset, _ ->
          offsets += offset
          if (offset == 0) {
            StoryPage(
              listOf(
                story("dynamic-visible", "visible title"),
                story("dynamic-tail", "blocked title"),
              ),
              hasMore = true,
            )
          } else StoryPage(listOf(story("dynamic-next", "second batch visible")), hasMore = true)
        }
      )
    compose.activityRule.scenario.onActivity { owner.put("content-blocking-dynamic", vm) }
    vm.ensureLoaded(334)
    compose.waitUntil(5_000) { vm.state.value.recommendations.items.size == 2 }

    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ProvideContentBlockingContext(context()) {
          DynamicFeedScreen(
            state = vm.state.collectAsState().value,
            ui = UiState(noImages = true),
            viewModel = vm,
            isCurrentPage = true,
            onLogin = {},
            onOpenStory = {},
          )
        }
      }
    }
    compose.onNodeWithText("visible title").assertIsDisplayed()
    compose.onNodeWithText("blocked title").assertDoesNotExist()
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose.waitForIdle()
    assertEquals("A blocked raw tail cannot auto-paginate", listOf(0), offsets.toList())

    compose.onNodeWithText("加载更多").performScrollTo().performClick()
    compose.waitUntil(5_000) {
      offsets.size == 2 &&
        !vm.state.value.recommendations.loadingMore &&
        vm.state.value.recommendations.items.size == 3
    }
    compose.waitForIdle()
    assertEquals("Manual continue requests one original page", listOf(0, 30), offsets.toList())
    assertEquals("The raw VM list stays intact", 3, vm.state.value.recommendations.items.size)
    assertFalse(
      "A successful manual page must not immediately auto-request another page",
      runCatching { compose.waitUntil(750) { offsets.size > 2 } }.isSuccess,
    )
  }

  @Test
  fun destinationAllBlockedPageOffersManualContinueAndKeepsRawStories() {
    val offsets = CopyOnWriteArrayList<Int>()
    val loader = DestinationLoader { _, offset, _, _, _, _, _, _, _, _ ->
      offsets += offset
      val pageStories =
        if (offset == 0) listOf(story("destination-blocked", "blocked destination"))
        else listOf(story("destination-next", "next destination"))
      DestinationPage(
        data = DestinationData(title = "合成分区"),
        stories = pageStories,
        nextOffset = offset + pageStories.size,
        hasMore = true,
      )
    }
    val target = DestinationTarget(DestinationType.SECTION, "section-id", "分区")
    val vm = DestinationViewModel(loader)
    compose.activityRule.scenario.onActivity { owner.put("content-blocking-destination", vm) }

    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ProvideContentBlockingContext(context()) {
          DestinationScreen(
            target = target,
            viewModel = vm,
            ui = UiState(noImages = true),
            onBack = {},
            onOpenStory = {},
            onOpenSection = {},
          )
        }
      }
    }
    compose.waitUntil(5_000) {
      offsets.isNotEmpty() && (!vm.state.value.loading || vm.state.value.error != null)
    }
    compose.waitForIdle()
    assertEquals(
      "Synthetic destination request starts at the original offset",
      listOf(0),
      offsets.toList(),
    )
    assertEquals("Initial destination load must succeed", null, vm.state.value.error)
    assertEquals(1, vm.state.value.stories.size)
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose.onNodeWithText("blocked destination").assertDoesNotExist()
    compose.waitForIdle()
    assertEquals("A fully blocked page cannot auto-paginate", listOf(0), offsets.toList())

    compose.onNodeWithTag("destination-content-blocking-continue").performScrollTo().performClick()
    compose.waitUntil(5_000) { offsets.size == 2 || vm.state.value.error != null }
    assertEquals(
      "Manual continue must request the next original destination offset; error=${vm.state.value.error}, hasMore=${vm.state.value.hasMore}",
      listOf(0, 1),
      offsets.toList(),
    )
    compose.waitUntil(5_000) { !vm.state.value.loadingMore && vm.state.value.stories.size == 2 }
    compose.waitForIdle()
    assertEquals("Continue preserves the VM's offset behavior", listOf(0, 1), offsets.toList())
    assertEquals(2, vm.state.value.stories.size)
    assertFalse(
      "A successful manual page must not immediately auto-request another page",
      runCatching { compose.waitUntil(750) { offsets.size > 2 } }.isSuccess,
    )
  }

  @Test
  fun cloudFavoritesRespectScopeAndTemporaryVisibilityWithoutMutatingRawList() {
    val account =
      AccountViewModel(IsolatedSessionApplication(compose.activity.applicationContext)).also { vm ->
        compose.activityRule.scenario.onActivity { owner.put("content-blocking-account", vm) }
      }
    val raw =
      listOf(
        story("favorite-blocked", "blocked favorite title").copy(source = Source.HEYBOX),
        story("favorite-visible", "visible favorite title").copy(source = Source.HEYBOX),
      )
    val state =
      AccountUiState(
        session = AccountSession("pkey=synthetic-only", "synthetic-user", "合成读者", ""),
        loadingSession = false,
        cloudFavorites = raw,
        favoritesHasMore = false,
        cloudMode = "favorites",
      )
    val blocking = mutableStateOf(context(applyToFavorites = false))

    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ProvideContentBlockingContext(blocking.value) {
          AccountLibraryScreen(
            state = state,
            account = account,
            onLogin = {},
            onOpen = {},
            noImages = true,
            homeLayout = HomeLayout.A,
          )
        }
      }
    }
    compose.onNodeWithText("blocked favorite title").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-notice").assertDoesNotExist()

    compose.runOnIdle {
      blocking.value =
        blocking.value.copy(config = blocking.value.config.copy(applyToFavorites = true))
    }
    compose.onNodeWithText("blocked favorite title").assertDoesNotExist()
    compose.onNodeWithText("visible favorite title").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.onNodeWithText("blocked favorite title").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.onNodeWithText("blocked favorite title").assertDoesNotExist()
    assertEquals("Filtering and temporary reveal preserve raw favorites", raw, state.cloudFavorites)
  }

  private fun context(applyToFavorites: Boolean = false) =
    ContentBlockingContext(
      config =
        ContentBlockingConfig(
          enabled = true,
          applyToFavorites = applyToFavorites,
          rules =
            listOf(
              ContentBlockRule(
                id = "test-keyword",
                kind = ContentBlockKind.KEYWORD,
                value = "blocked",
              )
            ),
        ),
      ready = true,
      sessionKey = "synthetic-test-session",
    )

  private fun story(id: String, title: String) =
    Story(id, Source.DEMO, title, "summary", "body", "author", "today")

  private class IsolatedSessionApplication(base: Context) : Application() {
    private val prefix = "content-blocking-list-${java.util.UUID.randomUUID()}-"

    init {
      attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
      baseContext.getSharedPreferences("$prefix$name", mode)
  }
}
