package cn.heyread.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.translation.SteamTranslationPolicy
import cn.heyread.app.ui.components.SteamTranslationSection
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.SteamTranslationPhase
import cn.heyread.app.viewmodel.SteamTranslationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamTranslationComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun translationControlsKeepOriginalReadableAndShowPrivacyDetails() {
        val state = mutableStateOf(SteamTranslationState("STEAM:1", ORIGINAL))
        var starts = 0
        compose.setContent {
            HeyReadTheme("light") {
                Surface {
                    SteamTranslationSection(
                        storyKey = "STEAM:1",
                        originalBody = ORIGINAL,
                        state = state.value,
                        noImages = true,
                        onTranslate = { starts++ },
                        onCancel = {},
                        onShowOriginal = {},
                        onShowTranslation = {},
                        onOpenUrl = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("steam-translation-start").assertIsDisplayed()
        compose.onNodeWithText("Original announcement.").assertIsDisplayed()
        compose.onNodeWithTag("steam-translation-info").performClick()
        compose
            .onNodeWithText("ML Kit 可能向 Google 发送性能和使用指标，也会联网获取或更新语言模型。", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("使用规则").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("steam-translation-start").performClick()
        assertTrue(starts == 1)
    }

    @Test
    fun readyTranslationCanSwitchBackToOriginal() {
        val state =
            mutableStateOf(
                SteamTranslationState(
                    storyKey = "STEAM:2",
                    sourceBody = ORIGINAL,
                    phase = SteamTranslationPhase.READY,
                    translatedBody = "中文公告。",
                    showingTranslation = true,
                )
            )
        compose.setContent {
            HeyReadTheme("light") {
                Surface {
                    SteamTranslationSection(
                        storyKey = "STEAM:2",
                        originalBody = ORIGINAL,
                        state = state.value,
                        noImages = true,
                        onTranslate = {},
                        onCancel = {},
                        onShowOriginal = {
                            state.value = state.value.copy(showingTranslation = false)
                        },
                        onShowTranslation = {
                            state.value = state.value.copy(showingTranslation = true)
                        },
                        onOpenUrl = {},
                    )
                }
            }
        }
        compose.onNodeWithText("中文公告。", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("steam-translation-attribution").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Google Translate").assertIsDisplayed()
        compose.onNodeWithTag("steam-translation-toggle").performClick()
        compose.onNodeWithText("Original announcement.").assertIsDisplayed()
        compose.onNodeWithText("查看译文").assertIsDisplayed()
    }

    @Test
    fun inProgressTranslationExposesCancellationWithoutHidingOriginal() {
        var cancellations = 0
        compose.setContent {
            HeyReadTheme("light") {
                Surface {
                    SteamTranslationSection(
                        storyKey = "STEAM:3",
                        originalBody = ORIGINAL,
                        state =
                            SteamTranslationState(
                                storyKey = "STEAM:3",
                                sourceBody = ORIGINAL,
                                phase = SteamTranslationPhase.PREPARING_MODEL,
                            ),
                        noImages = true,
                        onTranslate = {},
                        onCancel = { cancellations++ },
                        onShowOriginal = {},
                        onShowTranslation = {},
                        onOpenUrl = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("steam-translation-cancel").performClick()
        compose.onNodeWithText("Original announcement.").assertIsDisplayed()
        assertTrue(cancellations == 1)
    }

    @Test
    fun translationEligibilityIsLimitedToNonEmptySteamBody() {
        assertTrue(SteamTranslationPolicy.canTranslate(Source.STEAM, ORIGINAL))
        assertFalse(SteamTranslationPolicy.canTranslate(Source.STEAM, "   "))
        assertFalse(SteamTranslationPolicy.canTranslate(Source.STEAM, "请打开原文查看公告。"))
        assertFalse(SteamTranslationPolicy.canTranslate(Source.HEYBOX, ORIGINAL))
    }

    private companion object {
        const val ORIGINAL = "Original announcement."
    }
}
