package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.local.SearchHistoryStore
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.CaptchaChallenge
import cn.heyread.app.data.remote.CaptchaProof
import cn.heyread.app.data.remote.CaptchaRequiredException
import cn.heyread.app.data.remote.OnlineSearchBatch
import cn.heyread.app.data.remote.OnlineSearchLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SearchUiState(
    val scope: SearchScope = SearchScope.GLOBAL,
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<Story> = emptyList(),
    val checkedCount: Int = 0,
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val history: List<SearchHistoryEntry> = emptyList(),
    val captchaRequired: Boolean = false,
    val captchaAppId: String? = null,
    val captchaDialogOpen: Boolean = false,
    val captchaVerifying: Boolean = false,
    val captchaMessage: String? = null,
    val captchaChallengeId: Long? = null,
    val captchaAttemptId: Long? = null,
)

private data class PendingSearch(
    val id: Long,
    val challenge: CaptchaChallenge,
    val query: String,
    val scope: SearchScope,
    val offset: Int,
    val cursor: String?,
    val initial: Boolean,
    val proofInFlight: Boolean = false,
)

class SearchViewModel(
    private val loader: OnlineSearchLoader,
    private val historyStore: SearchHistoryStore,
    initialScope: SearchScope = SearchScope.GLOBAL,
    initialQuery: String = "",
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(SearchUiState(scope = initialScope, query = initialQuery.take(120)))
    val state = mutableState.asStateFlow()
    private var requestJob: Job? = null
    private var generation = 0L
    private var offset = 0
    private var cursor: String? = null
    private val rawIds = mutableSetOf<String>()
    private var pending: PendingSearch? = null
    private var nextChallengeId = 0L
    private var nextAttemptId = 0L
    private var retryOffset = 0
    private var retryInitial = true
    private var retryRefresh = false
    private var historyGeneration = 0L
    private val historyMutex = Mutex()
    private var accountRevision: Long? = null

    init {
        viewModelScope.launch { refreshHistory() }
        if (initialQuery.isNotBlank()) submit(initialQuery)
    }

    fun setQuery(value: String) {
        val current = mutableState.value
        val clean = value.take(120)
        if (clean == current.query) return
        generation++
        requestJob?.cancel()
        pending = null
        offset = 0
        cursor = null
        rawIds.clear()
        mutableState.value =
            current.copy(
                query = clean,
                submittedQuery = "",
                items = emptyList(),
                checkedCount = 0,
                searching = false,
                loadingMore = false,
                refreshing = false,
                hasMore = false,
                error = null,
                notice = null,
                captchaRequired = false,
                captchaAppId = null,
                captchaDialogOpen = false,
                captchaVerifying = false,
                captchaMessage = null,
                captchaChallengeId = null,
                captchaAttemptId = null,
            )
    }

    fun submit(query: String = mutableState.value.query) {
        val clean = query.trim().take(120)
        if (clean.isBlank()) return
        generation++
        requestJob?.cancel()
        pending = null
        offset = 0
        cursor = null
        rawIds.clear()
        mutableState.value =
            mutableState.value.copy(
                query = clean,
                submittedQuery = clean,
                items = emptyList(),
                checkedCount = 0,
                hasMore = false,
                error = null,
                notice = null,
                captchaRequired = false,
                captchaAppId = null,
                captchaDialogOpen = false,
                captchaVerifying = false,
                captchaMessage = null,
                captchaChallengeId = null,
                captchaAttemptId = null,
            )
        val submittedScope = mutableState.value.scope
        val historyToken = ++historyGeneration
        viewModelScope.launch {
            val entry = SearchHistoryEntry(clean, submittedScope)
            try {
                historyMutex.withLock {
                    if (historyToken == historyGeneration) historyStore.recordSearch(entry)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (historyToken == historyGeneration)
                    mutableState.value = mutableState.value.copy(notice = "搜索已提交，但本机记录保存失败。")
            }
            refreshHistory()
        }
        requestPage(0, initial = true)
    }

    fun searchNow() = submit()

    fun bindSession(revision: Long) {
        val previous = accountRevision
        accountRevision = revision
        if (previous == null || previous == revision) return
        generation++
        requestJob?.cancel()
        pending = null
        offset = 0
        cursor = null
        rawIds.clear()
        mutableState.value =
            mutableState.value.copy(
                submittedQuery = "",
                items = emptyList(),
                checkedCount = 0,
                searching = false,
                loadingMore = false,
                refreshing = false,
                hasMore = false,
                error = null,
                captchaRequired = false,
                captchaAppId = null,
                captchaDialogOpen = false,
                captchaVerifying = false,
                captchaMessage = null,
                captchaChallengeId = null,
                captchaAttemptId = null,
            )
    }

    fun refresh() {
        val current = mutableState.value
        if (current.submittedQuery.isBlank() || current.captchaRequired) return
        generation++
        requestJob?.cancel()
        offset = 0
        cursor = null
        rawIds.clear()
        mutableState.value = current.copy(items = emptyList(), checkedCount = 0, error = null)
        requestPage(0, initial = true, refresh = true)
    }

    fun loadMore() {
        val s = mutableState.value
        if (
            s.captchaRequired ||
                s.searching ||
                s.loadingMore ||
                s.refreshing ||
                s.error != null ||
                !s.hasMore
        )
            return
        requestPage(offset, initial = false)
    }

    fun retry() {
        val s = mutableState.value
        if (
            s.captchaRequired ||
                s.submittedQuery.isBlank() ||
                s.searching ||
                s.loadingMore ||
                s.refreshing
        )
            return
        requestPage(retryOffset, retryInitial, retryRefresh)
    }

    private fun requestPage(
        at: Int,
        initial: Boolean,
        refresh: Boolean = false,
        challenge: CaptchaChallenge? = null,
        proof: CaptchaProof? = null,
        retryCursor: String? = null,
    ) {
        val s = mutableState.value
        val query = s.submittedQuery
        val scope = s.scope
        val token = ++generation
        retryOffset = at
        retryInitial = initial
        retryRefresh = refresh
        mutableState.value =
            s.copy(
                searching = initial && !refresh,
                refreshing = refresh,
                loadingMore = !initial,
                error = null,
                captchaVerifying = proof != null || s.captchaVerifying,
            )
        requestJob =
            viewModelScope.launch {
                try {
                    val batch =
                        loader.load(
                            query,
                            scope,
                            at,
                            if (challenge != null) retryCursor else cursor,
                            challenge,
                            proof,
                        )
                    if (!current(token, query, scope)) return@launch
                    accept(batch, initial)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (!current(token, query, scope)) return@launch
                    if (error is CaptchaRequiredException) {
                        val id = ++nextChallengeId
                        pending =
                            PendingSearch(id, error.challenge, query, scope, at, cursor, initial)
                        val now = mutableState.value
                        mutableState.value =
                            now.copy(
                                searching = false,
                                loadingMore = false,
                                refreshing = false,
                                captchaRequired = true,
                                captchaAppId = error.challenge.appId,
                                captchaDialogOpen = false,
                                captchaVerifying = false,
                                captchaChallengeId = id,
                                captchaAttemptId = null,
                                captchaMessage = null,
                            )
                    } else {
                        pending = pending?.copy(proofInFlight = false)
                        mutableState.value =
                            mutableState.value.copy(
                                searching = false,
                                loadingMore = false,
                                refreshing = false,
                                captchaVerifying = false,
                                captchaMessage =
                                    if (pending != null) error.message ?: "读取失败，请重新验证后重试。"
                                    else null,
                                error =
                                    if (pending == null) error.message ?: "搜索暂时失败，请重试。" else null,
                            )
                    }
                }
            }
    }

    private fun accept(batch: OnlineSearchBatch, initial: Boolean) {
        val current = mutableState.value
        val base = if (initial) emptyList() else current.items
        val merged = (base + batch.stories).distinctBy { "${it.source}:${it.id}" }
        val checkedAdded = batch.rawIds.distinct().count { rawIds.add(it) }
        val added = merged.size - base.size
        val checkedCount = current.checkedCount + checkedAdded
        offset = batch.nextOffset
        cursor = batch.nextCursor
        val canContinue = batch.hasMore && batch.rawCount > 0 && (added > 0 || checkedAdded > 0)
        pending = null
        mutableState.value =
            current.copy(
                items = merged,
                checkedCount = checkedCount,
                searching = false,
                loadingMore = false,
                refreshing = false,
                hasMore = canContinue,
                error = null,
                captchaRequired = false,
                captchaAppId = null,
                captchaDialogOpen = false,
                captchaVerifying = false,
                captchaMessage = null,
                captchaChallengeId = null,
                captchaAttemptId = null,
            )
    }

    fun openCaptchaDialog() {
        val s = mutableState.value
        if (s.captchaRequired && !s.captchaVerifying)
            mutableState.value =
                s.copy(
                    captchaDialogOpen = true,
                    captchaAttemptId = ++nextAttemptId,
                    captchaMessage = null,
                )
    }

    fun closeCaptchaDialog() {
        mutableState.value =
            mutableState.value.copy(captchaDialogOpen = false, captchaAttemptId = null)
    }

    fun onCaptchaCallback(challengeId: Long, attemptId: Long, result: CaptchaCallbackResult) {
        val p = pending ?: return
        val s = mutableState.value
        if (
            p.id != challengeId ||
                s.captchaChallengeId != challengeId ||
                s.captchaAttemptId != attemptId ||
                !s.captchaDialogOpen ||
                s.captchaVerifying ||
                p.proofInFlight
        )
            return
        when (result) {
            CaptchaCallbackResult.Cancelled -> closeCaptchaDialog()
            is CaptchaCallbackResult.Failed ->
                mutableState.value =
                    s.copy(
                        captchaDialogOpen = false,
                        captchaAttemptId = null,
                        captchaMessage = result.message,
                    )
            is CaptchaCallbackResult.Completed -> {
                pending = p.copy(proofInFlight = true)
                mutableState.value =
                    s.copy(
                        captchaDialogOpen = false,
                        captchaAttemptId = null,
                        captchaVerifying = true,
                    )
                requestPage(
                    p.offset,
                    p.initial,
                    challenge = p.challenge,
                    proof = result.proof,
                    retryCursor = p.cursor,
                )
            }
        }
    }

    fun retryAfterOfficialVerification() {
        val p = pending ?: return
        if (p.proofInFlight) return
        pending = p.copy(proofInFlight = true)
        mutableState.value =
            mutableState.value.copy(
                captchaVerifying = true,
                captchaDialogOpen = false,
                captchaAttemptId = null,
            )
        requestPage(p.offset, p.initial, challenge = p.challenge, retryCursor = p.cursor)
    }

    fun refreshHistory() {
        val token = ++historyGeneration
        viewModelScope.launch {
            try {
                val entries = historyMutex.withLock { historyStore.searchHistory() }
                if (token == historyGeneration)
                    mutableState.value = mutableState.value.copy(history = entries)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (token == historyGeneration)
                    mutableState.value = mutableState.value.copy(notice = "本机搜索记录读取失败。")
            }
        }
    }

    fun visibleHistory(): List<SearchHistoryEntry> {
        val scope = mutableState.value.scope
        return if (scope.type == SearchScopeType.GLOBAL) mutableState.value.history
        else
            mutableState.value.history.filter {
                it.scope.type == scope.type && it.scope.id == scope.id
            }
    }

    fun removeHistory(entry: SearchHistoryEntry) {
        val token = ++historyGeneration
        viewModelScope.launch {
            try {
                val entries =
                    historyMutex.withLock {
                        if (token == historyGeneration) historyStore.removeSearch(entry)
                        historyStore.searchHistory()
                    }
                if (token == historyGeneration)
                    mutableState.value = mutableState.value.copy(history = entries)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.value = mutableState.value.copy(notice = "删除本机搜索记录失败。")
            }
        }
    }

    fun clearHistory() {
        val token = ++historyGeneration
        viewModelScope.launch {
            try {
                val entries =
                    historyMutex.withLock {
                        if (token == historyGeneration) historyStore.clearSearchHistory()
                        historyStore.searchHistory()
                    }
                if (token == historyGeneration)
                    mutableState.value = mutableState.value.copy(history = entries)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.value = mutableState.value.copy(notice = "清空本机搜索记录失败。")
            }
        }
    }

    private fun current(token: Long, query: String, scope: SearchScope) =
        token == generation &&
            query == mutableState.value.submittedQuery &&
            scope == mutableState.value.scope

    class Factory(
        private val loader: OnlineSearchLoader,
        private val history: SearchHistoryStore,
        private val scope: SearchScope,
        private val initialQuery: String = "",
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(loader, history, scope, initialQuery) as T
    }
}
