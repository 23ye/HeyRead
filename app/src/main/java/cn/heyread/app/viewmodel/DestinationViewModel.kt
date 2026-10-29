package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaChallenge
import cn.heyread.app.data.remote.CaptchaProof
import cn.heyread.app.data.remote.CaptchaRequiredException
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationRepository
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DestinationFeedState(
    val stories: List<Story> = emptyList(),
    val offset: Int = 0,
    val lastValue: String? = null,
    val initialized: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    val retryFromStart: Boolean = false,
)

data class DestinationUiState(
    val target: DestinationTarget? = null,
    val data: DestinationData? = null,
    val stories: List<Story> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    val feedType: DestinationFeedType = DestinationFeedType.MOMENTS,
    val feeds: Map<DestinationFeedType, DestinationFeedState> = emptyMap(),
    val captchaRequired: Boolean = false,
    val captchaAppId: String? = null,
    val captchaDialogOpen: Boolean = false,
    val captchaVerifying: Boolean = false,
    val captchaMessage: String? = null,
    val captchaChallengeId: Long? = null,
    val captchaAttemptId: Long? = null,
    val successfulLoadId: Long = 0L,
)

private data class PendingCaptchaOperation(
    val id: Long,
    val challenge: CaptchaChallenge,
    val target: DestinationTarget,
    val feedType: DestinationFeedType,
    val offset: Int,
    val lastValue: String?,
    val forceFromStart: Boolean,
    val refreshing: Boolean,
    val reloadHeader: Boolean,
    val partialData: DestinationData?,
    val inFlight: Boolean = false,
)

class DestinationViewModel(private val repository: DestinationLoader = DestinationRepository()) :
    ViewModel() {
    private val mutableState = MutableStateFlow(DestinationUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var headerLoadedForTarget = false
    private var dynamicScope = false
    private var successfulLoadId = 0L
    private var pendingCaptcha: PendingCaptchaOperation? = null
    private var nextCaptchaId = 0L
    private var nextCaptchaAttemptId = 0L

    fun show(target: DestinationTarget) {
        if (
            mutableState.value.target == target &&
                (mutableState.value.loading ||
                    mutableState.value.data != null ||
                    mutableState.value.captchaRequired)
        )
            return
        request?.cancel()
        generation++
        pendingCaptcha = null
        headerLoadedForTarget = false
        dynamicScope = false
        successfulLoadId = 0L
        val defaultFeed =
            if (target.type == DestinationType.USER) DestinationFeedType.MOMENTS
            else DestinationFeedType.ARTICLES
        mutableState.value =
            DestinationUiState(
                target = target,
                feedType = defaultFeed,
                feeds = DestinationFeedType.entries.associateWith { DestinationFeedState() },
            )
        load(nextOffset = 0, refreshing = false, forceFromStart = true, reloadHeader = true)
    }

    fun showForDynamicScope(target: DestinationTarget) {
        if (target.type != DestinationType.USER) return
        request?.cancel()
        generation++
        pendingCaptcha = null
        headerLoadedForTarget = true
        dynamicScope = true
        successfulLoadId = 0L
        mutableState.value =
            DestinationUiState(
                target = target,
                feedType = DestinationFeedType.MOMENTS,
                feeds = DestinationFeedType.entries.associateWith { DestinationFeedState() },
            )
        load(nextOffset = 0, refreshing = false, forceFromStart = true, reloadHeader = false)
    }

    fun selectFeed(feedType: DestinationFeedType) {
        val current = mutableState.value
        if (current.target?.type != DestinationType.USER || current.feedType == feedType) return
        val challengeActive = pendingCaptcha?.target == current.target
        if (challengeActive && current.captchaVerifying) return
        if (!challengeActive) {
            request?.cancel()
            generation++
        }
        val outgoing =
            (current.feeds[current.feedType] ?: DestinationFeedState()).copy(
                loading = false,
                refreshing = false,
                loadingMore = false,
            )
        val feeds = current.feeds + (current.feedType to outgoing)
        val feed = feeds[feedType] ?: DestinationFeedState()
        mutableState.value = current.copy(feeds = feeds).project(feedType, feed)
        if (challengeActive) return
        if (!feed.initialized)
            load(
                0,
                refreshing = false,
                forceFromStart = true,
                reloadHeader = !headerLoadedForTarget,
            )
    }

    fun refresh() {
        if (mutableState.value.target == null || mutableState.value.captchaRequired) return
        request?.cancel()
        generation++
        load(
            nextOffset = 0,
            refreshing = mutableState.value.stories.isNotEmpty(),
            forceFromStart = true,
            reloadHeader = !dynamicScope,
        )
    }

    fun retry() {
        if (mutableState.value.captchaRequired) return
        val current = mutableState.value
        val feed = current.feeds[current.feedType] ?: DestinationFeedState()
        if (feed.retryFromStart)
            load(
                0,
                refreshing = feed.stories.isNotEmpty(),
                forceFromStart = true,
                reloadHeader = !headerLoadedForTarget,
            )
        else load(feed.offset, refreshing = false, forceFromStart = false)
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.captchaRequired) return
        val feed = current.feeds[current.feedType] ?: return
        if (
            !current.loading &&
                !current.refreshing &&
                !current.loadingMore &&
                current.hasMore &&
                current.error == null
        )
            load(feed.offset, refreshing = false, forceFromStart = false)
    }

    fun pauseForDynamicScope(target: DestinationTarget) {
        val current = mutableState.value
        if (current.target != target) return
        request?.cancel()
        request = null
        generation++
        pendingCaptcha = null
        val feeds =
            current.feeds.mapValues { (_, feed) ->
                feed.copy(loading = false, refreshing = false, loadingMore = false)
            }
        val activeFeed = feeds[current.feedType] ?: DestinationFeedState()
        mutableState.value =
            current
                .copy(
                    feeds = feeds,
                    captchaRequired = false,
                    captchaAppId = null,
                    captchaDialogOpen = false,
                    captchaVerifying = false,
                    captchaMessage = null,
                    captchaChallengeId = null,
                    captchaAttemptId = null,
                )
                .project(current.feedType, activeFeed)
    }

    fun resumeCurrentFeed() {
        val current = mutableState.value
        if (current.target == null || current.captchaRequired) return
        val feed = current.feeds[current.feedType] ?: DestinationFeedState()
        if (!feed.initialized && !feed.loading)
            load(
                nextOffset = if (feed.retryFromStart) 0 else feed.offset,
                refreshing = false,
                forceFromStart = feed.retryFromStart,
                reloadHeader = !headerLoadedForTarget,
            )
    }

    fun openCaptchaDialog() {
        val current = mutableState.value
        if (current.captchaRequired && !current.captchaVerifying)
            mutableState.value =
                current.copy(
                    captchaDialogOpen = true,
                    captchaMessage = null,
                    captchaAttemptId = ++nextCaptchaAttemptId,
                )
    }

    fun closeCaptchaDialog() {
        val current = mutableState.value
        mutableState.value = current.copy(captchaDialogOpen = false, captchaAttemptId = null)
    }

    fun onCaptchaCallback(challengeId: Long, attemptId: Long, result: CaptchaCallbackResult) {
        val pending = pendingCaptcha ?: return
        val current = mutableState.value
        if (
            pending.id != challengeId ||
                current.captchaChallengeId != challengeId ||
                current.captchaAttemptId != attemptId ||
                !current.captchaDialogOpen ||
                current.captchaVerifying ||
                pending.inFlight
        )
            return
        when (result) {
            CaptchaCallbackResult.Cancelled ->
                mutableState.value =
                    current.copy(
                        captchaDialogOpen = false,
                        captchaAttemptId = null,
                        captchaVerifying = false,
                        captchaMessage = "验证已取消。",
                    )
            is CaptchaCallbackResult.Failed ->
                mutableState.value =
                    current.copy(
                        captchaDialogOpen = false,
                        captchaAttemptId = null,
                        captchaVerifying = false,
                        captchaMessage = result.message,
                    )
            is CaptchaCallbackResult.Completed -> resumeCaptcha(pending, result.proof)
        }
    }

    /** Explicit action after the user completes verification in the official app/site. */
    fun retryAfterOfficialVerification() {
        val pending = pendingCaptcha ?: return
        resumeCaptcha(pending, proof = null)
    }

    fun cancelCaptcha(target: DestinationTarget) {
        val pending = pendingCaptcha?.takeIf { it.target == target } ?: return
        request?.cancel()
        generation++
        pendingCaptcha = null
        val current = mutableState.value
        val feed =
            (current.feeds[pending.feedType] ?: DestinationFeedState()).copy(
                loading = false,
                refreshing = false,
                loadingMore = false,
            )
        val cleared =
            current.copy(
                captchaRequired = false,
                captchaAppId = null,
                captchaDialogOpen = false,
                captchaVerifying = false,
                captchaMessage = null,
                captchaChallengeId = null,
                captchaAttemptId = null,
                feeds = current.feeds + (pending.feedType to feed),
            )
        mutableState.value =
            if (current.feedType == pending.feedType) cleared.project(pending.feedType, feed)
            else cleared
    }

    private fun resumeCaptcha(pending: PendingCaptchaOperation, proof: CaptchaProof?) {
        if (pending.inFlight || pendingCaptcha?.id != pending.id) return
        pendingCaptcha = pending.copy(inFlight = true)
        val current = mutableState.value
        val retryFeed = current.feeds[pending.feedType] ?: DestinationFeedState()
        mutableState.value =
            current
                .project(pending.feedType, retryFeed)
                .copy(
                    captchaDialogOpen = false,
                    captchaAttemptId = null,
                    captchaVerifying = true,
                    captchaMessage = if (proof == null) "正在重新读取…" else "正在向小黑盒核验…",
                )
        load(
            nextOffset = pending.offset,
            refreshing = pending.refreshing,
            forceFromStart = pending.forceFromStart,
            reloadHeader = pending.reloadHeader,
            feedTypeOverride = pending.feedType,
            knownDataForRetry = pending.partialData,
            captchaChallenge = pending.challenge,
            captchaProof = proof,
            bypassCaptchaPause = true,
            retryLastValue = pending.lastValue,
        )
    }

    private fun load(
        nextOffset: Int,
        refreshing: Boolean,
        forceFromStart: Boolean,
        reloadHeader: Boolean = false,
        feedTypeOverride: DestinationFeedType? = null,
        knownDataForRetry: DestinationData? = null,
        captchaChallenge: CaptchaChallenge? = null,
        captchaProof: CaptchaProof? = null,
        bypassCaptchaPause: Boolean = false,
        retryLastValue: String? = null,
    ) {
        val current = mutableState.value
        val target = current.target ?: return
        if (current.captchaRequired && !bypassCaptchaPause) return
        val feedType = feedTypeOverride ?: current.feedType
        val existingFeed = current.feeds[feedType] ?: DestinationFeedState()
        if (!forceFromStart && existingFeed.loadingMore) return
        val token = ++generation
        val requestedCursor =
            if (forceFromStart) null
            else if (captchaChallenge != null) retryLastValue else existingFeed.lastValue
        val requestedOffset = if (forceFromStart) 0 else nextOffset
        val pendingFeed =
            existingFeed.copy(
                loading = forceFromStart && !existingFeed.initialized,
                refreshing = refreshing,
                loadingMore = !forceFromStart && !refreshing,
                error = null,
                retryFromStart = forceFromStart,
            )
        mutableState.value =
            if (current.feedType == feedType) current.project(feedType, pendingFeed)
            else current.copy(feeds = current.feeds + (feedType to pendingFeed))
        request =
            viewModelScope.launch {
                try {
                    val page =
                        repository.load(
                            target,
                            requestedOffset,
                            30,
                            lastValue =
                                requestedCursor
                                    ?: if (feedType == DestinationFeedType.MOMENTS) "" else null,
                            knownProfile = if (reloadHeader) null else current.data?.profile,
                            feedType = feedType,
                            reloadHeader = reloadHeader,
                            knownData = knownDataForRetry,
                            captchaChallenge = captchaChallenge,
                            captchaProof = captchaProof,
                        )
                    val active = mutableState.value
                    if (token != generation || active.target != target) return@launch
                    if (reloadHeader || knownDataForRetry?.profile != null)
                        headerLoadedForTarget = true
                    val base = if (forceFromStart) emptyList() else existingFeed.stories
                    val merged = (base + page.stories).distinctBy { "${it.source}:${it.id}" }
                    val added = merged.size - base.size
                    val nextCursor = page.lastValue
                    val hasMore =
                        page.hasMore &&
                            added > 0 &&
                            (feedType != DestinationFeedType.MOMENTS ||
                                (!nextCursor.isNullOrBlank() && nextCursor != requestedCursor))
                    val feed =
                        pendingFeed.copy(
                            stories = merged,
                            offset = page.nextOffset,
                            lastValue = nextCursor,
                            initialized = true,
                            loading = false,
                            refreshing = false,
                            loadingMore = false,
                            error = null,
                            hasMore = hasMore,
                            retryFromStart = false,
                        )
                    val latest = mutableState.value
                    val data = mergeData(latest.data, page.data, replaceNotice = reloadHeader)
                    pendingCaptcha = null
                    val successState =
                        latest.copy(
                            data = data,
                            captchaRequired = false,
                            captchaAppId = null,
                            captchaDialogOpen = false,
                            captchaVerifying = false,
                            captchaMessage = null,
                            captchaChallengeId = null,
                            captchaAttemptId = null,
                            successfulLoadId = ++successfulLoadId,
                        )
                    mutableState.value =
                        if (latest.feedType == feedType)
                            successState
                                .copy(feeds = latest.feeds + (feedType to feed))
                                .project(feedType, feed, data)
                        else successState.copy(feeds = latest.feeds + (feedType to feed))
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val active = mutableState.value
                    if (token == generation && active.target == target) {
                        if (error is CaptchaRequiredException) {
                            val captchaData = error.partialData
                            if (captchaData != null && target.type == DestinationType.USER)
                                headerLoadedForTarget = true
                            val id = ++nextCaptchaId
                            pendingCaptcha =
                                PendingCaptchaOperation(
                                    id = id,
                                    challenge = error.challenge,
                                    target = target,
                                    feedType = feedType,
                                    offset = requestedOffset,
                                    lastValue = requestedCursor,
                                    forceFromStart = forceFromStart,
                                    refreshing = refreshing,
                                    reloadHeader = reloadHeader && captchaData == null,
                                    partialData = captchaData,
                                )
                            val settledFeed =
                                existingFeed.copy(
                                    loading = false,
                                    refreshing = false,
                                    loadingMore = false,
                                    error = null,
                                )
                            val data =
                                captchaData?.let { mergeData(active.data, it) } ?: active.data
                            val requiredState =
                                active.copy(
                                    data = data,
                                    captchaRequired = true,
                                    captchaAppId = error.challenge.appId,
                                    captchaDialogOpen = false,
                                    captchaVerifying = false,
                                    captchaMessage = error.verificationMessage,
                                    captchaChallengeId = id,
                                    captchaAttemptId = null,
                                    feeds = active.feeds + (feedType to settledFeed),
                                )
                            mutableState.value =
                                if (active.feedType == feedType)
                                    requiredState.project(feedType, settledFeed, data)
                                else requiredState
                            return@launch
                        }
                        pendingCaptcha = null
                        val feed =
                            existingFeed.copy(
                                loading = false,
                                refreshing = false,
                                loadingMore = false,
                                error = error.message?.takeIf(String::isNotBlank) ?: "内容读取失败，请重试。",
                                retryFromStart = forceFromStart,
                            )
                        val failedState =
                            active.copy(
                                captchaRequired = false,
                                captchaAppId = null,
                                captchaDialogOpen = false,
                                captchaVerifying = false,
                                captchaMessage = null,
                                captchaChallengeId = null,
                                captchaAttemptId = null,
                                feeds = active.feeds + (feedType to feed),
                            )
                        mutableState.value =
                            if (active.feedType == feedType) failedState.project(feedType, feed)
                            else failedState
                    }
                }
            }
    }

    private fun DestinationUiState.project(
        feedType: DestinationFeedType,
        feed: DestinationFeedState,
        data: DestinationData? = this.data,
    ) =
        copy(
            data = data,
            feedType = feedType,
            stories = feed.stories,
            loading = feed.loading,
            refreshing = feed.refreshing,
            loadingMore = feed.loadingMore,
            error = feed.error,
            hasMore = feed.hasMore,
            feeds = feeds + (feedType to feed),
        )

    private fun mergeData(
        old: DestinationData?,
        new: DestinationData,
        replaceNotice: Boolean = false,
    ): DestinationData =
        if (old == null) new
        else
            new.copy(
                title = new.title.ifBlank { old.title },
                iconUrl = new.iconUrl ?: old.iconUrl,
                profile = new.profile ?: old.profile,
                description = new.description ?: old.description,
                notice = if (replaceNotice) new.notice else new.notice ?: old.notice,
                profileStats = new.profileStats ?: old.profileStats,
            )

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DestinationViewModel() as T
    }
}
