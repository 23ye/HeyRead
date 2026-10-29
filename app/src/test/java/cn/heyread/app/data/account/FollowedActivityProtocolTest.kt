package cn.heyread.app.data.account

import cn.heyread.app.data.model.Source
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedActivityProtocolTest {
  @Test
  fun readsOnlyMomentsAndPreservesOfficialOffsetAndLastValueParameters() = runTest {
    val requests = mutableListOf<Request>()
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { request ->
            requests += request
            """
            {
              "status":"ok",
              "result":{
                "lastval":"next/cursor+1",
                "moments":[
                  {
                    "linkid":194887646,
                    "title":"关注的真实帖子",
                    "description":"真实帖子摘要",
                    "share_url":"https://www.xiaoheihe.cn/app/bbs/link/194887646",
                    "create_at":1792844264,
                    "create_str":"刚刚",
                    "h_src":"verified-hsrc",
                    "imgs":[{"url":"https://imgheybox1.max-c.com/post.jpg"}],
                    "user":{"userid":"author-1","username":"真实作者","avatar":"https://imgheybox.max-c.com/avatar.jpg"},
                    "topics":[{"topic_id":123,"name":"分区","pic_url":"https://imgheybox.max-c.com/topic.png"}]
                  }
                ]
              }
            }
            """
              .trimIndent()
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-1", "reader-1")

    val first = repository.loadFollowedActivity("reader-1", offset = 0, lastValue = null)
    assertEquals(1, first.rawCount)
    assertEquals("next/cursor+1", first.lastValue)
    assertEquals(listOf("194887646"), first.stories.map { it.id })
    assertEquals(Source.HEYBOX, first.stories.single().source)
    assertEquals("真实作者", first.stories.single().author)
    assertEquals("author-1", first.stories.single().authorProfile?.userId)
    assertEquals("分区", first.stories.single().sections.single().name)

    val second =
      repository.loadFollowedActivity("reader-1", offset = 30, lastValue = first.lastValue)
    assertEquals(1, second.rawCount)
    assertEquals(2, requests.size)
    requests.forEachIndexed { index, request ->
      val url: HttpUrl = request.url
      assertEquals("GET", request.method)
      assertEquals("https", url.scheme)
      assertEquals(OfficialCookieJar.API_HOST, url.host)
      assertEquals("/bbs/app/profile/subscribed/events", url.encodedPath)
      assertEquals(if (index == 0) "0" else "30", url.queryParameter("offset"))
      assertEquals("30", url.queryParameter("limit"))
      assertFalse(url.queryParameterNames.contains("lastval") && index == 0)
      if (index == 1) assertEquals("next/cursor+1", url.queryParameter("lastval"))
      assertEquals("reader-1", url.queryParameter("userid"))
    }
  }

  @Test
  fun nonEmptyLoginCardRejectsGuestRecommendationsWithoutClearingSession() = runTest {
    var calls = 0
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            calls++
            """
            {"status":"ok","result":{"moments":[{"linkid":"guest-post","title":"推荐"}],"lastval":"","login_card":{"img":"https://img.example/login.png"}}}
            """
              .trimIndent()
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-2", "reader-2")

    val error =
      runCatching { repository.loadFollowedActivity("reader-2", offset = 0, lastValue = null) }
        .exceptionOrNull()
    assertTrue(error is AccountFollowingRestrictedException)
    assertFalse(error is AccountSessionExpiredException)
    assertTrue(
      runCatching { repository.loadFollowedActivity("reader-2", offset = 0, lastValue = null) }
        .exceptionOrNull() is AccountFollowingRestrictedException
    )
    assertEquals(2, calls)
    assertTrue(
      runCatching { repository.loadFollowedActivity("other-user", offset = 0, lastValue = null) }
        .exceptionOrNull() is AccountSessionChangedException
    )
  }

  @Test
  fun missingMomentsArrayIsAnInvalidResponseRatherThanAnEmptyPage() = runTest {
    val repository =
      AccountRepository(
        requestExecutor = AccountRequestExecutor { """{"status":"ok","result":{"lastval":""}}""" }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-4", "reader-4")

    val error =
      runCatching { repository.loadFollowedActivity("reader-4", offset = 0, lastValue = null) }
        .exceptionOrNull()
    assertEquals("关注动态响应缺少 moments", error?.message)
  }

  @Test
  fun nonArrayMomentsIsAnInvalidResponseRatherThanAnEmptyPage() = runTest {
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            """{"status":"ok","result":{"moments":{"linkid":"not-an-array"}}}"""
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-5", "reader-5")

    val error =
      runCatching { repository.loadFollowedActivity("reader-5", offset = 0, lastValue = null) }
        .exceptionOrNull()
    assertEquals("关注动态响应缺少 moments", error?.message)
  }

  @Test
  fun emptyMomentsArrayIsAValidEmptyPage() = runTest {
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { """{"status":"ok","result":{"moments":[],"lastval":""}}""" }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-6", "reader-6")

    val page = repository.loadFollowedActivity("reader-6", offset = 60, lastValue = "")

    assertEquals(0, page.rawCount)
    assertTrue(page.stories.isEmpty())
    assertEquals("", page.lastValue)
  }

  @Test
  fun malformedLoginCardTypeIsNotAcceptedAsAnUnauthenticatedSuccess() = runTest {
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            """{"status":"ok","result":{"moments":[],"login_card":"unexpected"}}"""
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-7", "reader-7")

    val error =
      runCatching { repository.loadFollowedActivity("reader-7", offset = 0, lastValue = null) }
        .exceptionOrNull()

    assertEquals("关注动态响应 login_card 格式无效", error?.message)
  }

  @Test
  fun guestAndStaleAccountRequestsAreRejectedBeforeNetwork() = runTest {
    var calls = 0
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            calls++
            "{}"
          }
      )

    assertTrue(
      runCatching { repository.loadFollowedActivity("reader-3", offset = 0, lastValue = null) }
        .exceptionOrNull() is AccountSessionRequiredException
    )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-3", "reader-3")
    assertTrue(
      runCatching { repository.loadFollowedActivity("old-user", offset = 0, lastValue = null) }
        .exceptionOrNull() is AccountSessionChangedException
    )
    assertEquals(0, calls)
  }
}
