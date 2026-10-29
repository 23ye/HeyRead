package cn.heyread.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.account.CloudFolder
import cn.heyread.app.data.account.FullArticle
import cn.heyread.app.data.account.QrChallenge
import cn.heyread.app.data.account.QrPollResult
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.account.SessionStore
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class AccountUiState(
    val session: AccountSession? = null,
    val challenge: QrChallenge? = null,
    val message: String = "",
    val loadingQr: Boolean = false,
    val revision: Long = 0,
    val loadingSession: Boolean = true,
    val fullArticles: Map<String, FullArticle> = emptyMap(),
    val articleLoading: Set<String> = emptySet(),
    val articleErrors: Map<String, String> = emptyMap(),
    val folders: List<CloudFolder> = emptyList(),
    val cloudFavorites: List<cn.heyread.app.data.model.Story> = emptyList(),
    val cloudHistory: List<cn.heyread.app.data.model.Story> = emptyList(),
    val cloudFolderId: String? = null,
    val cloudMode: String = "favorites",
    val cloudLoading: Boolean = false,
    val cloudRefreshing: Boolean = false,
    val cloudAppending: Boolean = false,
    val cloudError: String? = null,
    val historyAuthorGeneration: Long = 0,
    val historyAuthorLoadingIds: Set<String> = emptySet(),
    val historyAuthorErrorIds: Set<String> = emptySet(),
    val favoritesHasMore: Boolean = true,
    val historyHasMore: Boolean = true,
    val favoritesPage: Int = 0,
    val historyPage: Int = 0,
    val cloudFavoriteIds: Set<String> = emptySet(),
    val cloudFavoriteBusy: Set<String> = emptySet(),
    val replies: Map<String, List<RemoteComment>> = emptyMap(),
    val replyHasMore: Map<String, Boolean> = emptyMap(),
    val replyLastValue: Map<String, String?> = emptyMap(),
    val replyLoading: Set<String> = emptySet(),
    val replyErrors: Map<String, String> = emptyMap(),
    val commentRefreshing: Set<String> = emptySet(),
)

class AccountViewModel @JvmOverloads constructor(
    application: Application,
    internal val repository: AccountRepository = AccountRepository(),
) : AndroidViewModel(application) {
    private val sessionStore = SessionStore(application)
    private val mutableState = MutableStateFlow(AccountUiState())
    val state = mutableState.asStateFlow()
    private var challengeJob: Job? = null
    private var pollingJob: Job? = null
    private var isForeground = false
    @Volatile private var generation = 0L
    private val persistenceMutex = Mutex()
    private val privateJobs = mutableSetOf<Job>()
    private var cloudJob: Job? = null
    @Volatile private var cloudGeneration = 0L
    private val historyAuthorSemaphore = Semaphore(2)
    private val historyAuthorLock = Any()
    private val historyAuthorInFlight = mutableMapOf<String, Long>()
    private val historyAuthorAttemptedIds = mutableSetOf<String>()
    private var nextHistoryAuthorToken = 0L
    private val commentGenerations = mutableMapOf<String, Long>()
    private val commentJobs = mutableMapOf<String, Job>()
    private val replyGenerations = mutableMapOf<String, Long>()
    private val replyJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            val loadGeneration = generation
            val session = withContext(Dispatchers.IO) { sessionStore.load() }
            if (loadGeneration != generation) {
                mutableState.value = mutableState.value.copy(loadingSession = false)
                return@launch
            }
            if (session != null) repository.restoreSession(session.cookieHeader, session.userId)
            mutableState.value = mutableState.value.copy(session = session, loadingSession = false)
        }
    }

    fun startQr() {
        isForeground = true
        val requestGeneration = ++generation
        pollingJob?.cancel()
        challengeJob?.cancel()
        if (mutableState.value.session == null) repository.clearEphemeralCookies()
        mutableState.value =
            mutableState.value.copy(challenge = null, loadingQr = true, message = "正在获取官方二维码…")
        challengeJob =
            viewModelScope.launch {
                runCatching { repository.getQrChallenge() }
                    .onSuccess { challenge ->
                        if (requestGeneration != generation) return@onSuccess
                        mutableState.value =
                            mutableState.value.copy(
                                challenge = challenge,
                                loadingQr = false,
                                message = "请使用小黑盒官方 App 扫码并确认登录。",
                            )
                        if (isForeground) pollUntilDone(challenge, requestGeneration)
                    }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (requestGeneration != generation) return@onFailure
                        mutableState.value =
                            mutableState.value.copy(
                                challenge = null,
                                loadingQr = false,
                                message =
                                    error.message?.takeUnless { it.contains("Exception") }
                                        ?: "网络暂时不可用，无法获取二维码。",
                            )
                    }
            }
    }

    fun resumeQr() {
        isForeground = true
        val challenge = mutableState.value.challenge ?: return
        if (System.currentTimeMillis() >= challenge.expiresAtMillis) {
            stopPolling()
            mutableState.value =
                mutableState.value.copy(challenge = null, message = "二维码已过期，请重新获取。")
        } else if (pollingJob?.isActive != true) {
            pollUntilDone(challenge, generation)
        }
    }

    fun pauseQr() {
        isForeground = false
        stopPolling()
    }

    fun stopQr() {
        val stoppedGeneration = ++generation
        pauseQr()
        challengeJob?.cancel()
        challengeJob = null
        mutableState.value = mutableState.value.copy(challenge = null, loadingQr = false)
        if (mutableState.value.session == null) {
            repository.clearEphemeralCookies()
            viewModelScope.launch(Dispatchers.IO) {
                persistenceMutex.withLock {
                    if (generation == stoppedGeneration && mutableState.value.session == null)
                        sessionStore.clear()
                }
            }
        }
    }

    fun logout() {
        generation++
        cloudGeneration++
        resetHistoryAuthorRequests(clearAttempts = true)
        pauseQr()
        challengeJob?.cancel()
        cloudJob?.cancel()
        privateJobs.toList().forEach(Job::cancel)
        privateJobs.clear()
        repository.clearEphemeralCookies()
        viewModelScope.launch(Dispatchers.IO) { persistenceMutex.withLock { sessionStore.clear() } }
        mutableState.value =
            AccountUiState(
                message = "已退出登录。本机阅读历史仍保留。",
                revision = mutableState.value.revision + 1,
                loadingSession = false,
            )
    }

    fun loadFullArticle(story: cn.heyread.app.data.model.Story, force: Boolean = false) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        val key = "${story.source}:${story.id}"
        if (!force && (key in snapshot.fullArticles || key in snapshot.articleLoading)) return
        commentJobs[key]?.cancel()
        val commentToken = (commentGenerations[key] ?: 0L) + 1L
        commentGenerations[key] = commentToken
        mutableState.value =
            snapshot.copy(
                articleLoading = snapshot.articleLoading + key,
                commentRefreshing = snapshot.commentRefreshing - key,
                articleErrors = snapshot.articleErrors - key,
            )
        val job = launchPrivate {
            try {
                val article = repository.loadFullArticle(story)
                if (
                    !sessionCurrent(snapshot.revision, session.userId) ||
                        commentToken != (commentGenerations[key] ?: 0L)
                )
                    return@launchPrivate
                mutableState.value =
                    mutableState.value.copy(
                        fullArticles = mutableState.value.fullArticles + (key to article),
                        articleLoading = mutableState.value.articleLoading - key,
                        articleErrors = mutableState.value.articleErrors - key,
                        cloudFavoriteIds =
                            when (article.isFavorited) {
                                true -> mutableState.value.cloudFavoriteIds + key
                                false -> mutableState.value.cloudFavoriteIds - key
                                null -> mutableState.value.cloudFavoriteIds
                            },
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (
                    error is AccountSessionExpiredException &&
                        commentToken == (commentGenerations[key] ?: 0L)
                )
                    expireSession(snapshot.revision)
                else if (
                    sessionCurrent(snapshot.revision, session.userId) &&
                        commentToken == (commentGenerations[key] ?: 0L)
                )
                    mutableState.value =
                        mutableState.value.copy(
                            articleLoading = mutableState.value.articleLoading - key,
                            articleErrors =
                                mutableState.value.articleErrors + (key to safeAccountError(error)),
                        )
            }
        }
        commentJobs[key] = job
    }

    fun loadMoreComments(story: cn.heyread.app.data.model.Story) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        val key = "${story.source}:${story.id}"
        if (snapshot.fullArticles[key] == null) {
            loadFullArticle(story, force = true)
            return
        }
        val article = snapshot.fullArticles[key] ?: return
        if (
            !article.hasMoreComments ||
                key in snapshot.articleLoading ||
                key in snapshot.commentRefreshing
        )
            return
        val token = commentGenerations[key] ?: 0L
        mutableState.value = snapshot.copy(articleLoading = snapshot.articleLoading + key)
        val job = launchPrivate {
            try {
                val page = repository.loadCommentsPage(story, article.page + 1)
                if (
                    !sessionCurrent(snapshot.revision, session.userId) ||
                        token != (commentGenerations[key] ?: 0L)
                )
                    return@launchPrivate
                val updated =
                    article.copy(
                        comments = (article.comments + page.comments).distinctBy(RemoteComment::id),
                        page = article.page + 1,
                        hasMoreComments = page.hasMore,
                    )
                mutableState.value =
                    mutableState.value.copy(
                        fullArticles = mutableState.value.fullArticles + (key to updated),
                        articleLoading = mutableState.value.articleLoading - key,
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (
                    error is AccountSessionExpiredException &&
                        token == (commentGenerations[key] ?: 0L)
                )
                    expireSession(snapshot.revision)
                else if (
                    sessionCurrent(snapshot.revision, session.userId) &&
                        token == (commentGenerations[key] ?: 0L)
                )
                    mutableState.value =
                        mutableState.value.copy(
                            articleLoading = mutableState.value.articleLoading - key,
                            articleErrors =
                                mutableState.value.articleErrors + (key to safeAccountError(error)),
                        )
            }
        }
        commentJobs[key] = job
    }

    fun refreshComments(story: cn.heyread.app.data.model.Story) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        val key = "${story.source}:${story.id}"
        if (snapshot.fullArticles[key] == null) {
            loadFullArticle(story, force = true)
            return
        }
        commentJobs[key]?.cancel()
        val token = (commentGenerations[key] ?: 0L) + 1L
        commentGenerations[key] = token
        mutableState.value =
            snapshot.copy(
                articleLoading = snapshot.articleLoading + key,
                commentRefreshing = snapshot.commentRefreshing + key,
                articleErrors = snapshot.articleErrors - key,
            )
        val job = launchPrivate {
            try {
                val page = repository.loadCommentsPage(story, 1)
                if (
                    !sessionCurrent(snapshot.revision, session.userId) ||
                        token != (commentGenerations[key] ?: 0L)
                )
                    return@launchPrivate
                val current = mutableState.value.fullArticles[key] ?: return@launchPrivate
                mutableState.value =
                    mutableState.value.copy(
                        fullArticles =
                            mutableState.value.fullArticles +
                                (key to
                                    current.copy(
                                        comments = page.comments,
                                        page = 1,
                                        hasMoreComments = page.hasMore,
                                    )),
                        articleLoading = mutableState.value.articleLoading - key,
                        commentRefreshing = mutableState.value.commentRefreshing - key,
                        articleErrors = mutableState.value.articleErrors - key,
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (
                    error is AccountSessionExpiredException &&
                        token == (commentGenerations[key] ?: 0L)
                )
                    expireSession(snapshot.revision)
                else if (
                    sessionCurrent(snapshot.revision, session.userId) &&
                        token == (commentGenerations[key] ?: 0L)
                )
                    mutableState.value =
                        mutableState.value.copy(
                            articleLoading = mutableState.value.articleLoading - key,
                            commentRefreshing = mutableState.value.commentRefreshing - key,
                            articleErrors =
                                mutableState.value.articleErrors + (key to safeAccountError(error)),
                        )
            }
        }
        commentJobs[key] = job
    }

    fun loadReplies(rootCommentId: String, next: Boolean = false) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        if (rootCommentId in snapshot.replyLoading) return
        val cursor = if (next) snapshot.replyLastValue[rootCommentId] else null
        val request = replyGenerations[rootCommentId] ?: 0L
        mutableState.value =
            snapshot.copy(
                replyLoading = snapshot.replyLoading + rootCommentId,
                replyErrors = snapshot.replyErrors - rootCommentId,
            )
        val job = launchPrivate {
            try {
                val page = repository.loadSubComments(rootCommentId, cursor)
                if (
                    !sessionCurrent(snapshot.revision, session.userId) ||
                        request != (replyGenerations[rootCommentId] ?: 0L)
                )
                    return@launchPrivate
                val previous =
                    if (next) mutableState.value.replies[rootCommentId].orEmpty() else emptyList()
                mutableState.value =
                    mutableState.value.copy(
                        replies =
                            mutableState.value.replies +
                                (rootCommentId to
                                    (previous + page.comments).distinctBy(RemoteComment::id)),
                        replyHasMore =
                            mutableState.value.replyHasMore + (rootCommentId to page.hasMore),
                        replyLastValue =
                            mutableState.value.replyLastValue + (rootCommentId to page.lastValue),
                        replyLoading = mutableState.value.replyLoading - rootCommentId,
                        replyErrors = mutableState.value.replyErrors - rootCommentId,
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (
                    error is AccountSessionExpiredException &&
                        request == (replyGenerations[rootCommentId] ?: 0L)
                )
                    expireSession(snapshot.revision)
                else if (
                    sessionCurrent(snapshot.revision, session.userId) &&
                        request == (replyGenerations[rootCommentId] ?: 0L)
                )
                    mutableState.value =
                        mutableState.value.copy(
                            replyLoading = mutableState.value.replyLoading - rootCommentId,
                            replyErrors =
                                mutableState.value.replyErrors +
                                    (rootCommentId to safeAccountError(error)),
                        )
            }
        }
        replyJobs[rootCommentId] = job
        job.invokeOnCompletion {
            if (replyJobs[rootCommentId] === job) replyJobs.remove(rootCommentId)
        }
    }

    fun refreshReplies(rootCommentId: String) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        val request = (replyGenerations[rootCommentId] ?: 0L) + 1L
        replyGenerations[rootCommentId] = request
        replyJobs[rootCommentId]?.cancel()
        mutableState.value =
            snapshot.copy(
                replyLoading = snapshot.replyLoading + rootCommentId,
                replyErrors = snapshot.replyErrors - rootCommentId,
            )
        val job = launchPrivate {
            try {
                val page = repository.loadSubComments(rootCommentId, null)
                if (
                    !sessionCurrent(snapshot.revision, session.userId) ||
                        request != (replyGenerations[rootCommentId] ?: 0L)
                )
                    return@launchPrivate
                mutableState.value =
                    mutableState.value.copy(
                        replies =
                            mutableState.value.replies +
                                (rootCommentId to page.comments.distinctBy(RemoteComment::id)),
                        replyHasMore =
                            mutableState.value.replyHasMore + (rootCommentId to page.hasMore),
                        replyLastValue =
                            mutableState.value.replyLastValue + (rootCommentId to page.lastValue),
                        replyLoading = mutableState.value.replyLoading - rootCommentId,
                        replyErrors = mutableState.value.replyErrors - rootCommentId,
                    )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (
                    error is AccountSessionExpiredException &&
                        request == (replyGenerations[rootCommentId] ?: 0L)
                )
                    expireSession(snapshot.revision)
                else if (
                    sessionCurrent(snapshot.revision, session.userId) &&
                        request == (replyGenerations[rootCommentId] ?: 0L)
                )
                    mutableState.value =
                        mutableState.value.copy(
                            replyLoading = mutableState.value.replyLoading - rootCommentId,
                            replyErrors =
                                mutableState.value.replyErrors +
                                    (rootCommentId to safeAccountError(error)),
                        )
            }
        }
        replyJobs[rootCommentId] = job
        job.invokeOnCompletion {
            if (replyJobs[rootCommentId] === job) replyJobs.remove(rootCommentId)
        }
    }

    fun loadCloudFavorites(folderId: String? = mutableState.value.cloudFolderId, page: Int = 0) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        val request = ++cloudGeneration
        cloudJob?.cancel()
        resetHistoryAuthorRequests(clearAttempts = true)
        mutableState.value =
            snapshot.copy(
                cloudLoading = true,
                cloudRefreshing = page == 0 && snapshot.cloudFavorites.isNotEmpty(),
                cloudAppending = page > 0,
                cloudError = null,
                cloudMode = "favorites",
                historyAuthorLoadingIds = emptySet(),
                historyAuthorErrorIds = emptySet(),
            )
        cloudJob =
            viewModelScope.launch {
                try {
                    val folders =
                        mutableState.value.folders.ifEmpty { repository.loadFavoriteFolders() }
                    val selected = folderId
                    val result = repository.loadCloudFavorites(selected, page)
                    if (
                        request != cloudGeneration ||
                            !sessionCurrent(snapshot.revision, session.userId)
                    )
                        return@launch
                    if (
                        mutableState.value.cloudMode != "favorites" ||
                            mutableState.value.cloudFolderId != null &&
                                mutableState.value.cloudFolderId != selected
                    )
                        return@launch
                    val previous = if (page == 0) emptyList() else mutableState.value.cloudFavorites
                    mutableState.value =
                        mutableState.value.copy(
                            folders = folders,
                            cloudFolderId = selected,
                            cloudFavorites =
                                (previous + result.stories).distinctBy { "${it.source}:${it.id}" },
                            favoritesPage = page,
                            favoritesHasMore = result.hasMore,
                            cloudFavoriteIds =
                                mutableState.value.cloudFavoriteIds +
                                    result.stories.map { "${it.source}:${it.id}" },
                            cloudLoading = false,
                            cloudRefreshing = false,
                            cloudAppending = false,
                            cloudError = null,
                        )
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (error is AccountSessionExpiredException) expireSession(snapshot.revision)
                    else if (
                        request == cloudGeneration &&
                            sessionCurrent(snapshot.revision, session.userId)
                    )
                        mutableState.value =
                            mutableState.value.copy(
                                cloudLoading = false,
                                cloudRefreshing = false,
                                cloudAppending = false,
                                cloudError = safeAccountError(error),
                            )
                }
            }
    }

    suspend fun resolveHistoryAuthors(visibleStoryIds: Set<String>) {
        if (visibleStoryIds.isEmpty()) return
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        if (snapshot.cloudMode != "history" || snapshot.cloudLoading) return
        val requestGeneration = cloudGeneration
        val visibleStories =
            snapshot.cloudHistory
                .asSequence()
                .filter { it.source == Source.HEYBOX && it.id in visibleStoryIds }
                .filterNot(::hasAuthorName)
                .distinctBy(Story::id)
                .toList()
        val scheduled =
            visibleStories.mapNotNull { story ->
                synchronized(historyAuthorLock) {
                    if (
                        story.id in historyAuthorAttemptedIds ||
                            story.id in historyAuthorInFlight
                    ) {
                        null
                    } else {
                        val token = ++nextHistoryAuthorToken
                        historyAuthorInFlight[story.id] = token
                        story to token
                    }
                }
            }
        if (scheduled.isEmpty()) return
        if (!historyRequestMatches(snapshot, session.userId, requestGeneration)) {
            synchronized(historyAuthorLock) {
                scheduled.forEach { (story, token) ->
                    if (historyAuthorInFlight[story.id] == token) historyAuthorInFlight.remove(story.id)
                }
            }
            return
        }
        val beforeLoading = mutableState.value
        val scheduledForCurrentHistory =
            historyRequestMatches(beforeLoading, snapshot, session.userId, requestGeneration)
        if (scheduledForCurrentHistory)
            mutableState.value =
                beforeLoading.copy(
                    historyAuthorLoadingIds =
                        beforeLoading.historyAuthorLoadingIds + scheduled.map { it.first.id },
                    historyAuthorErrorIds =
                        beforeLoading.historyAuthorErrorIds - scheduled.map { it.first.id }.toSet(),
                )
        if (!scheduledForCurrentHistory) {
            synchronized(historyAuthorLock) {
                scheduled.forEach { (story, token) ->
                    if (historyAuthorInFlight[story.id] == token) historyAuthorInFlight.remove(story.id)
                }
            }
            return
        }

        try {
          coroutineScope {
            scheduled
                .map { (story, token) ->
                    async {
                        var completedForCurrentHistory = false
                        try {
                            historyAuthorSemaphore.withPermit {
                                if (!historyRequestMatches(snapshot, session.userId, requestGeneration))
                                    return@withPermit
                                try {
                                    val profile = repository.loadHistoryAuthor(story, session.userId)
                                    if (!historyRequestMatches(snapshot, session.userId, requestGeneration))
                                        return@withPermit
                                    val current = mutableState.value
                                    val matches =
                                        historyRequestMatches(current, snapshot, session.userId, requestGeneration) &&
                                            current.cloudHistory.any {
                                                it.source == Source.HEYBOX && it.id == story.id
                                            }
                                    if (matches) {
                                        mutableState.value =
                                            current.copy(
                                                cloudHistory =
                                                    current.cloudHistory.map { currentStory ->
                                                        if (
                                                            currentStory.source == Source.HEYBOX &&
                                                                currentStory.id == story.id
                                                        )
                                                            currentStory.copy(
                                                                author = profile.name,
                                                                authorProfile = profile,
                                                            )
                                                        else currentStory
                                                    },
                                                historyAuthorErrorIds = current.historyAuthorErrorIds - story.id,
                                            )
                                    }
                                    completedForCurrentHistory = matches
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    if (!historyRequestMatches(snapshot, session.userId, requestGeneration))
                                        return@withPermit
                                    val current = mutableState.value
                                    val matches =
                                        historyRequestMatches(current, snapshot, session.userId, requestGeneration) &&
                                            current.cloudHistory.any {
                                                it.source == Source.HEYBOX && it.id == story.id
                                            }
                                    if (matches)
                                        mutableState.value =
                                            current.copy(
                                                historyAuthorErrorIds = current.historyAuthorErrorIds + story.id
                                            )
                                    completedForCurrentHistory = matches
                                }
                            }
                        } finally {
                            val ownsAttempt =
                                synchronized(historyAuthorLock) {
                                    if (historyAuthorInFlight[story.id] == token) {
                                        historyAuthorInFlight.remove(story.id)
                                        if (
                                            completedForCurrentHistory &&
                                                historyRequestMatches(
                                                    mutableState.value,
                                                    snapshot,
                                                    session.userId,
                                                    requestGeneration,
                                                )
                                        )
                                            historyAuthorAttemptedIds.add(story.id)
                                        true
                                    } else false
                                }
                            if (ownsAttempt) {
                                mutableState.update { current ->
                                    if (
                                        historyRequestMatches(
                                            current,
                                            snapshot,
                                            session.userId,
                                            requestGeneration,
                                        )
                                    )
                                        current.copy(
                                            historyAuthorLoadingIds = current.historyAuthorLoadingIds - story.id
                                        )
                                    else current
                                }
                            }
                        }
                    }
                }
                .awaitAll()
          }
        } finally {
          val orphanedIds =
            synchronized(historyAuthorLock) {
              scheduled.mapNotNull { (story, token) ->
                if (historyAuthorInFlight[story.id] == token) {
                  historyAuthorInFlight.remove(story.id)
                  story.id
                } else null
              }.toSet()
            }
          if (orphanedIds.isNotEmpty()) {
            mutableState.update { current ->
              if (historyRequestMatches(current, snapshot, session.userId, requestGeneration))
                current.copy(historyAuthorLoadingIds = current.historyAuthorLoadingIds - orphanedIds)
              else current
            }
          }
        }
    }

    fun loadCloudHistory(page: Int = 0) {
        val snapshot = mutableState.value
        val session = snapshot.session ?: return
        val request = ++cloudGeneration
        cloudJob?.cancel()
        resetHistoryAuthorRequests(clearAttempts = page == 0)
        mutableState.value =
            snapshot.copy(
                cloudLoading = true,
                cloudRefreshing = page == 0 && snapshot.cloudHistory.isNotEmpty(),
                cloudAppending = page > 0,
                cloudError = null,
                cloudMode = "history",
                historyAuthorGeneration = request,
                historyAuthorLoadingIds = emptySet(),
                historyAuthorErrorIds = if (page == 0) emptySet() else snapshot.historyAuthorErrorIds,
            )
        cloudJob =
            viewModelScope.launch {
                try {
                    val result = repository.loadCloudHistory(page)
                    if (
                        request != cloudGeneration ||
                            !sessionCurrent(snapshot.revision, session.userId) ||
                            mutableState.value.cloudMode != "history"
                    )
                        return@launch
                    val previous = if (page == 0) emptyList() else mutableState.value.cloudHistory
                    mutableState.value =
                        mutableState.value.copy(
                            cloudHistory =
                                (previous + result.stories).distinctBy { "${it.source}:${it.id}" },
                            historyPage = page,
                            historyHasMore = result.hasMore,
                            cloudLoading = false,
                            cloudRefreshing = false,
                            cloudAppending = false,
                            cloudError = null,
                        )
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (error is AccountSessionExpiredException) expireSession(snapshot.revision)
                    else if (
                        request == cloudGeneration &&
                            sessionCurrent(snapshot.revision, session.userId)
                    )
                        mutableState.value =
                            mutableState.value.copy(
                                cloudLoading = false,
                                cloudRefreshing = false,
                                cloudAppending = false,
                                cloudError = safeAccountError(error),
                            )
                }
            }
    }

    fun selectCloudFolder(folder: CloudFolder) {
        mutableState.value =
            mutableState.value.copy(
                cloudFolderId = folder.id,
                cloudFavorites = emptyList(),
                favoritesPage = 0,
                favoritesHasMore = true,
            )
        loadCloudFavorites(folder.id)
    }

    fun selectAllCloudFavorites() {
        mutableState.value =
            mutableState.value.copy(
                cloudFolderId = null,
                cloudFavorites = emptyList(),
                favoritesPage = 0,
                favoritesHasMore = true,
            )
        loadCloudFavorites(null)
    }

    fun loadMoreCloud() {
        val current = mutableState.value
        if (current.cloudLoading) return
        if (current.cloudMode == "history") {
            if (current.historyHasMore) loadCloudHistory(current.historyPage + 1)
        } else if (current.favoritesHasMore) {
            loadCloudFavorites(current.cloudFolderId, current.favoritesPage + 1)
        }
    }

    fun refreshCloud() {
        val current = mutableState.value
        if (current.cloudMode == "history") loadCloudHistory(0)
        else loadCloudFavorites(current.cloudFolderId, 0)
    }

    fun noteCloudFavorite(story: cn.heyread.app.data.model.Story, saved: Boolean) {
        val key = "${story.source}:${story.id}"
        mutableState.value =
            mutableState.value.copy(
                cloudFavoriteIds =
                    if (saved) mutableState.value.cloudFavoriteIds + key
                    else mutableState.value.cloudFavoriteIds - key,
                cloudFavorites =
                    if (saved)
                        (mutableState.value.cloudFavorites + story).distinctBy {
                            "${it.source}:${it.id}"
                        }
                    else
                        mutableState.value.cloudFavorites.filterNot {
                            it.source == story.source && it.id == story.id
                        },
            )
    }

    fun handleSessionExpired() {
        viewModelScope.launch { expireSession(mutableState.value.revision) }
    }

    private fun launchPrivate(action: suspend () -> Unit): Job {
        val job = viewModelScope.launch { action() }
        privateJobs += job
        job.invokeOnCompletion { privateJobs.remove(job) }
        return job
    }

    private fun sessionCurrent(revision: Long, userId: String) =
        mutableState.value.revision == revision && mutableState.value.session?.userId == userId

    private fun hasAuthorName(story: Story): Boolean {
        fun valid(value: String?) =
            value?.trim()?.takeIf {
                it.isNotBlank() && it.lowercase() !in setOf("null", "undefined", "作者未公开")
            } != null
        return valid(story.authorProfile?.name) || valid(story.author)
    }

    private fun historyRequestMatches(
        snapshot: AccountUiState,
        expectedUserId: String,
        requestGeneration: Long,
    ): Boolean = historyRequestMatches(mutableState.value, snapshot, expectedUserId, requestGeneration)

    private fun historyRequestMatches(
        current: AccountUiState,
        snapshot: AccountUiState,
        expectedUserId: String,
        requestGeneration: Long,
    ): Boolean =
        cloudGeneration == requestGeneration &&
            current.revision == snapshot.revision &&
            current.session?.userId == expectedUserId &&
            current.cloudMode == "history" &&
            !current.cloudLoading

    private fun resetHistoryAuthorRequests(clearAttempts: Boolean) {
        synchronized(historyAuthorLock) {
            historyAuthorInFlight.clear()
            if (clearAttempts) historyAuthorAttemptedIds.clear()
        }
        mutableState.update { current ->
            current.copy(
                historyAuthorLoadingIds = emptySet(),
                historyAuthorErrorIds =
                    if (clearAttempts) emptySet() else current.historyAuthorErrorIds,
            )
        }
    }

    private suspend fun expireSession(revision: Long) {
        if (mutableState.value.revision != revision) return
        cloudGeneration++
        resetHistoryAuthorRequests(clearAttempts = true)
        cloudJob?.cancel()
        persistenceMutex.withLock { withContext(Dispatchers.IO) { sessionStore.clear() } }
        repository.clearEphemeralCookies()
        mutableState.value =
            AccountUiState(
                message = "小黑盒登录已失效，请重新扫码。",
                revision = revision + 1,
                loadingSession = false,
            )
        privateJobs.toList().forEach(Job::cancel)
        privateJobs.clear()
    }

    private fun safeAccountError(error: Throwable): String =
        when (error) {
            is java.net.SocketTimeoutException -> "请求超时，请稍后重试。"
            is java.net.UnknownHostException,
            is java.io.IOException -> "网络暂时不可用，请检查连接后重试。"
            else ->
                error.message?.take(100)?.takeUnless { it.contains("Exception") } ?: "账号内容暂时无法读取。"
        }

    private fun pollUntilDone(challenge: QrChallenge, requestGeneration: Long) {
        pollingJob?.cancel()
        pollingJob =
            viewModelScope.launch {
                while (
                    isForeground &&
                        requestGeneration == generation &&
                        mutableState.value.challenge == challenge
                ) {
                    if (System.currentTimeMillis() >= challenge.expiresAtMillis) {
                        mutableState.value =
                            mutableState.value.copy(challenge = null, message = "二维码已过期，请重新获取。")
                        repository.clearEphemeralCookies()
                        break
                    }
                    val result =
                        try {
                            repository.poll(challenge)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            mutableState.value = mutableState.value.copy(message = "网络波动，正在重试扫码状态…")
                            QrPollResult.Waiting
                        }
                    when (result) {
                        QrPollResult.Waiting ->
                            mutableState.value = mutableState.value.copy(message = "等待扫码…")
                        QrPollResult.Ready ->
                            mutableState.value =
                                mutableState.value.copy(message = "已扫码，请在小黑盒 App 确认。")
                        QrPollResult.Cancelled -> {
                            mutableState.value =
                                mutableState.value.copy(
                                    challenge = null,
                                    message = "登录已取消，请重新获取二维码。",
                                )
                            repository.clearEphemeralCookies()
                            break
                        }
                        is QrPollResult.Rejected -> {
                            mutableState.value =
                                mutableState.value.copy(challenge = null, message = result.message)
                            repository.clearEphemeralCookies()
                            break
                        }
                        is QrPollResult.LoggedIn -> {
                            try {
                                val committed =
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        persistenceMutex.withLock {
                                            if (
                                                requestGeneration != generation ||
                                                    mutableState.value.challenge != challenge ||
                                                    !isForeground
                                            ) {
                                                false
                                            } else {
                                                sessionStore.save(result.session)
                                                if (
                                                    requestGeneration != generation ||
                                                        mutableState.value.challenge != challenge ||
                                                        !isForeground
                                                ) {
                                                    sessionStore.clear()
                                                    false
                                                } else true
                                            }
                                        }
                                    }
                                if (
                                    !committed ||
                                        requestGeneration != generation ||
                                        mutableState.value.challenge != challenge ||
                                        !isForeground
                                )
                                    break
                                privateJobs.toList().forEach(Job::cancel)
                                privateJobs.clear()
                                repository.activateSession(result.session)
                                mutableState.value =
                                    AccountUiState(
                                        session = result.session,
                                        message = "已登录小黑盒账号。",
                                        revision = mutableState.value.revision + 1,
                                        loadingSession = false,
                                    )
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                repository.clearEphemeralCookies()
                                mutableState.value =
                                    mutableState.value.copy(
                                        challenge = null,
                                        message = "系统安全存储不可用，未保存账号会话。",
                                    )
                            }
                            break
                        }
                    }
                    delay(2_000L)
                }
            }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
