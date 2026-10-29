package cn.heyread.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamGameSearchRepositoryTest {
  private val repository = SteamGameSearchRepository()

  @Test
  fun storeSearchUsesOnlyObservedOfficialQueryFields() {
    val url = repository.storeSearchUrl("Hades & 中文")

    assertEquals("https", url.scheme)
    assertEquals("store.steampowered.com", url.host)
    assertEquals("/api/storesearch/", url.encodedPath)
    assertEquals("Hades & 中文", url.queryParameter("term"))
    assertEquals("schinese", url.queryParameter("l"))
    assertEquals("CN", url.queryParameter("cc"))
    assertEquals(3, url.queryParameterNames.size)
  }

  @Test
  fun numericLookupUsesAppDetailsWithExpectedRegionAndNoNameSearch() {
    val url = repository.appDetailsUrl(367520)

    assertEquals("/api/appdetails/", url.encodedPath)
    assertEquals("367520", url.queryParameter("appids"))
    assertEquals("schinese", url.queryParameter("l"))
    assertEquals("CN", url.queryParameter("cc"))
    assertEquals(3, url.queryParameterNames.size)
  }

  @Test
  fun numericInputMustBePositiveIntAndNameIsNotTranslated() {
    assertEquals(367520, repository.appIdForQuery("367520"))
    assertNull(repository.appIdForQuery("空洞骑士"))
    listOf("0", "-1", "999999999999999999999").forEach { invalid ->
      assertThrows(IllegalStateException::class.java) { repository.appIdForQuery(invalid) }
    }
  }

  @Test
  fun boundedReaderAcceptsShortBodyAndRejectsOversizedBody() {
    assertEquals("short body", readBoundedUtf8(Buffer().writeUtf8("short body"), 20))
    assertThrows(IllegalStateException::class.java) {
      readBoundedUtf8(Buffer().writeUtf8("too long"), 3)
    }
  }

  @Test
  fun storeSearchKeepsOnlyDeduplicatedAppRowsAndSafeImages() {
    val games =
      repository.parseStoreSearch(
        """
                    {"total":4,"items":[
                      {"id":1145350,"name":"Hades II","type":"app","tiny_image":"https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1145350/capsule.jpg"},
                      {"id":1,"name":"Bundle","type":"bundle","tiny_image":"https://example.com/bundle.jpg"},
                      {"id":1145350,"name":"Duplicate","type":"app"},
                      {"id":2,"name":"Bad image","type":"app","tiny_image":"http://example.com/x.jpg"}
                    ]}
                """
          .trimIndent()
      )

    assertEquals(2, games.size)
    assertEquals(1145350, games[0].appId)
    assertEquals("Hades II", games[0].name)
    assertEquals(
      "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1145350/capsule.jpg",
      games[0].imageUrl,
    )
    assertEquals(null, games[1].imageUrl)
  }

  @Test
  fun searchUsesInjectedTransportAndSuccessfullyReadsShortBoundedJsonWithoutCookies() =
    runBlocking {
      var requested: okhttp3.Request? = null
      var cookieReads = 0
      val injectedClient =
        OkHttpClient.Builder()
          .cookieJar(
            object : CookieJar {
              override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
                cookieReads++
                return listOf(
                  Cookie.Builder().name("session").value("test").domain(url.host).build()
                )
              }

              override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) = Unit
            }
          )
          .addInterceptor { chain ->
            requested = chain.request()
            Response.Builder()
              .request(chain.request())
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body(
                """{"total":1,"items":[{"id":1145350,"name":"Hades II","type":"app"}]}"""
                  .toResponseBody("application/json".toMediaType())
              )
              .build()
          }
          .build()
      val repository = SteamGameSearchRepository(injectedClient)

      val result = repository.search("Hades")

      assertEquals(1, result.size)
      assertEquals(1145350, result.single().appId)
      assertEquals("Hades II", result.single().name)
      assertEquals("store.steampowered.com", requested?.url?.host)
      assertEquals("Hades", requested?.url?.queryParameter("term"))
      assertNull(requested?.header("Cookie"))
      assertEquals(0, cookieReads)
    }

  @Test
  fun validNonAppOnlyResultsAreAnEmptyMatchButMalformedResponsesFail() {
    assertEquals(
      emptyList<Any>(),
      repository.parseStoreSearch(
        """{"total":1,"items":[{"id":1,"name":"Pack","type":"bundle"}]}"""
      ),
    )
    assertThrows(IllegalStateException::class.java) {
      repository.parseStoreSearch("""{"total":0}""")
    }
    assertThrows(IllegalStateException::class.java) {
      repository.parseStoreSearch("""{"items":[{"id":"bad","name":"Missing","type":"app"}]}""")
    }
    assertThrows(IllegalStateException::class.java) {
      repository.parseStoreSearch("""{"items":[{}]}""")
    }
    assertThrows(IllegalStateException::class.java) {
      repository.parseStoreSearch("""{"items":[{"id":1,"name":null,"type":"app"}]}""")
    }
    assertThrows(IllegalStateException::class.java) {
      repository.parseStoreSearch("""{"items":[null]}""")
    }
  }

  @Test
  fun searchResultsAreCappedAtTwentyValidAppsInsteadOfRejectingLargerResponse() {
    val items = (1..24).joinToString(",") { id -> """{"id":$id,"name":"Game $id","type":"app"}""" }

    val games = repository.parseStoreSearch("""{"total":24,"items":[$items]}""")

    assertEquals(20, games.size)
    assertEquals((1..20).toList(), games.map { it.appId })
  }

  @Test
  fun appDetailsRequiresSuccessAndExactAppId() {
    val game =
      repository.parseAppDetails(
        """{"367520":{"success":true,"data":{"type":"game","steam_appid":367520,"name":"Hollow Knight","header_image":"https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/367520/header.jpg"}}}""",
        367520,
      )
    assertEquals(367520, game?.appId)
    assertEquals("Hollow Knight", game?.name)
    assertEquals(null, repository.parseAppDetails("""{"367520":{"success":false}}""", 367520))
    assertEquals(
      "Soundtrack",
      repository
        .parseAppDetails(
          """{"10":{"success":true,"data":{"type":"soundtrack","steam_appid":10,"name":"Soundtrack"}}}""",
          10,
        )
        ?.name,
    )
    assertThrows(IllegalStateException::class.java) {
      repository.parseAppDetails(
        """{"367520":{"success":true,"data":{"type":"game","steam_appid":42,"name":"Wrong"}}}""",
        367520,
      )
    }
  }
}
