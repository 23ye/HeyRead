package cn.heyread.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.account.FollowedUser
import cn.heyread.app.data.model.ContentBlockingScope
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.ui.components.AnimatedImage
import cn.heyread.app.ui.components.CaptchaChallengeDialog
import cn.heyread.app.ui.components.ContentBlockingNotice
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.motion.HomeLayoutPaginationGate
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.cancelSharedCoverOnScroll
import cn.heyread.app.ui.motion.pageInputGate
import cn.heyread.app.viewmodel.DestinationUiState
import cn.heyread.app.viewmodel.DynamicFeedMode
import cn.heyread.app.viewmodel.DynamicFeedState
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.FeedPageState
import cn.heyread.app.viewmodel.FollowedUsersState
import cn.heyread.app.viewmodel.UiState
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.flow.collect

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DynamicFeedScreen(
  state: DynamicFeedState,
  ui: UiState,
  viewModel: DynamicFeedViewModel,
  isCurrentPage: Boolean,
  onLogin: () -> Unit,
  onOpenStory: (Story) -> Unit,
  selectedStoryIdentity: String? = null,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val displayWidth = (maxWidth.value.toInt() - 56).coerceIn(1, 4096)
    val paneResizeEpoch = LocalPaneResizeEpoch.current
    var observedPaneResizeEpoch by remember { mutableStateOf(paneResizeEpoch) }
    val currentPaneResizeEpoch by rememberUpdatedState(paneResizeEpoch)
    val currentHomeLayout by rememberUpdatedState(ui.homeLayout)
    val recommendationsListState = rememberLazyListState()
    val followingListState =
      key(state.accountUserId, state.sessionRevision) { rememberLazyListState() }
    val followedUsersScrollState =
      key(state.accountUserId, state.sessionRevision) { rememberLazyListState() }
    val authorListStates =
      remember(state.accountUserId, state.sessionRevision) {
        LinkedHashMap<String, LazyListState>()
      }
    var savedAuthorListPositions by
      rememberSaveable(
        state.accountUserId,
        state.sessionRevision,
        stateSaver = authorListPositionsSaver,
      ) {
        mutableStateOf(emptyMap())
      }
    LaunchedEffect(state.authorScopeRevision, state.evictedAuthorId) {
      state.evictedAuthorId?.let { evictedId ->
        authorListStates.keys
          .filter { it.startsWith("$evictedId\u0000") }
          .forEach(authorListStates::remove)
        savedAuthorListPositions =
          savedAuthorListPositions.filterKeys { !it.startsWith("$evictedId\u0000") }
      }
    }
    val selectedAuthorScopeKey =
      state.selectedAuthor?.let { "${it.userId}\u0000${state.selectedAuthorFeedType.name}" }
    val authorListState =
      selectedAuthorScopeKey?.let { scopeKey ->
        authorListStates
          .getOrPut(scopeKey) {
            val saved = savedAuthorListPositions[scopeKey]
            LazyListState(saved?.first ?: 0, saved?.second ?: 0)
          }
          .also {
            while (authorListStates.size > DynamicFeedViewModel.MAX_AUTHOR_SCOPES * 2) {
              val eldestKey = authorListStates.keys.first()
              if (eldestKey == scopeKey) break
              authorListStates.remove(eldestKey)
              savedAuthorListPositions = savedAuthorListPositions - eldestKey
            }
          }
      }
    LaunchedEffect(selectedAuthorScopeKey, authorListState) {
      val scopeKey = selectedAuthorScopeKey
      val activeList = authorListState
      if (scopeKey != null && activeList != null) {
        try {
          snapshotFlow {
              if (activeList.isScrollInProgress) null
              else activeList.firstVisibleItemIndex to activeList.firstVisibleItemScrollOffset
            }
            .collect { position ->
              if (position != null)
                savedAuthorListPositions =
                  savedAuthorListPositions.withBoundedAuthorScope(scopeKey, position)
            }
        } finally {
          savedAuthorListPositions =
            savedAuthorListPositions.withBoundedAuthorScope(
              scopeKey,
              activeList.firstVisibleItemIndex to activeList.firstVisibleItemScrollOffset,
            )
        }
      }
    }
    val paginationGateSaver =
      listSaver<HomeLayoutPaginationGate, String>(
        save = { listOf(it.observedLayout.name, it.suppressAutoPagination.toString()) },
        restore = {
          HomeLayoutPaginationGate(
            HomeLayout.valueOf(it[0]),
            it[1].toBooleanStrictOrNull() ?: false,
          )
        },
      )
    var recommendationGate by
      rememberSaveable(stateSaver = paginationGateSaver) {
        mutableStateOf(HomeLayoutPaginationGate(ui.homeLayout))
      }
    var followingGate by
      rememberSaveable(
        state.accountUserId,
        state.sessionRevision,
        stateSaver = paginationGateSaver,
      ) {
        mutableStateOf(HomeLayoutPaginationGate(ui.homeLayout))
      }
    var authorPaginationGates by
      rememberSaveable(
        state.accountUserId,
        state.sessionRevision,
        stateSaver = authorPaginationGatesSaver,
      ) {
        mutableStateOf(emptyMap())
      }
    val authorGate =
      selectedAuthorScopeKey?.let { scopeKey ->
        authorPaginationGates[scopeKey]
          ?: HomeLayoutPaginationGate(ui.homeLayout).suppressForPaneResize()
      }
    LaunchedEffect(state.authorScopeRevision, state.evictedAuthorId) {
      state.evictedAuthorId?.let { evictedId ->
        authorPaginationGates =
          authorPaginationGates.filterKeys { !it.startsWith("$evictedId\u0000") }
      }
    }
    val listState =
      when {
        state.mode != DynamicFeedMode.FOLLOWING -> recommendationsListState
        state.selectedAuthor != null -> authorListState ?: followingListState
        else -> followingListState
      }
    val activeGate =
      if (state.mode != DynamicFeedMode.FOLLOWING) recommendationGate
      else if (state.selectedAuthor != null) authorGate ?: followingGate else followingGate
    val currentActiveGate by rememberUpdatedState(activeGate)
    val updateActiveGate: (HomeLayoutPaginationGate.() -> HomeLayoutPaginationGate) -> Unit =
      { change ->
        if (state.mode != DynamicFeedMode.FOLLOWING)
          recommendationGate = recommendationGate.change()
        else if (selectedAuthorScopeKey != null) {
          val gate =
            authorPaginationGates[selectedAuthorScopeKey]
              ?: HomeLayoutPaginationGate(ui.homeLayout).suppressForPaneResize()
          authorPaginationGates =
            authorPaginationGates.withBoundedAuthorScope(selectedAuthorScopeKey, gate.change())
        } else followingGate = followingGate.change()
      }
    LaunchedEffect(ui.homeLayout, selectedAuthorScopeKey) {
      recommendationGate = recommendationGate.observeLayout(ui.homeLayout)
      followingGate = followingGate.observeLayout(ui.homeLayout)
      selectedAuthorScopeKey?.let { scopeKey ->
        val gate = authorPaginationGates[scopeKey] ?: HomeLayoutPaginationGate(ui.homeLayout)
        authorPaginationGates =
          authorPaginationGates.withBoundedAuthorScope(scopeKey, gate.observeLayout(ui.homeLayout))
      }
    }
    LaunchedEffect(paneResizeEpoch) {
      if (paneResizeEpoch > observedPaneResizeEpoch) {
        recommendationGate = recommendationGate.suppressForPaneResize()
        followingGate = followingGate.suppressForPaneResize()
        authorPaginationGates =
          authorPaginationGates.mapValues { (_, gate) -> gate.suppressForPaneResize() }
        selectedAuthorScopeKey?.let { scopeKey ->
          val gate = authorPaginationGates[scopeKey] ?: HomeLayoutPaginationGate(ui.homeLayout)
          authorPaginationGates =
            authorPaginationGates.withBoundedAuthorScope(scopeKey, gate.suppressForPaneResize())
        }
      }
      observedPaneResizeEpoch = paneResizeEpoch
    }
    LaunchedEffect(listState, state.mode) {
      listState.interactionSource.interactions.collect { interaction ->
        if (interaction is DragInteraction.Start) updateActiveGate { userScrolled() }
      }
    }
    val currentRecommendations by rememberUpdatedState(state.recommendations)
    val currentFollowing by rememberUpdatedState(state.following)
    val currentAuthorContent by rememberUpdatedState(state.selectedAuthorContent)
    val recommendationBlocking =
      rememberContentBlockingList(
        raw = state.recommendations.items,
        scope = ContentBlockingScope.FEED,
        identity = "dynamic:recommended",
        isCurrentPage = isCurrentPage && state.mode == DynamicFeedMode.RECOMMENDED,
        listState = recommendationsListState,
      )
    val followedStories =
      if (state.selectedAuthor != null) state.selectedAuthorContent?.stories.orEmpty()
      else state.following.items
    val followedIdentity =
      if (state.selectedAuthor != null)
        "dynamic:author:${state.selectedAuthor.userId}:${state.selectedAuthorFeedType.name}"
      else "dynamic:following:${state.accountUserId.orEmpty()}"
    val followedListState =
      if (state.selectedAuthor != null) authorListState ?: followingListState
      else followingListState
    val followedBlocking =
      rememberContentBlockingList(
        raw = followedStories,
        scope = ContentBlockingScope.FEED,
        identity = followedIdentity,
        isCurrentPage = isCurrentPage && state.mode == DynamicFeedMode.FOLLOWING,
        listState = followedListState,
      )
    val currentRecommendationBlocking by rememberUpdatedState(recommendationBlocking)
    val currentFollowedBlocking by rememberUpdatedState(followedBlocking)
    cancelSharedCoverOnScroll(
      listState,
      LocalSharedCoverEntryId.current,
      LocalSharedCoverCoordinator.current,
    )
    DisposableEffect(viewModel) { onDispose { viewModel.pauseCurrentMode() } }
    LaunchedEffect(
      state.mode,
      isCurrentPage,
      displayWidth,
      state.accountUserId,
      state.sessionRevision,
      state.sessionRestoring,
    ) {
      if (!isCurrentPage) {
        viewModel.pauseCurrentMode()
        return@LaunchedEffect
      }
      when (state.mode) {
        DynamicFeedMode.RECOMMENDED -> viewModel.ensureLoaded(displayWidth)
        DynamicFeedMode.FOLLOWING -> viewModel.ensureFollowingLoaded()
      }
    }
    LaunchedEffect(
      state.mode,
      state.selectedAuthor?.userId,
      state.selectedAuthorFeedType,
      isCurrentPage,
      listState,
      displayWidth,
    ) {
      snapshotFlow {
          val layoutChanged = currentActiveGate.observedLayout != currentHomeLayout
          val paneResizePending = currentPaneResizeEpoch > observedPaneResizeEpoch
          (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to
            (currentActiveGate.suppressAutoPagination || layoutChanged || paneResizePending)
        }
        .collect { (last, paginationSuppressed) ->
          if (!isCurrentPage || listState.layoutInfo.totalItemsCount - last > 3) return@collect
          if (paginationSuppressed) return@collect
          when (state.mode) {
            DynamicFeedMode.RECOMMENDED -> {
              val feed = currentRecommendations
              if (
                currentRecommendationBlocking.canAutoPaginate &&
                  feed.items.isNotEmpty() &&
                  feed.hasMore &&
                  !feed.loading &&
                  !feed.refreshing &&
                  !feed.loadingMore &&
                  feed.error == null
              )
                viewModel.loadMore(displayWidth)
            }
            DynamicFeedMode.FOLLOWING -> {
              if (state.selectedAuthor != null) {
                val content = currentAuthorContent
                if (
                  currentFollowedBlocking.canAutoPaginate &&
                    content != null &&
                    content.stories.isNotEmpty() &&
                    content.hasMore &&
                    !content.loading &&
                    !content.refreshing &&
                    !content.loadingMore &&
                    content.error == null &&
                    !content.captchaRequired
                )
                  viewModel.loadMoreSelectedAuthor()
              } else {
                val feed = currentFollowing
                if (
                  currentFollowedBlocking.canAutoPaginate &&
                    state.accountUserId != null &&
                    !state.sessionRestoring &&
                    feed.items.isNotEmpty() &&
                    feed.hasMore &&
                    !feed.loading &&
                    !feed.refreshing &&
                    !feed.loadingMore &&
                    feed.error == null
                )
                  viewModel.loadMoreFollowing()
              }
            }
          }
        }
    }
    Column(Modifier.fillMaxSize().pageInputGate(isCurrentPage).testTag("dynamic-feed-screen")) {
      PageTopBar("动态", isRoot = true, onBack = {})
      Row(
        Modifier.fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = state.mode == DynamicFeedMode.FOLLOWING,
          onClick = { viewModel.selectMode(DynamicFeedMode.FOLLOWING) },
          label = { Text("关注") },
          modifier = Modifier.testTag("dynamic-mode-following"),
          colors = dynamicFilterChipColors(),
        )
        FilterChip(
          selected = state.mode == DynamicFeedMode.RECOMMENDED,
          onClick = { viewModel.selectMode(DynamicFeedMode.RECOMMENDED, displayWidth) },
          label = { Text("推荐") },
          modifier = Modifier.testTag("dynamic-mode-recommended"),
          colors = dynamicFilterChipColors(),
        )
      }
      when (state.mode) {
        DynamicFeedMode.FOLLOWING -> {
          when {
            state.sessionRestoring ->
              MessagePanel("正在恢复账号状态…", modifier = Modifier.testTag("dynamic-follow-restoring"))
            state.accountUserId == null ->
              Column(
                Modifier.fillMaxSize()
                  .padding(horizontal = 24.dp, vertical = 20.dp)
                  .testTag("dynamic-follow-login-required"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text("登录后读取关注动态", style = MaterialTheme.typography.titleMedium)
                Text(
                  "关注动态来自小黑盒账号接口，不会用公开推荐内容代替。",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLogin, modifier = Modifier.height(48.dp)) { Text("登录小黑盒") }
              }
            else ->
              FollowedFeedList(
                state = state.following,
                ui = ui,
                listState = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                isRefreshing =
                  state.followedUsers.refreshing ||
                    if (state.selectedAuthor != null)
                      state.selectedAuthorContent?.refreshing == true
                    else state.following.refreshing,
                header = {
                  item(key = "dynamic-followed-users") {
                    FollowedUsersHeader(
                      state = state.followedUsers,
                      noImages = ui.noImages,
                      selectedUser = state.selectedAuthor,
                      listState = followedUsersScrollState,
                      onSelectUser = viewModel::selectAuthor,
                      onRetry = viewModel::retryFollowedUsers,
                    )
                  }
                  if (state.selectedAuthor != null) {
                    item(key = "dynamic-author-heading") {
                      AuthorFeedHeading(
                        author = state.selectedAuthor,
                        feedType = state.selectedAuthorFeedType,
                        onSelectFeedType = viewModel::selectAuthorFeedType,
                      )
                    }
                  }
                },
                onRefresh = {
                  if (state.selectedAuthor == null) viewModel.refreshFollowing()
                  else viewModel.refreshSelectedAuthor()
                  viewModel.refreshFollowedUsers()
                },
                onRetry = viewModel::retryFollowing,
                onLoadMore = {
                  updateActiveGate { userScrolled() }
                  if (state.selectedAuthor == null) viewModel.loadMoreFollowing()
                  else viewModel.loadMoreSelectedAuthor()
                },
                onOpenStory = onOpenStory,
                selectedStoryIdentity = selectedStoryIdentity,
                authorSelected = state.selectedAuthor != null,
                authorContent = state.selectedAuthorContent,
                blocking = followedBlocking,
                onAuthorRetry = viewModel::retrySelectedAuthor,
                onAuthorOpenCaptcha = {
                  val token = state.selectedAuthorScopeToken
                  val authorId = state.selectedAuthor?.userId
                  if (token != null && authorId != null)
                    viewModel.openSelectedAuthorCaptcha(
                      token,
                      authorId,
                      state.selectedAuthorFeedType,
                    )
                },
                onAuthorRetryAfterOfficialVerification =
                  viewModel::retrySelectedAuthorAfterOfficialVerification,
                onAuthorCaptchaDismiss = {
                  val token = state.selectedAuthorScopeToken
                  val authorId = state.selectedAuthor?.userId
                  val content = state.selectedAuthorContent
                  val challengeId = content?.captchaChallengeId
                  val attemptId = content?.captchaAttemptId
                  if (token != null && authorId != null && challengeId != null && attemptId != null)
                    viewModel.closeSelectedAuthorCaptcha(
                      token,
                      authorId,
                      state.selectedAuthorFeedType,
                      challengeId,
                      attemptId,
                    )
                },
                onAuthorCaptchaResult = { attemptId, result ->
                  val token = state.selectedAuthorScopeToken
                  val authorId = state.selectedAuthor?.userId
                  val challengeId = state.selectedAuthorContent?.captchaChallengeId
                  if (token != null && authorId != null && challengeId != null)
                    viewModel.onSelectedAuthorCaptchaCallback(
                      token,
                      authorId,
                      state.selectedAuthorFeedType,
                      challengeId,
                      attemptId,
                      result,
                    )
                },
              )
          }
        }
        DynamicFeedMode.RECOMMENDED ->
          RecommendedFeedList(
            state = state.recommendations,
            ui = ui,
            listState = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            displayWidth = displayWidth,
            viewModel = viewModel,
            onOpenStory = onOpenStory,
            selectedStoryIdentity = selectedStoryIdentity,
            blocking = recommendationBlocking,
            onManualLoadMore = { updateActiveGate { userScrolled() } },
          )
      }
    }
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RecommendedFeedList(
  state: FeedPageState,
  ui: UiState,
  listState: androidx.compose.foundation.lazy.LazyListState,
  modifier: Modifier,
  displayWidth: Int,
  viewModel: DynamicFeedViewModel,
  onOpenStory: (Story) -> Unit,
  selectedStoryIdentity: String?,
  blocking: cn.heyread.app.ui.components.ContentBlockingListState,
  onManualLoadMore: () -> Unit,
) {
  PullToRefreshBox(
    isRefreshing = state.refreshing,
    onRefresh = { viewModel.refresh(displayWidth) },
    modifier = modifier.testTag("dynamic-recommend-list"),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item(key = "dynamic-recommend-source") {
        Text(
          "来自小黑盒公开推荐",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("dynamic-recommend-source"),
        )
      }
      if (blocking.loading || blocking.error != null || blocking.matchedCount > 0) {
        item(key = "dynamic-recommend-blocking-notice") { ContentBlockingNotice(blocking) }
      }
      state.error?.let { error ->
        item(key = "dynamic-recommend-error") {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { viewModel.retryRecommendations(displayWidth) }) { Text("重试") }
          }
        }
      }
      if (state.loading && state.items.isEmpty()) {
        item(key = "dynamic-recommend-loading") { LoadingIndicator("dynamic-recommend-loading") }
      }
      if (!state.loading && state.error == null && state.items.isEmpty()) {
        item(key = "dynamic-recommend-empty") {
          Text("暂无推荐内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      itemsIndexed(blocking.items, key = { _, story -> "${story.source}:${story.id}" }) {
        index,
        story ->
        DynamicFeedCard(index, story, ui, onOpenStory, selectedStoryIdentity)
      }
      addFeedLoadMore(
        state.loadingMore,
        state.hasMore && state.items.isNotEmpty() && state.error == null,
      ) {
        onManualLoadMore()
        blocking.beforeManualLoad()
        viewModel.loadMore(displayWidth)
      }
    }
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FollowedFeedList(
  state: cn.heyread.app.viewmodel.DynamicActivityFeedState,
  ui: UiState,
  listState: androidx.compose.foundation.lazy.LazyListState,
  modifier: Modifier,
  isRefreshing: Boolean,
  header: LazyListScope.() -> Unit,
  onRefresh: () -> Unit,
  onRetry: () -> Unit,
  onLoadMore: () -> Unit,
  onOpenStory: (Story) -> Unit,
  selectedStoryIdentity: String?,
  authorSelected: Boolean,
  authorContent: DestinationUiState?,
  blocking: cn.heyread.app.ui.components.ContentBlockingListState,
  onAuthorRetry: () -> Unit,
  onAuthorOpenCaptcha: () -> Unit,
  onAuthorRetryAfterOfficialVerification: () -> Unit,
  onAuthorCaptchaDismiss: () -> Unit,
  onAuthorCaptchaResult: (Long, CaptchaCallbackResult) -> Unit,
) {
  PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    modifier = modifier.testTag("dynamic-follow-list"),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      header()
      item(key = "dynamic-follow-source") {
        Text(
          if (authorSelected) {
            if (authorContent?.feedType == DestinationFeedType.MOMENTS) "来自作者的公开动态" else "来自作者的公开投稿"
          } else "来自小黑盒账号关注动态",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
            Modifier.testTag(
              if (authorSelected) "dynamic-author-feed-source" else "dynamic-follow-source"
            ),
        )
      }
      if (blocking.loading || blocking.error != null || blocking.matchedCount > 0) {
        item(key = "dynamic-follow-blocking-notice") { ContentBlockingNotice(blocking) }
      }
      (authorContent?.error ?: if (!authorSelected) state.error else null)?.let { error ->
        item(key = if (authorSelected) "dynamic-author-error" else "dynamic-follow-error") {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = if (authorSelected) onAuthorRetry else onRetry) { Text("重试") }
          }
        }
      }
      if (authorContent?.captchaRequired == true) {
        item(key = "dynamic-author-captcha-required") {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("小黑盒要求完成验证后才能读取当前内容。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (authorContent.captchaVerifying) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在向小黑盒核验并读取…", color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            } else {
              authorContent.captchaMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
              }
              TextButton(onClick = onAuthorOpenCaptcha) {
                Text(if (authorContent.captchaMessage == null) "打开验证" else "重新验证")
              }
              TextButton(onClick = onAuthorRetryAfterOfficialVerification) {
                Text("已在官方客户端验证，尝试重读")
              }
            }
          }
        }
      }
      val activeAuthorContent = authorContent?.takeIf { authorSelected }
      val loading = if (authorSelected) activeAuthorContent?.loading ?: true else state.loading
      val rawStories = if (authorSelected) activeAuthorContent?.stories.orEmpty() else state.items
      val stories = blocking.items
      val error = activeAuthorContent?.error ?: if (!authorSelected) state.error else null
      val hasMore = activeAuthorContent?.hasMore ?: if (authorSelected) false else state.hasMore
      val loadingMore = activeAuthorContent?.loadingMore ?: false
      if (loading && stories.isEmpty()) {
        item(key = if (authorSelected) "dynamic-author-loading" else "dynamic-follow-loading") {
          LoadingIndicator(
            if (authorSelected) "dynamic-author-loading" else "dynamic-follow-loading"
          )
        }
      }
      if (
        !loading &&
          error == null &&
          rawStories.isEmpty() &&
          activeAuthorContent?.captchaRequired != true
      ) {
        item(key = if (authorSelected) "dynamic-author-empty" else "dynamic-follow-empty") {
          Text(
            if (authorSelected) "暂无内容" else "关注动态为空",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      itemsIndexed(stories, key = { _, story -> "${story.source}:${story.id}" }) { index, story ->
        DynamicFeedCard(index, story, ui, onOpenStory, selectedStoryIdentity)
      }
      addFeedLoadMore(
        loadingMore,
        hasMore &&
          rawStories.isNotEmpty() &&
          error == null &&
          activeAuthorContent?.captchaRequired != true,
        {
          blocking.beforeManualLoad()
          onLoadMore()
        },
      )
    }
  }
  val content = authorContent?.takeIf { authorSelected }
  val challengeId = content?.captchaChallengeId
  val attemptId = content?.captchaAttemptId
  val appId = content?.captchaAppId
  if (
    content?.captchaDialogOpen == true && challengeId != null && attemptId != null && appId != null
  ) {
    CaptchaChallengeDialog(
      appId = appId,
      attemptId = attemptId,
      onCallback = { callbackAttempt, result -> onAuthorCaptchaResult(callbackAttempt, result) },
      onDismiss = onAuthorCaptchaDismiss,
    )
  }
}

@Composable
private fun FollowedUsersHeader(
  state: FollowedUsersState,
  noImages: Boolean,
  selectedUser: FollowedUser?,
  listState: LazyListState,
  onSelectUser: (String?) -> Unit,
  onRetry: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().testTag("dynamic-followed-users-header"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(
        Modifier.widthIn(min = 68.dp)
          .heightIn(min = 72.dp)
          .clickable { onSelectUser(null) }
          .semantics {
            contentDescription = "全部关注用户动态"
            selected = selectedUser == null
          }
          .testTag("dynamic-followed-user-all"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color =
            if (selectedUser == null) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Text(
            "全部",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color =
              if (selectedUser == null) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }
      LazyRow(
        modifier = Modifier.weight(1f).testTag("dynamic-followed-user-row"),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 8.dp),
      ) {
        items(state.users.size, key = { index -> state.users[index].userId }) { index ->
          val user = state.users[index]
          FollowedUserChip(
            user = user,
            noImages = noImages,
            selected = selectedUser?.userId == user.userId,
            onClick = { onSelectUser(user.userId) },
          )
        }
      }
    }
    if (state.loading && state.users.isEmpty()) {
      Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("正在读取关注用户", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (state.error != null) {
      Row(
        Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          if (state.users.isEmpty()) state.error else "关注用户刷新失败：${state.error}",
          Modifier.weight(1f),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onRetry) { Text("重试") }
      }
    } else if (state.hasRequested && state.users.isEmpty() && !state.loading) {
      Text(
        "暂无可显示的关注用户",
        Modifier.padding(start = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (state.hasMore) {
      Text(
        "仅显示部分关注用户",
        Modifier.padding(start = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun FollowedUserChip(
  user: FollowedUser,
  noImages: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val name = user.displayName.ifBlank { "未知用户" }
  val avatarUrl = user.avatarUrl.takeUnless { noImages }
  var imageLoaded by remember(user.userId, avatarUrl) { mutableStateOf(false) }
  Column(
    Modifier.widthIn(min = 64.dp)
      .heightIn(min = 72.dp)
      .clickable(onClick = onClick)
      .semantics {
        contentDescription = buildString {
          append(name)
          if (selected) append("，已选中")
        }
        this.selected = selected
      }
      .testTag("dynamic-followed-user-${user.userId}"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
      Box(
        Modifier.align(Alignment.Center)
          .size(48.dp)
          .then(
            if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            else Modifier
          )
          .padding(if (selected) 2.dp else 0.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          name.firstOrNull()?.toString() ?: "?",
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          style = MaterialTheme.typography.titleMedium,
        )
        if (avatarUrl != null) {
          key(user.userId, avatarUrl) {
            AnimatedImage(
              model = avatarUrl,
              contentDescription = "$name 头像",
              contentScale = ContentScale.Crop,
              modifier =
                Modifier.fillMaxSize().clip(CircleShape).alpha(if (imageLoaded) 1f else 0f),
              onState = { painterState ->
                imageLoaded = painterState is AsyncImagePainter.State.Success
              },
            )
          }
        }
      }
    }
    Text(
      name,
      Modifier.widthIn(max = 72.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
private fun AuthorFeedHeading(
  author: FollowedUser,
  feedType: DestinationFeedType,
  onSelectFeedType: (DestinationFeedType) -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().testTag("dynamic-author-feed-heading"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      author.displayName.ifBlank { "未知用户" },
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("dynamic-author-feed-name"),
    )
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = feedType == DestinationFeedType.MOMENTS,
        onClick = { onSelectFeedType(DestinationFeedType.MOMENTS) },
        label = { Text("动态") },
        modifier = Modifier.testTag("dynamic-author-type-moments"),
        colors = dynamicFilterChipColors(),
      )
      FilterChip(
        selected = feedType == DestinationFeedType.ARTICLES,
        onClick = { onSelectFeedType(DestinationFeedType.ARTICLES) },
        label = { Text("投稿") },
        modifier = Modifier.testTag("dynamic-author-type-articles"),
        colors = dynamicFilterChipColors(),
      )
    }
  }
}

@Composable
private fun dynamicFilterChipColors() =
  FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
  )

@Composable
private fun DynamicFeedCard(
  index: Int,
  story: Story,
  ui: UiState,
  onOpenStory: (Story) -> Unit,
  selectedStoryIdentity: String?,
) {
  HomeStoryCard(
    story = story,
    layout = ui.homeLayout,
    featured = index == 0,
    noImages = ui.noImages,
    onOpen = onOpenStory,
    selected = "${story.source}:${story.id}" == selectedStoryIdentity,
  )
}

private fun LazyListScope.addFeedLoadMore(
  loading: Boolean,
  visible: Boolean,
  onLoadMore: () -> Unit,
) {
  if (loading) {
    item(key = "dynamic-feed-load-more-loading") {
      LoadingIndicator("dynamic-feed-load-more-loading")
    }
  } else if (visible) {
    item(key = "dynamic-feed-load-more") {
      TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
    }
  }
}

@Composable
private fun MessagePanel(message: String, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(message, style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun LoadingIndicator(tag: String) {
  Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.testTag(tag))
  }
}

private fun <T> Map<String, T>.withBoundedAuthorScope(key: String, value: T): Map<String, T> {
  val updated = LinkedHashMap(this)
  updated.remove(key)
  updated[key] = value
  while (updated.size > DynamicFeedViewModel.MAX_AUTHOR_SCOPES * 2) {
    updated.remove(updated.keys.first())
  }
  return updated
}

private val authorListPositionsSaver =
  listSaver<Map<String, Pair<Int, Int>>, String>(
    save = { positions ->
      positions.flatMap { (key, position) ->
        listOf(key, position.first.toString(), position.second.toString())
      }
    },
    restore = { saved ->
      saved
        .chunked(3)
        .mapNotNull { values ->
          val index = values.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
          val offset = values.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
          values[0] to (index to offset)
        }
        .toMap()
    },
  )

private val authorPaginationGatesSaver =
  listSaver<Map<String, HomeLayoutPaginationGate>, String>(
    save = { gates ->
      gates.flatMap { (key, gate) ->
        listOf(key, gate.observedLayout.name, gate.suppressAutoPagination.toString())
      }
    },
    restore = { saved ->
      saved
        .chunked(3)
        .mapNotNull { values ->
          val layout =
            runCatching { HomeLayout.valueOf(values[1]) }.getOrNull() ?: return@mapNotNull null
          val suppress = values[2].toBooleanStrictOrNull() ?: return@mapNotNull null
          values[0] to HomeLayoutPaginationGate(layout, suppress)
        }
        .toMap()
    },
  )
