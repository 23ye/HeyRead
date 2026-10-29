package cn.heyread.app.data.account

import cn.heyread.app.data.model.ArticleBlock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NestedArticleContentTest {
  private val repository = AccountRepository()

  @Test
  fun nestedSerializedTextIsDecodedIntoTextGameAndImageWithoutEventPayload() {
    val nested =
        JSONArray()
            .put(JSONObject().put("type", "text").put("text", "  第一行\n  缩进第二行\n\n结尾"))
            .put(JSONObject().put("type", "game_card").put("appid", 238460))
            .put(
                JSONObject()
                    .put("type", "img")
                    .put("url", "https://imgheybox.max-c.com/test/article.jpg"))
            .put(JSONObject().put("type", "event_entry").put("path", "C:/private/local/file"))
    val outer = JSONArray().put(JSONObject().put("type", "text").put("text", nested.toString()))

    val blocks = repository.parseArticleBlocks(outer)

    assertEquals("  第一行\n  缩进第二行\n\n结尾", (blocks[0] as ArticleBlock.Paragraph).text)
    assertEquals(238460, (blocks[1] as ArticleBlock.GameCard).appId)
    assertEquals(
        "https://imgheybox.max-c.com/test/article.jpg", (blocks[2] as ArticleBlock.Image).url)
    assertFalse(blocks.toString().contains("C:/private"))
  }

  @Test
  fun ordinaryJsonLookingTextAndMalformedPayloadRemainLiteralRatherThanBeingUnescaped() {
    val ordinary = "示例：{\"type\":\"text\",\"text\":\"a\\nb\"}"
    assertEquals(listOf(ArticleBlock.Paragraph(ordinary)), repository.decodeTextPayload(ordinary))
    val malformed = "[{\"type\":\"text\","
    assertEquals(listOf(ArticleBlock.Paragraph(malformed)), repository.decodeTextPayload(malformed))
  }

  @Test
  fun contentWrapperAndBoundsAreHandledWithoutRenderingUnknownObjects() {
    val wrapped =
        """{"content":[{"type":"text","text":"可读"},{"type":"event_entry","path":"/private"}]}"""
    assertEquals(listOf(ArticleBlock.Paragraph("可读")), repository.decodeTextPayload(wrapped))
    var tooDeep = "\"UNREACHABLE_TAIL\""
    repeat(12) { tooDeep = JSONObject().put("type", "quote").put("text", tooDeep).toString() }
    val bounded = repository.decodeTextPayload(tooDeep)
    assertFalse(bounded.toString().contains("UNREACHABLE_TAIL"))
    assertFalse(bounded.toString().contains("{\"type\""))

    val overBudget = JSONArray()
    repeat(520) { index ->
      overBudget.put(
          JSONObject().put("type", "text").put("text", if (index == 519) "BUDGET_TAIL" else "node"))
    }
    assertFalse(
        repository.decodeTextPayload(overBudget.toString()).toString().contains("BUDGET_TAIL"))
    val wrapper =
        JSONArray()
            .put(
                JSONObject()
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "wrapped"))))
    assertEquals(listOf(ArticleBlock.Paragraph("wrapped")), repository.parseArticleBlocks(wrapper))
  }

  @Test
  fun htmlBreakPreAndListsRetainTheirDistinctLayoutSemantics() {
    val blocks =
        repository.htmlBlocks(
            "<p>甲<br>乙</p><pre> a\n  b</pre><ul><li>项一<br>续行<ul><li>子项</li></ul><img src='https://imgheybox.max-c.com/list.jpg'>项后</li><li>项二</li></ul>")
    assertEquals(ArticleBlock.Paragraph("甲\n乙"), blocks[0])
    assertEquals(ArticleBlock.Paragraph(" a\n  b"), blocks[1])
    assertEquals(ArticleBlock.ListItem("项一\n续行", 0, "•"), blocks[2])
    assertEquals(ArticleBlock.ListItem("子项", 1, "•"), blocks[3])
    assertTrue(blocks[4] is ArticleBlock.Image)
    assertEquals(ArticleBlock.ListItem("项后", 0, ""), blocks[5])
    assertEquals(ArticleBlock.ListItem("项二", 0, "•"), blocks[6])
    assertEquals(
        ArticleBlock.Paragraph("  缩进"), repository.htmlBlocks("<p>&nbsp;&nbsp;缩进</p>").single())
  }

  @Test
  fun nestedHtmlAndTopLevelEventsAreHandledAndNestedAttachmentsAreDeduplicated() {
    val imageUrl = "https://imgheybox.max-c.com/test/inline.jpg"
    val nested =
        JSONArray()
            .put(JSONObject().put("type", "html").put("text", "<p>A<br>B</p>"))
            .put(JSONObject().put("type", "img").put("url", imageUrl))
    val content =
        JSONArray()
            .put(JSONObject().put("type", "text").put("text", nested.toString()))
            .put(JSONObject().put("type", "img").put("url", imageUrl).put("caption", "cap"))
            .put(JSONObject().put("type", "event_entry").put("text", "C:/must-not-render"))
    val blocks = repository.parseArticleBlocks(content)

    assertEquals(1, blocks.count { it is ArticleBlock.Image })
    assertEquals("cap", (blocks.single { it is ArticleBlock.Image } as ArticleBlock.Image).caption)
    assertEquals(ArticleBlock.Paragraph("A\nB"), blocks.first())
    assertFalse(blocks.toString().contains("C:/must-not-render"))
    val nestedLegacyHtml =
        JSONArray().put(JSONObject().put("type", "text").put("text", "<p>内层甲<br>内层乙</p>"))
    assertEquals(
        listOf(ArticleBlock.Paragraph("内层甲\n内层乙")),
        repository.parseArticleBlocks(nestedLegacyHtml),
    )
  }

  @Test
  fun legacyInlineHtmlInTextUsesSafeHtmlParserWhileCodeAndLessThanRemainText() {
    val html =
        "<strong>标题</strong> <a href=\"https://example.test/watch\">视频链接</a> <video src=\"https://cdn.test/v.mp4\"></video>"
    val parsed =
        repository.parseArticleBlocks(
            JSONArray().put(JSONObject().put("type", "text").put("text", html)))
    assertTrue(parsed.any { it is ArticleBlock.Paragraph && it.text.contains("标题") })
    assertFalse(parsed.toString().contains("<strong>"))
    assertEquals(
        listOf(ArticleBlock.Paragraph("示例：<3，代码 `<strong>`")),
        repository.decodeTextPayload("示例：<3，代码 `<strong>`"),
    )
    assertEquals(
        listOf(ArticleBlock.Paragraph("正文示例 <p>不转换</p>\n下一行")),
        repository.decodeTextPayload("正文示例 <p>不转换</p>\n下一行"),
    )
  }

  @Test
  fun quotePayloadUsesBoundedStructureDecoderAndPreservesHtmlLineBreaks() {
    val content =
        JSONArray()
            .put(JSONObject().put("type", "blockquote").put("text", "引文第一行\n引文第二行"))
            .put(JSONObject().put("type", "quote").put("text", "<p>HTML甲<br>HTML乙</p>"))
    val quotes = repository.parseArticleBlocks(content).filterIsInstance<ArticleBlock.Quote>()
    assertEquals(2, quotes.size)
    assertEquals(
        listOf(ArticleBlock.Paragraph("引文第一行\n引文第二行")),
        quotes[0].blocks,
    )
    assertEquals(listOf(ArticleBlock.Paragraph("HTML甲\nHTML乙")), quotes[1].blocks)
  }
}
