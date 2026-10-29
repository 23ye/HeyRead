package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.Source

enum class DetailRequestPhase {
  IDLE,
  LOADING,
  READY,
  FAILED,
  LIMITED,
}

data class DetailRequestState(
    val generation: Long,
    val phase: DetailRequestPhase,
    val message: String? = null,
) {
  val isTerminal: Boolean
    get() =
        phase == DetailRequestPhase.READY ||
            phase == DetailRequestPhase.FAILED ||
            phase == DetailRequestPhase.LIMITED
}

/**
 * Resolves only the first displayable article chain; comments and off-screen media are excluded.
 */
internal fun isInitialDetailReady(
    source: Source,
    sessionRestoring: Boolean,
    hasAccountSession: Boolean,
    accountAttempted: Boolean,
    accountLoading: Boolean,
    accountHasDisplayableBody: Boolean,
    publicPhase: DetailRequestPhase,
    publicAttemptedForEntry: Boolean,
    hasSuccessfulPublicBody: Boolean,
    gameIds: List<Int>,
    gameStateBound: Boolean,
    gameLoading: Boolean,
    completedGameIds: Set<Int>,
    gameFailedOrLimited: Boolean,
): Boolean {
  val articleReady =
      when {
        source != Source.HEYBOX -> true
        sessionRestoring -> false
        hasAccountSession && !accountAttempted -> false
        hasAccountSession && accountLoading -> false
        hasAccountSession && accountHasDisplayableBody -> true
        else ->
            hasSuccessfulPublicBody ||
                publicPhase == DetailRequestPhase.READY ||
                ((publicPhase == DetailRequestPhase.FAILED ||
                    publicPhase == DetailRequestPhase.LIMITED) && publicAttemptedForEntry)
      }
  if (!articleReady) return false
  if (gameIds.isEmpty()) return true
  if (!gameStateBound || gameLoading) return false
  return gameFailedOrLimited || gameIds.all { it in completedGameIds }
}

internal fun shouldShowInitialDetailSkeleton(
    initialContentReady: Boolean,
    lastReadyScope: String?,
    currentScope: String,
    requiredRequestNotStarted: Boolean,
): Boolean = !initialContentReady && (lastReadyScope != currentScope || requiredRequestNotStarted)

internal class DetailRequestGenerations {
  private val values = mutableMapOf<String, Long>()

  fun begin(key: String): Long {
    val next = (values[key] ?: 0L) + 1L
    values[key] = next
    return next
  }

  fun isCurrent(key: String, generation: Long): Boolean = values[key] == generation
}
