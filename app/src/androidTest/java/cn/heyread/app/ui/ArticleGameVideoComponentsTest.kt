package cn.heyread.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.StoryDetailResolver
import cn.heyread.app.data.local.decodeHistoryStory
import cn.heyread.app.data.local.encodeHistoryStory
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.PriceSource
import cn.heyread.app.data.remote.PublicGameInfo
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.ArticleGameUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArticleGameVideoComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openedSearchStoryKeepsPlaybackWhenPersistedHistoryAndCachedPlaceholderArePresent() {
        val opened =
            Story(
                id = "190978471",
                source = Source.HEYBOX,
                title = "好，好，好",
                excerpt = "摘要",
                body = "正文",
                author = "匿名的长夏",
                date = "2026-09-19",
                feedVideo =
                    ArticleBlock.Video(
                        url = "https://cdn.example/video.mp4?auth=keep&sign=keep",
                        durationSeconds = 33,
                    ),
            )
        val persisted = decodeHistoryStory(encodeHistoryStory(opened)) ?: error("history missing")
        val selected =
            StoryDetailResolver.currentStory(
                Source.HEYBOX,
                opened.id,
                openedStory = opened,
                candidates = listOf(persisted),
            ) ?: error("opened story missing")
        val cached =
            selected.copy(
                detailBlocks =
                    listOf(
                        ArticleBlock.Paragraph("正文"),
                        ArticleBlock.Video(url = null, sourcePlaceholder = true),
                    ),
                feedVideo = null,
            )
        val resolved = StoryDetailResolver.mergeCachedDetail(selected, cached)
        var openedVideo = ""

        compose.setContent {
            HeyReadTheme("light") {
                ArticleBlockContent(
                    block = resolved.detailBlocks!!.last(),
                    noImages = false,
                    onOpenImage = {},
                    gameState = ArticleGameUiState(),
                    onOpenGame = {},
                    onRetryInfos = {},
                    onOpenVideo = { openedVideo = it },
                )
            }
        }

        compose.onNodeWithTag("article-video-block").assertIsDisplayed().performClick()
        assertEquals("https://cdn.example/video.mp4?auth=keep&sign=keep", openedVideo)
    }

    @Test
    fun nestedGameAndVideoBlocksKeepTheirActionsAndVisibleMetadata() {
        var openedApp = 0
        var openedVideo = ""
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cover = File(testContext.cacheDir, "article-game-cover.png")
        Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            cover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val game =
            PublicGameInfo(
                appId = 3428810,
                name = "A long game name that wraps on a narrow display",
                imageUrl = cover.toURI().toString(),
                score = 8.8,
                followerCount = 1245,
                currentPrice = "¥19.99",
                originalPrice = "¥59.99",
                discountPercent = 66,
                isFree = false,
                isLowest = true,
                isNewLowest = false,
                couponPrice = "¥19.99",
                priceSource = PriceSource.HEYBOX,
            )
        val state = ArticleGameUiState(infos = mapOf(game.appId to game))
        compose.setContent {
            HeyReadTheme("light") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Column(Modifier.width(320.dp)) {
                        ArticleBlockContent(
                            ArticleBlock.Quote(
                                listOf(
                                    ArticleBlock.GameCard(game.appId),
                                    ArticleBlock.Video(
                                        "https://cdn.example/clip.mp4?signature=keep",
                                        title = "Trailer",
                                    ),
                                )
                            ),
                            noImages = false,
                            onOpenImage = {},
                            gameState = state,
                            onOpenGame = { openedApp = it },
                            onOpenVideo = { openedVideo = it },
                        )
                    }
                }
            }
        }
        compose
            .onNodeWithText("A long game name that wraps on a narrow display")
            .assertIsDisplayed()
        compose.onNodeWithText("券后 ¥19.99").assertIsDisplayed()
        compose.onNodeWithText("¥59.99").assertIsDisplayed()
        val currentPrice =
            compose
                .onNodeWithTag("article-game-price-current-3428810", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val originalPrice =
            compose
                .onNodeWithTag("article-game-price-original-3428810", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val cardBounds =
            compose
                .onNodeWithTag("article-game-card-3428810", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val density = testContext.resources.displayMetrics.density
        assertTrue(
            "current and original prices should occupy the same price row",
            currentPrice.bottom > originalPrice.top && originalPrice.bottom > currentPrice.top,
        )
        assertTrue(
            "original price should remain a single-line height",
            originalPrice.height <= 30f * density,
        )
        assertTrue(
            "the narrow game card should not become excessively tall",
            cardBounds.height <= 220f * density,
        )
        compose.onNodeWithText("Trailer", substring = true).performClick()
        assertEquals("https://cdn.example/clip.mp4?signature=keep", openedVideo)
        compose.onNodeWithContentDescription("${game.name}封面").assertIsDisplayed()
        compose.onNodeWithTag("article-game-card-3428810").performClick()
        assertEquals(3428810, openedApp)
        saveTargetCacheScreenshot("v114-game-card-narrow.png")
    }
}
