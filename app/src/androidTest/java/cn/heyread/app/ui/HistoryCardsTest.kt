package cn.heyread.app.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.screens.AccountLibraryScreen
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsCategoryScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.AccountViewModel
import cn.heyread.app.viewmodel.UiState
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryCardsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val viewModelStore = ViewModelStore()

  @After
  fun clearViewModels() {
    viewModelStore.clear()
  }

  @Test
  fun localHistoryUsesCurrentLayoutAndKeepsItsFirstFiveStoriesAndOpenTarget() {
    val stories = (1..6).map { historyStory("local-$it", Source.STEAM) }
    val ui = mutableStateOf(UiState(history = stories, homeLayout = HomeLayout.A, noImages = true))
    val opened = mutableStateOf<Story?>(null)
    val clearCount = mutableStateOf(0)
    compose.setContent {
      HeyReadTheme("light") {
        SettingsCategoryScreen(
          category = SettingsCategory.LocalHistory,
          ui = ui.value,
          gatewayValue = "",
          advanced = false,
          onGatewayChange = {},
          onAdvancedChange = {},
          onThemeChange = {},
          onHomeLayoutChange = {},
          onAccentChange = {},
          onMotionChange = {},
          onNoImagesChange = {},
          onGatewaySave = {},
          onClearCache = {},
          onOpenStory = { opened.value = it },
          onClearHistory = { clearCount.value++ },
          onBack = {},
        )
      }
    }

    for (layout in HomeLayout.entries) {
      compose.runOnIdle { ui.value = ui.value.copy(homeLayout = layout) }
      compose.waitForIdle()
      (1..5).forEach { index -> localCard(layout, index).assertExists() }
      localCard(layout, 6).assertDoesNotExist()
      compose.onNodeWithTag("home-cover-local-1", useUnmergedTree = true).assertDoesNotExist()
    }

    val firstTop = localCard(HomeLayout.C, 1).getBoundsInRoot().top.value
    val secondTop = localCard(HomeLayout.C, 2).getBoundsInRoot().top.value
    assertTrue("Local history keeps its original order", firstTop < secondTop)
    localCard(HomeLayout.C, 3).performScrollTo().performClick()
    compose.runOnIdle {
      assertSame(stories[2], opened.value)
      assertEquals(0, clearCount.value)
    }
    compose.onNodeWithTag("settings-history-clear").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(1, clearCount.value) }
  }

  @Test
  fun localHistoryRemainsReadableInNoImagesModeAtNarrowLargeTextWidth() {
    val stories = (1..5).map { historyStory("narrow-$it", Source.DEMO) }
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
        HeyReadTheme("light") {
          Box(Modifier.width(210.dp).fillMaxHeight()) {
            SettingsCategoryScreen(
              category = SettingsCategory.LocalHistory,
              ui = UiState(history = stories, homeLayout = HomeLayout.C, noImages = true),
              gatewayValue = "",
              advanced = false,
              onGatewayChange = {},
              onAdvancedChange = {},
              onThemeChange = {},
              onHomeLayoutChange = {},
              onAccentChange = {},
              onMotionChange = {},
              onNoImagesChange = {},
              onGatewaySave = {},
              onClearCache = {},
              onOpenStory = {},
              onClearHistory = {},
              onBack = {},
            )
          }
        }
      }
    }

    val container = compose.onNodeWithTag("settings-category-local-history").getBoundsInRoot()
    val card = localCard(HomeLayout.C, 1, "narrow").performScrollTo().assertIsDisplayed()
    val cardBounds = card.getBoundsInRoot()
    assertTrue("History card remains within the 210dp column", cardBounds.left >= container.left)
    assertTrue("History card remains within the 210dp column", cardBounds.right <= container.right)
    compose.onNodeWithText(stories.first().title).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home-cover-narrow-1", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun accountHistoryUsesSharedCardsAndOpensOnlyTheAccountHistoryStory() {
    val stories = (1..3).map { historyStory("account-$it", Source.HEYBOX) }
    val favoriteOnly = historyStory("favorite-only", Source.HEYBOX)
    // The screen state is synthetic; this isolated, sessionless ViewModel cannot request history.
    val account =
      AccountViewModel(IsolatedSessionApplication(compose.activity.applicationContext)).also {
        viewModelStore.put("history-cards-account-view-model", it)
      }
    val state =
      AccountUiState(
        session = AccountSession("pkey=synthetic-only", "synthetic-user", "合成读者", ""),
        loadingSession = false,
        cloudHistory = stories,
        cloudFavorites = listOf(favoriteOnly),
        cloudMode = "history",
        historyHasMore = false,
      )
    val layout = mutableStateOf(HomeLayout.A)
    val opened = mutableStateOf<Story?>(null)
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        AccountLibraryScreen(
          state = state,
          account = account,
          onLogin = {},
          onOpen = { opened.value = it },
          noImages = true,
          homeLayout = layout.value,
          history = true,
          selectedStoryIdentity = "${Source.HEYBOX}:${stories[1].id}",
        )
      }
    }

    for (homeLayout in HomeLayout.entries) {
      compose.runOnIdle { layout.value = homeLayout }
      compose.waitForIdle()
      stories.forEach { story ->
        compose
          .onNodeWithTag("home-card-${homeLayout.name}-${story.id}", useUnmergedTree = true)
          .assertIsDisplayed()
      }
      compose
        .onNodeWithTag("home-card-${homeLayout.name}-${favoriteOnly.id}", useUnmergedTree = true)
        .assertDoesNotExist()
      compose
        .onNodeWithTag("home-cover-${stories.first().id}", useUnmergedTree = true)
        .assertDoesNotExist()
    }

    compose
      .onNodeWithTag("home-card-C-${stories[1].id}", useUnmergedTree = true)
      .performScrollTo()
      .performClick()
    compose.runOnIdle { assertSame(stories[1], opened.value) }
  }

  private fun historyStory(id: String, source: Source) =
    Story(
      id = id,
      source = source,
      title = "历史内容 $id",
      excerpt = "合成历史摘要 $id",
      body = "",
      author = "测试作者",
      date = "刚刚",
      image = "https://images.invalid/$id.png",
    )

  private fun localCard(layout: HomeLayout, index: Int, idPrefix: String = "local") =
    compose.onNodeWithTag("home-card-${layout.name}-$idPrefix-$index", useUnmergedTree = true)

  private class IsolatedSessionApplication(base: Context) : Application() {
    private val prefix = "history-cards-${UUID.randomUUID()}-"

    init {
      attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
      baseContext.getSharedPreferences("$prefix$name", mode)
  }
}
