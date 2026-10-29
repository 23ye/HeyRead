package cn.heyread.app.data.remote

import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicAuthorCaptchaProtocolTest {
  @Test
  fun validRootIdentifierWinsAndTheLegacyOverloadUsesTheRootValue() {
    assertEquals("123456", resolveCaptchaAppId("123456", "222", EVENTS_PATH))
    assertEquals("0007", resolveCaptchaAppId("0007", "222", EVENTS_PATH))
    assertEquals("123456", resolveCaptchaAppId("123456", EVENTS_PATH))
  }

  @Test
  fun validNestedIdentifiersAcceptNumbersAndFallBackByPathOnlyWhenBothAreInvalid() {
    assertEquals("2061805035", resolveCaptchaAppId(null, 2061805035, EVENTS_PATH))
    assertEquals("2061805035", resolveCaptchaAppId("bad", BigDecimal("2061805035.0"), EVENTS_PATH))
    assertEquals("199251710", resolveCaptchaAppId(null, null, EVENTS_PATH))
    assertEquals(
      "2061805035",
      resolveCaptchaAppId(null, null, "/bbs/app/profile/user/link/list_v2"),
    )
    assertEquals("199251710", resolveCaptchaAppId("bad", false, EVENTS_PATH))
  }

  @Test
  fun invalidIdentifiersAreRejectedWithoutStringifyingOrCoercingValues() {
    val invalidValues =
      listOf(
        null,
        JSONObject.NULL,
        false,
        JSONArray().put(1),
        JSONObject().put("appid", "123"),
        "",
        "0",
        "000",
        "-1",
        "1.0",
        " 123",
        "１２３",
        "1".repeat(21),
        0,
        -1,
        1.5,
        BigDecimal("1.5"),
        BigDecimal("1E+1000000"),
        BigDecimal("1E-1000000"),
        Double.NaN,
        Double.POSITIVE_INFINITY,
        BigInteger.TEN.pow(20),
      )

    invalidValues.forEach { invalid ->
      assertEquals(
        "invalid root=$invalid should fall back",
        "199251710",
        resolveCaptchaAppId(invalid, null, EVENTS_PATH),
      )
    }
    assertEquals("1", resolveCaptchaAppId(null, BigDecimal("1.0"), EVENTS_PATH))
    assertEquals(
      "99999999999999999999",
      resolveCaptchaAppId(null, BigInteger.TEN.pow(20).minus(BigInteger.ONE), EVENTS_PATH),
    )
  }

  @Test
  fun repositoryUsesNestedNumericAppIdAndSendsPublicRequestsWithoutCookie() = runBlocking {
    val requests = mutableListOf<Request>()
    val repository =
      DestinationRepository(
        execute = { request ->
          requests += request
          """{"status":"show_captcha","result":{"appid":2061805035}}"""
        }
      )

    val error =
      runCatching {
          repository.load(authorTarget(), 0, 30, "", null, DestinationFeedType.MOMENTS, false)
        }
        .exceptionOrNull() as CaptchaRequiredException

    assertEquals("2061805035", error.challenge.appId)
    assertEquals(EVENTS_PATH, error.challenge.path)
    assertEquals(
      mapOf("userid" to "author-1", "list_type" to "moment", "dw" to "304", "lastval" to ""),
      error.challenge.parameters,
    )
    val request = requests.single()
    assertEquals("https", request.url.scheme)
    assertEquals("api.xiaoheihe.cn", request.url.host)
    assertEquals(EVENTS_PATH, request.url.encodedPath)
    assertEquals("author-1", request.url.queryParameter("userid"))
    assertEquals("moment", request.url.queryParameter("list_type"))
    assertEquals("304", request.url.queryParameter("dw"))
    assertEquals("", request.url.queryParameter("lastval"))
    assertFalse(request.headers.names().any { it.equals("Cookie", ignoreCase = true) })
  }

  @Test
  fun proofIsSubmittedOnceToOriginalRequestAndRejectedProofRetainsVerificationContext() =
    runBlocking {
      val requests = mutableListOf<Request>()
      val repository =
        DestinationRepository(
          execute = { request ->
            requests += request
            when (requests.size) {
              1 -> """{"status":"show_captcha","result":{"appid":2061805035}}"""
              2 -> """{"status":"failed","msg":"verification rejected"}"""
              3 -> """{"status":"show_captcha","result":{"appid":"2061805036"}}"""
              else ->
                """{"status":"ok","result":{"moments":[{"linkid":"m1","title":"Verified post"}],"lastval":""}}"""
            }
          }
        )
      val target = authorTarget()
      val challenge =
        runCatching { repository.load(target, 0, 30, "", null, DestinationFeedType.MOMENTS, false) }
          .exceptionOrNull() as CaptchaRequiredException
      val firstProof = CaptchaProof("first-ticket", "first-rand")

      val rejected =
        runCatching {
            repository.load(
              target,
              0,
              30,
              "",
              null,
              DestinationFeedType.MOMENTS,
              false,
              captchaChallenge = challenge.challenge,
              captchaProof = firstProof,
            )
          }
          .exceptionOrNull() as CaptchaRequiredException

      assertEquals(challenge.challenge, rejected.challenge)
      assertNotNull(rejected.verificationMessage)
      assertTrue(rejected.verificationMessage.orEmpty().isNotBlank())
      assertFalse(rejected.toString().contains("first-ticket"))
      assertEquals("first-ticket", requests[1].url.queryParameter("ticket"))
      assertEquals("first-rand", requests[1].url.queryParameter("randstr"))
      assertEquals("confirm", requests[1].url.queryParameter("captcha_type"))
      assertEquals("", requests[1].url.queryParameter("lastval"))

      val renewed =
        runCatching {
            repository.load(
              target,
              0,
              30,
              "",
              null,
              DestinationFeedType.MOMENTS,
              false,
              captchaChallenge = rejected.challenge,
              captchaProof = firstProof,
            )
          }
          .exceptionOrNull() as CaptchaRequiredException
      assertEquals("2061805036", renewed.challenge.appId)
      assertNull(requests[2].url.queryParameter("ticket"))
      assertNull(requests[2].url.queryParameter("randstr"))

      val secondProof = CaptchaProof("second-ticket", "second-rand")
      val page =
        repository.load(
          target,
          0,
          30,
          "",
          null,
          DestinationFeedType.MOMENTS,
          false,
          captchaChallenge = renewed.challenge,
          captchaProof = secondProof,
        )
      assertEquals(listOf("m1"), page.stories.map { it.id })
      assertEquals("second-ticket", requests[3].url.queryParameter("ticket"))
      assertEquals("second-rand", requests[3].url.queryParameter("randstr"))
      requests.forEach { request ->
        assertEquals(EVENTS_PATH, request.url.encodedPath)
        assertEquals("author-1", request.url.queryParameter("userid"))
        assertEquals("moment", request.url.queryParameter("list_type"))
        assertEquals("304", request.url.queryParameter("dw"))
        assertEquals("", request.url.queryParameter("lastval"))
        assertFalse(request.headers.names().any { it.equals("Cookie", ignoreCase = true) })
      }
    }

  @Test
  fun failedBusinessStatusWithoutSubmittedProofRemainsOrdinaryFailure() = runBlocking {
    val repository =
      DestinationRepository(execute = { """{"status":"failed","msg":"ordinary failure"}""" })

    val error =
      runCatching {
          repository.load(authorTarget(), 0, 30, "", null, DestinationFeedType.MOMENTS, false)
        }
        .exceptionOrNull()

    assertTrue(error != null)
    assertFalse(error is CaptchaRequiredException)
    assertTrue(error?.message.orEmpty().contains("failed"))
  }

  private fun authorTarget() = DestinationTarget(DestinationType.USER, "author-1", "Author")

  companion object {
    private const val EVENTS_PATH = "/bbs/app/profile/events"
  }
}
