package cn.heyread.app.ui

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.CommentImage
import cn.heyread.app.data.media.MediaImage
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.rich.OfficialEmojiCatalog
import cn.heyread.app.ui.components.AnimatedImage
import cn.heyread.app.ui.components.CommentImageThumbnail
import cn.heyread.app.ui.components.MediaImageThumbnail
import cn.heyread.app.ui.components.MediaPreviewDialog
import cn.heyread.app.ui.components.RichText
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.theme.HeyReadTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichMediaPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun bundledCubeEmojisRenderFromTheirExactOfficialImageModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OfficialEmojiCatalog.loadBundled(context)
        val expectedModels =
            linkedMapOf(
                "[cube_抱拳]" to "https://imgheybox.max-c.com/heybox/emoji/cube_191.png",
                "[cube_预言]" to "https://imgheybox.max-c.com/heybox/emoji/cube_192.png",
            )
        assertEquals(
            expectedModels,
            expectedModels.mapValues { (token, _) -> OfficialEmojiCatalog.entries.value[token] },
        )

        val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val imageLoader =
            ImageLoader.Builder(context)
                .components {
                    add(
                        object : Fetcher.Factory<Uri> {
                            override fun create(
                                data: Uri,
                                options: Options,
                                imageLoader: ImageLoader,
                            ): Fetcher? =
                                if (data.scheme == "https")
                                    object : Fetcher {
                                        override suspend fun fetch(): FetchResult {
                                            val url = data.toString()
                                            requestedUrls.add(url)
                                            check(url in expectedModels.values)
                                            val bitmap =
                                                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                                                    .apply { eraseColor(android.graphics.Color.CYAN) }
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
                                else null
                        }
                    )
                }
                .build()

        compose.setContent {
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                HeyReadTheme("light", motionEnabled = false) {
                    RichText("[cube_抱拳] 正文 [cube_预言]")
                }
            }
        }

        compose.waitUntil(10_000) {
            expectedModels.keys.all { token ->
                compose
                    .onAllNodesWithContentDescription(token, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
        expectedModels.forEach { (token, url) ->
            compose
                .onNodeWithContentDescription(token, useUnmergedTree = true)
                .assertIsDisplayed()
            assertTrue("Expected the bundled model $url", requestedUrls.contains(url))
        }
        imageLoader.shutdown()
    }

    @Test
    fun inlineMentionFromBodyParserIsVisibleInArticleParagraph() {
        val raw =
            "后续：ESL 官方运营账号已回复，<a data-user-id=\"16060506\" href=\"https://api.xiaoheihe.cn/open_inapp/#heybox://%7B%22protocol_type%22%3A%22openUser%22%2C%22user_id%22%3A%2216060506%22%7D\">@ESL官方账号</a>\n感谢盒友 [cube_哭泣]"
        val content =
            org.json.JSONArray()
                .put(org.json.JSONObject().put("type", "text").put("text", raw))
        val block = AccountRepository().parseArticleBlocks(content).single()
        assertTrue(block is ArticleBlock.Paragraph)
        assertEquals(
            "后续：ESL 官方运营账号已回复，@ESL官方账号\n感谢盒友 [cube_哭泣]",
            (block as ArticleBlock.Paragraph).text,
        )

        compose.setContent {
            HeyReadTheme("light", motionEnabled = false) {
                ArticleBlockContent(block = block, noImages = true, onOpenImage = {})
            }
        }

        compose
            .onNodeWithTag("article-paragraph-text")
            .assertIsDisplayed()
            .assertTextEquals("后续：ESL 官方运营账号已回复，@ESL官方账号\n感谢盒友 [cube_哭泣]")
    }

    @Test
    fun detailCommentAndFullscreenEntrypointsAnimateTheExplicitGifSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gif = File(context.cacheDir, "v17-entry-two-frames.gif")
        instrumentation.context.assets.open("v17-two-frames.gif").use { input ->
            gif.outputStream().use { input.copyTo(it) }
        }
        val still = File(context.cacheDir, "v17-still-thumb.jpg")
        val staticBitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        staticBitmap.eraseColor(android.graphics.Color.GREEN)
        still.outputStream().use { staticBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        staticBitmap.recycle()
        val gifUrl = Uri.fromFile(gif).toString()
        val stillUrl = Uri.fromFile(still).toString()
        val activeMedia = androidx.compose.runtime.mutableStateOf<MediaImage?>(null)

        compose.setContent {
            HeyReadTheme("light") {
                androidx.compose.foundation.layout.Column {
                    MediaImageThumbnail(
                        image = MediaImage(stillUrl, gifUrl, "详情 GIF", animationUrl = gifUrl),
                        modifier = Modifier.size(260.dp).testTag("v17-detail-gif"),
                    ) {
                        activeMedia.value =
                            MediaImage(stillUrl, gifUrl, "详情 GIF", animationUrl = gifUrl)
                    }
                    CommentImageThumbnail(
                        image =
                            CommentImage(
                                url = gifUrl,
                                thumbnailUrl = stillUrl,
                                originalUrl = gifUrl,
                                width = 96,
                                height = 64,
                                mimeType = "image/gif",
                                animated = true,
                            ),
                        noImages = false,
                        tag = "v17-comment-gif",
                    ) {
                        activeMedia.value =
                            MediaImage(stillUrl, gifUrl, "评论 GIF", animationUrl = gifUrl)
                    }
                }
                activeMedia.value?.let { media ->
                    MediaPreviewDialog(media, onDismiss = { activeMedia.value = null })
                }
            }
        }
        assertFramesChange("v17-detail-gif")
        assertFramesChange("v17-comment-gif")

        compose.onNodeWithTag("v17-detail-gif").performClick()
        compose.onNodeWithTag("media-preview").assertExists()
        assertFramesChange("media-preview")
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(context.cacheDir, "heyread-v17-fullscreen-gif.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithTag("media-close").performClick()

        compose.onNodeWithTag("v17-comment-gif").performClick()
        compose.onNodeWithTag("media-preview").assertExists()
        assertFramesChange("media-preview")
        compose.onNodeWithTag("media-close").performClick()
    }

    @Test
    fun realGifFramesAnimateAndPauseAndResumeWithLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gif = File(context.cacheDir, "v17-two-frames.gif")
        instrumentation.context.assets.open("v17-two-frames.gif").use { input ->
            gif.outputStream().use { input.copyTo(it) }
        }
        lateinit var owner: TestLifecycleOwner
        compose.runOnUiThread {
            owner = TestLifecycleOwner()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        var drawable: Animatable? = null
        var loaded = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.size(240.dp).background(Color.Black)) {
                    AnimatedImage(
                        model = Uri.fromFile(gif),
                        contentDescription = "合成双帧 GIF",
                        modifier = Modifier.size(220.dp).testTag("animated-gif"),
                        onState = { state ->
                            loaded = state is coil.compose.AsyncImagePainter.State.Success
                            drawable =
                                (state as? coil.compose.AsyncImagePainter.State.Success)
                                    ?.result
                                    ?.drawable as? Animatable
                        },
                    )
                }
            }
        }
        compose.waitUntil(10_000) { loaded && drawable != null }
        assertNotNull("Coil did not decode the fixture as an animatable GIF", drawable)
        val signatures = mutableSetOf<Long>()
        repeat(10) {
            Thread.sleep(110)
            signatures += frameSignature("animated-gif")
        }
        assertTrue("GIF pixels did not change across frames", signatures.size >= 2)

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        Thread.sleep(150)
        val paused = frameSignature("animated-gif")
        Thread.sleep(800)
        val stillPaused = frameSignature("animated-gif")
        assertTrue("GIF kept animating while its lifecycle was stopped", paused == stillPaused)

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        Thread.sleep(150)
        val resumedFrames = mutableSetOf<Long>()
        repeat(8) {
            Thread.sleep(110)
            resumedFrames += frameSignature("animated-gif")
        }
        assertTrue("GIF did not resume after returning to foreground", resumedFrames.size >= 2)
    }

    private fun assertFramesChange(tag: String) {
        val signatures = mutableSetOf<Long>()
        repeat(30) {
            Thread.sleep(110)
            signatures += frameSignature(tag)
            if (signatures.size >= 2) return
        }
        assertTrue(
            "GIF frames did not change in $tag; likely a static thumbnail was used",
            signatures.size >= 2,
        )
    }

    private fun frameSignature(tag: String): Long {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return try {
            var hash = 1125899906842597L
            val stepX = (bitmap.width / 12).coerceAtLeast(1)
            val stepY = (bitmap.height / 12).coerceAtLeast(1)
            for (y in stepY until bitmap.height step stepY) {
                for (x in stepX until bitmap.width step stepX) {
                    hash = hash * 31 + bitmap.getPixel(x, y)
                }
            }
            hash
        } finally {
            bitmap.recycle()
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
