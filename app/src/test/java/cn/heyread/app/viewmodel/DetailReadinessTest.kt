package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.articleGameIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailReadinessTest {
  @Test
  fun visitorWaitsForExplicitPublicTerminalState() {
    assertFalse(ready(publicPhase = DetailRequestPhase.IDLE))
    assertFalse(ready(publicPhase = DetailRequestPhase.LOADING))
    assertTrue(ready(publicPhase = DetailRequestPhase.READY))
    assertFalse(ready(publicPhase = DetailRequestPhase.FAILED))
    assertFalse(ready(publicPhase = DetailRequestPhase.LIMITED))
    assertTrue(ready(publicPhase = DetailRequestPhase.FAILED, publicAttemptedForEntry = true))
    assertTrue(ready(publicPhase = DetailRequestPhase.LIMITED, publicAttemptedForEntry = true))
  }

  @Test
  fun restoredAccountMustFinishBeforePublicFallbackCanBecomeReady() {
    assertFalse(ready(sessionRestoring = true, publicPhase = DetailRequestPhase.READY))
    assertFalse(ready(hasAccountSession = true, publicPhase = DetailRequestPhase.READY))
    assertFalse(
        ready(
            hasAccountSession = true,
            accountAttempted = true,
            accountLoading = true,
            publicPhase = DetailRequestPhase.READY,
        ))
    assertTrue(
        ready(
            hasAccountSession = true,
            accountAttempted = true,
            accountHasDisplayableBody = true,
        ))
    assertTrue(
        ready(
            hasAccountSession = true,
            accountAttempted = true,
            publicPhase = DetailRequestPhase.FAILED,
            publicAttemptedForEntry = true,
        ))
  }

  @Test
  fun gameCardMustBeBoundAndResolvedOrTerminallyUnavailable() {
    assertFalse(ready(publicPhase = DetailRequestPhase.READY, gameIds = listOf(570)))
    assertFalse(
        ready(
            publicPhase = DetailRequestPhase.READY,
            gameIds = listOf(570),
            gameStateBound = true,
            gameLoading = true,
        ))
    assertFalse(
        ready(
            publicPhase = DetailRequestPhase.READY,
            gameIds = listOf(570, 730),
            gameStateBound = true,
            completedGameIds = setOf(570),
        ))
    assertTrue(
        ready(
            publicPhase = DetailRequestPhase.READY,
            gameIds = listOf(570),
            gameStateBound = true,
            completedGameIds = setOf(570),
        ))
    assertTrue(
        ready(
            publicPhase = DetailRequestPhase.READY,
            gameIds = listOf(570),
            gameStateBound = true,
            gameFailedOrLimited = true,
        ))
  }

  @Test
  fun successfulButEmptyAccountArticleWaitsForPublicFallback() {
    assertFalse(
        ready(
            hasAccountSession = true,
            accountAttempted = true,
            publicPhase = DetailRequestPhase.IDLE,
        ))
    assertTrue(
        ready(
            hasAccountSession = true,
            accountAttempted = true,
            publicPhase = DetailRequestPhase.LIMITED,
            publicAttemptedForEntry = true,
        ))
  }

  @Test
  fun nonHeyboxExistingBodyDoesNotWaitForPublicArticleRequest() {
    assertTrue(ready(source = Source.STEAM, publicPhase = DetailRequestPhase.IDLE))
    assertTrue(ready(source = Source.DEMO, publicPhase = DetailRequestPhase.IDLE))
  }

  @Test
  fun retryGenerationInvalidatesLateCompletionWithoutCrossingArticleKeys() {
    val generations = DetailRequestGenerations()
    val first = generations.begin("HEYBOX:one")
    val otherArticle = generations.begin("HEYBOX:two")
    val retry = generations.begin("HEYBOX:one")

    assertTrue(generations.isCurrent("HEYBOX:one", retry))
    assertFalse(generations.isCurrent("HEYBOX:one", first))
    assertTrue(generations.isCurrent("HEYBOX:two", otherArticle))
  }

  @Test
  fun readinessAndNavigationShareNestedDistinctGameIds() {
    val ids =
        articleGameIds(
            listOf(
                ArticleBlock.Paragraph("正文"),
                ArticleBlock.Quote(
                    listOf(
                        ArticleBlock.GameCard(570),
                        ArticleBlock.GameCard(570),
                        ArticleBlock.GameCard(730))),
            ))

    assertEquals(listOf(570, 730), ids)
  }

  @Test
  fun publicBodyWaitsForPendingGameAndSuccessfulEmptyGameResponseIsTerminal() {
    assertFalse(
        ready(
            publicPhase = DetailRequestPhase.READY,
            hasSuccessfulPublicBody = true,
            gameIds = listOf(238460),
            gameStateBound = true,
            gameLoading = true,
        ))
    assertFalse(
        ready(
            publicPhase = DetailRequestPhase.READY,
            hasSuccessfulPublicBody = true,
            gameIds = listOf(238460),
            gameStateBound = true,
        ))
    assertTrue(
        ready(
            publicPhase = DetailRequestPhase.READY,
            hasSuccessfulPublicBody = true,
            gameIds = listOf(238460),
            gameStateBound = true,
            completedGameIds = setOf(238460),
        ))
  }

  @Test
  fun initialSkeletonTracksReadyScopeAndUnstartedRequestSeparately() {
    assertTrue(shouldShowInitialDetailSkeleton(false, null, "visitor:0", false))
    assertFalse(shouldShowInitialDetailSkeleton(false, "visitor:0", "visitor:0", false))
    assertTrue(shouldShowInitialDetailSkeleton(false, "account:a:1", "account:b:2", false))
    assertTrue(shouldShowInitialDetailSkeleton(false, "visitor:0", "visitor:0", true))
    assertFalse(shouldShowInitialDetailSkeleton(true, null, "visitor:0", false))
  }

  private fun ready(
      source: Source = Source.HEYBOX,
      sessionRestoring: Boolean = false,
      hasAccountSession: Boolean = false,
      accountAttempted: Boolean = false,
      accountLoading: Boolean = false,
      accountHasDisplayableBody: Boolean = false,
      publicPhase: DetailRequestPhase = DetailRequestPhase.IDLE,
      publicAttemptedForEntry: Boolean = false,
      hasSuccessfulPublicBody: Boolean = publicPhase == DetailRequestPhase.READY,
      gameIds: List<Int> = emptyList(),
      gameStateBound: Boolean = false,
      gameLoading: Boolean = false,
      completedGameIds: Set<Int> = emptySet(),
      gameFailedOrLimited: Boolean = false,
  ) =
      isInitialDetailReady(
          source,
          sessionRestoring,
          hasAccountSession,
          accountAttempted,
          accountLoading,
          accountHasDisplayableBody,
          publicPhase,
          publicAttemptedForEntry,
          hasSuccessfulPublicBody,
          gameIds,
          gameStateBound,
          gameLoading,
          completedGameIds,
          gameFailedOrLimited,
      )
}
