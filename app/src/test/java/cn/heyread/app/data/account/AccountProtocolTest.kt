package cn.heyread.app.data.account

import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.rich.OfficialEmojiCatalog
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProtocolTest {
    @Test
    fun qrPollingStatesAreDistinctAndOnlySuccessWaitsForCredentials() {
        assertEquals(QrPollResult.Ready, QrStateParser.parse("ok", "ready"))
        assertEquals(QrPollResult.Cancelled, QrStateParser.parse("success", "cancel"))
        assertEquals(QrPollResult.Waiting, QrStateParser.parse("ok", "wait"))
        assertEquals(null, QrStateParser.parse("failed", "ready"))
    }

    @Test
    fun htmlInlineTextIsJoinedAndImageOrderingIsKept() {
        val repo = AccountRepository()
        val blocks =
            repo.htmlBlocks(
                "<p>Hello <b>brave</b> <a href='#'>reader</a><img src='https://cdn.example/thumb.jpg' data-original='https://cdn.example/full.jpg'></p><blockquote>Quoted <em>words</em></blockquote>"
            )
        assertEquals(3, blocks.size)
        assertEquals(ArticleBlock.Paragraph("Hello brave reader"), blocks[0])
        assertEquals(
            ArticleBlock.Image(
                url = "https://cdn.example/thumb.jpg",
                originalUrl = "https://cdn.example/full.jpg",
            ),
            blocks[1],
        )
        assertEquals(ArticleBlock.Quote(listOf(ArticleBlock.Paragraph("Quoted words"))), blocks[2])
    }

    @Test
    fun htmlInlineImagesAreNotRepeatedAsTrailingImageAttachments() {
        val repo = AccountRepository()
        val content =
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "html")
                        .put(
                            "text",
                            "<p><img data-original='https://imgheybox.max-c.com/web/bbs/2026/10/16/a1/thumb.jpeg'></p>" +
                                "<p><img data-original='https://imgheybox.max-c.com/web/bbs/2026/10/16/b2/thumb.jpeg'></p>",
                        )
                )
                .put(
                    JSONObject()
                        .put("type", "img")
                        .put(
                            "url",
                            "https://imgheybox1.max-c.com/web/bbs/2026/10/16/a1/thumb.jpeg?imageMogr2/auto-orient/ignore-error/1/format/jpg/thumbnail/850x1450%3E",
                        )
                )
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "url",
                            "https://imgheybox1.max-c.com/web/bbs/2026/10/16/b2/thumb.jpeg?imageMogr2/auto-orient/ignore-error/1/format/jpg/thumbnail/850x1450%3E",
                        )
                )

        val blocks = repo.parseArticleBlocks(content)

        val images = blocks.filterIsInstance<ArticleBlock.Image>()
        assertEquals(2, images.size)
        assertEquals(
            listOf(
                "https://imgheybox.max-c.com/web/bbs/2026/10/16/a1/thumb.jpeg",
                "https://imgheybox.max-c.com/web/bbs/2026/10/16/b2/thumb.jpeg",
            ),
            images.map { it.url },
        )
        assertEquals(images.map { it.url }, images.map { it.originalUrl })
    }

    @Test
    fun articleImageDedupOnlyRemovesConfirmedCrossRepresentationMatches() {
        val repo = AccountRepository()
        val blocks =
            repo.parseArticleBlocks(
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put("url", "https://imgheybox1.max-c.com/p/a.jpg?imageView2/2/w/100")
                    )
                    .put(
                        JSONObject()
                            .put("type", "html")
                            .put(
                                "text",
                                "<p><img src='https://imgheybox.max-c.com/p/a.jpg'></p><p><img src='https://imgheybox.max-c.com/p/a.jpg'></p><p><img src='https://imgheybox.max-c.com/p/signed.jpg'></p>",
                            )
                    )
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put("url", "https://cdn.other.example/p/a.jpg")
                    )
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put("url", "https://cdn.other.example/p/a.jpg?sig=abc")
                    )
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put("url", "https://cdn.other.example/p/unique.jpg")
                    )
                    .put(
                        JSONObject()
                            .put("type", "image")
                            .put("url", "https://cdn.other.example/p/unique.jpg")
                    )
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put(
                                "url",
                                "https://imgheybox1.max-c.com/p/signed.jpg?imageMogr2/thumbnail/100x100&sig=abc",
                            )
                    )
            )

        assertEquals(
            listOf(
                "https://imgheybox.max-c.com/p/a.jpg",
                "https://imgheybox.max-c.com/p/a.jpg",
                "https://imgheybox.max-c.com/p/signed.jpg",
                "https://cdn.other.example/p/a.jpg",
                "https://cdn.other.example/p/a.jpg?sig=abc",
                "https://cdn.other.example/p/unique.jpg",
                "https://cdn.other.example/p/unique.jpg",
                "https://imgheybox1.max-c.com/p/signed.jpg?imageMogr2/thumbnail/100x100&sig=abc",
            ),
            blocks.filterIsInstance<ArticleBlock.Image>().map { it.url },
        )
    }

    @Test
    fun contentImagesAndNestedQuoteImagesKeepTheirPositionsAndMetadata() {
        val repo = AccountRepository()
        val blocks =
            repo.parseArticleBlocks(
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "<p>前文</p>"))
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put(
                                "url",
                                "https://imgheybox1.max-c.com/p/q.jpg?imageMogr2/thumbnail/100x100",
                            )
                    )
                    .put(
                        JSONObject()
                            .put("type", "quote")
                            .put(
                                "text",
                                "<img src='https://imgheybox.max-c.com/p/q.jpg' alt='保留替代文本'>",
                            )
                    )
                    .put(
                        JSONObject()
                            .put("type", "img")
                            .put("url", "https://cdn.other.example/p/extra.jpg")
                            .put("description", "附件说明")
                    )
            )

        assertEquals(1, blocks.filterIsInstance<ArticleBlock.Image>().size)
        assertEquals(ArticleBlock.Paragraph("前文"), blocks.first())
        val quote = blocks.filterIsInstance<ArticleBlock.Quote>().single()
        assertEquals(
            "保留替代文本",
            quote.blocks.filterIsInstance<ArticleBlock.Image>().single().description,
        )
        val endImage = blocks.last() as ArticleBlock.Image
        assertEquals("附件说明", endImage.caption)
        assertEquals("https://cdn.other.example/p/extra.jpg", endImage.url)
    }

    @Test
    fun repeatedStandaloneContentAttachmentsRemainInTheirOriginalOrder() {
        val blocks =
            AccountRepository()
                .parseArticleBlocks(
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "正文"))
                        .put(
                            JSONObject()
                                .put("type", "img")
                                .put("url", "https://cdn.example/repeat.jpg")
                        )
                        .put(
                            JSONObject()
                                .put("type", "image")
                                .put("url", "https://cdn.example/repeat.jpg")
                        )
                )

        assertEquals(
            listOf(
                ArticleBlock.Paragraph("正文"),
                ArticleBlock.Image("https://cdn.example/repeat.jpg"),
                ArticleBlock.Image("https://cdn.example/repeat.jpg"),
            ),
            blocks,
        )
    }

    @Test
    fun supplementaryAttachmentFillsOnlyMissingInlineMetadata() {
        val blocks =
            AccountRepository()
                .parseArticleBlocks(
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "html")
                                .put(
                                    "text",
                                    "<figure><img src='https://imgheybox.max-c.com/p/a.jpg' data-original='https://imgheybox.max-c.com/p/full.gif' alt='正文替代'><figcaption>正文图注</figcaption></figure>",
                                )
                        )
                        .put(
                            JSONObject()
                                .put("type", "img")
                                .put(
                                    "url",
                                    "https://imgheybox1.max-c.com/p/full.gif?imageMogr2/thumbnail/100x100",
                                )
                                .put("description", "附件描述")
                                .put("caption", "附件图注")
                                .put("mime_type", "image/gif")
                        )
                )

        val image = blocks.filterIsInstance<ArticleBlock.Image>().single()
        assertEquals("正文替代", image.description)
        assertEquals("正文图注", image.caption)
        assertEquals("https://imgheybox.max-c.com/p/full.gif", image.originalUrl)
        assertEquals("image/gif", image.mimeType)
        assertTrue(image.animated)
    }

    @Test
    fun supplementaryAttachmentCanFillAbsentInlineDescriptionAndCaption() {
        val blocks =
            AccountRepository()
                .parseArticleBlocks(
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "html")
                                .put("text", "<img src='https://imgheybox.max-c.com/p/a.jpg'>")
                        )
                        .put(
                            JSONObject()
                                .put("type", "img")
                                .put("url", "https://imgheybox1.max-c.com/p/a.jpg")
                                .put("alt", "附件替代文本")
                                .put("caption", "附件图注")
                        )
                )

        val image = blocks.filterIsInstance<ArticleBlock.Image>().single()
        assertEquals("附件替代文本", image.description)
        assertEquals("附件图注", image.caption)
    }

    @Test
    fun mixedRichHtmlKeepsCaptionsQuotesEmojiAndFollowingParagraphsInOrder() {
        val repo = AccountRepository()
        val emoji = "https://imgheybox.max-c.com/emoji.png"
        val blocks =
            repo.htmlBlocks(
                "<p>Before</p><figure><img src='bad' data-src='https://cdn.example/p.png' alt='图像替代'><figcaption>官方图注</figcaption></figure>" +
                    "<blockquote><p>引用 <b>文字</b><img src='$emoji'></p><img src='https://cdn.example/q.png'></blockquote><p>After</p>",
                mapOf("[cube_星乐蒂]" to emoji),
            )
        assertEquals(4, blocks.size)
        assertEquals(ArticleBlock.Paragraph("Before"), blocks[0])
        assertEquals(
            ArticleBlock.Image("https://cdn.example/p.png", description = "图像替代", caption = "官方图注"),
            blocks[1],
        )
        assertEquals(
            ArticleBlock.Quote(
                listOf(
                    ArticleBlock.Paragraph("引用 文字[cube_星乐蒂]"),
                    ArticleBlock.Image("https://cdn.example/q.png"),
                )
            ),
            blocks[2],
        )
        assertEquals(ArticleBlock.Paragraph("After"), blocks[3])
        assertEquals(
            "表情 [cube_星乐蒂]",
            repo.parseCommentText("表情 <img src='$emoji'>", mapOf("[cube_星乐蒂]" to emoji)),
        )
    }

    @Test
    fun malformedImageFieldsUseValidFallbackAndRetainReadableCaptions() {
        val repo = AccountRepository()
        val content =
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put("url", "bad")
                        .put("image_url", "https://cdn.example/fallback.gif")
                        .put("mime_type", "image/gif")
                        .put("caption", JSONObject().put("not", "text"))
                        .put("description", "动画")
                )
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put("url", "javascript:alert(1)")
                        .put("alt", "坏图替代文本")
                        .put("caption", "仍可阅读的图注")
                )
                .put(JSONObject().put("type", "text").put("text", JSONObject().put("bad", "JSON")))
                .put(JSONObject().put("type", "text").put("content", "有效备用正文"))
        val blocks = repo.parseArticleBlocks(content)
        assertEquals(
            listOf(
                ArticleBlock.Image(
                    "https://cdn.example/fallback.gif",
                    description = "",
                    caption = "动画",
                    mimeType = "image/gif",
                    animated = true,
                ),
                ArticleBlock.UnavailableImage("坏图替代文本", "仍可阅读的图注"),
                ArticleBlock.Paragraph("有效备用正文"),
            ),
            blocks,
        )
    }

    @Test
    fun officialImageDescriptionHeadingBecomesCaptionOnlyWhenImmediatelyAfterImage() {
        val blocks =
            AccountRepository()
                .htmlBlocks(
                    "<p><img src='https://cdn.example/a.jpg'></p><h4 class='img-desc'>图片说明</h4>" +
                        "<p>正文间隔</p><h4 class='img-desc'>独立小标题</h4>"
                )
        assertEquals(
            listOf(
                ArticleBlock.Image("https://cdn.example/a.jpg", caption = "图片说明"),
                ArticleBlock.Paragraph("正文间隔"),
                ArticleBlock.Paragraph("独立小标题"),
            ),
            blocks,
        )
    }

    @Test
    fun failedTreeTextFieldsFallBackToFullBodyBeforeDescriptionSummary() {
        val repo = AccountRepository()
        val link =
            JSONObject()
                .put("text", JSONObject().put("bad", "not text"))
                .put("description", "摘要不能盖过正文")
        val body =
            JSONObject()
                .put("text", "<blockquote><img src='https://cdn.example/q.jpg'>完整正文引用</blockquote>")
        assertEquals(
            listOf(
                ArticleBlock.Quote(
                    listOf(
                        ArticleBlock.Image("https://cdn.example/q.jpg"),
                        ArticleBlock.Paragraph("完整正文引用"),
                    )
                )
            ),
            repo.fallbackArticleBlocks(link, body),
        )
    }

    @Test
    fun bundledAndLiveEmojiCatalogsPreserveExactTokensAndRejectUntrustedHosts() {
        val bundled =
            JSONObject()
                .put("source", "Official public GET /bbs/app/api/emojis/list; factual map")
                .put(
                    "entries",
                    JSONObject().put("[cube_星乐蒂]", "https://imgheybox.max-c.com/emoji.png"),
                )
        assertEquals(
            mapOf("[cube_星乐蒂]" to "https://imgheybox.max-c.com/emoji.png"),
            OfficialEmojiCatalog.parseBundled(bundled),
        )
        val response =
            JSONObject()
                .put("status", "ok")
                .put(
                    "result",
                    JSONObject()
                        .put(
                            "emoji_groups",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("group_code", "cube")
                                        .put(
                                            "emojis",
                                            JSONArray()
                                                .put(
                                                    JSONObject()
                                                        .put("code", "星乐蒂")
                                                        .put(
                                                            "img",
                                                            "https://imgheybox.max-c.com/emoji.png",
                                                        )
                                                )
                                                .put(
                                                    JSONObject()
                                                        .put("code", "other")
                                                        .put(
                                                            "img",
                                                            "https://evil.example/emoji.png",
                                                        )
                                                ),
                                        )
                                ),
                        ),
                )
        assertEquals(
            mapOf("[cube_星乐蒂]" to "https://imgheybox.max-c.com/emoji.png"),
            OfficialEmojiCatalog.parseCatalog(response),
        )
        assertTrue(
            OfficialEmojiCatalog.parseCatalog(
                    JSONObject()
                        .put("status", "ok")
                        .put("result", JSONObject().put("emoji_groups", JSONArray()))
                )
                .isEmpty()
        )
    }

    @Test
    fun commentImagePreviewAndExplicitOriginalUrlsArePreserved() {
        val rows =
            JSONArray()
                .put(
                    JSONObject()
                        .put("comment_id", 7)
                        .put("text", "synthetic")
                        .put("user", JSONObject().put("username", "Reader"))
                        .put(
                            "imgs",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("url", "https://cdn.example/full-comment.jpg")
                                        .put("thumb", "https://cdn.example/thumb-comment.jpg")
                                        .put(
                                            "original_url",
                                            "https://cdn.example/original-comment.jpg",
                                        )
                                )
                                .put(
                                    JSONObject()
                                        .put("url", "invalid")
                                        .put("thumb", "https://cdn.example/static-thumb.jpg")
                                        .put("original_url", "https://cdn.example/animated-image")
                                        .put("is_gif", 1)
                                ),
                        )
                )

        val images = AccountRepository().parseComments(rows).single().images
        val image = images.first()
        assertEquals("https://cdn.example/full-comment.jpg", image.url)
        assertEquals("https://cdn.example/thumb-comment.jpg", image.thumbnailUrl)
        assertEquals("https://cdn.example/original-comment.jpg", image.originalUrl)
        assertEquals("https://cdn.example/animated-image", images.last().url)
        assertTrue(images.last().animated)
    }

    @Test
    fun cookieJarAcceptsOnlyOfficialHttpsHostAndMakesAcceptedCookieSecure() {
        val jar = OfficialCookieJar()
        val api = "https://api.xiaoheihe.cn/bbs/app/link/tree/v2".toHttpUrl()
        val cdn = "https://cdn.max-c.com/image".toHttpUrl()
        val plain =
            Cookie.Builder()
                .name("user_pkey")
                .value("test-secret")
                .domain("xiaoheihe.cn")
                .path("/")
                .build()
        val foreign =
            Cookie.Builder().name("leak").value("no").domain("max-c.com").path("/").build()
        jar.saveFromResponse(api, listOf(plain, foreign))
        assertEquals(
            "user_pkey=test-secret",
            jar.loadForRequest(api).joinToString("; ") { "${it.name}=${it.value}" },
        )
        assertTrue(jar.loadForRequest(api).all { it.secure })
        assertTrue(jar.loadForRequest(cdn).isEmpty())
    }

    @Test
    fun qrSuccessRequiresRealPkeyAndUserIdAndAddsOnlyReceivedAliases() {
        val repo = AccountRepository()
        val incomplete = repo.parseAuthenticatedSession(JSONObject().put("heyboxid", "42"))
        assertTrue(incomplete is QrPollResult.Rejected)

        val response =
            JSONObject()
                .put("user_pkey", "fake-test-token")
                .put("account_detail", JSONObject().put("userid", "42").put("username", "Reader"))
        val result = repo.parseAuthenticatedSession(response) as QrPollResult.LoggedIn
        assertTrue(result.session.isValid)
        assertTrue(result.session.cookieHeader.contains("pkey=fake-test-token"))
        assertTrue(result.session.cookieHeader.contains("x_pkey=fake-test-token"))
        assertTrue(result.session.cookieHeader.contains("heybox_id=42"))
        assertEquals("Reader", result.session.nickname)
        assertFalse(result.session.toString().contains("fake-test-token"))
    }

    @Test
    fun cloudPostParserAcceptsNestedLinkAndRejectsNonPostStatistics() {
        val repo = AccountRepository()
        val wrapped =
            JSONObject()
                .put(
                    "link_info",
                    JSONObject()
                        .put("link_id", 123)
                        .put("title", "Saved title")
                        .put("description", "Saved description")
                        .put("has_video", 1)
                        .put(
                            "video_info",
                            JSONObject().put("url", "https://cdn.example/saved.mp4?sig=keep"),
                        )
                        .put("user", JSONObject().put("username", "Author")),
                )
        val story = repo.normalizePost(wrapped)
        assertNotNull(story)
        assertEquals("123", story?.id)
        assertEquals("https://cdn.example/saved.mp4?sig=keep", story?.feedVideo?.url)
        val statistic = JSONObject().put("count", 42)
        assertEquals(null, repo.normalizePost(statistic))
        val root =
            JSONObject()
                .put(
                    "result",
                    JSONObject()
                        .put("folder_info", JSONObject().put("items", JSONArray().put(wrapped))),
                )
        assertEquals(1, repo.findKnownArray(root, listOf("items"))?.length())
    }

    @Test
    fun favoriteFoldersAndWrappedRootCommentsAreNormalized() {
        val repo = AccountRepository()
        val wrappedFolder =
            JSONObject()
                .put(
                    "data",
                    JSONObject()
                        .put(
                            "folder_info",
                            JSONObject().put("fid", "f-7").put("folder_name", "稍后阅读"),
                        ),
                )
        val folder = repo.unwrapFolder(wrappedFolder)
        assertEquals("f-7", folder?.optString("fid"))
        assertEquals("稍后阅读", folder?.optString("folder_name"))

        val roots =
            JSONArray()
                .put(
                    JSONObject()
                        .put(
                            "comment",
                            JSONArray()
                                .put(JSONObject().put("comment_id", 20).put("text", "root"))
                                .put(
                                    JSONObject().put("comment_id", 21).put("text", "preview reply")
                                ),
                        )
                )
        val parsed = repo.parseComments(roots)
        assertEquals(1, parsed.size)
        assertEquals("20", parsed.single().id)
        assertEquals("preview reply", parsed.single().replies.single().text)
    }

    @Test
    fun alternateFavoriteFolderAndCloudPostAliasesAreAccepted() {
        val repo = AccountRepository()
        val folderWrapper =
            JSONObject()
                .put("fav_folder", JSONObject().put("fav_folder_id", "f-9").put("tab_name", "特别收藏"))
        val folder = repo.unwrapFolder(folderWrapper)
        assertEquals("f-9", folder?.optString("fav_folder_id"))
        assertEquals("特别收藏", folder?.optString("tab_name"))

        val folderRows = JSONArray().put(folderWrapper)
        val folderRoot =
            JSONObject().put("result", JSONObject().put("favorite_folders", folderRows))
        assertEquals(1, repo.findKnownArray(folderRoot, listOf("favorite_folders"))?.length())

        val alternatePost =
            JSONObject()
                .put(
                    "link_detail",
                    JSONObject()
                        .put("linkId", "l-21")
                        .put("link_title", "Wrapped title")
                        .put("summary", "Wrapped excerpt")
                        .put("images", JSONArray().put("https://cdn.example/post.jpg")),
                )
        val post = repo.normalizePost(alternatePost)
        assertEquals("l-21", post?.id)
        assertEquals("Wrapped title", post?.title)
        assertEquals("https://cdn.example/post.jpg", post?.image)

        val postRoot =
            JSONObject()
                .put("result", JSONObject().put("link_list", JSONArray().put(alternatePost)))
        assertEquals(
            1,
            repo.findKnownArray(postRoot, listOf("favorites", "collects", "link_list"))?.length(),
        )
    }
}
