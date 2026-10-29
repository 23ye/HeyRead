package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.remote.ArticleGameRepository
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaChallenge
import cn.heyread.app.data.remote.CaptchaProof
import cn.heyread.app.data.remote.CaptchaRequiredException
import cn.heyread.app.data.remote.PublicGameInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ArticleGameUiState(
    val articleKey: String? = null,
    val gameIds: List<Int> = emptyList(),
    val infos: Map<Int, PublicGameInfo> = emptyMap(),
    val completedInfoIds: Set<Int> = emptySet(),
    val loadingInfos: Boolean = false,
    val infoError: String? = null,
    val resolvingAppId: Int? = null,
    val failedTopicAppId: Int? = null,
    val topicError: String? = null,
    val topicToOpen: ArticleLabel? = null,
    val captchaRequired: Boolean = false,
    val captchaAppId: String? = null,
    val captchaDialogOpen: Boolean = false,
    val captchaVerifying: Boolean = false,
    val captchaMessage: String? = null,
    val captchaChallengeId: Long? = null,
    val captchaAttemptId: Long? = null,
)

private sealed interface GameOperation {
  data class LoadInfo(val ids: List<Int>) : GameOperation

  data class ResolveTopic(val appId: Int) : GameOperation
}

private data class PendingGameChallenge(
    val id: Long,
    val operation: GameOperation,
    val challenge: CaptchaChallenge,
    val inFlight: Boolean = false,
)

class ArticleGameViewModel(
    private val repository: ArticleGameRepository = ArticleGameRepository()
) : ViewModel() {
  private val mutableState = MutableStateFlow(ArticleGameUiState())
  val state = mutableState.asStateFlow()
  private var request: Job? = null
  private var generation = 0L
  private var nextChallengeId = 0L
  private var nextAttemptId = 0L
  private var pending: PendingGameChallenge? = null
  private var active = true

  fun bindArticle(articleKey: String, appIds: List<Int>) {
    val ids = appIds.filter { it > 0 }.distinct()
    val current = mutableState.value
    if (current.articleKey != articleKey) {
      cancelCurrent()
      pending = null
      mutableState.value = ArticleGameUiState(articleKey = articleKey, gameIds = ids)
      if (active && ids.isNotEmpty()) loadInfos(ids)
      return
    }
    val mergedIds = (current.gameIds + ids).distinct()
    if (mergedIds == current.gameIds) {
      if (active &&
          current.gameIds.any { it !in current.infos && it !in current.completedInfoIds } &&
          !current.loadingInfos &&
          !current.captchaRequired) {
        loadInfos(
            current.gameIds.filter { it !in current.infos && it !in current.completedInfoIds })
      }
      return
    }
    mutableState.value = current.copy(gameIds = mergedIds)
    if (active && !current.captchaRequired) {
      val missing = mergedIds.filter { it !in current.infos && it !in current.completedInfoIds }
      if (missing.isNotEmpty()) loadInfos(missing)
    }
  }

  fun setActive(value: Boolean) {
    if (active == value) return
    active = value
    if (!value) {
      cancelCurrent()
      pending = null
      mutableState.value =
          mutableState.value.copy(
              loadingInfos = false,
              resolvingAppId = null,
              captchaRequired = false,
              captchaAppId = null,
              captchaDialogOpen = false,
              captchaVerifying = false,
              captchaMessage = null,
              captchaChallengeId = null,
              captchaAttemptId = null,
              topicToOpen = null,
          )
    } else {
      val current = mutableState.value
      val missing =
          current.gameIds.filter { it !in current.infos && it !in current.completedInfoIds }
      when {
        current.resolvingAppId != null -> openGameTopic(current.resolvingAppId)
        missing.isNotEmpty() -> loadInfos(missing)
      }
    }
  }

  fun retryInfos() {
    val current = mutableState.value
    val missing = current.gameIds.filter { it !in current.infos }
    if (active && missing.isNotEmpty() && !current.captchaRequired && !current.captchaVerifying) {
      mutableState.value =
          current.copy(completedInfoIds = current.completedInfoIds - missing.toSet())
      loadInfos(missing)
    }
  }

  fun openGameTopic(appId: Int) {
    val current = mutableState.value
    if (!active || appId <= 0 || current.captchaRequired || current.captchaVerifying) return
    if (current.resolvingAppId == appId) return
    cancelCurrent()
    pending = null
    mutableState.value =
        current.copy(
            loadingInfos = false,
            infoError = null,
            resolvingAppId = appId,
            failedTopicAppId = null,
            topicError = null,
            topicToOpen = null,
            captchaRequired = false,
            captchaAppId = null,
            captchaDialogOpen = false,
            captchaVerifying = false,
            captchaMessage = null,
            captchaChallengeId = null,
            captchaAttemptId = null,
        )
    resolveTopic(appId)
  }

  fun consumeTopicNavigation() {
    mutableState.value = mutableState.value.copy(topicToOpen = null)
  }

  fun retryTopic() {
    val appId = mutableState.value.failedTopicAppId ?: return
    if (active && !mutableState.value.captchaRequired) openGameTopic(appId)
  }

  fun openCaptchaDialog() {
    val current = mutableState.value
    if (current.captchaRequired && !current.captchaVerifying) {
      mutableState.value =
          current.copy(
              captchaDialogOpen = true,
              captchaMessage = null,
              captchaAttemptId = ++nextAttemptId,
          )
    }
  }

  fun closeCaptchaDialog() {
    mutableState.value = mutableState.value.copy(captchaDialogOpen = false, captchaAttemptId = null)
  }

  fun onCaptchaCallback(challengeId: Long, attemptId: Long, result: CaptchaCallbackResult) {
    val pendingChallenge = pending ?: return
    val current = mutableState.value
    if (pendingChallenge.id != challengeId ||
        current.captchaChallengeId != challengeId ||
        current.captchaAttemptId != attemptId ||
        !current.captchaDialogOpen ||
        current.captchaVerifying ||
        pendingChallenge.inFlight)
        return
    when (result) {
      CaptchaCallbackResult.Cancelled -> closeCaptchaDialog()
      is CaptchaCallbackResult.Failed ->
          mutableState.value =
              current.copy(
                  captchaDialogOpen = false,
                  captchaAttemptId = null,
                  captchaMessage = result.message,
              )
      is CaptchaCallbackResult.Completed -> resumeChallenge(pendingChallenge, result.proof)
    }
  }

  fun retryAfterOfficialVerification() {
    val challenge = pending ?: return
    if (active && !challenge.inFlight) resumeChallenge(challenge, null)
  }

  private fun loadInfos(
      ids: List<Int>,
      challenge: CaptchaChallenge? = null,
      proof: CaptchaProof? = null,
  ) {
    if (!active || ids.isEmpty()) return
    cancelCurrent()
    val token = ++generation
    if (challenge == null) pending = null
    mutableState.value =
        mutableState.value.copy(
            loadingInfos = true,
            infoError = null,
            captchaRequired = false,
            captchaAppId = null,
            captchaDialogOpen = false,
            captchaVerifying = challenge != null,
            captchaMessage = null,
            captchaChallengeId = null,
            captchaAttemptId = null,
        )
    request =
        viewModelScope.launch {
          var remainingIds = ids.distinct()
          try {
            var requestChallenge = challenge
            var requestProof = proof
            for (batch in ids.distinct().chunked(ArticleGameRepository.MAX_APP_IDS)) {
              val loaded = repository.loadInfos(batch, requestChallenge, requestProof)
              if (token != generation || !active) return@launch
              mutableState.value =
                  mutableState.value.copy(
                      infos = mutableState.value.infos + loaded,
                      completedInfoIds = mutableState.value.completedInfoIds + batch,
                  )
              remainingIds = remainingIds.drop(batch.size)
              requestChallenge = null
              requestProof = null
            }
            if (token != generation || !active) return@launch
            pending = null
            mutableState.value =
                mutableState.value.copy(loadingInfos = false, captchaVerifying = false)
          } catch (error: CaptchaRequiredException) {
            if (token == generation && active)
                holdChallenge(GameOperation.LoadInfo(remainingIds), error.challenge)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (token == generation && active) {
              pending = null
              mutableState.value =
                  mutableState.value.copy(
                      loadingInfos = false,
                      captchaVerifying = false,
                      infoError = "游戏资料暂不可用，请重试。",
                  )
            }
          }
        }
  }

  private fun resolveTopic(
      appId: Int,
      challenge: CaptchaChallenge? = null,
      proof: CaptchaProof? = null,
  ) {
    cancelCurrent()
    val token = ++generation
    if (challenge == null) pending = null
    request =
        viewModelScope.launch {
          try {
            val result = repository.resolveTopic(appId, challenge, proof)
            if (token != generation || !active) return@launch
            pending = null
            mutableState.value =
                mutableState.value.copy(
                    resolvingAppId = null,
                    failedTopicAppId = null,
                    topicToOpen = result.label,
                    topicError = null,
                    captchaVerifying = false,
                )
          } catch (error: CaptchaRequiredException) {
            if (token == generation && active)
                holdChallenge(GameOperation.ResolveTopic(appId), error.challenge)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (token == generation && active) {
              pending = null
              mutableState.value =
                  mutableState.value.copy(
                      resolvingAppId = null,
                      failedTopicAppId = appId,
                      topicError = "未能读取该游戏对应的分区，请重试。",
                      captchaVerifying = false,
                  )
            }
          }
        }
  }

  private fun holdChallenge(operation: GameOperation, challenge: CaptchaChallenge) {
    val id = ++nextChallengeId
    pending = PendingGameChallenge(id, operation, challenge)
    mutableState.value =
        mutableState.value.copy(
            loadingInfos = false,
            resolvingAppId = (operation as? GameOperation.ResolveTopic)?.appId,
            captchaRequired = true,
            captchaAppId = challenge.appId,
            captchaDialogOpen = false,
            captchaVerifying = false,
            captchaMessage = null,
            captchaChallengeId = id,
            captchaAttemptId = null,
            infoError =
                if (operation is GameOperation.LoadInfo) "读取游戏资料需要验证。"
                else mutableState.value.infoError,
        )
  }

  private fun resumeChallenge(challenge: PendingGameChallenge, proof: CaptchaProof?) {
    if (!active || challenge.inFlight) return
    pending = challenge.copy(inFlight = true)
    mutableState.value =
        mutableState.value.copy(
            captchaDialogOpen = false,
            captchaAttemptId = null,
            captchaVerifying = true,
        )
    when (val operation = challenge.operation) {
      is GameOperation.LoadInfo -> loadInfos(operation.ids, challenge.challenge, proof)
      is GameOperation.ResolveTopic -> resolveTopic(operation.appId, challenge.challenge, proof)
    }
  }

  private fun cancelCurrent() {
    request?.cancel()
    request = null
    generation++
  }

  class Factory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ArticleGameViewModel() as T
  }
}
