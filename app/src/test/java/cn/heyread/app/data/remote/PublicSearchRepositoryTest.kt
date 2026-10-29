package cn.heyread.app.data.remote

import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class PublicSearchRepositoryTest {
    @Test
    fun generalSearchUsesVerifiedScopeAndPublicParametersAndCleansHighlights() = runTest {
        lateinit var captured: Request
        val repository =
            PublicSearchRepository(
                execute = { request ->
                    captured = request
                    """{"status":"ok","result":{"has_more":true,"items":[{"type":"link","info":{"link_id":"p1","title":"<em>NiKo</em>","description":"<em>needle</em> match","topics":[{"topic_id":18745,"name":"硬件"}]}}]}}"""
                }
            )
        val batch =
            repository.load(
                "NiKo",
                SearchScope(SearchScopeType.SECTION, "18745", "硬件"),
                20,
                null,
                null,
                null,
            )
        assertEquals("18745", captured.url.queryParameter("topic_id"))
        assertEquals("20", captured.url.queryParameter("offset"))
        assertEquals("web", captured.url.queryParameter("os_type"))
        assertNull(captured.header("Cookie"))
        assertEquals("NiKo", batch.stories.single().title)
        assertEquals("needle match", batch.stories.single().body)
        assertEquals("p1", batch.rawIds.single())
        assertEquals(40, batch.nextOffset)
        assertTrue(batch.hasMore)
    }

    @Test
    fun missingTitleFallsBackToCleanedDescription() {
        val story =
            DestinationRepository()
                .parseStory(
                    org.json.JSONObject("""{"linkid":"p","description":"<em>real text</em>"}""")
                )
        assertEquals("real text", story?.title)
    }

    @Test
    fun sectionMismatchAndMalformedPaginationAreErrors() = runTest {
        val mismatch =
            PublicSearchRepository(
                execute = {
                    """{"status":"ok","result":{"items":[{"type":"link","info":{"linkid":"p","title":"x","topics":[{"topic_id":99,"name":"other"}]}}]}}"""
                }
            )
        val mismatchError =
            runCatching {
                    mismatch.load(
                        "x",
                        SearchScope(SearchScopeType.SECTION, "1", "section"),
                        0,
                        null,
                        null,
                        null,
                    )
                }
                .exceptionOrNull()
        assertTrue(mismatchError?.message.orEmpty().contains("分区不符"))

        val malformed =
            PublicSearchRepository(
                execute = { """{"status":"ok","result":{"has_more":"maybe","items":[]}}""" }
            )
        assertNotNull(
            runCatching { malformed.load("x", SearchScope.GLOBAL, 0, null, null, null) }
                .exceptionOrNull()
        )
    }

    @Test
    fun hashtagUsesRawOffsetAndMatchesFullCleanedDescription() = runTest {
        val calls = mutableListOf<Int>()
        val stories =
            listOf(
                story(
                    "a",
                    "cover",
                    "<em>needle</em> in the full description",
                    hashtags = listOf(cn.heyread.app.data.model.ArticleLabel("55", "topic")),
                ),
                story(
                    "b",
                    "other",
                    "not this",
                    hashtags = listOf(cn.heyread.app.data.model.ArticleLabel("55", "topic")),
                ),
            )
        val loader = DestinationLoader { _, offset, _, _, _, _, _, _, _, _ ->
            calls += offset
            DestinationPage(
                DestinationData("topic"),
                stories,
                offset + 29,
                "cursor-$offset",
                true,
                rawCount = 29,
                rawIds = (offset until offset + 29).map(Int::toString),
            )
        }
        val repository = PublicSearchRepository(hashtagFeeds = loader)
        val batch =
            repository.load(
                "needle",
                SearchScope(SearchScopeType.HASHTAG, "55", "topic"),
                29,
                null,
                null,
                null,
            )
        assertEquals(listOf(29), calls)
        assertEquals(listOf("a"), batch.stories.map(Story::id))
        assertEquals(58, batch.nextOffset)
        assertEquals(29, batch.rawCount)
        assertTrue(batch.hasMore)
        assertEquals("cursor-29", batch.nextCursor)
    }

    @Test
    fun hashtagScopeMismatchIsNotReportedAsNoMatch() = runTest {
        val loader = DestinationLoader { _, offset, _, _, _, _, _, _, _, _ ->
            DestinationPage(
                DestinationData("topic"),
                listOf(
                    story(
                        "a",
                        "title",
                        "text",
                        listOf(cn.heyread.app.data.model.ArticleLabel("other", "wrong")),
                    )
                ),
                offset + 1,
                null,
                true,
            )
        }
        val repo = PublicSearchRepository(hashtagFeeds = loader)
        assertTrue(
            runCatching {
                    repo.load(
                        "x",
                        SearchScope(SearchScopeType.HASHTAG, "right", "topic"),
                        0,
                        null,
                        null,
                        null,
                    )
                }
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("不属于")
        )
    }

    @Test
    fun captchaProofIsAttachedOnlyToMatchingRequestAndConsumedOnce() = runTest {
        val requests = mutableListOf<Request>()
        var first = true
        val repository =
            PublicSearchRepository(
                execute = { request ->
                    requests += request
                    if (first) {
                        first = false
                        """{"status":"show_captcha"}"""
                    } else """{"status":"ok","result":{"items":[],"has_more":false}}"""
                }
            )
        val exception =
            runCatching { repository.load("query", SearchScope.GLOBAL, 0, null, null, null) }
                .exceptionOrNull()
        assertTrue(exception is CaptchaRequiredException)
        val challenge = (exception as CaptchaRequiredException).challenge
        assertEquals("199251710", challenge.appId)
        val proof = CaptchaProof("synthetic-ticket", "synthetic-rand")
        repository.load("query", SearchScope.GLOBAL, 0, null, challenge, proof)
        assertEquals("synthetic-ticket", requests.last().url.queryParameter("ticket"))
        repository.load("query", SearchScope.GLOBAL, 0, null, challenge, proof)
        assertNull(requests.last().url.queryParameter("ticket"))

        val unusedProof = CaptchaProof("unused-ticket", "unused-rand")
        repository.load("different", SearchScope.GLOBAL, 0, null, challenge, unusedProof)
        assertNull(requests.last().url.queryParameter("ticket"))
        repository.load("query", SearchScope.GLOBAL, 0, null, challenge, unusedProof)
        assertEquals("unused-ticket", requests.last().url.queryParameter("ticket"))
    }

    private fun story(
        id: String,
        title: String,
        body: String,
        hashtags: List<cn.heyread.app.data.model.ArticleLabel> = emptyList(),
    ) =
        Story(
            id,
            Source.HEYBOX,
            title,
            body.take(160),
            body,
            "author",
            "today",
            hashtags = hashtags,
        )
}
