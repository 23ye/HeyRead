package cn.heyread.app.data.account

import cn.heyread.app.data.local.decodeHistoryStory
import cn.heyread.app.data.local.encodeHistoryStory
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StoryDetailResolverTest {
    @Test
    fun currentOpenedSearchStoryWinsOverHistoryAfterHistorySerialization() {
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
        val storedHistory =
            decodeHistoryStory(encodeHistoryStory(opened)) ?: error("history missing")

        assertNull("short-lived feed URL must not enter local history", storedHistory.feedVideo)
        val selected =
            StoryDetailResolver.currentStory(
                Source.HEYBOX,
                opened.id,
                openedStory = opened,
                candidates = listOf(storedHistory),
            )

        assertSame(opened, selected)
        assertEquals(opened.feedVideo, selected?.feedVideo)
    }

    @Test
    fun currentFeedVideoEnrichesSameArticleCachedDetailAndKeepsBody() {
        val current =
            Story(
                id = "190978471",
                source = Source.HEYBOX,
                title = "好，好，好",
                excerpt = "摘要",
                body = "摘要正文",
                author = "匿名的长夏",
                date = "2026-09-19",
                feedVideo =
                    ArticleBlock.Video(
                        url = "https://cdn.example/video.mp4?auth=keep&sign=keep",
                        durationSeconds = 33,
                    ),
            )
        val cached =
            current.copy(
                body = "已加载的正文",
                detailBlocks =
                    listOf(
                        ArticleBlock.Paragraph("已加载的正文"),
                        ArticleBlock.Video(url = null, sourcePlaceholder = true),
                    ),
                feedVideo = null,
            )

        val merged = StoryDetailResolver.mergeCachedDetail(current, cached)

        assertEquals("已加载的正文", merged.body)
        assertEquals(current.feedVideo, merged.feedVideo)
        assertEquals(
            "https://cdn.example/video.mp4?auth=keep&sign=keep",
            (merged.detailBlocks?.last() as ArticleBlock.Video).url,
        )
    }

    @Test
    fun cachedDetailFromAnotherSourceOrIdCannotReplaceCurrentStory() {
        val current = story("same", Source.HEYBOX)
        val unrelated = story("same", Source.DEMO).copy(body = "unrelated")

        assertSame(current, StoryDetailResolver.mergeCachedDetail(current, unrelated))
        assertSame(
            current,
            StoryDetailResolver.currentStory(
                Source.HEYBOX,
                "same",
                openedStory = story("other", Source.HEYBOX),
                candidates = listOf(unrelated, current),
            ),
        )
    }

    @Test
    fun cachedAuthorTaxonomyAndShareUrlSurviveWhileCurrentDetailBodyStaysConsistent() {
        val current =
            story("same", Source.HEYBOX)
                .copy(
                    author = "List author",
                    body = "Current detailed body",
                    url = "https://www.xiaoheihe.cn/list-share",
                    sections = listOf(ArticleLabel("list-section", "List section")),
                    detailBlocks = listOf(ArticleBlock.Paragraph("Current detailed body")),
                )
        val cached =
            story("same", Source.HEYBOX)
                .copy(
                    author = "Detail author",
                    authorProfile =
                        AuthorProfile(
                            name = "Detail author",
                            avatarUrl = "https://img.example/avatar.png",
                            level = "Lv.6",
                            location = "上海",
                            userId = "user-6",
                        ),
                    sections = listOf(ArticleLabel("detail-section", "Detail section")),
                    hashtags = listOf(ArticleLabel("tag-1", "Detail tag")),
                    url = "https://www.xiaoheihe.cn/detail-share",
                    body = "Earlier body",
                    detailBlocks = listOf(ArticleBlock.Paragraph("Earlier body")),
                )

        val merged = StoryDetailResolver.mergeCachedDetail(current, cached)

        assertEquals("Detail author", merged.author)
        assertEquals("上海", merged.authorProfile?.location)
        assertEquals("Lv.6", merged.authorProfile?.level)
        assertEquals("detail-section", merged.sections.single().id)
        assertEquals("tag-1", merged.hashtags.single().id)
        assertEquals("https://www.xiaoheihe.cn/detail-share", merged.url)
        assertEquals("Current detailed body", merged.body)
        assertEquals(merged.body, (merged.detailBlocks?.single() as ArticleBlock.Paragraph).text)
    }

    private fun story(id: String, source: Source) =
        Story(
            id = id,
            source = source,
            title = "title",
            excerpt = "excerpt",
            body = "body",
            author = "author",
            date = "date",
        )
}
