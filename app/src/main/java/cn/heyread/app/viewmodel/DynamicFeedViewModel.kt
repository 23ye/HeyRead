package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.account.FollowedActivityPage
import cn.heyread.app.data.account.FollowedUser
import cn.heyread.app.data.account.FollowedUsersPage
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationRepository
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DynamicFeedMode(val title: String) {
  FOLLOWING("关注"),
  RECOMMENDED("推荐"),
}

data class DynamicFeedState(
  val mode: DynamicFeedMode = DynamicFeedMode.RECOMMENDED,
  val recommendations: FeedPageState = FeedPageState(Source.HEYBOX),
  val recommendationRetryOffset: Int? = null,
  val following: DynamicActivityFeedState = DynamicActivityFeedState(),
  val followedUsers: FollowedUsersState = FollowedUsersState(),
  val selectedAuthor: FollowedUser? = null,
  val selectedAuthorScopeToken: Long? = null,
  val selectedAuthorFeedType: DestinationFeedType = DestinationFeedType.MOMENTS,
  val selectedAuthorContent: DestinationUiState? = null,
  val evictedAuthorId: String? = null,
  val authorScopeRevision: Long = 0L,
  val accountUserId: String? = null,
  val sessionRevision: Long = Long.MIN_VALUE,
  val sessionRestoring: Boolean = true,
) {
  fun forAccountPresentation(
    userId: String?,
    revision: Long,
    loadingSession: Boolean,
  ): DynamicFeedState {
    val normalizedUserId = userId?.takeIf(String::isNotBlank)
    if (
      accountUserId == normalizedUserId &&
        sessionRevision == revision &&
        sessionRestoring == loadingSession
    )
      return this
    return copy(
      following = DynamicActivityFeedState(),
      followedUsers = FollowedUsersState(),
      selectedAuthor = null,
      selectedAuthorScopeToken = null,
      selectedAuthorFeedType = DestinationFeedType.MOMENTS,
      selectedAuthorContent = null,
      evictedAuthorId = null,
      accountUserId = normalizedUserId,
      sessionRevision = revision,
      sessionRestoring = loadingSession,
    )
  }
}

data class FollowedUsersState(
  val users: List<FollowedUser> = emptyList(),
  val hasMore: Boolean = false,
  val hasRequested: Boolean = false,
  val loading: Boolean = false,
  val refreshing: Boolean = false,
  val error: String? = null,
  val revision: Long = 0L,
)

data class DynamicActivityFeedState(
  val items: List<Story> = emptyList(),
  val nextOffset: Int = 0,
  val lastValue: String? = null,
  val retryOffset: Int? = null,
  val loading: Boolean = false,
  val refreshing: Boolean = false,
  val loadingMore: Boolean = false,
  val error: String? = null,
  val hasMore: Boolean = true,
  val hasRequested: Boolean = false,
)

class DynamicFeedViewModel(
  private val loadRecommendations: suspend (offset: Int, displayWidth: Int) -> StoryPage,
  private val loadFollowing:
    suspend (userId: String, offset: Int, lastValue: String?) -> FollowedActivityPage =
    { _, _, _ ->
      throw IllegalStateException("关注动态读取暂不可用")
    },
  private val onSessionExpired: () -> Unit = {},
  private val loadFollowedUsers: suspend (userId: String) -> FollowedUsersPage = {
    throw IllegalStateException("关注用户读取暂不可用")
  },
  private val destinationLoader: DestinationLoader = DestinationRepository(),
) : ViewModel() {
  private val mutableState = MutableStateFlow(DynamicFeedState())
  val state = mutableState.asStateFlow()
  private var recommendationJob: Job? = null
  private var recommendationGeneration = 0L
  private var followingJob: Job? = null
  private var followingGeneration = 0L
  private var followedUsersJob: Job? = null
  private var followedUsersGeneration = 0L
  private var nextAuthorScopeToken = 0L
  private var presentationActive = false
  private val authorScopes = LinkedHashMap<String, AuthorScope>()
  private val selectedAuthorTypes = mutableMapOf<String, DestinationFeedType>()

  private class AuthorScope(
    var user: FollowedUser,
    var target: DestinationTarget,
    val store: ViewModelStore,
    val viewModel: DestinationViewModel,
    var state: DestinationUiState = DestinationUiState(),
    var observer: Job? = null,
    var armedAfterSuccessId: Long = 0L,
    var armedListRevision: Long? = null,
    val token: Long,
  )

  fun selectMode(mode: DynamicFeedMode, displayWidth: Int = 604) {
    if (mutableState.value.mode == mode) return
    when (mutableState.value.mode) {
      DynamicFeedMode.FOLLOWING -> {
        cancelFollowingRequest()
        cancelFollowedUsersRequest()
        pauseSelectedAuthorScope()
      }
      DynamicFeedMode.RECOMMENDED -> cancelRecommendationRequest()
    }
    mutableState.update { it.copy(mode = mode) }
    when (mode) {
      DynamicFeedMode.FOLLOWING -> ensureFollowingLoaded()
      DynamicFeedMode.RECOMMENDED -> ensureLoaded(displayWidth)
    }
  }

  fun bindAccount(userId: String?, revision: Long, loadingSession: Boolean) {
    val normalizedUserId = userId?.takeIf(String::isNotBlank)
    val current = mutableState.value
    if (
      current.accountUserId == normalizedUserId &&
        current.sessionRevision == revision &&
        current.sessionRestoring == loadingSession
    )
      return
    presentationActive = false
    followingGeneration++
    followingJob?.cancel()
    followingJob = null
    followedUsersGeneration++
    followedUsersJob?.cancel()
    followedUsersJob = null
    clearAuthorScopes()
    mutableState.update {
      it.copy(
        accountUserId = normalizedUserId,
        sessionRevision = revision,
        sessionRestoring = loadingSession,
        following = DynamicActivityFeedState(),
        followedUsers = FollowedUsersState(),
        selectedAuthor = null,
        selectedAuthorScopeToken = null,
        selectedAuthorFeedType = DestinationFeedType.MOMENTS,
        selectedAuthorContent = null,
        evictedAuthorId = null,
      )
    }
  }

  fun ensureLoaded(displayWidth: Int = 604) {
    val current = mutableState.value.recommendations
    if (!current.hasRequested) loadRecommendationPage(offset = 0, displayWidth = displayWidth)
  }

  fun refresh(displayWidth: Int = 604) {
    if (mutableState.value.mode == DynamicFeedMode.FOLLOWING) refreshFollowing()
    else loadRecommendationPage(offset = 0, displayWidth = displayWidth)
  }

  fun retryRecommendations(displayWidth: Int = 604) {
    if (mutableState.value.mode != DynamicFeedMode.RECOMMENDED) return
    loadRecommendationPage(
      offset = mutableState.value.recommendationRetryOffset ?: 0,
      displayWidth = displayWidth,
    )
  }

  fun pauseCurrentMode() {
    presentationActive = false
    when (mutableState.value.mode) {
      DynamicFeedMode.FOLLOWING -> {
        cancelFollowingRequest()
        cancelFollowedUsersRequest()
        pauseSelectedAuthorScope()
      }
      DynamicFeedMode.RECOMMENDED -> cancelRecommendationRequest()
    }
  }

  fun loadMore(displayWidth: Int = 604) {
    if (mutableState.value.mode == DynamicFeedMode.FOLLOWING) {
      loadMoreFollowing()
      return
    }
    val current = mutableState.value.recommendations
    if (current.loading || current.refreshing || current.loadingMore || !current.hasMore) return
    loadRecommendationPage(
      offset = if (current.page < 0) 0 else (current.page + 1) * RECOMMENDED_PAGE_SIZE,
      displayWidth = displayWidth,
    )
  }

  fun ensureFollowingLoaded() {
    val current = mutableState.value
    if (current.sessionRestoring || current.accountUserId == null) return
    presentationActive = true
    if (current.selectedAuthor == null && !current.following.hasRequested)
      loadFollowingPage(offset = 0)
    ensureFollowedUsersLoaded()
    current.selectedAuthor?.let(::ensureAuthorLoaded)
  }

  fun refreshFollowedUsers() {
    val current = mutableState.value
    if (current.sessionRestoring || current.accountUserId == null) return
    loadFollowedUsers(refreshing = current.followedUsers.users.isNotEmpty())
  }

  fun retryFollowedUsers() {
    val current = mutableState.value
    if (current.sessionRestoring || current.accountUserId == null) return
    loadFollowedUsers(refreshing = current.followedUsers.users.isNotEmpty())
  }

  fun selectAuthor(userId: String?) {
    val current = mutableState.value
    if (current.mode != DynamicFeedMode.FOLLOWING) return
    val user = userId?.let { id -> current.followedUsers.users.firstOrNull { it.userId == id } }
    if (userId != null && user == null) return
    if (current.selectedAuthor?.userId == user?.userId) return
    pauseSelectedAuthorScope()
    if (user == null) {
      mutableState.update {
        it.copy(
          selectedAuthor = null,
          selectedAuthorScopeToken = null,
          selectedAuthorFeedType = DestinationFeedType.MOMENTS,
          selectedAuthorContent = null,
        )
      }
      ensureFollowingLoaded()
      return
    }
    cancelFollowingRequest()
    val scope = authorScopeFor(user)
    val feedType = selectedAuthorTypes[user.userId] ?: DestinationFeedType.MOMENTS
    mutableState.update {
      it.copy(
        selectedAuthor = user,
        selectedAuthorScopeToken = scope.token,
        selectedAuthorFeedType = feedType,
        selectedAuthorContent = scope.viewModel.state.value,
      )
    }
    val currentTarget = scope.viewModel.state.value.target
    if (currentTarget == null) {
      armUnreadClear(scope)
      scope.viewModel.showForDynamicScope(scope.target)
    } else {
      if (scope.viewModel.state.value.captchaRequired) scope.viewModel.cancelCaptcha(scope.target)
      if (scope.viewModel.state.value.feedType != feedType) {
        armUnreadClear(scope)
        scope.viewModel.selectFeed(feedType)
      }
      armUnreadClear(scope)
      scope.viewModel.resumeCurrentFeed()
      disarmIfNoAuthorRequest(scope)
    }
  }

  fun selectAuthorFeedType(feedType: DestinationFeedType) {
    val current = mutableState.value
    val user = current.selectedAuthor ?: return
    if (current.selectedAuthorFeedType == feedType) return
    val scope = authorScopes[user.userId] ?: return
    if (scope.viewModel.state.value.captchaRequired) scope.viewModel.cancelCaptcha(scope.target)
    selectedAuthorTypes[user.userId] = feedType
    mutableState.update { it.copy(selectedAuthorFeedType = feedType) }
    armUnreadClear(scope)
    scope.viewModel.selectFeed(feedType)
    disarmIfNoAuthorRequest(scope)
  }

  fun refreshSelectedAuthor() {
    selectedAuthorScope()?.let { scope ->
      if (scope.viewModel.state.value.captchaRequired) return
      armUnreadClear(scope)
      scope.viewModel.refresh()
      disarmIfNoAuthorRequest(scope)
    }
  }

  fun retrySelectedAuthor() {
    selectedAuthorScope()?.let { scope ->
      if (scope.viewModel.state.value.captchaRequired) return
      armUnreadClear(scope)
      scope.viewModel.retry()
      disarmIfNoAuthorRequest(scope)
    }
  }

  fun loadMoreSelectedAuthor() {
    selectedAuthorScope()?.let { scope ->
      armUnreadClear(scope)
      scope.viewModel.loadMore()
      disarmIfNoAuthorRequest(scope)
    }
  }

  fun openSelectedAuthorCaptcha(scopeToken: Long, userId: String, feedType: DestinationFeedType) {
    activeAuthorScope(scopeToken, userId, feedType)?.viewModel?.openCaptchaDialog()
  }

  fun closeSelectedAuthorCaptcha(
    scopeToken: Long,
    userId: String,
    feedType: DestinationFeedType,
    challengeId: Long,
    attemptId: Long,
  ) {
    activeAuthorScope(scopeToken, userId, feedType, challengeId, attemptId)?.let { scope ->
      scope.viewModel.closeCaptchaDialog()
    }
  }

  fun onSelectedAuthorCaptchaCallback(
    scopeToken: Long,
    userId: String,
    feedType: DestinationFeedType,
    challengeId: Long,
    attemptId: Long,
    result: CaptchaCallbackResult,
  ) {
    activeAuthorScope(scopeToken, userId, feedType, challengeId, attemptId)?.let { scope ->
      if (result is CaptchaCallbackResult.Completed) armUnreadClear(scope)
      scope.viewModel.onCaptchaCallback(challengeId, attemptId, result)
      disarmIfNoAuthorRequest(scope)
    }
  }

  fun retrySelectedAuthorAfterOfficialVerification() {
    selectedAuthorScope()?.let { scope ->
      if (scope.viewModel.state.value.captchaRequired) {
        armUnreadClear(scope)
        scope.viewModel.retryAfterOfficialVerification()
        disarmIfNoAuthorRequest(scope)
      }
    }
  }

  private fun ensureFollowedUsersLoaded() {
    val users = mutableState.value.followedUsers
    if (!users.hasRequested && !users.loading) loadFollowedUsers(refreshing = false)
  }

  private fun loadFollowedUsers(refreshing: Boolean) {
    val current = mutableState.value
    val userId = current.accountUserId ?: return
    if (current.sessionRestoring) return
    followedUsersJob?.cancel()
    val generation = ++followedUsersGeneration
    val revision = current.sessionRevision
    mutableState.update { state ->
      state.copy(
        followedUsers =
          state.followedUsers.copy(
            hasRequested = true,
            loading = !refreshing && state.followedUsers.users.isEmpty(),
            refreshing = refreshing,
            error = null,
          )
      )
    }
    followedUsersJob =
      viewModelScope.launch {
        try {
          val result = loadFollowedUsers(userId)
          if (!matchesFollowedUsersRequest(generation, revision, userId)) return@launch
          mutableState.update { state ->
            val selected =
              state.selectedAuthor?.userId?.let { selectedId ->
                result.users.firstOrNull { it.userId == selectedId }
              } ?: state.selectedAuthor
            state.copy(
              followedUsers =
                FollowedUsersState(
                  users = result.users,
                  hasMore = result.hasMore,
                  hasRequested = true,
                  revision = state.followedUsers.revision + 1,
                ),
              selectedAuthor = selected,
            )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (!matchesFollowedUsersRequest(generation, revision, userId)) return@launch
          if (error is AccountSessionExpiredException) onSessionExpired()
          mutableState.update { state ->
            state.copy(
              followedUsers =
                state.followedUsers.copy(
                  hasRequested = true,
                  loading = false,
                  refreshing = false,
                  error = feedErrorMessage(error),
                )
            )
          }
        }
      }
  }

  private fun cancelFollowedUsersRequest() {
    followedUsersGeneration++
    followedUsersJob?.cancel()
    followedUsersJob = null
    mutableState.update { state ->
      state.copy(
        followedUsers =
          state.followedUsers.copy(
            hasRequested = state.followedUsers.users.isNotEmpty(),
            loading = false,
            refreshing = false,
          )
      )
    }
  }

  private fun matchesFollowedUsersRequest(generation: Long, revision: Long, userId: String) =
    generation == followedUsersGeneration &&
      mutableState.value.sessionRevision == revision &&
      mutableState.value.accountUserId == userId &&
      !mutableState.value.sessionRestoring

  private fun authorScopeFor(user: FollowedUser): AuthorScope {
    authorScopes.remove(user.userId)?.let { existing ->
      existing.user = user
      authorScopes[user.userId] = existing
      return existing
    }
    val target =
      DestinationTarget(DestinationType.USER, user.userId, user.displayName, user.avatarUrl)
    val store = ViewModelStore()
    val destinationFactory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          DestinationViewModel(destinationLoader) as T
      }
    val destinationViewModel =
      ViewModelProvider(store, destinationFactory)
        .get("dynamic-author", DestinationViewModel::class.java)
    val scope =
      AuthorScope(
        user = user,
        target = target,
        store = store,
        viewModel = destinationViewModel,
        token = ++nextAuthorScopeToken,
      )
    authorScopes[user.userId] = scope
    scope.observer =
      viewModelScope.launch {
        destinationViewModel.state.collect { content ->
          scope.state = content
          if (authorScopes[user.userId] !== scope) return@collect
          val active = mutableState.value
          if (active.selectedAuthor?.userId == user.userId) {
            mutableState.update { state ->
              if (state.selectedAuthor?.userId == user.userId)
                state.copy(
                  selectedAuthorContent = content,
                  selectedAuthorFeedType = content.feedType,
                )
              else state
            }
            clearUnreadAfterSuccessfulPresentation(scope, content)
          }
        }
      }
    while (authorScopes.size > MAX_AUTHOR_SCOPES) {
      val eldest = authorScopes.entries.first()
      authorScopes.remove(eldest.key)
      eldest.value.observer?.cancel()
      eldest.value.viewModel.pauseForDynamicScope(eldest.value.target)
      eldest.value.store.clear()
      selectedAuthorTypes.remove(eldest.key)
      mutableState.update {
        it.copy(evictedAuthorId = eldest.key, authorScopeRevision = it.authorScopeRevision + 1)
      }
    }
    return scope
  }

  private fun clearUnreadAfterSuccessfulPresentation(
    scope: AuthorScope,
    content: DestinationUiState,
  ) {
    val successfulFeed = content.feeds[content.feedType] ?: return
    if (
      scope.armedListRevision == null ||
        scope.armedListRevision != mutableState.value.followedUsers.revision ||
        content.successfulLoadId <= scope.armedAfterSuccessId ||
        !successfulFeed.initialized ||
        content.loading ||
        content.refreshing ||
        content.loadingMore ||
        content.error != null ||
        content.captchaRequired
    )
      return
    val activeState = mutableState.value
    if (
      !presentationActive ||
        activeState.mode != DynamicFeedMode.FOLLOWING ||
        activeState.selectedAuthor?.userId != scope.user.userId ||
        activeState.selectedAuthorScopeToken != scope.token
    )
      return
    scope.armedListRevision = null
    mutableState.update { state ->
      val selected =
        state.selectedAuthor?.takeIf { it.userId == scope.user.userId } ?: return@update state
      val users =
        state.followedUsers.users.map { user ->
          if (user.userId == scope.user.userId) user.copy(unread = 0) else user
        }
      state.copy(
        followedUsers = state.followedUsers.copy(users = users),
        selectedAuthor = selected.copy(unread = 0),
      )
    }
  }

  private fun selectedAuthorScope(): AuthorScope? =
    mutableState.value.selectedAuthor?.userId?.let(authorScopes::get)

  private fun activeAuthorScope(
    scopeToken: Long,
    userId: String,
    feedType: DestinationFeedType,
    challengeId: Long? = null,
    attemptId: Long? = null,
  ): AuthorScope? {
    val active = mutableState.value
    if (
      active.mode != DynamicFeedMode.FOLLOWING ||
        active.sessionRestoring ||
        active.accountUserId.isNullOrBlank() ||
        active.selectedAuthor?.userId != userId ||
        active.selectedAuthorScopeToken != scopeToken ||
        active.selectedAuthorFeedType != feedType
    )
      return null
    val scope = authorScopes[userId]?.takeIf { it.token == scopeToken } ?: return null
    val child = scope.viewModel.state.value
    if (challengeId != null && child.captchaChallengeId != challengeId) return null
    if (attemptId != null && child.captchaAttemptId != attemptId) return null
    if (child.feedType != feedType) return null
    return scope
  }

  private fun pauseSelectedAuthorScope() {
    selectedAuthorScope()?.let { scope ->
      scope.armedListRevision = null
      scope.viewModel.pauseForDynamicScope(scope.target)
    }
  }

  private fun ensureAuthorLoaded(user: FollowedUser) {
    if (!presentationActive || mutableState.value.mode != DynamicFeedMode.FOLLOWING) return
    val scope = authorScopeFor(user)
    val feedType = selectedAuthorTypes[user.userId] ?: DestinationFeedType.MOMENTS
    val content = scope.viewModel.state.value
    if (content.target == null) {
      armUnreadClear(scope)
      scope.viewModel.showForDynamicScope(scope.target)
    } else {
      if (content.captchaRequired) return
      if (content.feedType != feedType) {
        armUnreadClear(scope)
        scope.viewModel.selectFeed(feedType)
      }
      armUnreadClear(scope)
      scope.viewModel.resumeCurrentFeed()
      disarmIfNoAuthorRequest(scope)
    }
  }

  private fun armUnreadClear(scope: AuthorScope) {
    scope.armedAfterSuccessId = scope.viewModel.state.value.successfulLoadId
    scope.armedListRevision = mutableState.value.followedUsers.revision
  }

  private fun disarmIfNoAuthorRequest(scope: AuthorScope) {
    val content = scope.viewModel.state.value
    if (
      !content.loading &&
        !content.refreshing &&
        !content.loadingMore &&
        content.successfulLoadId > scope.armedAfterSuccessId
    ) {
      clearUnreadAfterSuccessfulPresentation(scope, content)
    } else if (!content.loading && !content.refreshing && !content.loadingMore) {
      scope.armedListRevision = null
    }
  }

  private fun clearAuthorScopes() {
    authorScopes.values.forEach { scope ->
      scope.observer?.cancel()
      scope.viewModel.pauseForDynamicScope(scope.target)
      scope.store.clear()
    }
    authorScopes.clear()
    selectedAuthorTypes.clear()
  }

  fun refreshFollowing() {
    val current = mutableState.value
    if (current.sessionRestoring || current.accountUserId == null) return
    loadFollowingPage(offset = 0)
  }

  fun retryFollowing() {
    val current = mutableState.value
    if (current.sessionRestoring || current.accountUserId == null) return
    loadFollowingPage(current.following.retryOffset ?: 0)
  }

  fun loadMoreFollowing() {
    val current = mutableState.value
    val feed = current.following
    if (
      current.sessionRestoring ||
        current.accountUserId == null ||
        !feed.hasMore ||
        feed.loading ||
        feed.refreshing ||
        feed.loadingMore
    )
      return
    loadFollowingPage(feed.nextOffset)
  }

  private fun loadRecommendationPage(offset: Int, displayWidth: Int) {
    val current = mutableState.value.recommendations
    if (offset > 0 && (current.loading || current.refreshing || current.loadingMore)) return
    recommendationJob?.cancel()
    val generation = ++recommendationGeneration
    mutableState.update { state ->
      state.copy(
        recommendationRetryOffset = offset,
        recommendations =
          state.recommendations.copy(
            hasRequested = true,
            loading = offset == 0 && current.items.isEmpty(),
            refreshing = offset == 0 && current.items.isNotEmpty(),
            loadingMore = offset > 0,
            error = null,
          ),
      )
    }
    recommendationJob =
      viewModelScope.launch {
        try {
          val result = loadRecommendations(offset, displayWidth)
          if (generation != recommendationGeneration) return@launch
          val latest = mutableState.value.recommendations
          val merged =
            if (offset == 0) result.items
            else (latest.items + result.items).distinctBy { "${it.source}:${it.id}" }
          val added = merged.size - if (offset == 0) 0 else latest.items.size
          mutableState.update { state ->
            state.copy(
              recommendationRetryOffset = null,
              recommendations =
                state.recommendations.copy(
                  items = merged,
                  page = offset / RECOMMENDED_PAGE_SIZE,
                  loading = false,
                  refreshing = false,
                  loadingMore = false,
                  error = null,
                  hasMore = result.hasMore && added > 0,
                ),
            )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (generation != recommendationGeneration) return@launch
          mutableState.update { state ->
            state.copy(
              recommendations =
                state.recommendations.copy(
                  loading = false,
                  refreshing = false,
                  loadingMore = false,
                  error = feedErrorMessage(error),
                )
            )
          }
        }
      }
  }

  private fun cancelRecommendationRequest() {
    recommendationGeneration++
    recommendationJob?.cancel()
    recommendationJob = null
    mutableState.update { state ->
      state.copy(
        recommendations =
          state.recommendations.copy(
            hasRequested = state.recommendations.items.isNotEmpty(),
            loading = false,
            refreshing = false,
            loadingMore = false,
          )
      )
    }
  }

  private fun loadFollowingPage(offset: Int) {
    val currentState = mutableState.value
    val userId = currentState.accountUserId ?: return
    if (currentState.sessionRestoring) return
    val currentFeed = currentState.following
    if (
      offset > 0 &&
        (currentFeed.loading ||
          currentFeed.refreshing ||
          currentFeed.loadingMore ||
          !currentFeed.hasMore)
    )
      return
    followingJob?.cancel()
    val generation = ++followingGeneration
    val revision = currentState.sessionRevision
    val lastValue = if (offset == 0) null else currentFeed.lastValue
    mutableState.update { state ->
      state.copy(
        following =
          state.following.copy(
            hasRequested = true,
            loading = offset == 0 && currentFeed.items.isEmpty(),
            refreshing = offset == 0 && currentFeed.items.isNotEmpty(),
            loadingMore = offset > 0,
            error = null,
          )
      )
    }
    followingJob =
      viewModelScope.launch {
        try {
          val result = loadFollowing(userId, offset, lastValue)
          if (!matchesFollowingRequest(generation, revision, userId)) return@launch
          val latest = mutableState.value.following
          val merged =
            if (offset == 0) result.stories
            else (latest.items + result.stories).distinctBy { "${it.source}:${it.id}" }
          val added = merged.size - if (offset == 0) 0 else latest.items.size
          val unsupportedOnly = result.rawCount > 0 && result.stories.isEmpty()
          mutableState.update { state ->
            state.copy(
              following =
                state.following.copy(
                  items = merged,
                  nextOffset = offset + FOLLOWING_PAGE_SIZE,
                  lastValue = result.lastValue,
                  retryOffset = null,
                  loading = false,
                  refreshing = false,
                  loadingMore = false,
                  error = if (unsupportedOnly) "关注动态包含暂不支持的内容，暂时无法显示。" else null,
                  hasMore = result.rawCount >= FOLLOWING_PAGE_SIZE && added > 0,
                )
            )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (!matchesFollowingRequest(generation, revision, userId)) return@launch
          if (error is AccountSessionExpiredException) onSessionExpired()
          mutableState.update { state ->
            state.copy(
              following =
                state.following.copy(
                  retryOffset = offset,
                  loading = false,
                  refreshing = false,
                  loadingMore = false,
                  error = feedErrorMessage(error),
                )
            )
          }
        }
      }
  }

  private fun cancelFollowingRequest() {
    followingGeneration++
    followingJob?.cancel()
    followingJob = null
    mutableState.update { state ->
      state.copy(
        following =
          state.following.copy(
            hasRequested = state.following.items.isNotEmpty(),
            loading = false,
            refreshing = false,
            loadingMore = false,
          )
      )
    }
  }

  private fun matchesFollowingRequest(generation: Long, revision: Long, userId: String) =
    generation == followingGeneration &&
      mutableState.value.sessionRevision == revision &&
      mutableState.value.accountUserId == userId &&
      !mutableState.value.sessionRestoring

  override fun onCleared() {
    recommendationGeneration++
    recommendationJob?.cancel()
    followingGeneration++
    followingJob?.cancel()
    followedUsersGeneration++
    followedUsersJob?.cancel()
    clearAuthorScopes()
  }

  class Factory(
    private val loader: suspend (offset: Int, displayWidth: Int) -> StoryPage,
    private val followingLoader:
      suspend (userId: String, offset: Int, lastValue: String?) -> FollowedActivityPage,
    private val onSessionExpired: () -> Unit,
    private val followedUsersLoader: suspend (userId: String) -> FollowedUsersPage = {
      throw IllegalStateException("关注用户读取暂不可用")
    },
    private val destinationLoader: DestinationLoader = DestinationRepository(),
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(DynamicFeedViewModel::class.java))
      return DynamicFeedViewModel(
        loader,
        followingLoader,
        onSessionExpired,
        followedUsersLoader,
        destinationLoader,
      )
        as T
    }
  }

  companion object {
    const val RECOMMENDED_PAGE_SIZE = 30
    const val FOLLOWING_PAGE_SIZE = 30
    const val MAX_AUTHOR_SCOPES = 8
  }
}
