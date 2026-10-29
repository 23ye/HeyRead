package cn.heyread.app.data.account

import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSocialProtocolTest {
    private val story =
        Story(
            id = "post-42",
            source = Source.HEYBOX,
            title = "Synthetic post",
            excerpt = "Synthetic excerpt",
            body = "",
            author = "Synthetic author",
            date = "2026-01-01",
            url = "https://www.xiaoheihe.cn/app/bbs/link/post-42",
            hSrc = "synthetic-home",
        )

    @Test
    fun onlineSearchFiltersMixedRowsAndCleansHighlightedTitle() = runBlocking {
        lateinit var request: Request
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor {
                        request = it
                        """{"status":"ok","result":{"items":[
              {"type":"link","info":{"linkid":12,"title":"<em>Steam</em> 公告","description":"post","h_src":"discover","user":{"username":"Author"}}},
              {"type":"space","info":{"title":"ignored"}},
              {"type":"relevant_search","info":{"title":"ignored"}},
              {"type":"link","info":{"linkid":13,"title":"Second","description":"post"}}
            ]}}"""
                    }
            )

        val page = repo.search("Steam", offset = 20)

        assertEquals("/bbs/app/api/general/search/v1", request.url.encodedPath)
        assertEquals("Steam", request.url.queryParameter("q"))
        assertEquals("link", request.url.queryParameter("search_type"))
        assertEquals("20", request.url.queryParameter("offset"))
        assertEquals("20", request.url.queryParameter("limit"))
        assertEquals(listOf("12", "13"), page.stories.map { it.id })
        assertEquals("Steam 公告", page.stories.first().title)
        assertEquals("discover", page.stories.first().hSrc)
        assertTrue(page.hasMore)
    }

    @Test
    fun accountFullArticleMergesListVideoWhenTreeAndWebViewOmitItsAddress() = runBlocking {
        var calls = 0
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor {
                        calls++
                        if (it.url.encodedPath.endsWith("tree/v2")) {
                            """{"status":"ok","result":{"link":{"linkid":"post-42","body":{"text":"summary"}},"comment":{"comments":[]}}}"""
                        } else {
                            """{"status":"ok","result":{"link":{"content":[{"type":"html","text":"<p>Full synthetic text.</p>"}]}}}"""
                        }
                    }
            )
        val listStory =
            story.copy(
                feedVideo =
                    ArticleBlock.Video(
                        url = "https://cdn.example/video.mp4?auth=keep&sign=keep",
                        durationSeconds = 33,
                    )
            )

        val article = repo.loadFullArticle(listStory)

        assertEquals(2, calls)
        assertEquals(
            "Full synthetic text.",
            (article.blocks.first() as ArticleBlock.Paragraph).text,
        )
        assertEquals(
            "https://cdn.example/video.mp4?auth=keep&sign=keep",
            article.blocks.filterIsInstance<ArticleBlock.Video>().single().url,
        )
    }

    @Test
    fun treeStatsUseIsAwardLinkAndKeepServerCountsAndReceivedAuthCode() = runBlocking {
        var calls = 0
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor {
                        calls++
                        if (it.url.encodedPath.endsWith("tree/v2")) {
                            """{"status":"ok","result":{"link":{"linkid":"post-42","title":"Synthetic","body":{"text":"summary"},"stats":{"is_award_link":1,"is_favour":1,"link_award_num":73,"comment_num":12},"auth_code":"received-code"},"comment":{"comments":[],"comment_config":{"has_more_floors":false}}}}"""
                        } else {
                            """{"status":"ok","result":{"link":{"content":[{"type":"html","text":"<p>Full synthetic text.</p>"}]}}}"""
                        }
                    }
            )

        val article = repo.loadFullArticle(story)

        assertEquals(2, calls)
        assertTrue(article.isLiked == true)
        assertTrue(article.isFavorited == true)
        assertEquals(73, article.likeCount)
        assertEquals(12, article.commentCount)
        assertEquals("received-code", article.authCode)
        assertFalse(article.hasMoreComments)
    }

    @Test
    fun explicitUserWritesUseOfficialPathsAndReceivedContextOnly() = runBlocking {
        val requests = mutableListOf<Request>()
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor { request ->
                        requests += request
                        """{"status":"ok"}"""
                    }
            )

        repo.setLiked(story, liked = true)
        repo.setCloudFavorite(story, saved = true)
        repo.createComment(story, "Synthetic comment", authCode = "received-code")

        assertEquals(
            listOf(
                "/bbs/app/profile/award/link",
                "/bbs/app/link/favour",
                "/bbs/app/comment/create",
            ),
            requests.map { it.url.encodedPath },
        )
        requests.forEach { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("api.xiaoheihe.cn", request.url.host)
            assertEquals("synthetic-home", request.url.queryParameter("h_src"))
        }
        assertEquals("1", form(requests[0])["award_type"])
        assertEquals("1", form(requests[1])["favour_type"])
        assertEquals("post-42", form(requests[2])["link_id"])
        assertEquals("Synthetic comment", form(requests[2])["text"])
        assertEquals("", form(requests[2])["imgs"])
        assertEquals("0", form(requests[2])["is_cy"])
        assertEquals("received-code", requests[2].url.queryParameter("auth_code"))
    }

    @Test
    fun mainAndNestedRepliesUseOnlyTheirVerifiedIdentifiers() = runBlocking {
        val requests = mutableListOf<Request>()
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor { request ->
                        requests += request
                        """{"status":"ok"}"""
                    }
            )
        repo.createComment(story, "main", null, ReplyTarget.MainComment)
        repo.createComment(story, "reply main", null, ReplyTarget("main-7", "main-7", "User"))
        repo.createComment(story, "reply nested", null, ReplyTarget("main-7", "child-9", "User"))
        val forms = requests.map(::form)
        assertFalse("root_id" in forms[0])
        assertFalse("reply_id" in forms[0])
        assertEquals("main-7", forms[1]["root_id"])
        assertEquals("main-7", forms[1]["reply_id"])
        assertEquals("main-7", forms[2]["root_id"])
        assertEquals("child-9", forms[2]["reply_id"])
    }

    @Test
    fun previewAndPagedRepliesRetainRootContextAndRealProfiles() {
        val repo = AccountRepository(requestExecutor = AccountRequestExecutor { "{}" })
        val preview =
            org.json.JSONArray(
                """
          [{"comment":[
            {"commentid":"root-1","text":"main","user":{"username":"Root","avatar":"https://img.example/root.jpg","level_info":{"level":7}},"ip_location":"上海"},
            {"commentid":"child-2","text":"child","replyid":"root-1","replyuser":{"username":"Root"},"user":{"username":"Child"}}
          ]}]
        """
            )
        val main = repo.parseComments(preview).single()
        assertEquals("root-1", main.rootCommentId)
        assertEquals("Lv.7", main.profile?.level)
        assertEquals("上海", main.profile?.location)
        assertEquals("root-1", main.replies.single().rootCommentId)
        assertEquals("Root", main.replies.single().replyTo)

        val page =
            repo
                .parseCommentRows(
                    org.json.JSONArray(
                        """
          [{"commentid":"child-3","text":"page child","user":{"username":"Pager"},"ip_location":"北京"}]
        """
                    ),
                    "root-1",
                )
                .single()
        assertEquals("root-1", page.rootCommentId)
        assertEquals("北京", page.profile?.location)
        assertEquals(null, repo.parseProfile(org.json.JSONObject("""{"username":"Only"}"""))?.level)
    }

    @Test
    fun articleTaxonomyReadsOnlyOfficialTopicsAndHashtags() {
        val repo = AccountRepository(requestExecutor = AccountRequestExecutor { "{}" })
        val sections =
            repo.parseLabels(
                org.json.JSONArray(
                    """
          [{"topic_id":"g1","name":"游戏区","pic_url":"https://img.example/g.jpg"},{"topic_id":"g1","name":"重复"},{"name":"缺少ID"},{"topic_id":{},"name":{"bad":"name"}},{"name":""}]
        """
                ),
                listOf("topic_id", "id"),
                "pic_url",
            )
        val topics =
            repo.parseLabels(
                org.json.JSONArray("""[{"hashtag_id":"h1","name":"攻略"}]"""),
                listOf("hashtag_id", "id"),
                "",
            )
        assertEquals(listOf("游戏区", "缺少ID"), sections.map { it.name })
        assertEquals("", sections.last().id)
        assertEquals("https://img.example/g.jpg", sections.single { it.id == "g1" }.imageUrl)
        assertEquals(listOf("攻略"), topics.map { it.name })
    }

    @Test
    fun businessFailureIsNotReportedAsSuccessfulWrite() = runBlocking {
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor { """{"status":"failed","msg":"lack_token"}""" }
            )
        val error = runCatching { repo.setLiked(story, liked = true) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("官方客户端完成验证"))
    }

    @Test
    fun commentSupportAndFollowUseOnlyVerifiedOfficialIdentifiers() = runBlocking {
        val requests = mutableListOf<Request>()
        val repo =
            AccountRepository(
                requestExecutor =
                    AccountRequestExecutor { request ->
                        requests += request
                        """{"status":"ok"}"""
                    }
            )
        repo.supportComment("comment-8", true, story.hSrc)
        repo.supportComment("comment-8", false, story.hSrc)
        repo.followUser("author-9", story.hSrc)
        repo.cancelFollowUser("author-9", story.hSrc)
        assertEquals(
            listOf(
                "/bbs/app/comment/support",
                "/bbs/app/comment/support",
                "/bbs/app/profile/follow/user",
                "/bbs/app/profile/follow/user/cancel",
            ),
            requests.map { it.url.encodedPath },
        )
        assertEquals("1", form(requests[0])["support_type"])
        assertEquals("2", form(requests[1])["support_type"])
        assertEquals("comment-8", form(requests[0])["comment_id"])
        assertEquals("author-9", form(requests[2])["following_id"])
        requests.forEach { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("api.xiaoheihe.cn", request.url.host)
            assertEquals("synthetic-home", request.url.queryParameter("h_src"))
        }
    }

    @Test
    fun supportAndFollowStatesUseExplicitServiceMappingsAndCommentMediaIsSafe() {
        val repo = AccountRepository(requestExecutor = AccountRequestExecutor { "{}" })
        assertEquals(true, repo.parseCommentSupport(org.json.JSONObject("""{"is_support":1}""")))
        assertEquals(false, repo.parseCommentSupport(org.json.JSONObject("""{"is_support":2}""")))
        assertEquals(null, repo.parseCommentSupport(org.json.JSONObject("""{"is_support":0}""")))
        assertEquals(false, repo.parseFollowStatus(0))
        assertEquals(true, repo.parseFollowStatus(1))
        assertEquals(false, repo.parseFollowStatus(2))
        assertEquals(true, repo.parseFollowStatus(3))
        assertEquals(null, repo.parseFollowStatus(7))

        val comments =
            repo.parseCommentRows(
                org.json.JSONArray(
                    """[{"commentid":"c1","text":"ok","user":{"userid":"u1","username":"User","level_info":{"level":4}},"imgs":[{"url":"https://img.example/full.jpg","thumb":"https://img.example/thumb.jpg","width":640,"height":480,"mimetype":"image/jpeg"},{"url":"http://insecure.example/no.jpg"}]}]"""
                ),
                "root",
            )
        assertEquals("u1", comments.single().profile?.userId)
        assertEquals("Lv.4", comments.single().profile?.level)
        assertEquals(1, comments.single().images.size)
        assertEquals(
            "https://img.example/thumb.jpg",
            comments.single().images.single().thumbnailUrl,
        )
        assertEquals(640, comments.single().images.single().width)
    }

    private fun form(request: Request): Map<String, String> {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8().split('&').associate { pair ->
            val key = java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }
    }
}
