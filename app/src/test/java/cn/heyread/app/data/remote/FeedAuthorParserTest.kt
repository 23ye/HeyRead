package cn.heyread.app.data.remote

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedAuthorParserTest {
    private val repository =
        StoryRepository(
            { "https://api.xiaoheihe.cn" },
            File(System.getProperty("java.io.tmpdir"), "heyread-parser-test"),
        )

    @Test
    fun readsRealAuthorAliasesAndNeverSubstitutesSourceName() {
        assertEquals(
            "Alice",
            repository.parseFeedAuthor(JSONObject("""{"user":{"username":"Alice"}}""")),
        )
        assertEquals(
            "Bobby",
            repository.parseFeedAuthor(JSONObject("""{"poster":{"nickname":"Bobby"}}""")),
        )
        assertEquals("Casey", repository.parseFeedAuthor(JSONObject("""{"username":"Casey"}""")))
        assertEquals(
            "作者未公开",
            repository.parseFeedAuthor(JSONObject("""{"title":"Post","app":"heybox"}""")),
        )
    }

    @Test
    fun keepsServerDisplayDateAndDoesNotInventEpochWhenMissing() {
        assertEquals(
            "刚刚",
            repository.parseFeedDate(JSONObject("""{"create_str":"刚刚","create_at":1792158231}""")),
        )
        assertEquals("时间未公开", repository.parseFeedDate(JSONObject("{}")))
        assertNotEquals(
            "时间未公开",
            repository.parseFeedDate(JSONObject("""{"create_at":1792230000}""")),
        )
    }

    @Test
    fun feedMetadataKeepsOnlyRealTypedIdsAndOfficialIconUrls() {
        val metadata =
            repository.parseFeedMetadata(
                JSONObject(
                    """{"userid":43436149,"user":{"username":"Alice"},"topics":[{"topic_id":18745,"name":"Section","pic_url":"https://img.example/s.png"},{"name":"No id"},{"topic_id":{},"name":{"bad":true}}],"hashtags":[{"hashtag_id":"554","name":"tag"},{"name":"missing id"}]}"""
                )
            )
        assertEquals("43436149", metadata.profile?.userId)
        assertEquals(listOf("18745", ""), metadata.sections.map { it.id })
        assertEquals(listOf("554", ""), metadata.hashtags.map { it.id })
        assertEquals("https://img.example/s.png", metadata.sections.first().imageUrl)
    }
}
