package cn.heyread.app.data.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedUsersProtocolTest {
  @Test
  fun requestsOfficialFollowListForTheCurrentUserWithoutInventedPagination() = runTest {
    val requests = mutableListOf<Request>()
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { request ->
            requests += request
            """{"status":"ok","result":{"follow_list":[],"has_more":0}}"""
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-1", "reader-1")

    val page = repository.loadFollowedUsers("reader-1")

    assertTrue(page.users.isEmpty())
    assertFalse(page.hasMore)
    val request = requests.single()
    val url: HttpUrl = request.url
    assertEquals("GET", request.method)
    assertEquals("https", url.scheme)
    assertEquals(OfficialCookieJar.API_HOST, url.host)
    assertEquals("/bbs/app/profile/following/simple_list", url.encodedPath)
    assertEquals("reader-1", url.queryParameter("userid"))
    assertEquals("web", url.queryParameter("os_type"))
    assertTrue(url.queryParameter("_time")?.isNotBlank() == true)
    assertTrue(url.queryParameter("nonce")?.isNotBlank() == true)
    assertTrue(url.queryParameter("hkey")?.isNotBlank() == true)
    assertFalse(url.queryParameterNames.any { it in setOf("offset", "limit", "lastval") })
  }

  @Test
  fun guestMismatchedIdentityAndMissingPkeyAreRejectedBeforeNetwork() = runTest {
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
      runCatching { repository.loadFollowedUsers("reader-1") }.exceptionOrNull()
        is AccountSessionRequiredException
    )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-1", "reader-1")
    assertTrue(
      runCatching { repository.loadFollowedUsers("other-user") }.exceptionOrNull()
        is AccountSessionChangedException
    )
    repository.clearEphemeralCookies()
    repository.restoreSession("heybox_id=reader-1", "reader-1")
    assertTrue(
      runCatching { repository.loadFollowedUsers("reader-1") }.exceptionOrNull()
        is AccountSessionRequiredException
    )
    assertEquals(0, calls)
  }

  @Test
  fun mapsOnlyKnownIdentityFieldsUsesAvartarBeforeAvatarAndKeepsOfficialOrder() = runTest {
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            """
            {"status":"ok","result":{"has_more":1,"follow_list":[
              {"userid":"u-1","username":"First","avartar":"https://imgheybox.max-c.com/one.jpg","avatar":"https://imgheybox.max-c.com/fallback.jpg","unread":2,"last_event_at":9999999999},
              {"userid":"u-2","display_name":"Second","avartar":"http://imgheybox.max-c.com/insecure.jpg","avatar":"https://imgheybox.max-c.com/two.jpg","unread":0},
              {"userid":"u-3","avatar":"https://imgheybox.max-c.com/three.jpg","unread":-1},
              {"userid":"u-4","username":"Integral decimal","unread":1.0}
            ]}}"""
              .trimIndent()
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-2", "reader-2")

    val page = repository.loadFollowedUsers("reader-2")

    assertEquals(listOf("u-1", "u-2", "u-3", "u-4"), page.users.map { it.userId })
    assertEquals(
      listOf("First", "Second", "", "Integral decimal"),
      page.users.map { it.displayName },
    )
    assertEquals("https://imgheybox.max-c.com/one.jpg", page.users[0].avatarUrl)
    assertEquals("https://imgheybox.max-c.com/two.jpg", page.users[1].avatarUrl)
    assertEquals("https://imgheybox.max-c.com/three.jpg", page.users[2].avatarUrl)
    assertEquals(listOf(2, 0, 0, 1), page.users.map { it.unread })
    assertTrue(page.hasMore)
  }

  @Test
  fun malformedUnreadAndUnknownFieldsDoNotCreateUnreadStateOrGuessAUser() = runTest {
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor {
            """
            {"status":"ok","result":{"has_more":0,"follow_list":[
              {"userid":"u-1","username":"Known","unread":"5","is_unread":1,"last_event_at":1},
              {"username":"Missing identity","unread":4},
              {"userid":"u-3","username":"Bad unread","unread":1.5},
              {"userid":"u-4","username":"Bad avatar","avartar":"javascript:alert(1)","avatar":"//imgheybox.max-c.com/no.jpg"}
            ]}}"""
              .trimIndent()
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-3", "reader-3")

    val page = repository.loadFollowedUsers("reader-3")

    assertEquals(listOf("u-1", "u-3", "u-4"), page.users.map { it.userId })
    assertEquals(listOf(0, 0, 0), page.users.map { it.unread })
    assertEquals(listOf("Known", "Bad unread", "Bad avatar"), page.users.map { it.displayName })
    assertNull(page.users.last().avatarUrl)
    assertFalse(page.hasMore)
  }

  @Test
  fun invalidFollowListSchemaAndAllMalformedRowsFailButEmptyArrayIsValid() = runTest {
    val bodies =
      ArrayDeque(
        listOf(
          """{"status":"ok","result":{"has_more":0}}""",
          """{"status":"ok","result":{"follow_list":{},"has_more":0}}""",
          """{"status":"ok","result":{"follow_list":[null,{},"user",{"name":"Unknown"}],"has_more":0}}""",
          """{"status":"ok","result":{"follow_list":[],"has_more":0}}""",
        )
      )
    val repository =
      AccountRepository(requestExecutor = AccountRequestExecutor { bodies.removeFirst() })
    repository.restoreSession("pkey=synthetic; heybox_id=reader-4", "reader-4")

    val missing = runCatching { repository.loadFollowedUsers("reader-4") }.exceptionOrNull()
    assertEquals("关注用户响应缺少 follow_list", missing?.message)
    val wrongType = runCatching { repository.loadFollowedUsers("reader-4") }.exceptionOrNull()
    assertTrue(wrongType != null)
    val allMalformed = runCatching { repository.loadFollowedUsers("reader-4") }.exceptionOrNull()
    assertTrue(allMalformed != null)
    assertTrue(repository.loadFollowedUsers("reader-4").users.isEmpty())
  }

  @Test
  fun negativeHasMoreIsRejectedAndStringHasMoreDoesNotImplyAnotherPage() = runTest {
    val bodies =
      ArrayDeque(
        listOf(
          """{"status":"ok","result":{"follow_list":[],"has_more":-1}}""",
          """{"status":"ok","result":{"follow_list":[],"has_more":"1"}}""",
          """{"status":"ok","result":{"follow_list":[],"has_more":1.0}}""",
        )
      )
    val repository =
      AccountRepository(requestExecutor = AccountRequestExecutor { bodies.removeFirst() })
    repository.restoreSession("pkey=synthetic; heybox_id=reader-5", "reader-5")

    assertTrue(runCatching { repository.loadFollowedUsers("reader-5") }.isFailure)
    assertTrue(runCatching { repository.loadFollowedUsers("reader-5") }.isFailure)
    assertTrue(repository.loadFollowedUsers("reader-5").hasMore)
  }

  @Test
  fun lateResponseKeepsTheRequestIdentitySnapshotAfterAccountSwitch() = runTest {
    val entered = CompletableDeferred<Request>()
    val release = CompletableDeferred<String>()
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { request ->
            entered.complete(request)
            release.await()
          }
      )
    repository.restoreSession("pkey=first-secret; heybox_id=reader-a", "reader-a")

    val pending = async { repository.loadFollowedUsers("reader-a") }
    val request = entered.await()
    repository.restoreSession("pkey=second-secret; heybox_id=reader-b", "reader-b")
    release.complete("""{"status":"ok","result":{"follow_list":[],"has_more":0}}""")
    pending.await()

    assertEquals("reader-a", request.url.queryParameter("userid"))
    assertEquals("reader-a", request.url.queryParameter("heybox_id"))
  }
}
