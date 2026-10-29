package cn.heyread.app.data.account

import cn.heyread.app.data.model.ArticleBlock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleGameVideoParsingTest {
    private val repository = AccountRepository()

    @Test
    fun parsesGameCardIdsFromHtmlAndNumericNestedJson() {
        val html =
            repository.htmlBlocks(
                "<p>before</p><img data-gameid='3428810' src='https://cdn.example/game.png'>"
            )
        assertEquals(3428810, (html.last() as ArticleBlock.GameCard).appId)

        val blocks =
            repository.parseArticleBlocks(
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "game")
                            .put("app_info", JSONObject().put("steam_appid", 4773180))
                    )
            )
        assertEquals(listOf(ArticleBlock.GameCard(4773180)), blocks)
    }

    @Test
    fun ordinaryUrlsAreNotVideoAndOnlyKnownIframeProvidersAreExternal() {
        assertEquals(
            null,
            repository.parsePublicVideo(JSONObject().put("url", "https://example.com/article")),
        )
        assertTrue(
            repository.htmlBlocks("<iframe src='https://login.example/ad'></iframe>").none {
                it is ArticleBlock.ExternalVideo
            }
        )
        assertTrue(
            repository.htmlBlocks("<iframe src='https://www.youtube.com/embed/abc'></iframe>").any {
                it is ArticleBlock.ExternalVideo
            }
        )
    }

    @Test
    fun videoFallbackUsesOnlyVideoFieldsAndParsesClockDuration() {
        val video =
            repository.parsePublicVideo(
                JSONObject()
                    .put("has_video", 1)
                    .put(
                        "video_info",
                        JSONObject()
                            .put("url", "https://cdn.example/v.m3u8?sig=a")
                            .put("duration", "00:45"),
                    )
            )
        assertEquals("https://cdn.example/v.m3u8?sig=a", video?.url)
        assertEquals(45L, video?.durationSeconds)
        assertFalse(
            repository
                .htmlBlocks("<video><source src='file:///sdcard/a.mp4'></video>")
                .filterIsInstance<ArticleBlock.Video>()
                .any { it.url != null }
        )
    }

    @Test
    fun hasVideoPlaceholderDoesNotDuplicateOrHideAValidFeedCandidate() {
        val placeholder = JSONObject().put("has_video", 1)
        val valid =
            JSONObject()
                .put("video_url", "https://cdn.example/video.mp4")
                .put("video_thumb", "https://cdn.example/thumb.jpg")
        val result = repository.withPublicVideo(emptyList(), placeholder, valid)
        assertEquals(1, result.filterIsInstance<ArticleBlock.Video>().size)
        assertEquals("https://cdn.example/video.mp4", (result.single() as ArticleBlock.Video).url)
    }

    @Test
    fun validFeedUrlReplacesOnlyExplicitPlaceholderAndKeepsInlineUnavailableVideo() {
        val unavailableInline = ArticleBlock.Video(url = null, title = "作者插入的视频")
        val sourcePlaceholder = ArticleBlock.Video(url = null, sourcePlaceholder = true)
        val result =
            repository.withPublicVideo(
                listOf(ArticleBlock.Paragraph("正文"), unavailableInline, sourcePlaceholder),
                JSONObject().put("video_url", "https://cdn.example/video.mp4"),
            )

        assertEquals(3, result.size)
        assertEquals(unavailableInline, result[1])
        assertEquals("https://cdn.example/video.mp4", (result[2] as ArticleBlock.Video).url)
    }

    @Test
    fun refreshedPostVideoReplacesItsOldUrlWithoutChangingInlineVideo() {
        val refreshed =
            repository.withPublicVideo(
                listOf(
                    ArticleBlock.Video(
                        url = "https://cdn.example/old.mp4?sig=old",
                        postLevelVideo = true,
                    ),
                    ArticleBlock.Video(url = null, title = "Inline author video"),
                ),
                JSONObject().put("video_url", "https://cdn.example/new.mp4?sig=new"),
            )

        assertEquals(
            "https://cdn.example/new.mp4?sig=new",
            refreshed.filterIsInstance<ArticleBlock.Video>().first().url,
        )
        assertEquals(
            "Inline author video",
            refreshed.filterIsInstance<ArticleBlock.Video>()[1].title,
        )
        assertEquals(2, refreshed.filterIsInstance<ArticleBlock.Video>().size)
    }

    @Test
    fun fallbackSkipsUnknownJsonObjectsAndContinuesToBodyText() {
        val blocks =
            repository.fallbackArticleBlocks(
                JSONObject().put("text", "{\"unknown\":[1,2,3]}"),
                JSONObject().put("text", "<p>可读正文</p>"),
            )

        assertEquals(listOf(ArticleBlock.Paragraph("可读正文")), blocks)
    }
}
