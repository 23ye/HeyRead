package cn.heyread.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.CommentImage
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.media.MediaImage
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.ui.components.CommentThreadCard
import cn.heyread.app.ui.components.MediaImageThumbnail
import cn.heyread.app.ui.components.MediaPreviewDialog
import cn.heyread.app.ui.components.ProfileRow
import cn.heyread.app.ui.components.RichText
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.CommentSupportState
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentComponentsTest {
    @org.junit.Rule @JvmField val compose = createComposeRule()

    @Test
    fun productionArticleParserAndRendererShowEachInlineAndUniqueAttachmentOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val imageLoader = syntheticImageLoader(context, syntheticJpeg(), requestedUrls)
        val noImages = mutableStateOf(false)
        val openedImage = mutableStateOf<MediaImage?>(null)
        val content =
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "html")
                        .put(
                            "text",
                            "<figure><img src='https://imgheybox.max-c.com/a-preview.jpg' data-original='https://imgheybox.max-c.com/a.jpg' alt='图A'><figcaption>图A图注</figcaption></figure>" +
                                "<figure><img src='https://imgheybox.max-c.com/b.jpg' alt='图B'><figcaption>图B图注</figcaption></figure>",
                        )
                )
                .put(
                    JSONObject()
                        .put("type", "img")
                        .put(
                            "url",
                            "https://imgheybox1.max-c.com/a.jpg?imageMogr2/thumbnail/100x100",
                        )
                )
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put("url", "https://imgheybox1.max-c.com/b.jpg")
                )
                .put(
                    JSONObject()
                        .put("type", "img")
                        .put("url", "https://imgheybox1.max-c.com/unique.jpg")
                        .put("caption", "独有附件图注")
                )
        val blocks = AccountRepository().parseArticleBlocks(content)
        assertEquals(3, blocks.filterIsInstance<ArticleBlock.Image>().size)

        compose.setContent {
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                HeyReadTheme("light") {
                    androidx.compose.material3.Surface {
                        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                            blocks.forEach { block ->
                                ArticleBlockContent(
                                    block,
                                    noImages = noImages.value,
                                    onOpenImage = { openedImage.value = it },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            synchronized(requestedUrls) {
                requestedUrls
                    .toSet()
                    .containsAll(blocks.filterIsInstance<ArticleBlock.Image>().map { it.url })
            }
        }
        compose.onAllNodesWithTag("article-rich-image", useUnmergedTree = true).assertCountEquals(3)
        listOf("图A图注", "图B图注", "独有附件图注").forEach {
            compose.onNodeWithText(it, substring = true).performScrollTo().assertIsDisplayed()
        }
        saveDeviceScreenshot(context, "v1131-article-images.png")
        compose
            .onAllNodesWithTag("article-rich-image", useUnmergedTree = true)[0]
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals("https://imgheybox.max-c.com/a.jpg", openedImage.value?.originalUrl)
            assertEquals("https://imgheybox.max-c.com/a-preview.jpg", openedImage.value?.previewUrl)
        }

        val requestCountBeforeNoImages = synchronized(requestedUrls) { requestedUrls.size }
        compose.runOnIdle { noImages.value = true }
        listOf("图A图注", "图B图注", "独有附件图注").forEach {
            compose.onNodeWithText(it, substring = true).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithTag("article-rich-image", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(requestCountBeforeNoImages, synchronized(requestedUrls) { requestedUrls.size })
        saveDeviceScreenshot(context, "v1131-article-images-hidden.png")
    }

    @Test
    fun productionRichContentRendererShowsFigureCaptionsQuotesAndNoImageCaption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageBytes = syntheticJpeg()
        val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val imageLoader = syntheticImageLoader(context, imageBytes, requestedUrls)
        val blocks =
            listOf(
                ArticleBlock.Image("https://images.invalid/article.png", caption = "图一说明"),
                ArticleBlock.Quote(
                    listOf(
                        ArticleBlock.Paragraph("引用中的文字"),
                        ArticleBlock.Image("https://images.invalid/quote.png", caption = "引用图片说明"),
                        ArticleBlock.Paragraph("引用后文字"),
                    )
                ),
                ArticleBlock.Image("https://images.invalid/hidden.png", caption = "无图模式仍保留的说明"),
                ArticleBlock.Paragraph("引用后的正文"),
            )
        compose.setContent {
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                HeyReadTheme("light") {
                    androidx.compose.material3.Surface {
                        Column(Modifier.padding(16.dp)) {
                            blocks.forEachIndexed { index, block ->
                                ArticleBlockContent(block, noImages = index == 2, onOpenImage = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            requestedUrls.contains("https://images.invalid/article.png") &&
                requestedUrls.contains("https://images.invalid/quote.png")
        }
        listOf("图一说明", "引用中的文字", "引用图片说明", "引用后文字", "无图模式仍保留的说明", "引用后的正文").forEach {
            compose.onNodeWithText(it, substring = true).assertIsDisplayed()
        }
        saveDeviceScreenshot(context, "v17-rich-content.png")
    }

    @Test
    fun failedOfficialEmojiImageRestoresSelectableFullShortcode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cn.heyread.app.data.rich.OfficialEmojiCatalog.loadBundled(context)
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failingLoader =
            ImageLoader.Builder(context)
                .components {
                    add(
                        object : Fetcher.Factory<Uri> {
                            override fun create(
                                data: Uri,
                                options: Options,
                                imageLoader: ImageLoader,
                            ): Fetcher? =
                                if (data.host == "imgheybox.max-c.com")
                                    object : Fetcher {
                                        override suspend fun fetch(): FetchResult {
                                            requested.add(data.toString())
                                            throw java.io.IOException("synthetic image failure")
                                        }
                                    }
                                else null
                        }
                    )
                }
                .build()
        compose.setContent {
            CompositionLocalProvider(LocalImageLoader provides failingLoader) {
                HeyReadTheme("light") {
                    SelectionContainer {
                        RichText("前缀 [cube_星乐蒂] 后缀", modifier = Modifier.padding(20.dp))
                    }
                }
            }
        }
        compose.waitUntil(10_000) { requested.isNotEmpty() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("前缀 [cube_星乐蒂] 后缀").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("前缀 [cube_星乐蒂] 后缀").assertIsDisplayed()
    }

    @Test
    fun profileAndCommentActionsRenderWithoutCouplingClicks() {
        val supportClicks = mutableIntStateOf(0)
        val profileClicks = mutableStateListOf<String>()
        val replyTargets = mutableStateListOf<Pair<String, Boolean>>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageBytes = syntheticJpeg()
        val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val imageLoader = syntheticImageLoader(context, imageBytes, requestedUrls)
        val imageUrl = "https://images.invalid/full.jpg"
        val previewUrl = "https://images.invalid/preview.jpg"
        val articlePreviewUrl = "https://images.invalid/article-preview.jpg"
        val originalUrl = "https://images.invalid/original.jpg"
        val activeImage = mutableStateOf<MediaImage?>(null)
        val nested =
            RemoteComment(
                id = "synthetic-child",
                author = "回复者",
                text = "合成楼中楼回复",
                createdAt = 1_760_000_100,
                likes = 3,
                profile =
                    AuthorProfile(
                        "回复者",
                        level = "Lv.4",
                        location = "上海",
                        userId = "synthetic-replier",
                    ),
                isSupported = false,
                supportCount = 3,
            )
        val root =
            RemoteComment(
                id = "synthetic-root",
                author = "测试作者",
                text = "合成评论：正文与图片信息用于布局验收。",
                createdAt = 1_760_000_000,
                likes = 12,
                childCount = 1,
                profile =
                    AuthorProfile(
                        "测试作者",
                        level = "Lv.18",
                        location = "广东",
                        userId = "synthetic-user",
                    ),
                isSupported = false,
                supportCount = 12,
                replies = listOf(nested),
                images =
                    listOf(CommentImage(imageUrl, previewUrl, 320, 180, "image/jpeg", imageUrl)),
            )
        compose.setContent {
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                HeyReadTheme("light") {
                    androidx.compose.material3.Surface {
                        Column(Modifier.padding(16.dp)) {
                            MediaImageThumbnail(
                                MediaImage(articlePreviewUrl, originalUrl, "合成正文图"),
                                Modifier.height(176.dp).testTag("article-image-synthetic"),
                            ) {
                                activeImage.value =
                                    MediaImage(articlePreviewUrl, originalUrl, "合成正文图")
                            }
                            ProfileRow(
                                root.profile,
                                root.author,
                                noImages = true,
                                timestamp = "10-17 12:30",
                            )
                            CommentThreadCard(
                                comment = root,
                                replies = listOf(nested),
                                replyLoading = false,
                                replyHasMore = false,
                                replyError = null,
                                noImages = false,
                                onReply = { target, nestedReply ->
                                    replyTargets.add(target.id to nestedReply)
                                },
                                onLoadReplies = {},
                                onMoreReplies = {},
                                onRetryReplies = {},
                                supportState = { id ->
                                    CommentSupportState(
                                        supported = false,
                                        count = if (id == root.id) 12 else 3,
                                    )
                                },
                                onSupport = { supportClicks.intValue++ },
                                authorUserId = root.profile?.userId,
                                onProfileClick = { profileClicks.add(it.userId.orEmpty()) },
                                onImageClick = { image ->
                                    activeImage.value =
                                        MediaImage(
                                            image.thumbnailUrl ?: image.url,
                                            image.originalUrl ?: image.url,
                                            "合成评论图",
                                        )
                                },
                            )
                        }
                        activeImage.value?.let { image ->
                            MediaPreviewDialog(image, onDismiss = { activeImage.value = null })
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            requestedUrls.contains(articlePreviewUrl) && requestedUrls.contains(previewUrl)
        }
        assertTrue("合成预览图片没有实际加载", requestedUrls.contains(articlePreviewUrl))
        assertTrue("合成评论缩略图没有实际加载", requestedUrls.contains(previewUrl))
        saveDeviceScreenshot(context, "v16-support-before-click.png")
        compose.onNodeWithTag("comment-support-synthetic-root").assertIsDisplayed()
        compose.onNodeWithTag("comment-support-synthetic-root").performClick()
        compose.runOnIdle { assertEquals(1, supportClicks.intValue) }
        compose.onNodeWithTag("comment-support-synthetic-child").assertDoesNotExist()
        compose
            .onAllNodes(hasTestTag("profile-row-synthetic-user") and hasClickAction())
            .assertCountEquals(1)
            .get(0)
            .performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-user"), profileClicks.toList()) }
        compose
            .onAllNodes(hasTestTag("profile-row-synthetic-replier") and hasClickAction())
            .assertCountEquals(1)
            .get(0)
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf("synthetic-user", "synthetic-replier"), profileClicks.toList())
        }
        compose.onNodeWithTag("comment-reply-synthetic-root").assertDoesNotExist()
        compose.onNodeWithTag("comment-reply-synthetic-child").assertDoesNotExist()
        compose.onAllNodesWithText("Lv.18").assertCountEquals(2)
        compose.onNodeWithText("Lv.4").assertDoesNotExist()
        compose.onNodeWithText("作者").assertIsDisplayed()
        compose.onNodeWithText("合成评论：正文与图片信息用于布局验收。", substring = true).performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-root" to false), replyTargets.toList()) }
        compose.onNodeWithText("合成楼中楼回复").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("synthetic-root" to false, "synthetic-child" to true),
                replyTargets.toList(),
            )
        }
        compose.onNodeWithTag("comment-image-synthetic-root-0").performClick()
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
        compose.onNodeWithTag("media-original-toggle").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { requestedUrls.contains(imageUrl) }
        org.junit.Assert.assertTrue("评论原图URL没有交给图片加载器", requestedUrls.contains(imageUrl))
        val syntheticScreenshot =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, "heyread-v16-synthetic.png").outputStream().use {
            syntheticScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("media-close").performClick()
        compose.onNodeWithTag("article-image-synthetic").performClick()
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
        compose.onNodeWithTag("media-original-toggle").performClick()
        compose.waitUntil(5_000) { requestedUrls.contains(originalUrl) }
        compose.onNodeWithTag("media-close").performClick()
        compose.runOnIdle { assertEquals(2, replyTargets.size) }
    }

    @Test
    fun noImageModeShowsHiddenPlaceholderWithoutOpeningViewer() {
        val comment =
            RemoteComment(
                id = "hidden-image",
                author = "User",
                text = "fixture",
                createdAt = 0,
                likes = 0,
                images = listOf(CommentImage("https://images.invalid/never-requested.jpg")),
            )
        compose.setContent {
            HeyReadTheme("light") {
                CommentThreadCard(
                    comment = comment,
                    replies = emptyList(),
                    replyLoading = false,
                    replyHasMore = false,
                    replyError = null,
                    noImages = true,
                    onReply = { _, _ -> },
                    onLoadReplies = {},
                    onMoreReplies = {},
                    onRetryReplies = {},
                )
            }
        }
        compose.onNodeWithText("图片已隐藏").assertIsDisplayed()
    }

    @Test
    fun longPressingMainAndNestedCommentShowsNativeCopyAndDoesNotReply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replyTargets = mutableStateListOf<String>()
        val child =
            RemoteComment(
                id = "selection-child",
                author = "Child",
                text = "楼中楼可选择复制的内容",
                createdAt = 0,
                likes = 0,
            )
        val root =
            RemoteComment(
                id = "selection-root",
                author = "Root",
                text = "主评论可选择复制的内容",
                createdAt = 0,
                likes = 0,
                childCount = 1,
            )
        compose.setContent {
            HeyReadTheme("light") {
                Column {
                    SelectionContainer { androidx.compose.material3.Text("正文可选择复制的段落") }
                    CommentThreadCard(
                        comment = root,
                        replies = listOf(child),
                        replyLoading = false,
                        replyHasMore = false,
                        replyError = null,
                        noImages = true,
                        onReply = { target, _ -> replyTargets.add(target.id) },
                        onLoadReplies = {},
                        onMoreReplies = {},
                        onRetryReplies = {},
                    )
                }
            }
        }
        val automation = instrumentation.uiAutomation
        val previous = automation.serviceInfo
        val previousFlags = previous.flags
        automation.serviceInfo =
            AccessibilityServiceInfo().apply {
                eventTypes = previous.eventTypes
                feedbackType = previous.feedbackType
                notificationTimeout = previous.notificationTimeout
                packageNames = previous.packageNames
                flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        try {
            listOf(
                    "正文可选择复制的段落" to "article",
                    "主评论可选择复制的内容" to "main-comment",
                    "楼中楼可选择复制的内容" to "nested-comment",
                )
                .forEach { (text, screenshotName) ->
                    compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput {
                        longClick()
                    }
                    instrumentation.waitForIdleSync()
                    saveDeviceScreenshot(context, "v16-long-press-$screenshotName.png")
                    val roots = automation.windows.mapNotNull { it.root }
                    val copyNode =
                        roots.asSequence().flatMap(::walkNodes).firstOrNull {
                            it.text?.toString() in setOf("Copy", "复制")
                        }
                    assertNotNull("系统原生复制菜单未出现：$text", copyNode)
                    assertTrue(clickAccessibilityAction(automation, copyNode!!))
                    instrumentation.waitForIdleSync()
                    val clipboard =
                        context.getSystemService(android.content.ClipboardManager::class.java)
                    var copied: String? = null
                    compose.runOnIdle {
                        copied =
                            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    }
                    assertTrue(copied?.contains(text.substring(0, 2)) == true)
                }
            compose.runOnIdle { assertEquals(emptyList<String>(), replyTargets.toList()) }
        } finally {
            automation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            automation.serviceInfo = previous
        }
    }

    private fun clickAccessibilityAction(
        automation: android.app.UiAutomation,
        node: AccessibilityNodeInfo,
    ): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return true
            current = current.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun saveDeviceScreenshot(context: android.content.Context, name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun syntheticJpeg(): ByteArray =
        java.io.ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(220, 190, 80))
                compress(Bitmap.CompressFormat.JPEG, 90, output)
                recycle()
            }
            output.toByteArray()
        }

    private fun syntheticImageLoader(
        context: android.content.Context,
        bytes: ByteArray,
        requestedUrls: MutableList<String>,
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
                            return if (
                                url.startsWith("https://images.invalid/") ||
                                    data.host == "imgheybox.max-c.com" ||
                                    data.host == "imgheybox1.max-c.com"
                            ) {
                                object : Fetcher {
                                    override suspend fun fetch(): FetchResult {
                                        requestedUrls.add(url)
                                        val bitmap =
                                            android.graphics.BitmapFactory.decodeByteArray(
                                                bytes,
                                                0,
                                                bytes.size,
                                            )
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
                            } else null
                        }
                    }
                )
            }
            .build()

    private fun walkNodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(walkNodes(it)) }
    }
}
