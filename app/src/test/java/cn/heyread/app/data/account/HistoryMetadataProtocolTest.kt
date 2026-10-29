package cn.heyread.app.data.account

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMetadataProtocolTest {
  @Test
  fun parsesHistoryContentMetadataWithoutMixingOuterReaderFieldsOrExtraRequests() = runTest {
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
                "has_more":true,
                "history_visit":[
                  {
                    "id":"history-record-1",
                    "userid":"reader-id",
                    "content":{
                      "linkid":"post-user",
                      "title":"User metadata wins",
                      "username":42,
                      "user":{
                        "username":{},
                        "display_name":"Display name",
                        "userid":"author-user",
                        "avatar":"https://cdn.example/avatar.jpg?sig=keep"
                      },
                      "author":{"nickname":"Fallback author","avatar":"https://cdn.example/fallback.jpg"},
                      "cover":"https://cdn.example/cover.jpg?token=cover",
                      "img":"https://cdn.example/img.jpg",
                      "imgs":["https://cdn.example/array.jpg"]
                    }
                  },
                  {
                    "id":"history-record-2",
                    "userid":"reader-id",
                    "content":{
                      "linkid":"post-author",
                      "title":"Author fallback",
                      "userid":"inner-unrelated-id",
                      "username":false,
                      "user":{"username":[],"display_name":null,"userid":"wrong-person","avatar":"https://cdn.example/wrong-user-avatar.jpg"},
                      "author":{"nickname":"Author fallback","avatar":"https://cdn.example/author.jpg?sig=original"},
                      "cover":{"bad":"type"},
                      "img":9,
                      "image":"http://cdn.example/not-secure.jpg",
                      "imgs":[{},"http://cdn.example/not-secure.jpg",{"url":"http://cdn.example/not-secure.jpg","src":"https://cdn.example/array-fallback.jpg?x=1"}]
                    }
                  },
                  {
                    "id":"history-record-3",
                    "userid":"reader-id",
                    "content":{
                      "linkid":"post-array",
                      "title":"Existing user and images",
                      "user":{"username":"Existing user","userid":"user-3","avatar":"https://cdn.example/user-3.jpg"},
                      "imgs":[{"url":null,"src":"https://cdn.example/existing-array.jpg?keep=1"}]
                    }
                  },
                  {
                    "id":"history-record-4",
                    "userid":"reader-id",
                    "content":{
                      "linkid":"post-img",
                      "title":"Official img field",
                      "cover":{"bad":"type"},
                      "img":"https://cdn.example/official-img.jpg?keep=img",
                      "image":"https://cdn.example/low-priority-image.jpg"
                    }
                  },
                  {
                    "id":"history-record-5",
                    "userid":"reader-id",
                    "content":{
                      "linkid":"post-image",
                      "title":"Official image alias",
                      "image":"https://cdn.example/official-image.jpg?keep=image"
                    }
                  },
                  {
                    "id":"history-record-6",
                    "userid":"reader-id",
                    "content":{
                      "linkid":"post-poster",
                      "title":"Valid alternate user identity",
                      "user":{"username":" null ","userid":"wrong-user"},
                      "poster":{"username":"Poster author","userid":"poster-id","avatar":"https://cdn.example/poster.jpg"},
                      "author":{"nickname":"Lower priority author","avatar":"https://cdn.example/lower-priority.jpg"}
                    }
                  },
                  {
                    "id":"history-record-duplicate",
                    "userid":"reader-id",
                    "content":{"linkid":"post-user","title":"Duplicate later row"}
                  }
                ]
              }
            }
            """
              .trimIndent()
          }
      )

    val page = repository.loadCloudHistory(page = 2)

    assertEquals(
      listOf("post-user", "post-author", "post-array", "post-img", "post-image", "post-poster"),
      page.stories.map { it.id },
    )
    assertTrue(page.hasMore)
    val userStory = page.stories[0]
    assertEquals("User metadata wins", userStory.title)
    assertEquals("Display name", userStory.author)
    assertEquals("author-user", userStory.authorProfile?.userId)
    assertEquals("https://cdn.example/avatar.jpg?sig=keep", userStory.authorProfile?.avatarUrl)
    assertEquals("https://cdn.example/cover.jpg?token=cover", userStory.image)

    val authorFallbackStory = page.stories[1]
    assertEquals("Author fallback", authorFallbackStory.author)
    assertNull(authorFallbackStory.authorProfile?.userId)
    assertEquals(
      "https://cdn.example/author.jpg?sig=original",
      authorFallbackStory.authorProfile?.avatarUrl,
    )
    assertEquals("https://cdn.example/array-fallback.jpg?x=1", authorFallbackStory.image)

    val existingStory = page.stories[2]
    assertEquals("Existing user", existingStory.author)
    assertEquals("user-3", existingStory.authorProfile?.userId)
    assertEquals("https://cdn.example/existing-array.jpg?keep=1", existingStory.image)
    assertEquals("https://cdn.example/official-img.jpg?keep=img", page.stories[3].image)
    assertEquals("https://cdn.example/official-image.jpg?keep=image", page.stories[4].image)
    assertEquals("Poster author", page.stories[5].author)
    assertEquals("poster-id", page.stories[5].authorProfile?.userId)
    assertEquals("https://cdn.example/poster.jpg", page.stories[5].authorProfile?.avatarUrl)

    assertEquals(1, requests.size)
    val request = requests.single()
    val url: HttpUrl = request.url
    assertEquals("GET", request.method)
    assertEquals("https", url.scheme)
    assertEquals(OfficialCookieJar.API_HOST, url.host)
    assertEquals("/bbs/app/profile/history/visit", url.encodedPath)
    assertEquals("link", url.queryParameter("type"))
    assertEquals("40", url.queryParameter("offset"))
    assertEquals("20", url.queryParameter("limit"))
    assertFalse(url.queryParameterNames.contains("id"))
  }
}
