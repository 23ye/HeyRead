package cn.heyread.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.ui.screens.SettingsCategory
import cn.heyread.app.ui.screens.SettingsOverviewScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountHeaderNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun accountHeaderOpensCurrentOwnProfileWithoutBubblingLogout() {
    var openedProfile = 0
    var login = 0
    var logout = 0
    compose.setContent {
      HeyReadTheme("light") {
        SettingsOverviewScreen(
          account =
            AccountUiState(
              session = AccountSession("pkey=synthetic", "user-current", "读者", ""),
              loadingSession = false,
            ),
          ui = UiState(),
          onLogin = { login++ },
          onLogout = { logout++ },
          onOpenFavorites = {},
          onAccountHistory = {},
          onCategory = {},
          onOpenOwnProfile = { openedProfile++ },
        )
      }
    }

    compose.onNodeWithTag("settings-account-header").performClick()
    compose.onNodeWithTag("settings-account-logout").performClick()
    compose.runOnIdle {
      assertEquals(1, openedProfile)
      assertEquals(0, login)
      assertEquals(1, logout)
    }
  }

  @Test
  fun guestHeaderOpensLoginAndRestoringSessionDoesNotTreatNullAsGuest() {
    var login = 0
    var profile = 0
    val restoring = mutableStateOf(true)
    compose.setContent {
      HeyReadTheme("light") {
        SettingsOverviewScreen(
          account = AccountUiState(loadingSession = restoring.value),
          ui = UiState(),
          onLogin = { login++ },
          onLogout = {},
          onOpenFavorites = {},
          onAccountHistory = {},
          onCategory = {},
          onOpenOwnProfile = { profile++ },
        )
      }
    }

    compose.onNodeWithTag("settings-account-header").performClick()
    compose.runOnIdle { assertEquals(0, login) }
    compose.runOnIdle { restoring.value = false }
    compose.waitForIdle()
    compose.onNodeWithTag("settings-account-header").performClick()
    compose.runOnIdle {
      assertEquals(1, login)
      assertEquals(0, profile)
    }
  }

  @Test
  fun missingAccountIdDoesNotGuessAnOwnProfile() {
    var openedProfile = 0
    var login = 0
    compose.setContent {
      HeyReadTheme("light") {
        SettingsOverviewScreen(
          account =
            AccountUiState(
              session = AccountSession("pkey=synthetic", "", "无 ID", ""),
              loadingSession = false,
            ),
          ui = UiState(),
          onLogin = { login++ },
          onLogout = {},
          onOpenFavorites = {},
          onAccountHistory = {},
          onCategory = { _: SettingsCategory -> },
          onOpenOwnProfile = { openedProfile++ },
        )
      }
    }

    compose.onNodeWithTag("settings-account-header").performClick()
    compose.runOnIdle {
      assertEquals(0, openedProfile)
      assertEquals(0, login)
    }
  }
}
