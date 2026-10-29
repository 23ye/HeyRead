package cn.heyread.app.data.local

import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StoryHistoryCodecTest {
    @Test
    fun historyRoundTripKeepsProfileAndSectionMetadata() {
        val story =
            Story(
                id = "post-1",
                source = Source.HEYBOX,
                title = "Title",
                excerpt = "Summary",
                body = "Body",
                author = "Author",
                date = "Today",
                authorProfile =
                    AuthorProfile("Author", "https://img.example/a.png", "Lv.5", "上海", "u1", "简介"),
                sections =
                    listOf(
                        ArticleLabel("s1", "分区", "https://img.example/s.png"),
                        ArticleLabel("", "无 ID"),
                    ),
                hashtags = listOf(ArticleLabel("h1", "话题")),
                detailBlocks =
                    listOf(ArticleBlock.Video("https://cdn.example/short-lived.m3u8?sig=private")),
                feedVideo = ArticleBlock.Video("https://cdn.example/feed.mp4?sig=private"),
            )

        val encoded = encodeHistoryStory(story)
        val decoded = decodeHistoryStory(encoded)

        assertNotNull(decoded)
        assertEquals(story.authorProfile, decoded?.authorProfile)
        assertEquals(story.sections, decoded?.sections)
        assertEquals(story.hashtags, decoded?.hashtags)
        assertNull(decoded?.detailBlocks)
        assertNull(decoded?.feedVideo)
        org.junit.Assert.assertFalse(encoded.contains("short-lived"))
        org.junit.Assert.assertFalse(encoded.contains("feed.mp4"))
    }

    @Test
    fun oldHistoryWithoutDestinationMetadataStillDecodes() {
        val decoded =
            decodeHistoryStory(
                """{"id":"old","source":"HEYBOX","title":"Old","excerpt":"","body":"","author":"A","date":""}"""
            )

        assertNotNull(decoded)
        assertNull(decoded?.authorProfile)
        assertEquals(emptyList<Any>(), decoded?.sections)
    }
}
