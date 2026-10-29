package cn.heyread.app.data.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedDynamicFeedParserTest {
  private val repository =
    StoryRepository(
      { "https://api.xiaoheihe.cn" },
      File(System.getProperty("java.io.tmpdir"), "heyread-recommended-dynamic-test"),
    )

  @Test
  fun requestUsesObservedPublicFeedParametersWithoutInventingALimit() {
    assertEquals(
      linkedMapOf(
        "pull" to "0",
        "offset" to "30",
        "dw" to "604",
        "os_type" to "web",
        "app" to "heybox",
        "x_client_type" to "web",
        "x_os_type" to "Mac",
        "x_app" to "heybox",
        "version" to "999.0.4",
      ),
      repository.recommendedDynamicFeedParameters(offset = 30, displayWidth = 604),
    )
  }

  @Test
  fun excludesUnsupportedTypeAndKeepsNonEmptyResultEligibleForPaging() {
    val page =
      repository.parseRecommendedDynamicFeedResponse(
        """{"status":"ok","result":{"links":[
          {"linkid":"moment","content_type":44,"title":"动态帖子","description":"内容","user":{"username":"作者"}},
          {"linkid":"unsupported","content_type":18,"title":"其他内容","description":"不应出现"}
        ]}}"""
      )

    assertEquals(listOf("moment"), page.items.map { it.id })
    assertEquals("动态帖子", page.items.single().title)
    assertTrue(page.hasMore)
  }

  @Test
  fun rejectsUnknownResponseAndDoesNotTurnMalformedLinksIntoEmptySuccess() {
    val statusError =
      runCatching {
          repository.parseRecommendedDynamicFeedResponse(
            """{"status":"error","result":{"links":[]}}"""
          )
        }
        .exceptionOrNull()
    val shapeError =
      runCatching {
          repository.parseRecommendedDynamicFeedResponse(
            """{"status":"ok","result":{"links":{}}}"""
          )
        }
        .exceptionOrNull()

    assertEquals("小黑盒推荐动态暂不可用，请稍后重试。", statusError?.message)
    assertEquals("推荐动态响应缺少 links", shapeError?.message)
  }
}
