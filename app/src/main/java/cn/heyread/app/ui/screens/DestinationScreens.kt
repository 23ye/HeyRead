package cn.heyread.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockTarget
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.canonicalContentBlockTargetId
import cn.heyread.app.data.model.ContentBlockingScope
import cn.heyread.app.data.remote.DestinationFeedType
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.ui.components.CaptchaChallengeDialog
import cn.heyread.app.ui.components.ContentBlockingNotice
import cn.heyread.app.ui.components.ContentBlockingQuickAdd
import cn.heyread.app.ui.components.LocalContentBlockingContext
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.components.ProfileRow
import cn.heyread.app.ui.components.SectionLabels
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.motion.LocalMotionEnabled
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.StaggeredCard
import cn.heyread.app.ui.motion.cancelSharedCoverOnScroll
import cn.heyread.app.ui.motion.pageInputGate
import cn.heyread.app.ui.motion.rememberFirstVisibleBatch
import cn.heyread.app.viewmodel.DestinationViewModel
import cn.heyread.app.viewmodel.UiState
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DestinationScreen(
  target: DestinationTarget,
  viewModel: DestinationViewModel,
  ui: UiState,
  onBack: () -> Unit,
  onOpenStory: (cn.heyread.app.data.model.Story) -> Unit,
  onOpenSection: (ArticleLabel) -> Unit,
  onSearch: () -> Unit = {},
  onContentBlockingAction: (ContentBlockingAction) -> Unit = {},
  isCurrentPage: Boolean = true,
  selectedStoryIdentity: String? = null,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val canonicalRouteId = runCatching { canonicalContentBlockTargetId(target.id) }.getOrNull()
  val destinationDataMatchesTarget = state.target == target
  val currentProfileId =
    state.data?.profile?.userId?.takeIf { destinationDataMatchesTarget && it.isNotBlank() }
  val canonicalProfileId =
    currentProfileId?.let { runCatching { canonicalContentBlockTargetId(it) }.getOrNull() }
  val routeProfileIdMismatch =
    target.type == DestinationType.USER &&
      destinationDataMatchesTarget &&
      canonicalProfileId != null &&
      canonicalRouteId != canonicalProfileId
  val quickBlockAllowed = canonicalRouteId != null && !routeProfileIdMismatch
  val quickBlockTarget =
    remember(target, canonicalRouteId, state.data?.title, destinationDataMatchesTarget) {
      val kind =
        when (target.type) {
          DestinationType.USER -> ContentBlockKind.AUTHOR
          DestinationType.SECTION -> ContentBlockKind.SECTION
          DestinationType.HASHTAG -> ContentBlockKind.TOPIC
        }
      ContentBlockTarget(
        kind,
        canonicalRouteId.orEmpty(),
        if (destinationDataMatchesTarget) state.data?.title ?: target.name else target.name,
      )
    }
  LaunchedEffect(isCurrentPage) { if (!isCurrentPage) viewModel.closeCaptchaDialog() }
  val stateHolder = rememberSaveableStateHolder()
  val context = LocalContext.current
  val officialPageError = remember(target.type, target.id) { mutableStateOf<String?>(null) }
  val motion = LocalMotionEnabled.current
  val contentBlockingSessionKey = LocalContentBlockingContext.current.sessionKey
  LaunchedEffect(target) { viewModel.show(target) }
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  androidx.compose.runtime.DisposableEffect(viewModel, target, lifecycleOwner) {
    val observer =
      androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) viewModel.closeCaptchaDialog()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.closeCaptchaDialog()
    }
  }
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar(
      state.data?.title ?: target.name.ifBlank { destinationTitle(target.type) },
      isRoot = false,
      onBack = {
        viewModel.cancelCaptcha(target)
        onBack()
      },
    ) {
      if (target.type != DestinationType.USER) {
        TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) {
          Text(if (target.type == DestinationType.SECTION) "搜索本分区" else "搜索本话题")
        }
      }
      if (state.captchaRequired) {
        TextButton(
          onClick = viewModel::openCaptchaDialog,
          enabled = !state.captchaVerifying,
          modifier = Modifier.heightIn(min = 48.dp).testTag("captcha-start-topbar"),
        ) {
          Text(if (state.captchaVerifying) "验证中" else "开始验证")
        }
      }
      ContentBlockingQuickAdd(
        targets = if (quickBlockAllowed) listOf(quickBlockTarget) else emptyList(),
        ui = ui,
        isCurrentPage = isCurrentPage,
        identityKey =
          "${target.type}:${target.id}:${contentBlockingSessionKey}:${if (destinationDataMatchesTarget) state.data?.profile?.userId.orEmpty() else ""}",
        preferTarget = true,
        label = "屏蔽此${quickBlockTarget.kind.label}",
        disabled = !ui.contentBlockingLoaded || !quickBlockAllowed,
        onAction = onContentBlockingAction,
      )
    }
    PullToRefreshBox(
      isRefreshing = state.refreshing,
      onRefresh = viewModel::refresh,
      modifier = Modifier.weight(1f),
    ) {
      val stateKey = "${target.type}:${target.id}:${state.feedType}"
      // Dispose each tab's regular Lazy and draw layers while preserving its saveable list position.
      key(stateKey) {
        stateHolder.SaveableStateProvider(stateKey) {
        val listState = rememberLazyListState()
        cancelSharedCoverOnScroll(
          listState,
          LocalSharedCoverEntryId.current,
          LocalSharedCoverCoordinator.current,
        )
        val listIdentity = "destination:${target.type}:${target.id}:${state.feedType}"
        val blocking =
          rememberContentBlockingList(
            raw = state.stories,
            scope = ContentBlockingScope.FEED,
            identity = listIdentity,
            isCurrentPage = isCurrentPage,
            listState = listState,
          )
        val paneResizeEpoch = LocalPaneResizeEpoch.current
        var resizePaginationSuppressed by rememberSaveable(listIdentity) { mutableStateOf(false) }
        LaunchedEffect(paneResizeEpoch) {
          if (paneResizeEpoch > 0) resizePaginationSuppressed = true
        }
        LaunchedEffect(listState) {
          listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) resizePaginationSuppressed = false
          }
        }
        val storyKeys =
          remember(blocking.items) { blocking.items.map { "${it.source}:${it.id}" }.toSet() }
        val firstBatch = rememberFirstVisibleBatch(listIdentity, listState, storyKeys, motion)
        val animatedKeys =
          androidx.compose.runtime.saveable.rememberSaveable(
            listIdentity,
            saver =
              androidx.compose.runtime.saveable.listSaver(
                { it.toList() },
                { it.toMutableStateList() },
              ),
          ) {
            androidx.compose.runtime.mutableStateListOf<String>()
          }
        val paginationState = state
        LaunchedEffect(
          listState,
          target,
          listIdentity,
          paginationState.feedType,
          paginationState.stories,
          paginationState.loading,
          paginationState.refreshing,
          paginationState.loadingMore,
          paginationState.hasMore,
          paginationState.error,
          paginationState.captchaRequired,
          blocking.canAutoPaginate,
          isCurrentPage,
          resizePaginationSuppressed,
        ) {
          snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
              val latest = viewModel.state.value
              if (
                isCurrentPage &&
                  !resizePaginationSuppressed &&
                  blocking.canAutoPaginate &&
                  latest.target == target &&
                  latest.feedType == paginationState.feedType &&
                  (latest.stories === paginationState.stories ||
                    latest.stories == paginationState.stories) &&
                  latest.hasMore &&
                  !latest.loading &&
                  !latest.refreshing &&
                  !latest.loadingMore &&
                  latest.error == null &&
                  !latest.captchaRequired &&
                  paginationState.hasMore &&
                  !paginationState.captchaRequired &&
                  paginationState.error == null &&
                  !paginationState.loading &&
                  !paginationState.refreshing &&
                  !paginationState.loadingMore &&
                  paginationState.stories.isNotEmpty() &&
                  listState.layoutInfo.totalItemsCount - last <= 3
              )
                viewModel.loadMore()
            }
        }
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (blocking.loading || blocking.error != null || blocking.matchedCount > 0) {
            item(key = "destination-content-blocking-notice") { ContentBlockingNotice(blocking) }
          }
          state.data?.notice?.let { notice ->
            item {
              Text(
                notice,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          if (routeProfileIdMismatch) {
            item(key = "destination-blocking-profile-mismatch") {
              Text(
                "当前作者资料ID与打开的主页ID不一致，不能从此页快捷屏蔽。",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("destination-blocking-profile-mismatch"),
              )
            }
          }
          if (target.type == DestinationType.USER) {
            item { DestinationUserHeader(state.data, target, ui.noImages) }
          } else if (target.type == DestinationType.SECTION) {
            item {
              SectionLabels(
                listOf(
                  ArticleLabel(target.id, state.data?.title ?: target.name, state.data?.iconUrl)
                ),
                ui.noImages,
                modifier = Modifier.testTag("destination-section-header"),
              )
            }
          } else if (target.type == DestinationType.HASHTAG) {
            item {
              Text(
                "#${state.data?.title ?: target.name}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
              )
              state.data?.description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
          if (target.type == DestinationType.USER) {
            stickyHeader(key = "destination-user-tabs") {
              TabRow(
                selectedTabIndex = if (state.feedType == DestinationFeedType.MOMENTS) 0 else 1,
                modifier =
                  Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("destination-user-tabs"),
              ) {
                Tab(
                  selected = state.feedType == DestinationFeedType.MOMENTS,
                  onClick = { viewModel.selectFeed(DestinationFeedType.MOMENTS) },
                  text = { Text("动态") },
                  modifier = Modifier.testTag("destination-tab-moments"),
                )
                Tab(
                  selected = state.feedType == DestinationFeedType.ARTICLES,
                  onClick = { viewModel.selectFeed(DestinationFeedType.ARTICLES) },
                  text = { Text("投稿") },
                  modifier = Modifier.testTag("destination-tab-articles"),
                )
              }
            }
          }
          if (state.captchaRequired) {
            item(key = "destination-captcha-notice") {
              Column(
                Modifier.fillMaxWidth()
                  .background(MaterialTheme.colorScheme.errorContainer)
                  .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  "小黑盒需要人机验证",
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  "验证期间已暂停刷新与加载，已显示的内容会保留。",
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.bodySmall,
                )
                state.captchaMessage?.let { message ->
                  Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
                if (state.captchaVerifying) {
                  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(
                      " 正在验证并读取内容…",
                      Modifier.padding(start = 8.dp),
                      color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                  }
                } else {
                  Button(
                    onClick = viewModel::openCaptchaDialog,
                    modifier = Modifier.heightIn(min = 48.dp),
                  ) {
                    Text("开始验证")
                  }
                  if (target.type == DestinationType.USER) {
                    TextButton(
                      onClick = {
                        val url =
                          Uri.Builder()
                            .scheme("https")
                            .authority("www.xiaoheihe.cn")
                            .appendPath("app")
                            .appendPath("user")
                            .appendPath("profile")
                            .appendPath(target.id)
                            .build()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                          .onFailure { officialPageError.value = "无法打开浏览器。你可以继续在应用内验证，或稍后重试。" }
                      },
                      modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                      Text("打开官方主页")
                    }
                    Text(
                      "官方页面验证后可返回尝试，仍可能需要应用内验证。",
                      color = MaterialTheme.colorScheme.onErrorContainer,
                      style = MaterialTheme.typography.bodySmall,
                    )
                    officialPageError.value?.let { message ->
                      Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                      )
                    }
                    TextButton(
                      onClick = viewModel::retryAfterOfficialVerification,
                      modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                      Text("我已在官方页面验证，重新读取")
                    }
                  }
                }
              }
            }
          }
          if (state.loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
          state.error?.let { message ->
            item {
              Column {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry) { Text("重试") }
              }
            }
          }
          if (
            !state.loading &&
              state.error == null &&
              !state.captchaRequired &&
              state.stories.isEmpty()
          ) {
            val emptyMessage =
              if (target.type == DestinationType.USER)
                if (state.feedType == DestinationFeedType.MOMENTS) "暂无公开动态" else "暂无公开投稿"
              else "暂无公开文章"
            item { Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          }
          itemsIndexed(blocking.items, key = { _, story -> "${story.source}:${story.id}" }) {
            index,
            story ->
            StaggeredCard("${story.source}:${story.id}", firstBatch, animatedKeys, motion) {
              Column {
                StoryRow(
                  story,
                  ui.noImages,
                  { opened ->
                    viewModel.cancelCaptcha(target)
                    onOpenStory(opened)
                  },
                  onOpenSection,
                  selected = "${story.source}:${story.id}" == selectedStoryIdentity,
                )
                HorizontalDivider(Modifier.padding(top = 10.dp))
              }
            }
          }
          if (state.loadingMore) item { CircularProgressIndicator(Modifier.size(24.dp)) }
          if (
            state.hasMore &&
              state.stories.isNotEmpty() &&
              !state.loading &&
              state.error == null &&
              !blocking.loading &&
              !state.captchaRequired &&
              !blocking.canAutoPaginate &&
              !state.loadingMore
          ) {
            item(key = "destination-content-blocking-continue") {
              TextButton(
                onClick = {
                  blocking.beforeManualLoad()
                  viewModel.loadMore()
                },
                modifier =
                  Modifier.heightIn(min = 48.dp).testTag("destination-content-blocking-continue"),
              ) {
                Text("继续加载")
              }
            }
          }
          if (state.error != null && state.stories.isNotEmpty()) {
            item { TextButton(onClick = viewModel::retry) { Text("重试加载") } }
          }
        }
        }
      }
    }
  }
  val challengeId = state.captchaChallengeId
  val attemptId = state.captchaAttemptId
  val appId = state.captchaAppId
  if (state.captchaDialogOpen && challengeId != null && attemptId != null && appId != null) {
    CaptchaChallengeDialog(
      appId = appId,
      attemptId = attemptId,
      onCallback = { callbackAttempt, result ->
        viewModel.onCaptchaCallback(challengeId, callbackAttempt, result)
      },
      onDismiss = viewModel::closeCaptchaDialog,
    )
  }
}

@Composable
fun DestinationUserHeader(
  data: cn.heyread.app.data.remote.DestinationData?,
  target: DestinationTarget,
  noImages: Boolean,
) {
  Column(Modifier.fillMaxWidth().testTag("destination-user-profile")) {
    if (data?.profile != null) ProfileRow(data.profile, data.title, noImages)
    else Text(data?.title ?: target.name.ifBlank { "用户主页" }, fontWeight = FontWeight.Bold)
    Text(
      "ID ${data?.profile?.userId ?: target.id}",
      Modifier.padding(start = 4.dp, top = 2.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    val stats = data?.profileStats
    Row(
      Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      ProfileStat("关注", stats?.following)
      ProfileStat("粉丝", stats?.followers)
      ProfileStat("获赞与收藏", stats?.receivedLikesAndFavorites)
    }
    data?.description?.takeIf(String::isNotBlank)?.let {
      Text(
        it,
        Modifier.padding(start = 4.dp, top = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
  }
}

@Composable
private fun RowScope.ProfileStat(label: String, value: Long?) {
  Column(
    Modifier.weight(1f).testTag("profile-stat-$label").semantics {
      contentDescription = "$label ${value ?: "—"}"
    },
    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
  ) {
    Text(
      value?.toString() ?: "—",
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
    Text(
      label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 2,
      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
  }
}

private fun destinationTitle(type: DestinationType) =
  when (type) {
    DestinationType.SECTION -> "分区"
    DestinationType.HASHTAG -> "话题"
    DestinationType.USER -> "用户主页"
  }
