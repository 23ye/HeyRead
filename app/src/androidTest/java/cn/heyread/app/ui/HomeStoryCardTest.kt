package cn.heyread.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.screens.HomeStoryCard
import cn.heyread.app.ui.theme.HeyReadTheme
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeStoryCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allLayoutsKeepArticleAndSectionClicksSeparateWithoutCovers() {
        val story = sampleStory()
        val selectedLayout = mutableStateOf(HomeLayout.A)
        val openedStories = mutableIntStateOf(0)
        val openedSections = mutableIntStateOf(0)
        compose.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, 1.3f)) {
                HeyReadTheme("dark") {
                    Column(Modifier.width(285.dp).verticalScroll(rememberScrollState())) {
                        HomeStoryCard(
                            story = story,
                            layout = selectedLayout.value,
                            featured = true,
                            noImages = true,
                            onOpen = { openedStories.intValue++ },
                            onOpenSection = { openedSections.intValue++ },
                        )
                    }
                }
            }
        }

        HomeLayout.entries.forEach { layout ->
            compose.runOnIdle { selectedLayout.value = layout }
            compose.waitForIdle()
            compose.onNodeWithTag("home-card-${layout.name}-${story.id}").assertIsDisplayed()
            val title = compose.onNodeWithText(story.title, useUnmergedTree = true)
            title.assertIsDisplayed()
            val author =
                compose.onNodeWithContentDescription("作者：${story.author}", useUnmergedTree = true)
            author.assertIsDisplayed()
            val titleTop = title.fetchSemanticsNode().boundsInRoot.top
            val authorTop = author.fetchSemanticsNode().boundsInRoot.top
            if (layout == HomeLayout.C) assertTrue(authorTop > titleTop)
            else assertTrue(authorTop < titleTop)
            val section = compose.onNodeWithContentDescription("分区：开放世界", useUnmergedTree = true)
            section.assertIsDisplayed()
            assertTrue(
                section.fetchSemanticsNode().boundsInRoot.height >=
                    with(compose.density) { 48.dp.toPx() }
            )
            section.performClick()
            compose.runOnIdle { assertEquals(1, openedSections.intValue) }
            assertEquals("A section click must not open its article", 0, openedStories.intValue)

            compose
                .onNode(hasTestTag("home-card-${layout.name}-${story.id}") and hasClickAction())
                .performClick()
            compose.runOnIdle { assertEquals(1, openedStories.intValue) }
            compose.runOnIdle {
                openedSections.intValue = 0
                openedStories.intValue = 0
            }
        }
    }

    @Test
    fun coverLayoutsUseDistinctImageSizesAndNoImageModeCollapsesCoverSpace() {
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val imageLoader = syntheticImageLoader(context, syntheticJpeg(), requested)
        val story = sampleStory().copy(image = "https://images.invalid/home-cover.jpg")
        val selectedLayout = mutableStateOf(HomeLayout.A)
        val noImages = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                HeyReadTheme("light") {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        HomeStoryCard(
                            story = story,
                            layout = selectedLayout.value,
                            featured = true,
                            noImages = noImages.value,
                            onOpen = {},
                        )
                    }
                }
            }
        }

        HomeLayout.entries.forEach { layout ->
            compose.runOnIdle {
                selectedLayout.value = layout
                noImages.value = false
            }
            compose.waitUntil(5_000) { requested.isNotEmpty() }
            val cover = compose.onNodeWithTag("home-cover-${story.id}", useUnmergedTree = true)
            cover.assertIsDisplayed()
            val withCoverHeight =
                compose
                    .onNodeWithTag("home-card-${layout.name}-${story.id}")
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .height
            val coverWidth = cover.fetchSemanticsNode().boundsInRoot.width
            val titleWidthWithCover =
                compose
                    .onNodeWithText(story.title, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .width
            if (layout == HomeLayout.B) {
                assertTrue(coverWidth < with(compose.density) { 130.dp.toPx() })
            } else {
                assertTrue(coverWidth > with(compose.density) { 200.dp.toPx() })
            }

            compose.runOnIdle { noImages.value = true }
            compose.waitForIdle()
            compose
                .onNodeWithTag("home-cover-${story.id}", useUnmergedTree = true)
                .assertDoesNotExist()
            val withoutCoverHeight =
                compose
                    .onNodeWithTag("home-card-${layout.name}-${story.id}")
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .height
            val titleWidthWithoutCover =
                compose
                    .onNodeWithText(story.title, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .width
            if (layout == HomeLayout.B) {
                assertTrue(
                    "compact layout should not grow when its thumbnail is removed",
                    withoutCoverHeight <= withCoverHeight + with(compose.density) { 2.dp.toPx() },
                )
                assertTrue(
                    "text should gain the thumbnail's space",
                    titleWidthWithoutCover - titleWidthWithCover >
                        with(compose.density) { 48.dp.toPx() },
                )
            } else {
                assertTrue(
                    "large cover space should collapse",
                    withCoverHeight - withoutCoverHeight > with(compose.density) { 48.dp.toPx() },
                )
            }
        }
    }

    private fun syntheticImageLoader(
        context: Context,
        bytes: ByteArray,
        requested: MutableList<String>,
    ): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    object : Fetcher.Factory<Uri> {
                        override fun create(
                            data: Uri,
                            options: Options,
                            imageLoader: ImageLoader,
                        ): Fetcher? {
                            val url = data.toString()
                            if (!url.startsWith("https://images.invalid/")) return null
                            return object : Fetcher {
                                override suspend fun fetch(): FetchResult {
                                    requested += url
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    return DrawableResult(
                                        android.graphics.drawable.BitmapDrawable(
                                            context.resources,
                                            bitmap,
                                        ),
                                        false,
                                        DataSource.MEMORY,
                                    )
                                }
                            }
                        }
                    }
                )
            }
            .build()

    private fun syntheticJpeg(): ByteArray =
        java.io.ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(60, 120, 180))
                compress(Bitmap.CompressFormat.JPEG, 90, output)
                recycle()
            }
            output.toByteArray()
        }

    private fun sampleStory() =
        Story(
            id = "home-layout-fixture",
            source = Source.HEYBOX,
            title = "长标题在窄屏和大字体下仍然有合理换行",
            excerpt = "没有封面时应收敛为可读的文字卡片，不保留大块空白。",
            body = "",
            author = "一位名字较长的真实作者",
            date = "刚刚发布",
            sections = listOf(ArticleLabel("section-1", "开放世界")),
        )
}
