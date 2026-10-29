package cn.heyread.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.remote.ArticleGameRepository
import cn.heyread.app.ui.motion.StaggeredCard
import cn.heyread.app.ui.motion.rememberFirstVisibleBatch
import cn.heyread.app.ui.screens.DetailInitialContentGate
import cn.heyread.app.ui.screens.rememberDetailLastReadyScope
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.ArticleGameViewModel
import cn.heyread.app.viewmodel.DetailRequestPhase
import cn.heyread.app.viewmodel.isInitialDetailReady
import cn.heyread.app.viewmodel.shouldShowInitialDetailSkeleton
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailFirstReadyGateTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun slowPublicBodyAndGameDataShareOneRevealAndRefreshDoesNotReplay() {
    val releaseGameInfo = CompletableDeferred<Unit>()
    val requests = AtomicInteger()
    val games =
        ArticleGameViewModel(
            ArticleGameRepository(
                execute = { _: Request ->
                  requests.incrementAndGet()
                  releaseGameInfo.await()
                  """{"status":"ok","result":{"base_infos":[]}}"""
                }))
    games.bindArticle("HEYBOX:ready-gate", listOf(238460))
    compose.mainClock.autoAdvance = false
    val publicPhase = mutableStateOf(DetailRequestPhase.LOADING)
    val hasPublicBody = mutableStateOf(false)
    val updatedTitle = mutableStateOf("正文标题")
    val animatedKeys = mutableStateListOf<String>()
    compose.setContent {
      val game by games.state.collectAsState()
      val ready =
          isInitialDetailReady(
              source = Source.HEYBOX,
              sessionRestoring = false,
              hasAccountSession = false,
              accountAttempted = false,
              accountLoading = false,
              accountHasDisplayableBody = false,
              publicPhase = publicPhase.value,
              publicAttemptedForEntry = true,
              hasSuccessfulPublicBody = hasPublicBody.value,
              gameIds = listOf(238460),
              gameStateBound = game.articleKey == "HEYBOX:ready-gate",
              gameLoading = game.loadingInfos,
              completedGameIds = game.infos.keys + game.completedInfoIds,
              gameFailedOrLimited = game.infoError != null || game.captchaRequired,
          )
      val lastReadyScope =
          rememberDetailLastReadyScope("detail-entry-ready-gate", ready, "visitor:0")
      val showSkeleton =
          shouldShowInitialDetailSkeleton(
              initialContentReady = ready,
              lastReadyScope = lastReadyScope,
              currentScope = "visitor:0",
              requiredRequestNotStarted = false,
          )
      val listState = rememberLazyListState()
      val headingKey = "detail-heading:HEYBOX:ready-gate"
      val bodyKey = "detail-block:HEYBOX:ready-gate:0:Paragraph"
      val candidates = if (ready) setOf(headingKey, bodyKey) else emptySet()
      val batch = rememberFirstVisibleBatch("detail-entry-ready-gate", listState, candidates)
      HeyReadTheme("light", motionEnabled = true) {
        Surface(Modifier.fillMaxSize()) {
          LazyColumn(state = listState) {
            item(key = if (showSkeleton) "detail-initial-skeleton" else headingKey) {
              DetailInitialContentGate(showSkeleton, motion = true, isCurrentPage = true) {
                StaggeredCard(headingKey, batch, animatedKeys, enabled = true) {
                  Text(updatedTitle.value, Modifier.testTag("ready-detail-heading"))
                }
              }
            }
            if (!showSkeleton) {
              item(key = bodyKey) {
                StaggeredCard(bodyKey, batch, animatedKeys, enabled = true) {
                  Text("首段正文", Modifier.testTag("ready-detail-body"))
                }
              }
            }
          }
        }
      }
    }

    compose.waitForIdle()
    compose.onNodeWithTag("detail-initial-skeleton").assertExists()
    compose.onNodeWithTag("ready-detail-heading").assertDoesNotExist()

    compose.runOnIdle {
      publicPhase.value = DetailRequestPhase.READY
      hasPublicBody.value = true
    }
    compose.waitForIdle()
    compose.onNodeWithTag("detail-initial-skeleton").assertExists()
    compose.onNodeWithTag("ready-detail-heading").assertDoesNotExist()

    releaseGameInfo.complete(Unit)
    compose.waitUntil(5_000) {
      !games.state.value.loadingInfos && games.state.value.completedInfoIds.contains(238460)
    }
    compose.mainClock.advanceTimeBy(90)
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    compose.onNodeWithTag("detail-initial-skeleton").assertDoesNotExist()
    compose.onNodeWithText("正文标题").assertExists()
    assertTrue(
        "successful empty metadata is terminal rather than fabricated",
        games.state.value.infos.isEmpty())
    val initialRevealFrame = compose.onRoot().captureToImage()

    compose.mainClock.advanceTimeBy(500)
    compose.mainClock.advanceTimeByFrame()
    compose.waitUntil(5_000) { animatedKeys.containsAll(listOf(headingKey(), bodyKey())) }
    compose.waitForIdle()
    val settledFrame = compose.onRoot().captureToImage()
    assertTrue(
        "the real heading/body StaggeredCard batch animates into place",
        !samePixels(initialRevealFrame, settledFrame))

    compose.runOnIdle {
      publicPhase.value = DetailRequestPhase.LOADING
      updatedTitle.value = "后续更新标题"
    }
    // runOnIdle mutates state without advancing the test clock. Let the new
    // semantics tree and pixels reach a rendered frame before checking them.
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    compose.onNodeWithTag("detail-initial-skeleton").assertDoesNotExist()
    compose.onNodeWithText("后续更新标题").assertExists()
    val refreshedFrame = compose.onRoot().captureToImage()
    compose.mainClock.advanceTimeBy(500)
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    assertTrue(
        "same-entry refresh/update does not replay its initial reveal",
        samePixels(refreshedFrame, compose.onRoot().captureToImage()))

    games.bindArticle("HEYBOX:ready-gate", listOf(238460))
    compose.waitForIdle()
    assertEquals("completed missing metadata is not fetched repeatedly", 1, requests.get())
  }

  private fun headingKey() = "detail-heading:HEYBOX:ready-gate"

  private fun bodyKey() = "detail-block:HEYBOX:ready-gate:0:Paragraph"

  private fun samePixels(first: ImageBitmap, second: ImageBitmap): Boolean {
    val a = first.toPixelMap()
    val b = second.toPixelMap()
    if (a.width != b.width || a.height != b.height) return false
    for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) return false
    return true
  }
}
