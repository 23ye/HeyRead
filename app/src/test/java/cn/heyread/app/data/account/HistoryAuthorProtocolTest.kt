package cn.heyread.app.data.account

import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAuthorProtocolTest {
  @Test
  fun loadsAuthorInfoWithOneSignedLinkIdGetAndMapsOfficialProfileFields() = runTest {
    val requests = mutableListOf<Request>()
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { request ->
            requests += request
            """
            {"status":"ok","result":{"author_info":{
              "username":"真实作者","userid":"author-7",
              "avartar":"https://cdn.example/avatar.gif?sig=keep",
              "avatar":"https://cdn.example/lower-priority.jpg",
              "ip_location":"上海"
            }}}
            """.trimIndent()
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-7", "reader-7")

    val profile = repository.loadHistoryAuthor(story("post-7"), "reader-7")

    assertEquals("真实作者", profile.name)
    assertEquals("author-7", profile.userId)
    assertEquals("https://cdn.example/avatar.gif?sig=keep", profile.avatarUrl)
    assertEquals("上海", profile.location)
    assertEquals(1, requests.size)
    val request = requests.single()
    val url: HttpUrl = request.url
    assertEquals("GET", request.method)
    assertEquals("https", url.scheme)
    assertEquals(OfficialCookieJar.API_HOST, url.host)
    assertEquals("/bbs/app/link/author_info", url.encodedPath)
    assertEquals("post-7", url.queryParameter("link_id"))
    assertTrue(
      url.queryParameter("userid") == "reader-7" || url.queryParameter("heybox_id") == "reader-7"
    )
    assertFalse(url.queryParameterNames.any { it in setOf("user_id", "comment_id", "type") })
    assertTrue(url.queryParameter("hkey")?.isNotBlank() == true)
  }

  @Test
  fun usesAvatarAliasAndRejectsMalformedOrMissingAuthorInfo() = runTest {
    val responses =
      ArrayDeque(
        listOf(
          """{"status":"ok","result":{"author_info":{"username":"作者","avartar":"http://cdn.example/bad.jpg","avatar":"https://cdn.example/avatar.jpg"}}}""",
          """{"status":"ok","result":{"author_info":{"username":42,"userid":"author"}}}""",
          """{"status":"ok","result":{"author_info":{"username":"   "}}}""",
          """{"status":"ok","result":{}}""",
        )
      )
    val repository =
      AccountRepository(requestExecutor = AccountRequestExecutor { responses.removeFirst() })
    repository.restoreSession("pkey=synthetic; heybox_id=reader-8", "reader-8")

    val profile = repository.loadHistoryAuthor(story("post-8"), "reader-8")
    assertEquals("https://cdn.example/avatar.jpg", profile.avatarUrl)
    assertNull(profile.userId)
    repeat(3) {
      assertTrue(runCatching { repository.loadHistoryAuthor(story("post-8"), "reader-8") }.isFailure)
    }
  }

  @Test
  fun rejectsMissingMismatchedOrNonHeyboxSessionBeforeRequest() = runTest {
    var calls = 0
    val repository =
      AccountRepository(requestExecutor = AccountRequestExecutor { calls++; "{}" })
    assertTrue(
      runCatching { repository.loadHistoryAuthor(story("post"), "reader") }.exceptionOrNull()
        is AccountSessionRequiredException
    )
    repository.restoreSession("pkey=synthetic; heybox_id=reader", "reader")
    assertTrue(
      runCatching { repository.loadHistoryAuthor(story("post"), "other") }.exceptionOrNull()
        is AccountSessionChangedException
    )
    assertTrue(
      runCatching { repository.loadHistoryAuthor(story("post").copy(source = Source.STEAM), "reader") }
        .isFailure
    )
    repository.clearEphemeralCookies()
    repository.restoreSession("heybox_id=reader", "reader")
    assertTrue(runCatching { repository.loadHistoryAuthor(story("post"), "reader") }.isFailure)
    assertEquals(0, calls)
  }

  @Test
  fun cancellationFromCallerPropagatesToAuthorRequest() = runTest {
    val started = CompletableDeferred<Unit>()
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            started.complete(Unit)
            CompletableDeferred<String>().await()
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-9", "reader-9")
    val request = async { repository.loadHistoryAuthor(story("post-9"), "reader-9") }
    started.await()

    request.cancelAndJoin()

    assertTrue(request.isCancelled)
  }

  private fun story(id: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title = "合成历史文章",
      excerpt = "",
      body = "",
      author = "",
      date = "",
    )
}
