package cn.heyread.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.screens.MineSettingsContent
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsCategoryScreen
import cn.heyread.app.ui.screens.SettingsOverviewScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCardsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun overviewShowsAccountAndRoutesOnlyAfterTheMatchingShortcutOrCategoryIsTapped() {
    val favorites = mutableIntStateOf(0)
    val accountHistory = mutableIntStateOf(0)
    val login = mutableIntStateOf(0)
    val selectedCategory = mutableStateOf<SettingsCategory?>(null)
    val account =
      AccountUiState(
        session = AccountSession("pkey=synthetic", "user-42", "测试读者", ""),
        loadingSession = false,
      )
    compose.setContent {
      HeyReadTheme("light") {
        SettingsOverviewScreen(
          account = account,
          ui = UiState(),
          onLogin = { login.intValue++ },
          onLogout = {},
          onOpenFavorites = { favorites.intValue++ },
          onAccountHistory = { accountHistory.intValue++ },
          onCategory = { selectedCategory.value = it },
        )
      }
    }

    compose.onNodeWithText("测试读者").assertIsDisplayed()
    compose.onNodeWithTag("settings-account-favorites").performClick()
    compose.onNodeWithTag("settings-account-history").performClick()
    compose.onNodeWithTag("settings-category-appearance").performClick()
    compose.runOnIdle {
      assertEquals(1, favorites.intValue)
      assertEquals(1, accountHistory.intValue)
      assertEquals(SettingsCategory.Appearance, selectedCategory.value)
      assertEquals(0, login.intValue)
    }
  }

  @Test
  fun appearanceChoicesSurviveSystemBackFromTheCategory() {
    val ui = mutableStateOf(UiState(theme = "system", homeLayout = HomeLayout.A))
    compose.setContent {
      HeyReadTheme("light") {
        MineSettingsContent(
          ui = ui.value,
          account = AccountUiState(loadingSession = false),
          isCurrentPage = true,
          onLogin = {},
          onLogout = {},
          onOpenFavorites = {},
          onAccountHistory = {},
          onThemeChange = { ui.value = ui.value.copy(theme = it) },
          onHomeLayoutChange = { ui.value = ui.value.copy(homeLayout = it) },
          onAccentChange = { ui.value = ui.value.copy(accent = it) },
          onMotionChange = { ui.value = ui.value.copy(motionEnabled = it) },
          onNoImagesChange = { ui.value = ui.value.copy(noImages = it) },
          onGatewaySave = {},
          onClearCache = {},
          onOpenStory = {},
          onClearHistory = {},
        )
      }
    }

    compose.onNodeWithTag("settings-category-appearance").performClick()
    compose.onNodeWithTag("settings-theme-light").performScrollTo().performClick()
    compose.onNodeWithTag("settings-layout-C").performScrollTo().performClick()
    compose.onNodeWithTag("settings-motion").performScrollTo().performClick()
    compose.onNodeWithTag("settings-no-images").performScrollTo().performClick()
    compose.onNodeWithTag("settings-layout-C").performScrollTo()
    compose.onNodeWithTag("settings-layout-C").assertIsSelected()
    compose.onNodeWithTag("settings-motion").performScrollTo()
    compose.onNodeWithTag("settings-motion").assertIsOff()
    compose.onNodeWithTag("settings-no-images").performScrollTo()
    compose.onNodeWithTag("settings-no-images").assertIsOn()
    compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag("settings-overview").assertIsDisplayed()
    compose.onNodeWithText("C · 大封面画廊 · 无图模式开启").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals("light", ui.value.theme)
      assertEquals(HomeLayout.C, ui.value.homeLayout)
    }
  }

  @Test
  fun gatewayDraftAndErrorStayVisibleUntilTheUserExplicitlySaves() {
    val ui = mutableStateOf(UiState(error = "网关地址格式错误"))
    val savedValues = mutableListOf<String>()
    val clearedCache = mutableIntStateOf(0)
    compose.setContent {
      HeyReadTheme("light") {
        MineSettingsContent(
          ui = ui.value,
          account = AccountUiState(loadingSession = false),
          isCurrentPage = true,
          onLogin = {},
          onLogout = {},
          onOpenFavorites = {},
          onAccountHistory = {},
          onThemeChange = {},
          onHomeLayoutChange = {},
          onAccentChange = {},
          onMotionChange = {},
          onNoImagesChange = {},
          onGatewaySave = { savedValues += it },
          onClearCache = { clearedCache.intValue++ },
          onOpenStory = {},
          onClearHistory = {},
        )
      }
    }

    compose.onNodeWithTag("settings-category-data").performClick()
    compose.onNodeWithTag("settings-gateway").assertDoesNotExist()
    compose.onNodeWithText("高级数据源设置").performClick()
    compose.onNodeWithTag("settings-gateway").assertIsDisplayed()
    compose.onNodeWithText("网关地址格式错误").assertIsDisplayed()
    compose.onNodeWithTag("settings-gateway").performTextInput("https://draft.example")
    compose.runOnIdle {
      assertTrue(savedValues.isEmpty())
      assertEquals(0, clearedCache.intValue)
    }

    compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag("settings-category-data").performClick()
    compose.onNodeWithTag("settings-gateway").assertIsDisplayed()
    compose.onNodeWithText("https://draft.example").assertIsDisplayed()
    compose.onNodeWithTag("settings-gateway-save").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(listOf("https://draft.example"), savedValues)
      assertEquals(0, clearedCache.intValue)
    }
  }

  @Test
  fun localHistoryOpensTheSelectedStoryAndClearsOnlyAfterExplicitAction() {
    val stories = (1..6).map { historyStory(it) }
    val ui = mutableStateOf(UiState(history = stories))
    val opened = mutableStateOf<Story?>(null)
    val clearCount = mutableIntStateOf(0)
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
          onClearHistory = { clearCount.intValue++ },
          onBack = {},
        )
      }
    }

    compose.onNodeWithTag("settings-history").assertIsDisplayed()
    compose.onNodeWithTag("home-card-A-history-4").performScrollTo().performClick()
    compose.runOnIdle {
      assertSame(stories[3], opened.value)
      assertEquals(0, clearCount.intValue)
      assertEquals(6, ui.value.history.size)
    }
    compose.onNodeWithTag("settings-history-clear").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, clearCount.intValue)
      assertEquals(6, ui.value.history.size)
    }
    compose.onNodeWithTag("home-card-A-history-6").assertDoesNotExist()
  }

  @Test
  fun overviewRowsRemainInsideA210DpColumnAtLargeFontScaleWithTouchTargets() {
    val account = AccountUiState(loadingSession = false)
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
        HeyReadTheme("light") {
          Box(Modifier.width(210.dp).fillMaxHeight()) {
            SettingsOverviewScreen(
              account = account,
              ui = UiState(),
              onLogin = {},
              onLogout = {},
              onOpenFavorites = {},
              onAccountHistory = {},
              onCategory = {},
            )
          }
        }
      }
    }

    val targetWidthPx = with(compose.density) { 210.dp.toPx() }
    listOf(
        "settings-account-login",
        "settings-account-favorites",
        "settings-account-history",
        "settings-category-appearance",
        "settings-category-data",
        "settings-category-local-history",
        "settings-category-about",
      )
      .forEach { tag ->
        compose.onNodeWithTag(tag).performScrollTo()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
          "$tag must remain within its narrow parent",
          bounds.left >= 0f && bounds.right <= targetWidthPx,
        )
        assertTrue(
          "$tag must keep a 48dp touch target",
          bounds.height >= with(compose.density) { 48.dp.toPx() },
        )
      }
  }

  private fun historyStory(index: Int) =
    Story(
      id = "history-$index",
      source = Source.HEYBOX,
      title = "本机历史文章 $index",
      excerpt = "合成历史记录",
      body = "",
      author = "测试作者",
      date = "刚刚",
    )
}
