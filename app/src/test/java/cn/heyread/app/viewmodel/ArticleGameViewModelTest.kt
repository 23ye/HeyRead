package cn.heyread.app.viewmodel

import cn.heyread.app.data.remote.ArticleGameRepository
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleGameViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun sameArticleCanGainIdsLaterAndReturnedInfosSurviveInactiveRoute() =
      runTest(dispatcher) {
        var calls = 0
        val vm =
            ArticleGameViewModel(
                ArticleGameRepository(
                    execute = { request ->
                      calls++
                      infosResponse(request)
                    },
                    ioDispatcher = dispatcher,
                ))
        vm.bindArticle("HEYBOX:1", emptyList())
        vm.bindArticle("HEYBOX:1", listOf(2567870))
        advanceUntilIdle()
        assertTrue(vm.state.value.infos.containsKey(2567870))
        val loaded = vm.state.value.infos
        vm.setActive(false)
        vm.setActive(true)
        advanceUntilIdle()
        assertEquals(loaded, vm.state.value.infos)
        assertEquals(1, calls)
      }

  @Test
  fun failedTopicCanRetryAndCaptchaBlocksChangingThePendingTarget() =
      runTest(dispatcher) {
        val requests = mutableListOf<Request>()
        var menuCount = 0
        val vm =
            ArticleGameViewModel(
                ArticleGameRepository(
                    execute = { request ->
                      requests += request
                      if (request.url.encodedPath.endsWith("/menu")) {
                        menuCount++
                        when (menuCount) {
                          1 -> """{"status":"error"}"""
                          2 -> """{"status":"show_captcha","appid":2061805035}"""
                          else ->
                              """{"status":"ok","result":{"topic":{"topic_id":570904,"name":"Topic"}}}"""
                        }
                      } else """{"status":"ok","result":{"base_infos":[]}}"""
                    },
                    ioDispatcher = dispatcher,
                ))
        vm.openGameTopic(2567870)
        advanceUntilIdle()
        assertEquals(2567870, vm.state.value.failedTopicAppId)
        vm.retryTopic()
        advanceUntilIdle()
        assertTrue(vm.state.value.captchaRequired)
        vm.openGameTopic(203160)
        assertEquals(2567870, vm.state.value.resolvingAppId)
        vm.openCaptchaDialog()
        val challenge = vm.state.value.captchaChallengeId!!
        val attempt = vm.state.value.captchaAttemptId!!
        vm.onCaptchaCallback(
            challenge,
            attempt,
            CaptchaCallbackResult.Completed(CaptchaProof("t", "r")),
        )
        advanceUntilIdle()
        assertEquals("570904", vm.state.value.topicToOpen?.id)
        assertEquals(2567870, requests.last().url.queryParameter("appid")?.toInt())
        assertTrue(requests.last().url.queryParameter("ticket") == "t")
        assertFalse(vm.state.value.captchaVerifying)
      }

  @Test
  fun successfulInfoRequestIsNotRestartedByRepeatedBind() =
      runTest(dispatcher) {
        var calls = 0
        val vm =
            ArticleGameViewModel(
                ArticleGameRepository(
                    execute = { request ->
                      calls++
                      infosResponse(request)
                    },
                    ioDispatcher = dispatcher,
                ))
        vm.bindArticle("HEYBOX:2", listOf(4773180))
        advanceUntilIdle()
        vm.bindArticle("HEYBOX:2", listOf(4773180))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(vm.state.value.infoError)
      }

  @Test
  fun successfulEmptyInfoResponseIsTerminalUntilExplicitRetry() =
      runTest(dispatcher) {
        var calls = 0
        val vm =
            ArticleGameViewModel(
                ArticleGameRepository(
                    execute = {
                      calls++
                      """{"status":"ok","result":{"base_infos":[]}}"""
                    },
                    ioDispatcher = dispatcher,
                ))

        vm.bindArticle("HEYBOX:empty-game", listOf(238460))
        advanceUntilIdle()
        assertEquals(setOf(238460), vm.state.value.completedInfoIds)
        assertTrue(vm.state.value.infos.isEmpty())
        assertFalse(vm.state.value.loadingInfos)

        vm.bindArticle("HEYBOX:empty-game", listOf(238460))
        vm.setActive(false)
        vm.setActive(true)
        advanceUntilIdle()
        assertEquals("an empty successful response is not retried on rebind or resume", 1, calls)

        vm.retryInfos()
        advanceUntilIdle()
        assertEquals("the unavailable card retains an explicit retry path", 2, calls)
      }

  @Test
  fun partialSuccessfulInfoResponseMarksOmittedIdsCompleteWithoutRepeatingRequest() =
      runTest(dispatcher) {
        var calls = 0
        val vm =
            ArticleGameViewModel(
                ArticleGameRepository(
                    execute = {
                      calls++
                      """{"status":"ok","result":{"base_infos":[{"steam_appid":570,"name":"Game 570"}]}}"""
                    },
                    ioDispatcher = dispatcher,
                ))

        vm.bindArticle("HEYBOX:partial-game", listOf(570, 730))
        advanceUntilIdle()
        assertTrue(vm.state.value.infos.containsKey(570))
        assertEquals(setOf(570, 730), vm.state.value.completedInfoIds)

        vm.bindArticle("HEYBOX:partial-game", listOf(570, 730))
        advanceUntilIdle()
        assertEquals("an omitted app ID is terminal until the user retries", 1, calls)
      }

  @Test
  fun captchaOnSecondBatchKeepsFirstBatchAndRetriesOnlyTheFailedBatch() =
      runTest(dispatcher) {
        val requests = mutableListOf<Request>()
        val vm =
            ArticleGameViewModel(
                ArticleGameRepository(
                    execute = { request ->
                      requests += request
                      if (requests.size == 2) {
                        """{"status":"show_captcha","appid":2061805035}"""
                      } else {
                        infosResponse(request)
                      }
                    },
                    ioDispatcher = dispatcher,
                ))
        val appIds = (1..25).map { 5000000 + it }

        vm.bindArticle("HEYBOX:25", appIds)
        advanceUntilIdle()

        assertEquals(24, vm.state.value.infos.size)
        assertTrue(vm.state.value.captchaRequired)
        assertEquals("5000025", requests.last().url.queryParameter("appids"))
        val firstBatchIds = requests.first().url.queryParameter("appids").orEmpty().split(',')
        assertEquals(24, firstBatchIds.size)

        vm.openCaptchaDialog()
        vm.onCaptchaCallback(
            vm.state.value.captchaChallengeId!!,
            vm.state.value.captchaAttemptId!!,
            CaptchaCallbackResult.Completed(CaptchaProof("ticket", "rand")),
        )
        advanceUntilIdle()

        assertEquals(3, requests.size)
        assertEquals("5000025", requests.last().url.queryParameter("appids"))
        assertEquals("ticket", requests.last().url.queryParameter("ticket"))
        assertEquals(25, vm.state.value.infos.size)
        assertFalse(vm.state.value.captchaRequired)
      }

  private fun infosResponse(request: Request): String {
    val rows = request.url.queryParameter("appids").orEmpty().split(',').filter(String::isNotBlank)
    return org.json
        .JSONObject()
        .put("status", "ok")
        .put(
            "result",
            org.json
                .JSONObject()
                .put(
                    "base_infos",
                    org.json.JSONArray(
                        rows.map {
                          org.json
                              .JSONObject()
                              .put("steam_appid", it.toInt())
                              .put("name", "Game $it")
                        }),
                ),
        )
        .toString()
  }
}
