package cn.heyread.app.data.local

import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
          listOf(ArticleLabel("s1", "分区", "https://img.example/s.png"), ArticleLabel("", "无 ID")),
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

  @Test
  fun legacyStringArrayNormalizesNumericMetadataSkipsBadRowsAndDropsRuntimeFields() {
    val legacy =
      JSONObject()
        .put("id", "old")
        .put("source", "HEYBOX")
        .put("title", "  Kept title whitespace  ")
        .put("appId", "18745.9")
        .put("publishedAt", "1.7000000001239e12")
        .put("authorProfile", JSONObject().put("userId", 45))
        .put("detailBlocks", JSONArray().put(JSONObject().put("private", "do-not-save")))
    val stored = JSONArray().put(legacy.toString()).put("{bad").toString()
    val decoded = decodeHistoryArray(stored)
    assertEquals(1, decoded.size)
    assertEquals("  Kept title whitespace  ", decoded.single().title)
    assertEquals(18745, decoded.single().appId)
    assertEquals(1700000000123L, decoded.single().publishedAt)
    assertEquals("", decoded.single().authorProfile?.name)
    val rewritten = encodeHistoryArray(decoded)
    assertEquals(decoded, decodeHistoryArray(rewritten))
    assertFalse(rewritten.contains("do-not-save"))
    assertNull(decodeHistoryStory("""{"source":"HEYBOX","title":"missing id"}"""))

    val largeNumeric =
      JSONObject()
        .put("id", "large")
        .put("source", "HEYBOX")
        .put("title", "Large timestamp")
        .put("appId", 4_294_967_297L)
        .put("publishedAt", 9_007_199_254_740_999L)
    val exact = decodeHistoryArray(JSONArray().put(largeNumeric.toString()).toString()).single()
    assertEquals(1, exact.appId)
    assertEquals(9_007_199_254_740_999L, exact.publishedAt)
  }
}
