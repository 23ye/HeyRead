package cn.heyread.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.CaptchaChallenge
import cn.heyread.app.data.remote.CaptchaRequiredException
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.data.remote.UserProfileStats
import cn.heyread.app.ui.components.ArticleTaxonomy
import cn.heyread.app.ui.components.ProfileRow
import cn.heyread.app.ui.components.SectionLabels
import cn.heyread.app.ui.components.StoryMetadataRow
import cn.heyread.app.ui.screens.DestinationScreen
import cn.heyread.app.ui.screens.DestinationUserHeader
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.DestinationViewModel
import cn.heyread.app.viewmodel.UiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DestinationComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sectionAndHashtagClicksKeepTheirTargetTypesAndMissingIdsStayStatic() {
        val destinations = mutableStateListOf<String>()
        val unavailableProfileClicks = mutableStateListOf<String>()
        compose.setContent {
            HeyReadTheme("light") {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        SectionLabels(
                            listOf(
                                ArticleLabel(
                                    "section-1",
                                    "Section",
                                    "https://images.invalid/icon.png",
                                ),
                                ArticleLabel("", "Unlinked section"),
                            ),
                            noImages = true,
                            onClick = { destinations.add("section:${it.id}") },
                        )
                        ArticleTaxonomy(
                            sections = listOf(ArticleLabel("section-2", "Body section")),
                            hashtags =
                                listOf(
                                    ArticleLabel("tag-1", "tag"),
                                    ArticleLabel("", "unlinked tag"),
                                ),
                            noImages = true,
                            onSectionClick = { destinations.add("section:${it.id}") },
                            onHashtagClick = { destinations.add("hashtag:${it.id}") },
                        )
                        ProfileRow(
                            profile = AuthorProfile(name = "Name only"),
                            fallbackName = "Fallback",
                            noImages = true,
                            onProfileClick = { unavailableProfileClicks.add(it.name) },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("section-label-section-1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("section-label-Unlinked section").assertIsDisplayed()
        compose.onNodeWithTag("hashtag-label-tag-1").performClick()
        compose.onNodeWithTag("hashtag-label-unlinked tag").assertIsDisplayed()
        compose.onNodeWithTag("profile-row-unavailable").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("section:section-1", "hashtag:tag-1"), destinations.toList())
            assertEquals(emptyList<String>(), unavailableProfileClicks.toList())
        }
        val taxonomyBounds =
            compose.onNodeWithTag("section-label-section-2").fetchSemanticsNode().boundsInRoot
        assertTrue(taxonomyBounds.width >= with(compose.density) { 48.dp.toPx() })
        assertTrue(taxonomyBounds.height >= with(compose.density) { 48.dp.toPx() })
    }

    @Test
    fun userHeaderAndCompactMetadataFitNarrowWidthAndKeepSectionAction() {
        val destinations = mutableStateListOf<String>()
        compose.setContent {
            HeyReadTheme("light") {
                Surface(Modifier.width(320.dp)) {
                    Column {
                        DestinationUserHeader(
                            DestinationData(
                                title = "Long display name",
                                profile = AuthorProfile("Long display name", userId = "98319707"),
                                profileStats = UserProfileStats(80, 381, 40831),
                            ),
                            DestinationTarget(DestinationType.USER, "98319707", "User"),
                            noImages = true,
                        )
                        StoryMetadataRow(
                            Story(
                                id = "compact",
                                source = Source.HEYBOX,
                                title = "title",
                                excerpt = "",
                                body = "",
                                author = "An extremely long author display name for a narrow phone",
                                date = "2026-10-18 23:59",
                                sections = listOf(ArticleLabel("section-9", "Counter Strike 2")),
                            ),
                            noImages = true,
                            onOpenSection = { destinations.add(it.id) },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("ID 98319707").assertIsDisplayed()
        compose.onNodeWithText("80").assertIsDisplayed()
        compose.onNodeWithText("381").assertIsDisplayed()
        compose.onNodeWithText("40831").assertIsDisplayed()
        compose.onNodeWithText("2026-10-18 23:59").assertIsDisplayed()
        compose.onNodeWithText("Counter Strike 2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("section-9"), destinations.toList()) }
    }

    @Test
    fun captchaGateKeepsFixedAccessibleEntryAndPreservesVisibleStories() {
        val target = DestinationTarget(DestinationType.USER, "captcha-user", "Reader")
        val loader = DestinationLoader { _, _, _, _, _, _, _, _, _, _ ->
            throw CaptchaRequiredException(
                CaptchaChallenge(
                    "/bbs/app/profile/events",
                    mapOf("userid" to target.id),
                    "199251710",
                ),
                DestinationData("Reader", profile = AuthorProfile("Reader", userId = target.id)),
            )
        }
        val viewModel = DestinationViewModel(loader)
        compose.setContent {
            HeyReadTheme("light") {
                DestinationScreen(
                    target = target,
                    viewModel = viewModel,
                    ui = UiState(noImages = true),
                    onBack = {},
                    onOpenStory = {},
                    onOpenSection = {},
                )
            }
        }

        compose.onNodeWithTag("captcha-start-topbar").assertIsDisplayed()
        compose.onNodeWithText("验证期间已暂停刷新与加载，已显示的内容会保留。").assertIsDisplayed()
        val buttonBounds =
            compose.onNodeWithTag("captcha-start-topbar").fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.height >= with(compose.density) { 48.dp.toPx() })
    }

    @Test
    fun captchaDialogLoadsOfficialSdkWithoutSubmittingAndCloseKeepsGate() {
        val target = DestinationTarget(DestinationType.USER, "captcha-smoke", "Reader")
        val loader = DestinationLoader { _, _, _, _, _, _, _, _, _, _ ->
            throw CaptchaRequiredException(
                CaptchaChallenge(
                    "/bbs/app/profile/events",
                    mapOf("userid" to target.id),
                    "199251710",
                ),
                DestinationData("Reader", profile = AuthorProfile("Reader", userId = target.id)),
            )
        }
        val viewModel = DestinationViewModel(loader)
        compose.setContent {
            HeyReadTheme("light") {
                DestinationScreen(
                    target = target,
                    viewModel = viewModel,
                    ui = UiState(noImages = true),
                    onBack = {},
                    onOpenStory = {},
                    onOpenSection = {},
                )
            }
        }

        compose.onNodeWithTag("captcha-start-topbar").performClick()
        compose.onNodeWithTag("captcha-dialog").assertIsDisplayed()
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("captcha-sdk-ready").fetchSemanticsNode() }
                .isSuccess || !viewModel.state.value.captchaDialogOpen
        }
        val ready =
            runCatching { compose.onNodeWithTag("captcha-sdk-ready").fetchSemanticsNode() }
                .isSuccess
        assertTrue("The official CAPTCHA SDK did not reach its ready callback", ready)
        SystemClock.sleep(5_000)
        compose.waitForIdle()
        assertTrue(
            "The verification dialog closed before the non-interactive smoke capture",
            viewModel.state.value.captchaDialogOpen,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val screenshotFile = File(instrumentation.targetContext.cacheDir, "v191-captcha-sdk.png")
        screenshotFile.outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("CAPTCHA_SDK_SCREENSHOT=${screenshotFile.absolutePath}; ready=$ready")

        compose.onNodeWithTag("captcha-dialog-close").performClick()
        compose.onNodeWithText("验证期间已暂停刷新与加载，已显示的内容会保留。").assertIsDisplayed()
        assertTrue(viewModel.state.value.captchaRequired)
    }
}
