package cn.heyread.app.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import cn.heyread.app.data.account.ReplyTarget
import cn.heyread.app.data.account.SharedArticleContentParser
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.media.MediaImage
import cn.heyread.app.data.model.*
import cn.heyread.app.data.translation.SteamTranslationPolicy
import cn.heyread.app.ui.components.AnimatedImage
import cn.heyread.app.ui.components.ArticleTaxonomy
import cn.heyread.app.ui.components.ArticleVideoPlayerDialog
import cn.heyread.app.ui.components.CaptchaChallengeDialog
import cn.heyread.app.ui.components.CommentThreadCard
import cn.heyread.app.ui.components.ContentBlockingNotice
import cn.heyread.app.ui.components.ContentBlockingQuickAdd
import cn.heyread.app.ui.components.ContentBlockingScope
import cn.heyread.app.ui.components.LocalContentBlockingCardActions
import cn.heyread.app.ui.components.LocalPaneContentActive
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.components.MediaImageThumbnail
import cn.heyread.app.ui.components.MediaPreviewDialog
import cn.heyread.app.ui.components.MotionAlertDialog
import cn.heyread.app.ui.components.MotionButton
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.components.ProfileRow
import cn.heyread.app.ui.components.RichText
import cn.heyread.app.ui.components.SteamTranslationSection
import cn.heyread.app.ui.components.StoryMetadataRow
import cn.heyread.app.ui.components.bodyFontSize
import cn.heyread.app.ui.components.bodyLineHeight
import cn.heyread.app.ui.components.paragraphBottomSpacing
import cn.heyread.app.ui.components.quoteBlockSpacing
import cn.heyread.app.ui.components.quoteFontSize
import cn.heyread.app.ui.components.quoteLineHeight
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.components.scaledListSpacing
import cn.heyread.app.ui.components.splitReaderParagraphs
import cn.heyread.app.ui.components.toComposeFontFamily
import cn.heyread.app.ui.motion.HomeLayoutPaginationGate
import cn.heyread.app.ui.motion.LocalMotionEnabled
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.LocalSharedCoverIsDetail
import cn.heyread.app.ui.motion.LocalSharedCoverOriginId
import cn.heyread.app.ui.motion.PressScaleColumn
import cn.heyread.app.ui.motion.StaggeredCard
import cn.heyread.app.ui.motion.cancelSharedCoverOnScroll
import cn.heyread.app.ui.motion.pageInputGate
import cn.heyread.app.ui.motion.rememberFirstVisibleBatch
import cn.heyread.app.ui.motion.sharedCoverEndpoint
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.AccountViewModel
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.ArticleGameUiState
import cn.heyread.app.viewmodel.ArticleGameViewModel
import cn.heyread.app.viewmodel.AuthorFollowViewModel
import cn.heyread.app.viewmodel.CommentActionsViewModel
import cn.heyread.app.viewmodel.CommentSupportState
import cn.heyread.app.viewmodel.DetailRequestPhase
import cn.heyread.app.viewmodel.FeedTab
import cn.heyread.app.viewmodel.PostSocialState
import cn.heyread.app.viewmodel.SearchViewModel
import cn.heyread.app.viewmodel.SocialActionsViewModel
import cn.heyread.app.viewmodel.SteamGameSearchState
import cn.heyread.app.viewmodel.SteamTranslationViewModel
import cn.heyread.app.viewmodel.UiState
import cn.heyread.app.viewmodel.isInitialDetailReady
import cn.heyread.app.viewmodel.shouldShowInitialDetailSkeleton
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FeedScreen(
  title: String,
  ui: UiState,
  vm: AppViewModel,
  onOpen: (Story) -> Unit,
  onSearch: () -> Unit,
  games: Boolean = false,
  onOpenSection: ((ArticleLabel) -> Unit)? = null,
  isCurrentPage: Boolean = true,
  paneResizeEpoch: Int = LocalPaneResizeEpoch.current,
  selectedStoryIdentity: String? = null,
  steamGameSearchState: SteamGameSearchState = SteamGameSearchState(),
  onSteamGameQueryChange: (String) -> Unit = {},
  onSteamGameSearchSubmit: () -> Unit = {},
  onSteamGameSearchClear: () -> Unit = {},
  onSteamGameSelect: (SteamGame) -> Unit = {},
) {
  val feedTab = if (games) FeedTab.GAMES else FeedTab.DISCOVER
  val feed = if (games) ui.gamesFeed else ui.discoverFeed
  val listState = rememberLazyListState()
  cancelSharedCoverOnScroll(
    listState,
    LocalSharedCoverEntryId.current,
    LocalSharedCoverCoordinator.current,
  )
  val motion = LocalMotionEnabled.current
  val searchFocusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  var searchFocusRequestId by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()
  val steamSearchItemIndex = 1 + if (feed.source == Source.DEMO) 1 else 0
  fun focusSteamSearch() {
    if (!games || !isCurrentPage) return
    scope.launch {
      if (motion) listState.animateScrollToItem(steamSearchItemIndex)
      else listState.scrollToItem(steamSearchItemIndex)
      searchFocusRequestId++
    }
  }
  fun selectSteamGame(game: SteamGame) {
    onSteamGameSelect(game)
    onSteamGameSearchClear()
    keyboard?.hide()
    focusManager.clearFocus()
    scope.launch { if (motion) listState.animateScrollToItem(0) else listState.scrollToItem(0) }
  }
  LaunchedEffect(feedTab, isCurrentPage) { if (isCurrentPage) vm.ensureFeedLoaded(feedTab) }
  val listIdentity = "feed:$title:${feed.source}:${feed.channel}:${feed.steamAppId}"
  val blocking =
    rememberContentBlockingList(
      raw = feed.items,
      scope = ContentBlockingScope.FEED,
      identity = listIdentity,
      isCurrentPage = isCurrentPage,
      listState = listState,
    )
  val visibleStories = blocking.items
  val homeLayoutGateSaver =
    listSaver<HomeLayoutPaginationGate, String>(
      save = { listOf(it.observedLayout.name, it.suppressAutoPagination.toString()) },
      restore = {
        HomeLayoutPaginationGate(HomeLayout.valueOf(it[0]), it[1].toBooleanStrictOrNull() ?: false)
      },
    )
  var homeLayoutGate by
    rememberSaveable(listIdentity, stateSaver = homeLayoutGateSaver) {
      mutableStateOf(HomeLayoutPaginationGate(ui.homeLayout))
    }
  LaunchedEffect(ui.homeLayout) { homeLayoutGate = homeLayoutGate.observeLayout(ui.homeLayout) }
  LaunchedEffect(paneResizeEpoch) {
    if (paneResizeEpoch > 0) homeLayoutGate = homeLayoutGate.suppressForPaneResize()
  }
  LaunchedEffect(listState, feedTab) {
    listState.interactionSource.interactions.collect { interaction ->
      if (interaction is DragInteraction.Start) homeLayoutGate = homeLayoutGate.userScrolled()
    }
  }
  val explicitLoadMore = {
    blocking.beforeManualLoad()
    homeLayoutGate = homeLayoutGate.userScrolled()
    vm.loadMore(feedTab)
  }
  val storyKeys =
    remember(visibleStories) { visibleStories.map { "${it.source}:${it.id}" }.toSet() }
  val firstBatch = rememberFirstVisibleBatch(listIdentity, listState, storyKeys, motion)
  val animatedKeys =
    rememberSaveable(
      listIdentity,
      saver = listSaver({ it.toList() }, { it.toMutableStateList() }),
    ) {
      mutableStateListOf<String>()
    }
  LaunchedEffect(
    listState,
    feed.items.size,
    visibleStories.size,
    feed.loadingMore,
    feed.refreshing,
    feed.hasMore,
    feed.error,
    isCurrentPage,
    blocking.canAutoPaginate,
  ) {
    snapshotFlow {
        (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to
          homeLayoutGate.suppressAutoPagination
      }
      .collect { (last, paginationSuppressed) ->
        if (
          isCurrentPage &&
            !paginationSuppressed &&
            blocking.canAutoPaginate &&
            feed.error == null &&
            feed.hasMore &&
            !feed.loading &&
            !feed.refreshing &&
            !feed.loadingMore &&
            feed.items.isNotEmpty() &&
            listState.layoutInfo.totalItemsCount - last <= 3
        )
          vm.loadMore(feedTab)
      }
  }
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar(if (games) "游戏" else "发现", isRoot = true, onBack = {}) {
      IconButton(onClick = { if (games) focusSteamSearch() else onSearch() }) {
        Icon(Icons.Filled.Search, if (games) "搜索 Steam 游戏" else "搜索内容")
      }
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
      isRefreshing = feed.refreshing,
      onRefresh = {
        homeLayoutGate = homeLayoutGate.userScrolled()
        vm.refresh(feedTab)
      },
      modifier = Modifier.weight(1f),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("feed-story-list"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          Text(if (games) "游戏的另一面" else "今天，读点游戏故事", fontSize = 27.sp, fontWeight = FontWeight.Bold)
          Text(
            if (games) "精选游戏与官方社区公告" else "把值得读的游戏故事，慢慢读完。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (feed.source == Source.DEMO)
          item {
            Surface(
              color = MaterialTheme.colorScheme.tertiaryContainer,
              shape = RoundedCornerShape(14.dp),
            ) {
              Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  "离线演示内容",
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                  modifier = Modifier.weight(1f),
                )
                TextButton(
                  onClick = { vm.selectSource(feedTab, if (games) Source.STEAM else Source.HEYBOX) }
                ) {
                  Text(if (games) "返回 Steam" else "返回小黑盒")
                }
              }
            }
          }
        if (games)
          item(key = "steam-game-search") {
            SteamGameSearchSection(
              state = steamGameSearchState,
              noImages = ui.noImages,
              active = isCurrentPage,
              onQueryChange = onSteamGameQueryChange,
              onSubmit = onSteamGameSearchSubmit,
              onClear = onSteamGameSearchClear,
              onSelect = ::selectSteamGame,
              modifier = Modifier.fillMaxWidth(),
              focusRequester = searchFocusRequester,
              focusRequestId = searchFocusRequestId,
            )
          }
        if (games)
          item(key = "steam-selected-game") {
            Text(
              feed.steamGameName?.let { "当前游戏：$it · AppID ${feed.steamAppId}" }
                ?: "当前 Steam AppID：${feed.steamAppId}",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.testTag("steam-selected-game"),
            )
          }
        if (games)
          item {
            Row(
              Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              listOf(
                  570 to "Dota 2",
                  730 to "CS2",
                  578080 to "PUBG",
                  1086940 to "博德之门 3",
                  1172470 to "Apex",
                )
                .forEach { (id, name) ->
                  FilterChip(
                    selected = feed.steamAppId == id,
                    onClick = { selectSteamGame(SteamGame(id, name)) },
                    label = { Text(name) },
                    modifier = Modifier.testTag("steam-hot-game-$id"),
                    colors =
                      FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                      ),
                  )
                }
            }
          }
        if (!games)
          item {
            Row(
              Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Channel.entries.forEach { c ->
                FilterChip(
                  selected = feed.channel == c,
                  onClick = { vm.selectChannel(c) },
                  label = { Text(c.label) },
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
              }
            }
          }
        if (feed.loading)
          item {
            LinearProgressIndicator(
              Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        if (blocking.loading || blocking.error != null || blocking.matchedCount > 0)
          item(key = "content-blocking-notice-feed") { ContentBlockingNotice(blocking) }
        if (feed.items.isEmpty() && feed.loading && feed.error == null && !blocking.loading)
          item { FeedSkeletonCards(motion, isCurrentPage) }
        feed.error?.let {
          item {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = RoundedCornerShape(16.dp),
            ) {
              Column(Modifier.padding(16.dp)) {
                Text(
                  "暂时无法连接",
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                Row {
                  TextButton(onClick = { vm.refresh(feedTab) }) { Text("重试") }
                  if (feed.page >= 0 && feed.hasMore)
                    TextButton(onClick = explicitLoadMore) { Text("重试加载") }
                  if (feed.source == Source.DEMO) {
                    TextButton(
                      onClick = {
                        vm.selectSource(feedTab, if (games) Source.STEAM else Source.HEYBOX)
                      }
                    ) {
                      Text(if (games) "返回 Steam" else "返回小黑盒")
                    }
                  } else {
                    TextButton(onClick = { vm.selectSource(feedTab, Source.DEMO) }) {
                      Text("查看离线演示")
                    }
                  }
                }
              }
            }
          }
        }
        if (feed.items.isEmpty() && !feed.loading && feed.error == null && !blocking.loading)
          item {
            if (feed.source == Source.HEYBOX && feed.channel != Channel.RECOMMEND && feed.hasMore) {
              EmptyState("本页暂无${feed.channel.label}内容", "继续读取后续内容。", explicitLoadMore, "继续加载")
            } else if (games && feed.source == Source.STEAM) {
              val selectedName = feed.steamGameName?.let { "《$it》" } ?: "该游戏"
              EmptyState(
                if (feed.hasRequested) "$selectedName 暂未返回社区公告" else "正在准备 Steam 公告",
                "Steam 当前没有返回可显示的社区公告。",
                { vm.refresh(FeedTab.GAMES) },
                "重新读取公告",
              )
            } else if (feed.source == Source.DEMO) {
              EmptyState(
                "离线演示暂时没有内容",
                "当前显示的是明确标注的本机演示数据。",
                { vm.selectSource(feedTab, if (games) Source.STEAM else Source.HEYBOX) },
                if (games) "返回 Steam" else "返回小黑盒",
              )
            } else
              EmptyState(
                "还没有内容",
                "可以查看明确标注的离线演示内容。",
                { vm.selectSource(feedTab, Source.DEMO) },
                "查看离线演示",
              )
          }
        if (visibleStories.isEmpty() && feed.items.isNotEmpty() && blocking.allBlocked)
          item(key = "content-blocking-all-hidden-feed") {
            Text(
              "当前已加载内容均被屏蔽，可临时显示或继续手动加载。",
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        if (visibleStories.isNotEmpty())
          item(key = "${visibleStories.first().source}:${visibleStories.first().id}") {
            val story = visibleStories.first()
            StaggeredCard("${story.source}:${story.id}", firstBatch, animatedKeys, motion) {
              HomeStoryCard(
                story,
                ui.homeLayout,
                featured = true,
                noImages = ui.noImages,
                onOpen = onOpen,
                onOpenSection = onOpenSection,
                selected = "${story.source}:${story.id}" == selectedStoryIdentity,
              )
            }
          }
        itemsIndexed(visibleStories.drop(1), key = { _, story -> "${story.source}:${story.id}" }) {
          index,
          story ->
          StaggeredCard("${story.source}:${story.id}", firstBatch, animatedKeys, motion) {
            HomeStoryCard(
              story,
              ui.homeLayout,
              featured = false,
              noImages = ui.noImages,
              onOpen = onOpen,
              onOpenSection = onOpenSection,
              selected = "${story.source}:${story.id}" == selectedStoryIdentity,
            )
          }
        }
        if (feed.hasMore && feed.error == null)
          item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
              if (feed.loadingMore)
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
              else
                TextButton(onClick = explicitLoadMore) {
                  Text(if (visibleStories.isEmpty()) "加载下一页" else "加载更多")
                }
              TextButton(onClick = { vm.refresh(feedTab) }) { Text("刷新") }
              TextButton(onClick = { if (games) focusSteamSearch() else onSearch() }) {
                Text(if (games) "搜索 Steam 游戏" else "搜索")
              }
            }
          }
        item {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "内容来源：${sourceLabel(feed.source)}",
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (feed.source == Source.DEMO)
              TextButton(
                onClick = { vm.selectSource(feedTab, if (games) Source.STEAM else Source.HEYBOX) }
              ) {
                Text(if (games) "返回 Steam" else "返回小黑盒")
              }
          }
        }
      }
    }
  }
}

@Composable
private fun FeaturedStory(
  s: Story,
  noImages: Boolean,
  onOpen: (Story) -> Unit,
  onOpenSection: ((ArticleLabel) -> Unit)? = null,
) {
  PressScaleColumn(modifier = Modifier.fillMaxWidth(), onClick = { onOpen(s) }) {
    StoryMetadataRow(s, noImages, onOpenSection)
    Spacer(Modifier.height(6.dp))
    Cover(s, noImages, Modifier.fillMaxWidth().height(205.dp), s.source != Source.HEYBOX)
    Spacer(Modifier.height(14.dp))
    Spacer(Modifier.height(7.dp))
    Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp)
    Spacer(Modifier.height(6.dp))
    Text(
      s.excerpt,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      lineHeight = 21.sp,
    )
  }
}

@Composable
internal fun HomeStoryCard(
  story: Story,
  layout: HomeLayout,
  featured: Boolean,
  noImages: Boolean,
  onOpen: (Story) -> Unit,
  onOpenSection: ((ArticleLabel) -> Unit)? = null,
  selected: Boolean = false,
) {
  val onBlockLongClick = rememberStoryCardBlockingLongClick(story)
  val hasCover = !noImages && !story.image.isNullOrBlank()
  val shape = RoundedCornerShape(20.dp)
  val cardModifier = Modifier.fillMaxWidth()
  val clickableCard = Modifier.fillMaxWidth().testTag("home-card-${layout.name}-${story.id}")
  when (layout) {
    HomeLayout.A ->
      Surface(
        modifier = cardModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
        border =
          BorderStroke(
            if (selected) 2.dp else 0.8.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          ),
      ) {
        PressScaleColumn(
          modifier = clickableCard.padding(start = 12.dp, top = 3.dp, end = 12.dp, bottom = 8.dp),
          onClick = { onOpen(story) },
          onLongClick = onBlockLongClick,
        ) {
          StoryMetadataRow(story, noImages, onOpenSection)
          if (featured && hasCover)
            Cover(
              story,
              noImages,
              Modifier.fillMaxWidth().height(138.dp).testTag("home-cover-${story.id}"),
              story.source != Source.HEYBOX,
            )
          if (featured) {
            if (hasCover) Spacer(Modifier.height(8.dp))
            HomeStoryText(story, largeTitle = true)
          } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
              // The card adds 12 dp horizontal padding on each side (300 - 24).
              if (maxWidth < 276.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  if (hasCover)
                    Cover(
                      story,
                      noImages,
                      Modifier.fillMaxWidth().height(122.dp).testTag("home-cover-${story.id}"),
                      story.source != Source.HEYBOX,
                    )
                  HomeStoryText(story, Modifier.fillMaxWidth())
                }
              } else {
                Row(
                  Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  HomeStoryText(story, Modifier.weight(1f))
                  if (hasCover)
                    Cover(
                      story,
                      noImages,
                      Modifier.size(width = 104.dp, height = 82.dp)
                        .testTag("home-cover-${story.id}"),
                      story.source != Source.HEYBOX,
                    )
                }
              }
            }
          }
        }
      }
    HomeLayout.B ->
      Surface(
        modifier = cardModifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border =
          BorderStroke(
            if (selected) 2.dp else 1.2.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
          ),
      ) {
        PressScaleColumn(
          modifier = clickableCard.padding(start = 12.dp, top = 3.dp, end = 12.dp, bottom = 8.dp),
          onClick = { onOpen(story) },
          onLongClick = onBlockLongClick,
        ) {
          StoryMetadataRow(story, noImages, onOpenSection)
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
            // The card adds 12 dp horizontal padding on each side (300 - 24).
            if (maxWidth < 276.dp) {
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasCover)
                  Cover(
                    story,
                    noImages,
                    Modifier.fillMaxWidth().height(122.dp).testTag("home-cover-${story.id}"),
                    story.source != Source.HEYBOX,
                  )
                HomeStoryText(story, Modifier.fillMaxWidth())
              }
            } else {
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                HomeStoryText(story, Modifier.weight(1f))
                if (hasCover)
                  Cover(
                    story,
                    noImages,
                    Modifier.size(width = 104.dp, height = 82.dp).testTag("home-cover-${story.id}"),
                    story.source != Source.HEYBOX,
                  )
              }
            }
          }
        }
      }
    HomeLayout.C ->
      Surface(
        modifier = cardModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border =
          BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          ),
      ) {
        PressScaleColumn(
          modifier = clickableCard,
          onClick = { onOpen(story) },
          onLongClick = onBlockLongClick,
        ) {
          if (hasCover)
            Cover(
              story,
              noImages,
              Modifier.fillMaxWidth()
                .height(if (featured) 164.dp else 152.dp)
                .testTag("home-cover-${story.id}"),
              story.source != Source.HEYBOX,
            )
          Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            HomeStoryText(story, largeTitle = true)
            Spacer(Modifier.height(4.dp))
            StoryMetadataRow(story, noImages, onOpenSection)
          }
        }
      }
  }
}

@Composable
private fun HomeStoryText(
  story: Story,
  modifier: Modifier = Modifier,
  largeTitle: Boolean = false,
) {
  Column(modifier.fillMaxWidth()) {
    Text(
      story.title,
      fontWeight = FontWeight.SemiBold,
      fontSize = if (largeTitle) 20.sp else 17.sp,
      lineHeight = if (largeTitle) 27.sp else 23.sp,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
    if (story.excerpt.isNotBlank()) {
      Spacer(Modifier.height(3.dp))
      Text(
        story.excerpt,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun StoryRow(
  s: Story,
  noImages: Boolean,
  onOpen: (Story) -> Unit,
  onOpenSection: ((ArticleLabel) -> Unit)? = null,
  selected: Boolean = false,
) {
  val onBlockLongClick = rememberStoryCardBlockingLongClick(s)
  PressScaleColumn(
    modifier =
      Modifier.fillMaxWidth()
        .then(
          if (selected)
            Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
          else Modifier
        )
        .testTag(
          if (selected) "story-row-selected-${s.source}-${s.id}"
          else "story-row-${s.source}-${s.id}"
        ),
    onClick = { onOpen(s) },
    onLongClick = onBlockLongClick,
  ) {
    StoryMetadataRow(s, noImages, onOpenSection)
    Spacer(Modifier.height(4.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      if (maxWidth < 300.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Cover(s, noImages, Modifier.fillMaxWidth().height(122.dp))
          StoryRowText(s, Modifier.fillMaxWidth())
        }
      } else {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          StoryRowText(s, Modifier.weight(1f))
          Cover(s, noImages, Modifier.size(width = 104.dp, height = 82.dp))
        }
      }
    }
  }
}

@Composable
private fun rememberStoryCardBlockingLongClick(story: Story): (() -> Unit)? {
  val actions = LocalContentBlockingCardActions.current ?: return null
  val paneActive = LocalPaneContentActive.current
  val ui = actions.ui
  if (!paneActive || !ui.contentBlockingLoaded || ui.contentBlockingLoading || ui.contentBlockingBusy)
    return null

  var openSignal by remember(story.source, story.id, actions.sessionKey) { mutableIntStateOf(0) }
  val targets = remember(story) { contentBlockStoryTargets(story) }
  val identity = "${story.source}:${story.id}:${actions.sessionKey}"
  ContentBlockingQuickAdd(
    targets = targets,
    ui = ui,
    isCurrentPage = paneActive,
    identityKey = identity,
    keywordPrefill = story.title,
    keywordTooLong = story.title.length > 100,
    showTrigger = false,
    externalOpenSignal = openSignal,
    persistDialogState = false,
    onAction = actions.onAction,
  )
  return { openSignal++ }
}

@Composable
private fun StoryRowText(story: Story, modifier: Modifier = Modifier) {
  Column(modifier) {
    Spacer(Modifier.height(5.dp))
    Text(
      story.title,
      fontWeight = FontWeight.SemiBold,
      fontSize = 17.sp,
      lineHeight = 23.sp,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    Text(
      story.excerpt,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      fontSize = 13.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun Cover(
  s: Story,
  noImages: Boolean,
  modifier: Modifier = Modifier,
  showLabel: Boolean = false,
) {
  val context = LocalContext.current
  val sharedOrigin = LocalSharedCoverOriginId.current
  val entryId = LocalSharedCoverEntryId.current
  val isDetail = LocalSharedCoverIsDetail.current
  val sharedCoordinator = LocalSharedCoverCoordinator.current
  var retry by remember(s.image) { mutableIntStateOf(0) }
  var imageState by remember(s.image) { mutableStateOf<AsyncImagePainter.State?>(null) }
  val identity = storyCoverSharedIdentity(s.source.name, s.id, s.image, sharedOrigin, !noImages)
  val painter = (imageState as? AsyncImagePainter.State.Success)?.painter
  val isAnimated =
    (imageState as? AsyncImagePainter.State.Success)?.result?.drawable is
      android.graphics.drawable.Animatable
  val isHidden =
    sharedCoordinator?.isHidden(identity, sharedOrigin, entryId.orEmpty(), isDetail) == true
  val sharedEligible =
    sharedCoordinator != null &&
      identity != null &&
      !noImages &&
      entryId != null &&
      sharedOrigin != null
  val sharedModifier =
    Modifier.sharedCoverEndpoint(
        identity,
        sharedOrigin,
        entryId,
        isDetail,
        painter,
        isAnimated,
        imageState is AsyncImagePainter.State.Error,
        sharedEligible,
        sharedCoordinator,
      )
      .let { endpoint ->
        if (sharedEligible) endpoint.graphicsLayer { alpha = if (isHidden) 0f else 1f }
        else Modifier
      }
  Box(
    modifier
      .then(sharedModifier)
      .clip(RoundedCornerShape(18.dp))
      .background(
        if (!noImages && imageState is AsyncImagePainter.State.Success) Color.Transparent
        else Color(0xFF272E39)
      ),
    contentAlignment = Alignment.BottomStart,
  ) {
    if (!noImages && s.image != null)
      AnimatedImage(
        model =
          remember(s.image, retry) {
            ImageRequest.Builder(context).data(s.image).memoryCacheKey("${s.image}-$retry").build()
          },
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        onState = { imageState = it },
      )
    if (showLabel)
      Text(
        if (s.source == Source.HEYBOX) s.author else sourceLabel(s.source),
        Modifier.padding(12.dp)
          .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
          .padding(horizontal = 5.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
      )
    if (!noImages && imageState is AsyncImagePainter.State.Error)
      IconButton(onClick = { retry++ }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
        Icon(Icons.Default.Refresh, contentDescription = "重新加载封面")
      }
  }
}

internal fun storyCoverSharedIdentity(
  source: String,
  id: String,
  imageUrl: String?,
  originEntryId: String?,
  enabled: Boolean,
): String? {
  if (
    !enabled ||
      source.isBlank() ||
      id.isBlank() ||
      imageUrl.isNullOrBlank() ||
      originEntryId.isNullOrBlank()
  )
    return null
  if (!imageUrl.startsWith("https://", ignoreCase = true)) return null
  return "story-cover:$originEntryId:$source:$id:$imageUrl"
}

@Composable
internal fun FeedSkeletonCards(motion: Boolean, isCurrentPage: Boolean) {
  if (!motion || !isCurrentPage) {
    SkeletonCardContent(alphaState = null)
    return
  }
  LifecycleSkeletonCards()
}

@Composable
internal fun DetailInitialSkeleton(motion: Boolean, isCurrentPage: Boolean) {
  if (!motion || !isCurrentPage) {
    DetailInitialSkeletonContent(null)
    return
  }
  val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  var resumed by
    remember(owner) {
      mutableStateOf(
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
      )
    }
  DisposableEffect(owner) {
    val observer =
      androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed = true
        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) resumed = false
      }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  val alphaState =
    if (resumed) {
      androidx.compose.animation.core
        .rememberInfiniteTransition(label = "detail-skeleton")
        .animateFloat(
          initialValue = 0.40f,
          targetValue = 0.75f,
          animationSpec =
            androidx.compose.animation.core.infiniteRepeatable(
              androidx.compose.animation.core.tween(850),
              repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
          label = "detail-skeleton-alpha",
        )
    } else null
  DetailInitialSkeletonContent(alphaState)
}

@Composable
internal fun DetailInitialContentGate(
  showSkeleton: Boolean,
  motion: Boolean,
  isCurrentPage: Boolean,
  content: @Composable () -> Unit,
) {
  if (showSkeleton) DetailInitialSkeleton(motion, isCurrentPage) else content()
}

@Composable
internal fun rememberDetailLastReadyScope(
  identity: String,
  initialContentReady: Boolean,
  currentScope: String,
): String? {
  var lastReadyScope by rememberSaveable(identity) { mutableStateOf<String?>(null) }
  LaunchedEffect(initialContentReady, currentScope) {
    if (initialContentReady) lastReadyScope = currentScope
  }
  return lastReadyScope
}

@Composable
private fun DetailInitialSkeletonContent(alphaState: androidx.compose.runtime.State<Float>?) {
  val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
  Column(
    Modifier.fillMaxWidth().testTag("detail-initial-skeleton"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SkeletonPlaceholder(Modifier.fillMaxWidth(0.18f).height(10.dp), lineColor, alphaState)
    SkeletonPlaceholder(Modifier.fillMaxWidth(0.92f).height(29.dp), lineColor, alphaState)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SkeletonPlaceholder(Modifier.size(30.dp), lineColor, alphaState, circle = true)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SkeletonPlaceholder(Modifier.width(92.dp).height(10.dp), lineColor, alphaState)
        SkeletonPlaceholder(Modifier.width(126.dp).height(8.dp), lineColor, alphaState)
      }
    }
    Spacer(Modifier.height(8.dp))
    repeat(4) { index ->
      SkeletonPlaceholder(
        Modifier.fillMaxWidth(if (index == 3) 0.68f else 1f).height(12.dp),
        lineColor,
        alphaState,
      )
    }
    SkeletonPlaceholder(
      Modifier.fillMaxWidth().height(66.dp),
      lineColor,
      alphaState,
      rounded = true,
    )
    repeat(3) { index ->
      SkeletonPlaceholder(
        Modifier.fillMaxWidth(if (index == 2) 0.76f else 1f).height(12.dp),
        lineColor,
        alphaState,
      )
    }
  }
}

@Composable
private fun SkeletonPlaceholder(
  modifier: Modifier,
  color: Color,
  alphaState: androidx.compose.runtime.State<Float>?,
  circle: Boolean = false,
  rounded: Boolean = false,
) {
  val shape =
    when {
      circle -> androidx.compose.foundation.shape.CircleShape
      rounded -> RoundedCornerShape(12.dp)
      else -> RoundedCornerShape(6.dp)
    }
  Box(
    modifier
      .then(
        if (alphaState == null) Modifier else Modifier.graphicsLayer { alpha = alphaState.value }
      )
      .background(color.copy(alpha = 0.16f), shape)
  )
}

@Composable
private fun LifecycleSkeletonCards() {
  val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  var resumed by
    remember(owner) {
      mutableStateOf(
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
      )
    }
  DisposableEffect(owner) {
    val observer =
      androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed = true
        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) resumed = false
      }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  if (!resumed) {
    SkeletonCardContent(alphaState = null)
    return
  }
  val transition =
    androidx.compose.animation.core.rememberInfiniteTransition(label = "feed-skeleton")
  val alpha =
    transition.animateFloat(
      initialValue = 0.40f,
      targetValue = 0.75f,
      animationSpec =
        androidx.compose.animation.core.infiniteRepeatable(
          androidx.compose.animation.core.tween(850),
          repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
      label = "skeleton-alpha",
    )
  SkeletonCardContent(alpha)
}

@Composable
private fun SkeletonCardContent(alphaState: androidx.compose.runtime.State<Float>?) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    repeat(3) { index ->
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
      ) {
        Column(
          Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val titleModifier =
            Modifier.fillMaxWidth(if (index == 1) 0.54f else 0.7f)
              .height(12.dp)
              .then(
                if (alphaState == null) Modifier
                else Modifier.graphicsLayer { alpha = alphaState.value }
              )
              .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                RoundedCornerShape(8.dp),
              )
          Box(titleModifier)
          val bodyModifier =
            Modifier.fillMaxWidth()
              .height(if (index == 0) 92.dp else 54.dp)
              .then(
                if (alphaState == null) Modifier
                else Modifier.graphicsLayer { alpha = alphaState.value }
              )
              .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.13f),
                RoundedCornerShape(12.dp),
              )
          Box(bodyModifier)
        }
      }
    }
    Text(
      "正在加载内容",
      Modifier.semantics { contentDescription = "正在加载内容" },
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchScreen(
  ui: UiState,
  vm: SearchViewModel,
  onBack: () -> Unit,
  onOpen: (Story) -> Unit,
  onOpenHistoryScope: (SearchHistoryEntry) -> Unit = {},
  isCurrentPage: Boolean = true,
  selectedStoryIdentity: String? = null,
) {
  val state by vm.state.collectAsState()
  LaunchedEffect(isCurrentPage) { if (!isCurrentPage) vm.closeCaptchaDialog() }
  val listState = rememberLazyListState()
  cancelSharedCoverOnScroll(
    listState,
    LocalSharedCoverEntryId.current,
    LocalSharedCoverCoordinator.current,
  )
  val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
  var confirmClear by remember { mutableStateOf(false) }
  val visibleHistory = vm.visibleHistory()
  val motion = LocalMotionEnabled.current
  val listIdentity = "search:${state.scope.type}:${state.scope.id}:${state.submittedQuery}"
  val blocking =
    rememberContentBlockingList(
      raw = state.items,
      scope = ContentBlockingScope.FEED,
      identity = listIdentity,
      isCurrentPage = isCurrentPage,
      listState = listState,
    )
  val visibleStories = blocking.items
  val paginationState = state
  val paginationCanAutoPaginate = blocking.canAutoPaginate
  val paneResizeEpoch = LocalPaneResizeEpoch.current
  var resizePaginationSuppressed by rememberSaveable(listIdentity) { mutableStateOf(false) }
  LaunchedEffect(paneResizeEpoch) { if (paneResizeEpoch > 0) resizePaginationSuppressed = true }
  LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { interaction ->
      if (interaction is DragInteraction.Start) resizePaginationSuppressed = false
    }
  }
  val storyKeys =
    remember(visibleStories) { visibleStories.map { "${it.source}:${it.id}" }.toSet() }
  val firstBatch = rememberFirstVisibleBatch(listIdentity, listState, storyKeys, motion)
  val animatedKeys =
    rememberSaveable(
      listIdentity,
      saver = listSaver({ it.toList() }, { it.toMutableStateList() }),
    ) {
      mutableStateListOf<String>()
    }
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  DisposableEffect(vm, lifecycleOwner) {
    val observer =
      androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) vm.closeCaptchaDialog()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      vm.closeCaptchaDialog()
    }
  }
  LaunchedEffect(vm) { vm.refreshHistory() }
  LaunchedEffect(
    listState,
    listIdentity,
    paginationState.items.size,
    paginationState.hasMore,
    paginationState.loadingMore,
    paginationState.scope.type,
    paginationState.searching,
    paginationState.refreshing,
    isCurrentPage,
    resizePaginationSuppressed,
    paginationCanAutoPaginate,
  ) {
    if (paginationState.scope.type == SearchScopeType.HASHTAG) return@LaunchedEffect
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
      .collect { last ->
        val latest = vm.state.value
        if (
          isCurrentPage &&
            !resizePaginationSuppressed &&
            blocking.canAutoPaginate &&
            paginationState.error == null &&
            paginationState.hasMore &&
            !paginationState.captchaRequired &&
            !paginationState.searching &&
            !paginationState.refreshing &&
            !paginationState.loadingMore &&
            paginationState.items.isNotEmpty() &&
            latest.scope == paginationState.scope &&
            latest.submittedQuery == paginationState.submittedQuery &&
            (latest.items === paginationState.items || latest.items == paginationState.items) &&
            listState.layoutInfo.totalItemsCount - last <= 3
        )
          vm.loadMore()
      }
  }
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .imePadding()
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar("搜索", isRoot = false, onBack = onBack) {
      if (state.captchaRequired) {
        TextButton(
          onClick = vm::openCaptchaDialog,
          enabled = !state.captchaVerifying,
          modifier = Modifier.heightIn(min = 48.dp).testTag("search-captcha-start"),
        ) {
          Text(if (state.captchaVerifying) "验证中" else "开始验证")
        }
      }
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
      isRefreshing = state.refreshing,
      onRefresh = vm::refresh,
      modifier = Modifier.weight(1f),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
      ) {
        item {
          Column {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
              value = state.query,
              onValueChange = vm::setQuery,
              modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("search-query"),
              placeholder = { Text("搜索小黑盒帖子") },
              leadingIcon = { Icon(Icons.Filled.Search, null) },
              singleLine = true,
              keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                  imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
              keyboardActions =
                androidx.compose.foundation.text.KeyboardActions(
                  onSearch = {
                    keyboard?.hide()
                    vm.submit()
                  }
                ),
            )
            Spacer(Modifier.height(16.dp))
            MotionButton(
              onClick = {
                keyboard?.hide()
                vm.submit()
              },
              enabled =
                state.query.isNotBlank() &&
                  !state.searching &&
                  !state.loadingMore &&
                  !state.captchaRequired,
              modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("search-submit"),
            ) {
              Text("搜索")
            }
            val scopeText =
              when (state.scope.type) {
                SearchScopeType.GLOBAL -> "在线搜索全站帖子"
                SearchScopeType.SECTION -> "在线搜索分区：${state.scope.name}"
                SearchScopeType.HASHTAG -> "在本话题的线上标题和完整摘要中逐页查找：${state.scope.name}"
              }
            Text(
              scopeText,
              Modifier.padding(top = 12.dp),
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.captchaRequired)
              Text(
                if (state.captchaVerifying) "正在验证并读取内容…" else "验证期间已暂停刷新与加载，已显示的内容会保留。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
        }
        if (state.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (blocking.loading || blocking.error != null || blocking.matchedCount > 0)
          item(key = "content-blocking-notice-search") { ContentBlockingNotice(blocking) }
        state.notice?.let { message ->
          item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (state.submittedQuery.isNotBlank() && state.scope.type == SearchScopeType.HASHTAG) {
          item {
            Text(
              "已检查${state.checkedCount}篇，找到${state.items.size}篇",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        state.error?.let { error ->
          item {
            Column {
              Text(error, color = MaterialTheme.colorScheme.error)
              TextButton(onClick = vm::retry) { Text("重试搜索") }
            }
          }
        }
        if (
          state.submittedQuery.isNotBlank() &&
            !state.searching &&
            !state.loadingMore &&
            !state.refreshing &&
            !state.captchaRequired &&
            state.items.isEmpty() &&
            !blocking.loading &&
            state.error == null
        )
          item {
            Text(
              when {
                state.scope.type == SearchScopeType.HASHTAG && state.hasMore -> "本批暂未找到，可继续查找"
                state.scope.type == SearchScopeType.HASHTAG -> "已检索完返回的内容，未找到匹配"
                else -> "没有找到帖子"
              },
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 20.dp),
            )
          }
        if (state.submittedQuery.isNotBlank() && visibleStories.isEmpty() && blocking.allBlocked)
          item(key = "content-blocking-all-hidden-search") {
            Text(
              "当前已加载内容均被屏蔽，可临时显示或继续手动加载。",
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        itemsIndexed(visibleStories, key = { _, story -> "${story.source}:${story.id}" }) {
          index,
          story ->
          StaggeredCard("${story.source}:${story.id}", firstBatch, animatedKeys, motion) {
            StoryRow(
              story,
              ui.noImages,
              onOpen,
              selected = "${story.source}:${story.id}" == selectedStoryIdentity,
            )
          }
        }
        if (state.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.hasMore && !state.loadingMore && state.error == null && !state.captchaRequired)
          item {
            TextButton(
              onClick = {
                blocking.beforeManualLoad()
                vm.loadMore()
              }
            ) {
              Text(if (state.scope.type == SearchScopeType.HASHTAG) "继续查找一批" else "加载更多")
            }
          }
        if (state.captchaRequired)
          item {
            Column(
              Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp)
            ) {
              Text("小黑盒需要人机验证")
              Text("验证期间已暂停刷新与加载，已显示的内容会保留。")
              state.captchaMessage?.let { Text(it) }
              if (state.captchaVerifying) Text("正在验证并读取内容…")
              else {
                TextButton(
                  onClick = vm::retryAfterOfficialVerification,
                  modifier = Modifier.heightIn(min = 48.dp),
                ) {
                  Text("我已在官方页面验证，重新读取")
                }
                Text("官方页面验证后可返回尝试，仍可能需要应用内验证。", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        if (state.submittedQuery.isBlank() && visibleHistory.isNotEmpty()) {
          item {
            Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("本机搜索记录", fontWeight = FontWeight.SemiBold)
              TextButton(
                onClick = { confirmClear = true },
                modifier = Modifier.heightIn(min = 48.dp),
              ) {
                Text("清空搜索记录")
              }
            }
          }
          items(visibleHistory, key = { it.key }) { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Column(
                Modifier.weight(1f)
                  .heightIn(min = 48.dp)
                  .clickable {
                    if (entry.scope == state.scope) vm.submit(entry.query)
                    else onOpenHistoryScope(entry)
                  }
                  .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
              ) {
                Text(entry.query, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                  if (entry.scope.type == SearchScopeType.GLOBAL) "全站"
                  else
                    "${if (entry.scope.type == SearchScopeType.SECTION) "分区" else "话题"} · ${entry.scope.name}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              IconButton(
                onClick = { vm.removeHistory(entry) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
              ) {
                Icon(Icons.Filled.Close, "删除搜索记录")
              }
            }
          }
        }
      }
    }
  }
  MotionAlertDialog(
    visible = confirmClear,
    onDismiss = { confirmClear = false },
    title = "清空本机搜索记录？",
    message = "将清空所有分区、话题和全站的本机搜索记录，不会清除阅读历史。",
    confirmLabel = "清空",
    onConfirm = vm::clearHistory,
    dismissLabel = "取消",
  )
  val challengeId = state.captchaChallengeId
  val attemptId = state.captchaAttemptId
  val appId = state.captchaAppId
  if (state.captchaDialogOpen && challengeId != null && attemptId != null && appId != null) {
    CaptchaChallengeDialog(
      appId = appId,
      attemptId = attemptId,
      onCallback = { callbackAttempt, result ->
        vm.onCaptchaCallback(challengeId, callbackAttempt, result)
      },
      onDismiss = vm::closeCaptchaDialog,
    )
  }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
internal fun DetailPagerPages(
  state: PagerState,
  pageCount: Int,
  routeActive: Boolean,
  motionEnabled: Boolean,
  commentLabel: String,
  modifier: Modifier = Modifier,
  onPageScrollStarted: () -> Unit = {},
  bodyContent: @Composable () -> Unit,
  commentsContent: @Composable () -> Unit,
) {
  val parentPaneActive = LocalPaneContentActive.current
  val pagerScope = rememberCoroutineScope()
  val currentOnPageScrollStarted by rememberUpdatedState(onPageScrollStarted)
  fun selectPage(page: Int) {
    if (page !in 0 until pageCount || (page == state.currentPage && !state.isScrollInProgress))
      return
    currentOnPageScrollStarted()
    pagerScope.launch {
      if (motionEnabled) state.animateScrollToPage(page) else state.scrollToPage(page)
    }
  }
  LaunchedEffect(state, pageCount) {
    snapshotFlow { state.isScrollInProgress }
      .collect { scrolling -> if (scrolling) currentOnPageScrollStarted() }
  }
  Column(modifier) {
    if (pageCount > 1) {
      TabRow(
        selectedTabIndex = state.currentPage,
        modifier = Modifier.testTag("article-detail-tabs"),
        indicator = { tabPositions ->
          val tabDistance = tabPositions[1].left - tabPositions[0].left
          val indicatorWidth = tabPositions[0].width
          val physicalDirection =
            if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
          val moving = state.isScrollInProgress
          Box(
            Modifier.fillMaxWidth()
              .wrapContentSize(Alignment.BottomStart)
              .width(indicatorWidth)
              .height(2.dp)
              .then(
                if (moving) {
                  Modifier.graphicsLayer {
                    val logicalPage =
                      (state.currentPage + state.currentPageOffsetFraction).coerceIn(
                        0f,
                        (pageCount - 1).toFloat(),
                      )
                    translationX =
                      physicalDirection * (tabPositions[0].left + tabDistance * logicalPage).toPx()
                  }
                } else {
                  Modifier.offset(x = tabPositions[state.currentPage].left)
                }
              )
              .background(MaterialTheme.colorScheme.primary)
              .testTag("article-detail-indicator")
          )
        },
      ) {
        Tab(
          selected = state.currentPage == 0,
          onClick = { selectPage(0) },
          enabled = routeActive && parentPaneActive,
          modifier = Modifier.testTag("article-tab-body"),
          text = { Text("正文") },
        )
        Tab(
          selected = state.currentPage == 1,
          onClick = { selectPage(1) },
          enabled = routeActive && parentPaneActive,
          modifier = Modifier.testTag("article-tab-comments"),
          text = { Text(commentLabel) },
        )
      }
    }
    HorizontalPager(
      state = state,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      userScrollEnabled = pageCount > 1 && routeActive && parentPaneActive,
      key = { page -> page },
    ) { page ->
      val pageActive = routeActive && parentPaneActive && state.settledPage == page
      val pageInputActive =
        routeActive && parentPaneActive && (state.settledPage == page || state.isScrollInProgress)
      CompositionLocalProvider(LocalPaneContentActive provides pageActive) {
        Box(
          Modifier.fillMaxSize()
            .testTag(if (page == 0) "article-page-body" else "article-page-comments")
            .then(if (pageActive) Modifier else Modifier.semantics { hideFromAccessibility() })
            .pageInputGate(pageInputActive)
        ) {
          if (page == 0) bodyContent() else commentsContent()
        }
      }
    }
  }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
fun DetailScreen(
  s: Story,
  ui: UiState,
  vm: AppViewModel,
  account: AccountUiState,
  accountViewModel: AccountViewModel,
  socialViewModel: SocialActionsViewModel,
  commentActionsViewModel: CommentActionsViewModel,
  authorFollowViewModel: AuthorFollowViewModel,
  translationViewModel: SteamTranslationViewModel,
  articleGameViewModel: ArticleGameViewModel,
  onBack: () -> Unit,
  onLogin: () -> Unit,
  onShare: () -> Unit,
  onOpenUrl: (String) -> Unit,
  onOpenProfile: ((AuthorProfile) -> Unit)? = null,
  onOpenSection: ((ArticleLabel) -> Unit)? = null,
  onOpenHashtag: ((ArticleLabel) -> Unit)? = null,
  isCurrentPage: Boolean = true,
  publicAttemptedForEntry: Boolean = false,
) {
  val key = "${s.source}:${s.id}"
  val detailMotion = LocalMotionEnabled.current
  val detailPageCount = if (s.source == Source.HEYBOX) 2 else 1
  val pagerState =
    androidx.compose.runtime.key(key) { rememberPagerState(pageCount = { detailPageCount }) }
  val sharedCoverCoordinator = LocalSharedCoverCoordinator.current
  val sharedCoverEntryId = LocalSharedCoverEntryId.current
  val bodyPageActive =
    isCurrentPage && LocalPaneContentActive.current && pagerState.settledPage == 0
  val commentsPageActive =
    detailPageCount > 1 &&
      isCurrentPage &&
      LocalPaneContentActive.current &&
      pagerState.settledPage == 1
  val bodyMotion = detailMotion && bodyPageActive
  val translationEligible = SteamTranslationPolicy.canTranslate(s.source, s.body)
  val translationState by translationViewModel.state.collectAsState()
  val gameState by articleGameViewModel.state.collectAsState()
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, bodyPageActive, key, s.body, translationEligible) {
    fun updateActive() {
      val active =
        bodyPageActive &&
          translationEligible &&
          lifecycleOwner.lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.RESUMED
          )
      translationViewModel.bind(
        if (translationEligible) s.source else Source.DEMO,
        key,
        s.body,
        active,
      )
    }
    val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateActive() }
    lifecycleOwner.lifecycle.addObserver(observer)
    updateActive()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      translationViewModel.deactivate()
    }
  }
  var activeMedia by remember(key) { mutableStateOf<MediaImage?>(null) }
  var activeMediaGallery by remember(key) { mutableStateOf<List<MediaImage>?>(null) }
  var activeMediaInitialIndex by remember(key) { mutableIntStateOf(0) }
  var activeVideoUrl by remember(key) { mutableStateOf<String?>(null) }
  var observedAccountRevision by remember(key) { mutableLongStateOf(account.revision) }
  var mediaAccountIdentity by
    remember(key) { mutableStateOf(account.session?.userId to account.revision) }
  LaunchedEffect(account.revision) {
    if (observedAccountRevision != account.revision) activeVideoUrl = null
    observedAccountRevision = account.revision
  }
  LaunchedEffect(gameState.topicToOpen, bodyPageActive, key) {
    gameState.topicToOpen
      ?.takeIf { bodyPageActive && gameState.articleKey == key }
      ?.let { label ->
        onOpenSection?.invoke(label)
        articleGameViewModel.consumeTopicNavigation()
      }
  }
  LaunchedEffect(bodyPageActive) { if (!bodyPageActive) activeVideoUrl = null }
  LaunchedEffect(account.session?.userId, account.revision, key) {
    val currentIdentity = account.session?.userId to account.revision
    if (mediaAccountIdentity != currentIdentity) {
      activeMedia = null
      activeMediaGallery = null
      activeMediaInitialIndex = 0
    }
    mediaAccountIdentity = currentIdentity
  }
  val signedInHeybox = s.source == Source.HEYBOX && account.session != null
  val article = account.fullArticles[key]
  val accountBlocks =
    remember(article?.blocks, s.feedVideo) {
      article?.blocks?.let { SharedArticleContentParser.withFeedVideo(it, s.feedVideo) }
    }
  val commentActionStates by commentActionsViewModel.states.collectAsState()
  val followStates by authorFollowViewModel.states.collectAsState()
  val profile = article?.authorProfile ?: s.authorProfile
  val contentBlockTargets =
    remember(s, profile, article?.sections, article?.hashtags) {
      contentBlockStoryTargets(
        s.copy(
          authorProfile = profile,
          sections = article?.sections?.ifEmpty { s.sections } ?: s.sections,
          hashtags = article?.hashtags?.ifEmpty { s.hashtags } ?: s.hashtags,
        )
      )
    }
  val authorId = profile?.userId.orEmpty()
  val followState = followStates[authorId]
  val socialStates by socialViewModel.states.collectAsState()
  val social = socialStates[key] ?: PostSocialState()
  LaunchedEffect(
    key,
    article?.isLiked,
    article?.isFavorited,
    article?.likeCount,
    article?.commentCount,
  ) {
    socialViewModel.syncArticle(s, article)
  }
  LaunchedEffect(account.session?.userId, account.revision, article?.authorFollowed, authorId) {
    if (
      account.session != null &&
        profile != null &&
        authorId.isNotBlank() &&
        authorId != account.session.userId
    ) {
      if (article?.authorFollowed != null)
        authorFollowViewModel.seedFromArticle(profile, article.authorFollowed)
      else authorFollowViewModel.refresh(profile, s)
    }
  }
  LaunchedEffect(article?.comments, account.replies) {
    commentActionsViewModel.syncComments(
      article?.comments.orEmpty() + account.replies.values.flatten()
    )
  }
  var showCommentSheet by remember(key) { mutableStateOf(false) }
  LaunchedEffect(account.revision) { showCommentSheet = false }
  val commentsList = androidx.compose.runtime.key(key) { rememberLazyListState() }
  val detailList = androidx.compose.runtime.key(key) { rememberLazyListState() }
  cancelSharedCoverOnScroll(
    detailList,
    LocalSharedCoverEntryId.current,
    LocalSharedCoverCoordinator.current,
  )
  cancelSharedCoverOnScroll(
    commentsList,
    LocalSharedCoverEntryId.current,
    LocalSharedCoverCoordinator.current,
  )
  val displayBlocks = accountBlocks?.takeIf { it.isNotEmpty() } ?: s.detailBlocks.orEmpty()
  val bodyImageOccurrences = remember(displayBlocks) { articleImageOccurrences(displayBlocks) }
  val articleImageGallery =
    remember(bodyImageOccurrences) { bodyImageOccurrences.map { it.block.toMediaImage() } }
  val articleImageOffsets = remember(displayBlocks) { articleImageOffsets(displayBlocks) }
  LaunchedEffect(
    bodyPageActive,
    commentsPageActive,
    ui.noImages,
    account.session?.userId,
    account.revision,
    key,
  ) {
    if (ui.noImages || (!bodyPageActive && !commentsPageActive)) {
      activeMedia = null
      activeMediaGallery = null
      activeMediaInitialIndex = 0
    } else if (!bodyPageActive) {
      if (activeMediaGallery != null) activeMedia = null
      activeMediaGallery = null
      activeMediaInitialIndex = 0
    }
  }
  val presentationMetadata =
    rememberDetailPresentationMetadata(key, displayBlocks, s.body.isNotBlank())
  val headingRevealKey = "detail-heading:$key"
  val fallbackBodyRevealKey = "detail-fallback:$key"
  val accountAttempted =
    article != null || key in account.articleLoading || key in account.articleErrors
  val accountBodyAvailable = article?.blocks?.isNotEmpty() == true
  val publicRequest = ui.detailRequests[key]
  val hasSuccessfulPublicBody = ui.details[key]?.detailBlocks?.isNotEmpty() == true
  val gameIds = presentationMetadata.gameIds
  val initialContentReady =
    isInitialDetailReady(
      source = s.source,
      sessionRestoring = s.source == Source.HEYBOX && account.loadingSession,
      hasAccountSession = signedInHeybox,
      accountAttempted = accountAttempted,
      accountLoading = article == null && key in account.articleLoading,
      accountHasDisplayableBody = accountBodyAvailable,
      publicPhase = publicRequest?.phase ?: DetailRequestPhase.IDLE,
      publicAttemptedForEntry = publicAttemptedForEntry,
      hasSuccessfulPublicBody = hasSuccessfulPublicBody,
      gameIds = gameIds,
      gameStateBound = gameState.articleKey == key,
      gameLoading = gameState.loadingInfos,
      completedGameIds = gameState.infos.keys + gameState.completedInfoIds,
      gameFailedOrLimited = gameState.infoError != null || gameState.captchaRequired,
    )
  val presentationEntryId = LocalSharedCoverEntryId.current.orEmpty()
  val presentationIdentity = "detail-entry:$presentationEntryId:$key"
  val readinessScope =
    when {
      s.source != Source.HEYBOX -> "local"
      account.loadingSession -> "session-restoring"
      signedInHeybox -> "account:${account.session?.userId.orEmpty()}:${account.revision}"
      else -> "visitor:${account.revision}"
    }
  val lastReadyScope =
    rememberDetailLastReadyScope(presentationIdentity, initialContentReady, readinessScope)
  val publicRequestNotStarted =
    s.source == Source.HEYBOX &&
      !account.loadingSession &&
      if (signedInHeybox) {
        article == null && key !in account.articleLoading && key !in account.articleErrors
      } else {
        publicRequest == null || publicRequest.phase == DetailRequestPhase.IDLE
      }
  val showInitialSkeleton =
    shouldShowInitialDetailSkeleton(
      initialContentReady,
      lastReadyScope,
      readinessScope,
      publicRequestNotStarted,
    )
  val initialRevealKeys = presentationMetadata.initialRevealKeys
  val initialRevealBatch =
    rememberFirstVisibleBatch(
      presentationIdentity,
      detailList,
      if (initialContentReady) initialRevealKeys else emptySet(),
      bodyMotion,
    )
  val animatedRevealKeys =
    rememberSaveable(
      presentationIdentity,
      saver = listSaver({ it.toList() }, { it.toMutableStateList() }),
    ) {
      mutableStateListOf<String>()
    }
  LaunchedEffect(
    key,
    commentsPageActive,
    commentsList,
    article?.hasMoreComments,
    account.articleLoading,
    account.articleErrors,
  ) {
    snapshotFlow { commentsList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
      .collect { last ->
        if (
          commentsPageActive &&
            signedInHeybox &&
            article?.hasMoreComments == true &&
            key !in account.articleLoading &&
            key !in account.articleErrors &&
            commentsList.layoutInfo.totalItemsCount - last <= 3
        )
          accountViewModel.loadMoreComments(s)
      }
  }
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar("文章详情", isRoot = false, onBack = onBack) {
      if (gameState.captchaRequired && !gameState.captchaVerifying) {
        TextButton(
          onClick = articleGameViewModel::openCaptchaDialog,
          modifier = Modifier.heightIn(min = 48.dp).testTag("article-game-captcha-start"),
        ) {
          Text("开始验证")
        }
      }
      IconButton(onClick = onShare) { Icon(Icons.AutoMirrored.Filled.Send, "分享文章") }
      ContentBlockingQuickAdd(
        targets = contentBlockTargets,
        ui = ui,
        isCurrentPage = isCurrentPage,
        identityKey = "$key:${account.session?.userId.orEmpty()}:${account.revision}",
        keywordPrefill = s.title,
        keywordTooLong = s.title.length > 100,
        disabled = !ui.contentBlockingLoaded,
        onAction = vm::onContentBlockingAction,
      )
    }
    DetailPagerPages(
      state = pagerState,
      pageCount = detailPageCount,
      routeActive = isCurrentPage,
      motionEnabled = detailMotion,
      commentLabel = "评论${social.commentCount?.let { " · $it" } ?: ""}",
      modifier = Modifier.weight(1f),
      onPageScrollStarted = { sharedCoverCoordinator?.cancelForEntry(sharedCoverEntryId) },
      bodyContent = {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
          isRefreshing = false,
          onRefresh = {
            if (signedInHeybox) accountViewModel.loadFullArticle(s, force = true)
            else vm.detail(s, force = true)
          },
          modifier = Modifier.fillMaxSize(),
        ) {
          LazyColumn(
            state = detailList,
            modifier = Modifier.fillMaxSize().testTag("article-body-list"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            item(key = "detail-cover-item:$key") {
              val coverUrl = s.image?.takeIf { it.startsWith("https://", true) }
              Box(
                Modifier.fillMaxWidth()
                  .height(220.dp)
                  .then(
                    if (!ui.noImages && coverUrl != null)
                      Modifier.clickable {
                        activeMedia =
                          MediaImage(coverUrl, description = "文章封面", animationUrl = coverUrl)
                        activeMediaGallery = null
                        activeMediaInitialIndex = 0
                      }
                    else Modifier
                  )
                  .testTag("detail-cover-${s.id}")
              ) {
                Cover(s, ui.noImages, Modifier.fillMaxSize())
              }
            }
            item(
              key =
                if (showInitialSkeleton) "detail-initial-skeleton:$key" else "detail-heading:$key"
            ) {
              DetailInitialContentGate(showInitialSkeleton, bodyMotion, bodyPageActive) {
                StaggeredCard(
                  headingRevealKey,
                  initialRevealBatch,
                  animatedRevealKeys,
                  bodyMotion,
                ) {
                  Column {
                    Text(
                      sourceLabel(s.source),
                      fontSize = 12.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    RichText(
                      s.title,
                      fontSize = 29.sp,
                      fontWeight = FontWeight.Bold,
                      lineHeight = 37.sp,
                      noImages = ui.noImages,
                    )
                    ProfileRow(
                      profile,
                      s.author,
                      ui.noImages,
                      Modifier.padding(top = 10.dp),
                      timestamp = s.date,
                      onProfileClick = onOpenProfile,
                    ) {
                      if (
                        s.source == Source.HEYBOX &&
                          profile != null &&
                          authorId.isNotBlank() &&
                          authorId != account.session?.userId
                      ) {
                        val follow = followState
                        when {
                          account.session == null ->
                            Button(
                              onClick = onLogin,
                              shape = RoundedCornerShape(8.dp),
                              contentPadding = PaddingValues(horizontal = 10.dp),
                              modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                              Text("+ 关注")
                            }
                          follow?.loading == true && follow.followed == null ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                          follow?.error != null && follow.followed == null ->
                            TextButton(onClick = { authorFollowViewModel.refresh(profile, s) }) {
                              Text("重试")
                            }
                          follow?.followed == null ->
                            TextButton(onClick = { authorFollowViewModel.refresh(profile, s) }) {
                              Text("读取关注")
                            }
                          else ->
                            Button(
                              onClick = { authorFollowViewModel.toggle(profile, s) },
                              shape = RoundedCornerShape(8.dp),
                              enabled = !follow.busy && !follow.loading,
                              colors =
                                ButtonDefaults.buttonColors(
                                  containerColor = MaterialTheme.colorScheme.inverseSurface,
                                  contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                ),
                              contentPadding = PaddingValues(horizontal = 10.dp),
                              modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                              Text(
                                if (follow.busy) "处理中"
                                else if (follow.followed == true) "已关注" else "+ 关注"
                              )
                            }
                        }
                      } else if (
                        s.source == Source.HEYBOX &&
                          account.session != null &&
                          authorId == account.session.userId
                      ) {
                        Text(
                          "自己",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.labelSmall,
                        )
                      } else if (
                        s.source == Source.HEYBOX && (profile == null || authorId.isBlank())
                      ) {
                        Text(
                          "作者关注信息不可用",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.labelSmall,
                        )
                      }
                    }
                    if (followState?.error != null) {
                      Text(
                        followState.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                      )
                      if (
                        account.session != null &&
                          profile != null &&
                          authorId.isNotBlank() &&
                          authorId != account.session.userId
                      ) {
                        TextButton(
                          onClick = { authorFollowViewModel.refresh(profile, s) },
                          enabled = !followState.loading && !followState.busy,
                        ) {
                          Text("重新读取关注关系")
                        }
                      }
                    }
                  }
                }
              }
            }
            s.detailNotice
              ?.takeIf { initialContentReady }
              ?.takeIf { article == null }
              ?.let { notice ->
                item {
                  Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                  ) {
                    Column(Modifier.padding(14.dp)) {
                      Text(notice, fontSize = 13.sp, lineHeight = 19.sp)
                      s.url?.let { TextButton(onClick = { onOpenUrl(it) }) { Text("打开原文继续阅读") } }
                    }
                  }
                }
              }
            if (s.source == Source.HEYBOX && account.session == null)
              item {
                Surface(
                  Modifier.fillMaxWidth(),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  shape = RoundedCornerShape(14.dp),
                ) {
                  BoxWithConstraints {
                    if (maxWidth < 300.dp) {
                      Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("登录后读取全文、评论和账号收藏。", fontSize = 13.sp)
                        TextButton(onClick = onLogin) { Text("登录") }
                      }
                    } else {
                      Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Text("登录后读取全文、评论和账号收藏。", Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = onLogin) { Text("登录") }
                      }
                    }
                  }
                }
              }
            account.articleErrors[key]?.let { error ->
              item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { accountViewModel.loadFullArticle(s, force = true) }) {
                  Text("重试")
                }
              }
            }
            if (account.articleLoading.contains(key))
              item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (
              s.source == Source.HEYBOX &&
                account.session != null &&
                article == null &&
                !account.articleLoading.contains(key)
            ) {
              item {
                TextButton(onClick = { accountViewModel.loadFullArticle(s, force = true) }) {
                  Text("重新加载账号正文与评论")
                }
              }
            }
            if (!showInitialSkeleton && translationEligible) {
              item(key = fallbackBodyRevealKey) {
                StaggeredCard(
                  fallbackBodyRevealKey,
                  initialRevealBatch,
                  animatedRevealKeys,
                  bodyMotion,
                ) {
                  SteamTranslationSection(
                    storyKey = key,
                    originalBody = s.body,
                    state = translationState,
                    noImages = ui.noImages,
                    readingPreferences = ui.readingPreferences,
                    onTranslate = translationViewModel::translate,
                    onCancel = translationViewModel::cancel,
                    onShowOriginal = translationViewModel::showOriginal,
                    onShowTranslation = translationViewModel::showTranslation,
                    onOpenUrl = onOpenUrl,
                  )
                }
              }
            } else if (
              !showInitialSkeleton &&
                (accountBlocks?.takeIf { it.isNotEmpty() } ?: s.detailBlocks).orEmpty().isNotEmpty()
            ) {
              val blocks = accountBlocks?.takeIf { it.isNotEmpty() } ?: s.detailBlocks.orEmpty()
              itemsIndexed(
                blocks,
                key = { index, block -> "detail-block:$key:$index:${block::class.simpleName}" },
              ) { index, block ->
                val blockContent: @Composable () -> Unit = {
                  ArticleBlockContent(
                    block,
                    noImages = ui.noImages,
                    onOpenImage = {
                      activeMedia = it
                      activeMediaGallery = null
                      activeMediaInitialIndex = 0
                    },
                    imageOccurrenceStart = articleImageOffsets.getOrElse(index) { 0 },
                    onOpenImageOccurrence = { occurrenceIndex, image ->
                      activeMedia = image
                      activeMediaGallery = articleImageGallery
                      activeMediaInitialIndex = occurrenceIndex
                    },
                    gameState = gameState,
                    onOpenGame = articleGameViewModel::openGameTopic,
                    onRetryInfos = articleGameViewModel::retryInfos,
                    onOpenVideo = { activeVideoUrl = it },
                    onOpenUrl = onOpenUrl,
                    readingPreferences = ui.readingPreferences,
                  )
                }
                if (isTextRevealBlock(block)) {
                  StaggeredCard(
                    "detail-block:$key:$index:${block::class.simpleName}",
                    initialRevealBatch,
                    animatedRevealKeys,
                    bodyMotion,
                    blockContent,
                  )
                } else blockContent()
              }
              article?.restrictedNotice?.let {
                item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
              }
            } else if (!showInitialSkeleton) {
              if (s.body.isNotBlank()) {
                item(key = "detail-fallback:$key") {
                  StaggeredCard(
                    fallbackBodyRevealKey,
                    initialRevealBatch,
                    animatedRevealKeys,
                    bodyMotion,
                  ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      splitReaderParagraphs(s.body).forEach { paragraph ->
                        ArticleBlockContent(
                          cn.heyread.app.data.model.ArticleBlock.Paragraph(paragraph),
                          noImages = ui.noImages,
                          onOpenImage = {
                            activeMedia = it
                            activeMediaGallery = null
                            activeMediaInitialIndex = 0
                          },
                          gameState = gameState,
                          onOpenGame = articleGameViewModel::openGameTopic,
                          onRetryInfos = articleGameViewModel::retryInfos,
                          onOpenVideo = { activeVideoUrl = it },
                          onOpenUrl = onOpenUrl,
                          readingPreferences = ui.readingPreferences,
                        )
                      }
                    }
                  }
                }
              }
            }
            if (gameState.topicError != null) {
              item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    gameState.topicError.orEmpty(),
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                  )
                  TextButton(onClick = articleGameViewModel::retryTopic) { Text("重试") }
                }
              }
            }
            val sections = article?.sections?.ifEmpty { s.sections } ?: s.sections
            val hashtags = article?.hashtags?.ifEmpty { s.hashtags } ?: s.hashtags
            if (sections.isNotEmpty() || hashtags.isNotEmpty())
              item {
                ArticleTaxonomy(
                  sections,
                  hashtags,
                  noImages = ui.noImages,
                  onSectionClick = onOpenSection,
                  onHashtagClick = onOpenHashtag,
                )
              }
            if (s.source == Source.DEMO)
              item {
                Text(
                  "演示数据 · 本地离线内容",
                  color = MaterialTheme.colorScheme.tertiary,
                  fontWeight = FontWeight.Bold,
                )
              }
            s.url?.let { url ->
              item { TextButton(onClick = { onOpenUrl(url) }) { Text("在浏览器打开原文") } }
            }
            item { Spacer(Modifier.height(12.dp)) }
          }
        }
      },
      commentsContent = {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
          isRefreshing = signedInHeybox && key in account.commentRefreshing,
          onRefresh = { if (signedInHeybox) accountViewModel.refreshComments(s) else onLogin() },
          modifier = Modifier.fillMaxSize(),
        ) {
          LazyColumn(
            state = commentsList,
            modifier = Modifier.fillMaxSize().testTag("article-comments-list"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (account.loadingSession) {
              item(key = "comments-session-restoring:$key") {
                Row(
                  Modifier.fillMaxWidth().padding(vertical = 18.dp),
                  horizontalArrangement = Arrangement.Center,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                  Spacer(Modifier.width(10.dp))
                  Text("正在恢复账号…")
                }
              }
            } else if (account.session == null) {
              item(key = "comments-login:$key") {
                Surface(
                  Modifier.fillMaxWidth(),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  shape = RoundedCornerShape(14.dp),
                ) {
                  Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text("登录小黑盒账号后查看这篇文章的评论。")
                    Button(onClick = onLogin, modifier = Modifier.heightIn(min = 48.dp)) {
                      Text("登录后查看评论")
                    }
                  }
                }
              }
            } else {
              account.articleErrors[key]?.let { error ->
                item(key = "comments-error:$key") {
                  Text(error, color = MaterialTheme.colorScheme.error)
                  TextButton(
                    onClick = { accountViewModel.refreshComments(s) },
                    enabled = key !in account.articleLoading,
                  ) {
                    Text("重试读取评论")
                  }
                }
              }
              if (key in account.articleLoading)
                item(key = "comments-loading:$key") {
                  LinearProgressIndicator(Modifier.fillMaxWidth())
                }
              if (
                article == null && key !in account.articleLoading && key !in account.articleErrors
              )
                item(key = "comments-load-article:$key") {
                  TextButton(onClick = { accountViewModel.loadFullArticle(s, force = true) }) {
                    Text("重新加载账号正文与评论")
                  }
                }
              if (
                article?.comments.orEmpty().isEmpty() &&
                  article != null &&
                  key !in account.articleLoading &&
                  key !in account.articleErrors
              ) {
                item(key = "comments-empty:$key") {
                  Text(
                    article.commentsError ?: "暂时没有评论。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              items(article?.comments.orEmpty(), key = { "comment:${it.id}" }) { comment ->
                CommentThreadCard(
                  comment = comment,
                  replies = account.replies[comment.id] ?: comment.replies,
                  replyLoading = comment.id in account.replyLoading,
                  replyHasMore =
                    account.replyHasMore[comment.id] ?: (comment.childCount > comment.replies.size),
                  replyError = account.replyErrors[comment.id],
                  noImages = ui.noImages,
                  supportState = { commentActionStates[it] ?: CommentSupportState() },
                  authorUserId = profile?.userId,
                  onProfileClick = onOpenProfile,
                  onSupport = { target -> commentActionsViewModel.support(target, s) },
                  onImageClick = { image ->
                    if (!ui.noImages) {
                      activeMedia =
                        MediaImage(
                          previewUrl = image.thumbnailUrl ?: image.url,
                          originalUrl =
                            image.originalUrl
                              ?: image.url.takeIf {
                                image.thumbnailUrl != null && image.thumbnailUrl != image.url
                              },
                          animationUrl =
                            if (image.animated) image.originalUrl ?: image.url else null,
                          description = "评论图片",
                        )
                      activeMediaGallery = null
                      activeMediaInitialIndex = 0
                    }
                  },
                  onReply = { targetComment, nested ->
                    val target =
                      if (nested) ReplyTarget.nested(comment.id, targetComment)
                      else ReplyTarget.main(comment)
                    if (socialViewModel.selectCommentTarget(s, target)) showCommentSheet = true
                  },
                  onLoadReplies = {
                    accountViewModel.loadReplies(comment.id, next = comment.id in account.replies)
                  },
                  onMoreReplies = { accountViewModel.loadReplies(comment.id, next = true) },
                  onRetryReplies = { accountViewModel.refreshReplies(comment.id) },
                )
              }
              if (article?.hasMoreComments == true)
                item(key = "comments-more:$key") {
                  TextButton(
                    onClick = { accountViewModel.loadMoreComments(s) },
                    enabled = key !in account.articleLoading,
                  ) {
                    Text("加载更多评论")
                  }
                }
            }
          }
        }
      },
    )
    if (s.source == Source.HEYBOX) {
      HorizontalDivider()
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = { if (signedInHeybox) socialViewModel.like(s) else onLogin() },
          enabled = !signedInHeybox || (social.liked != null && !social.likeBusy),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            "${if (social.liked == true) "♥" else "♡"} 喜欢${social.likeCount?.let { " · $it" } ?: ""}"
          )
        }
        TextButton(
          onClick = {
            if (!signedInHeybox) onLogin()
            else if (socialViewModel.selectCommentTarget(s, ReplyTarget.MainComment))
              showCommentSheet = true
          },
          modifier = Modifier.weight(1f),
        ) {
          Text("评论${social.commentCount?.let { " · $it" } ?: ""}")
        }
        TextButton(
          onClick = {
            if (!signedInHeybox) onLogin()
            else
              socialViewModel.favorite(s) { saved -> accountViewModel.noteCloudFavorite(s, saved) }
          },
          enabled = !signedInHeybox || (social.favorited != null && !social.favoriteBusy),
          modifier = Modifier.weight(1f),
        ) {
          Text("${if (social.favorited == true) "★" else "☆"} 收藏")
        }
      }
      social.error?.let {
        Text(
          it,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }
    }
  }
  val currentMediaIdentity = account.session?.userId to account.revision
  val mediaPreviewPageActive =
    (bodyPageActive || commentsPageActive) && (activeMediaGallery == null || bodyPageActive)
  if (!ui.noImages && mediaAccountIdentity == currentMediaIdentity && mediaPreviewPageActive)
    activeMedia?.let { image ->
      MediaPreviewDialog(
        image = image,
        gallery = activeMediaGallery ?: listOf(image),
        initialIndex = activeMediaInitialIndex,
        onDismiss = {
          activeMedia = null
          activeMediaGallery = null
          activeMediaInitialIndex = 0
        },
      )
    }
  if (gameState.captchaRequired) {
    val challengeId = gameState.captchaChallengeId
    val attemptId = gameState.captchaAttemptId
    val captchaAppId = gameState.captchaAppId
    if (
      challengeId != null &&
        attemptId != null &&
        captchaAppId != null &&
        gameState.captchaDialogOpen
    ) {
      CaptchaChallengeDialog(
        appId = captchaAppId,
        attemptId = attemptId,
        onCallback = { callbackAttempt, result ->
          articleGameViewModel.onCaptchaCallback(challengeId, callbackAttempt, result)
        },
        onDismiss = articleGameViewModel::closeCaptchaDialog,
      )
    }
  }
  activeVideoUrl?.let { url ->
    ArticleVideoPlayerDialog(
      url = url,
      onClose = { activeVideoUrl = null },
      onReloadArticle = { if (s.source == Source.HEYBOX) vm.detail(s, force = true) },
      active = isCurrentPage && bodyPageActive,
    )
  }
  if (showCommentSheet && signedInHeybox) {
    ModalBottomSheet(onDismissRequest = { if (!social.commentBusy) showCommentSheet = false }) {
      Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          social.commentTarget.username?.let { "回复 $it" } ?: "评论这篇文章",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          social.commentTarget.excerpt ?: s.title,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
          value = social.commentDraft,
          onValueChange = { socialViewModel.draft(s, it) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(if (social.commentTarget.isReply) "写下回复" else "写下你的评论") },
          enabled = !social.commentBusy,
          minLines = 3,
          maxLines = 7,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        )
        social.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = { showCommentSheet = false }, enabled = !social.commentBusy) {
            Text("取消")
          }
          Button(
            onClick = {
              socialViewModel.comment(s, article?.authCode) { target ->
                accountViewModel.refreshComments(s)
                target.rootCommentId?.let(accountViewModel::refreshReplies)
                showCommentSheet = false
              }
            },
            enabled = !social.commentBusy && article != null && social.commentDraft.isNotBlank(),
          ) {
            Text(
              if (social.commentBusy) "正在发送…"
              else if (social.commentTarget.isReply) "发送回复" else "发布评论"
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun ArticleBlockContent(
  block: cn.heyread.app.data.model.ArticleBlock,
  noImages: Boolean,
  onOpenImage: (MediaImage) -> Unit,
  gameState: ArticleGameUiState = ArticleGameUiState(),
  onOpenGame: (Int) -> Unit = {},
  onRetryInfos: () -> Unit = {},
  onOpenVideo: (String) -> Unit = {},
  onOpenUrl: (String) -> Unit = {},
  readingPreferences: ReadingPreferences = ReadingPreferences(),
  imageOccurrenceStart: Int = 0,
  onOpenImageOccurrence: ((Int, MediaImage) -> Unit)? = null,
) {
  when (block) {
    is cn.heyread.app.data.model.ArticleBlock.Paragraph ->
      SelectionContainer {
        RichText(
          text = block.text,
          fontSize = readingPreferences.bodyFontSize,
          lineHeight = readingPreferences.bodyLineHeight,
          fontFamily = readingPreferences.font.toComposeFontFamily(),
          noImages = noImages,
          modifier =
            Modifier.testTag("article-paragraph-text")
              .padding(bottom = readingPreferences.paragraphBottomSpacing),
        )
      }
    is cn.heyread.app.data.model.ArticleBlock.ListItem ->
      SelectionContainer {
        Row(
          Modifier.fillMaxWidth()
            .testTag("article-list-item")
            .padding(start = (block.depth * 16).dp, bottom = readingPreferences.scaledListSpacing)
        ) {
          Text(
            "${block.marker} ",
            fontSize = readingPreferences.bodyFontSize,
            lineHeight = readingPreferences.bodyLineHeight,
            fontFamily = readingPreferences.font.toComposeFontFamily(),
          )
          RichText(
            text = block.text,
            modifier = Modifier.weight(1f).testTag("article-list-item-text"),
            fontSize = readingPreferences.bodyFontSize,
            lineHeight = readingPreferences.bodyLineHeight,
            fontFamily = readingPreferences.font.toComposeFontFamily(),
            noImages = noImages,
          )
        }
      }
    is cn.heyread.app.data.model.ArticleBlock.Image ->
      ArticleImageBlock(
        block,
        noImages,
        "article-rich-image",
        onOpenImage,
        imageOccurrenceStart,
        onOpenImageOccurrence,
      )
    is cn.heyread.app.data.model.ArticleBlock.Quote ->
      ArticleQuote(
        block,
        noImages,
        onOpenImage = onOpenImage,
        gameState = gameState,
        onOpenGame = onOpenGame,
        onRetryInfos = onRetryInfos,
        onOpenVideo = onOpenVideo,
        onOpenUrl = onOpenUrl,
        readingPreferences = readingPreferences,
        imageOccurrenceStart = imageOccurrenceStart,
        onOpenImageOccurrence = onOpenImageOccurrence,
      )
    is cn.heyread.app.data.model.ArticleBlock.UnavailableImage ->
      ArticleUnavailableImage(block, noImages)
    is cn.heyread.app.data.model.ArticleBlock.GameCard ->
      ArticleGameCard(
        block.appId,
        gameState.infos[block.appId],
        gameState.loadingInfos,
        gameState.infoError,
        noImages,
        onRetry = onRetryInfos,
        onClick = { onOpenGame(block.appId) },
      )
    is cn.heyread.app.data.model.ArticleBlock.Video ->
      ArticleVideoBlock(block, noImages, onOpenVideo)
    is cn.heyread.app.data.model.ArticleBlock.ExternalVideo ->
      androidx.compose.material3.TextButton(onClick = { onOpenUrl(block.url) }) {
        Text(block.title?.takeIf(String::isNotBlank) ?: "在原站打开视频")
      }
    is cn.heyread.app.data.model.ArticleBlock.UnavailableVideo ->
      Text(
        block.title?.takeIf(String::isNotBlank) ?: "视频暂不可用",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ArticleGameCard(
  appId: Int,
  info: cn.heyread.app.data.remote.PublicGameInfo?,
  loading: Boolean,
  error: String?,
  noImages: Boolean,
  onRetry: () -> Unit,
  onClick: () -> Unit,
) {
  androidx.compose.material3.Surface(
    modifier =
      Modifier.fillMaxWidth()
        .padding(vertical = 7.dp)
        .heightIn(min = 88.dp)
        .clickable(onClick = onClick)
        .testTag("article-game-card-$appId"),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(14.dp),
    border =
      androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!noImages && info?.imageUrl != null) {
        coil.compose.AsyncImage(
          info.imageUrl,
          contentDescription = "${info.name}封面",
          modifier = Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(9.dp)),
          contentScale = ContentScale.Crop,
        )
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          info?.name ?: "游戏 $appId",
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (info == null) {
          Text(
            if (loading) "正在读取游戏资料…" else "资料未返回 · ID $appId",
            style = MaterialTheme.typography.bodySmall,
            color =
              if (error != null) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (!loading)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
              Text(
                if (error != null) "重试读取资料" else "重试读取游戏资料",
                style = MaterialTheme.typography.bodySmall,
              )
            }
        } else {
          val price =
            when {
              info.currentPrice == "免费" -> "免费"
              info.priceSource == cn.heyread.app.data.remote.PriceSource.PLATFORM ->
                info.currentPrice?.let { "平台参考价 $it" }
              info.couponPrice != null -> info.currentPrice?.let { "券后 $it" }
              else -> info.currentPrice
            }
          price?.let { current ->
            FlowRow(
              itemVerticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(7.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Text(
                current,
                modifier = Modifier.testTag("article-game-price-current-$appId"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
              )
              info.originalPrice?.let { old ->
                Text(
                  old,
                  modifier = Modifier.testTag("article-game-price-original-$appId"),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                  maxLines = 1,
                  softWrap = false,
                )
              }
            }
          }
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            info.score?.let { Text("评分 $it", style = MaterialTheme.typography.labelSmall) }
            info.discountPercent?.let { Text("-$it%", style = MaterialTheme.typography.labelSmall) }
            if (info.isNewLowest) Text("新史低", style = MaterialTheme.typography.labelSmall)
            else if (info.isLowest) Text("史低", style = MaterialTheme.typography.labelSmall)
            info.followerCount?.let {
              Text("${it}人关注", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ArticleVideoBlock(
  block: cn.heyread.app.data.model.ArticleBlock.Video,
  noImages: Boolean,
  onOpen: (String) -> Unit,
) {
  androidx.compose.material3.Surface(
    modifier =
      Modifier.fillMaxWidth()
        .padding(vertical = 8.dp)
        .heightIn(min = 54.dp)
        .then(if (block.url != null) Modifier.clickable { onOpen(block.url) } else Modifier)
        .testTag("article-video-block"),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!noImages && block.thumbnailUrl != null) {
        coil.compose.AsyncImage(
          block.thumbnailUrl,
          contentDescription = "视频封面",
          modifier = Modifier.size(width = 112.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)),
          contentScale = ContentScale.Crop,
        )
      }
      Text(
        if (block.url != null) "▶  ${block.title ?: "播放视频"}"
        else "${block.title ?: "视频暂不可播放"}，可打开原文查看",
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (block.durationSeconds != null)
        Text(
          "${block.durationSeconds / 60}:${(block.durationSeconds % 60).toString().padStart(2, '0')}",
          style = MaterialTheme.typography.labelSmall,
        )
    }
  }
}

@Composable
private fun ArticleImageBlock(
  block: cn.heyread.app.data.model.ArticleBlock.Image,
  noImages: Boolean,
  testTag: String,
  onOpen: (MediaImage) -> Unit,
  occurrenceIndex: Int? = null,
  onOpenOccurrence: ((Int, MediaImage) -> Unit)? = null,
) {
  val media =
    MediaImage(
      previewUrl = block.url,
      originalUrl = block.originalUrl,
      description = block.description,
      animationUrl = if (block.animated) block.originalUrl ?: block.url else null,
    )
  if (!noImages)
    MediaImageThumbnail(
      image = media,
      modifier =
        Modifier.fillMaxWidth()
          .padding(top = 8.dp)
          .clip(RoundedCornerShape(12.dp))
          .testTag(testTag),
      onClick = {
        if (occurrenceIndex != null && onOpenOccurrence != null)
          onOpenOccurrence(occurrenceIndex, media)
        else onOpen(media)
      },
    )
  if (block.caption.isNotBlank())
    SelectionContainer {
      RichText(
        text = block.caption,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        noImages = noImages,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
      )
    }
}

private fun ArticleBlock.Image.toMediaImage(): MediaImage =
  MediaImage(
    previewUrl = url,
    originalUrl = originalUrl,
    description = description,
    animationUrl = if (animated) originalUrl ?: url else null,
  )

@Composable
private fun ArticleUnavailableImage(
  block: cn.heyread.app.data.model.ArticleBlock.UnavailableImage,
  noImages: Boolean,
) {
  androidx.compose.material3.Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
      if (!noImages) Text("图片暂不可用", style = MaterialTheme.typography.labelSmall)
      block.description.takeIf(String::isNotBlank)?.let { description ->
        SelectionContainer { RichText(description, noImages = noImages, fontSize = 13.sp) }
      }
      block.caption.takeIf(String::isNotBlank)?.let { caption ->
        SelectionContainer {
          RichText(
            caption,
            noImages = noImages,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun ArticleQuote(
  block: cn.heyread.app.data.model.ArticleBlock.Quote,
  noImages: Boolean,
  depth: Int = 0,
  onOpenImage: (MediaImage) -> Unit,
  gameState: ArticleGameUiState = ArticleGameUiState(),
  onOpenGame: (Int) -> Unit = {},
  onRetryInfos: () -> Unit = {},
  onOpenVideo: (String) -> Unit = {},
  onOpenUrl: (String) -> Unit = {},
  readingPreferences: ReadingPreferences = ReadingPreferences(),
  imageOccurrenceStart: Int = 0,
  onOpenImageOccurrence: ((Int, MediaImage) -> Unit)? = null,
) {
  if (depth >= 4) {
    val fallback = flattenArticleBlocks(block.blocks)
    if (fallback.isNotBlank())
      SelectionContainer {
        RichText(
          text = fallback,
          noImages = noImages,
          fontSize = readingPreferences.quoteFontSize,
          lineHeight = readingPreferences.quoteLineHeight,
          fontFamily = readingPreferences.font.toComposeFontFamily(),
        )
      }
    return
  }
  val quoteLineColor = MaterialTheme.colorScheme.outlineVariant
  val childImageOffsets =
    remember(block.blocks, depth, imageOccurrenceStart) {
      var nextIndex = imageOccurrenceStart
      block.blocks.map { child ->
        val offset = nextIndex
        nextIndex += articleImageCount(listOf(child), depth + 1)
        offset
      }
    }
  androidx.compose.material3.Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f),
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(
      Modifier.fillMaxWidth()
        .drawBehind {
          val x = 2.dp.toPx()
          drawLine(quoteLineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
        }
        .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
    ) {
      block.blocks.forEachIndexed { childIndex, child ->
        val childImageOffset = childImageOffsets[childIndex]
        when (child) {
          is cn.heyread.app.data.model.ArticleBlock.Paragraph ->
            SelectionContainer {
              RichText(
                child.text,
                noImages = noImages,
                fontSize = readingPreferences.quoteFontSize,
                lineHeight = readingPreferences.quoteLineHeight,
                fontFamily = readingPreferences.font.toComposeFontFamily(),
                modifier =
                  Modifier.testTag("article-quote-paragraph-text")
                    .padding(vertical = readingPreferences.quoteBlockSpacing),
              )
            }
          is cn.heyread.app.data.model.ArticleBlock.ListItem ->
            SelectionContainer {
              Row(
                Modifier.fillMaxWidth()
                  .padding(
                    start = (child.depth * 16).dp,
                    top = readingPreferences.quoteBlockSpacing,
                    bottom = readingPreferences.quoteBlockSpacing,
                  )
              ) {
                Text(
                  "${child.marker} ",
                  fontSize = readingPreferences.quoteFontSize,
                  lineHeight = readingPreferences.quoteLineHeight,
                  fontFamily = readingPreferences.font.toComposeFontFamily(),
                )
                RichText(
                  child.text,
                  modifier = Modifier.weight(1f),
                  noImages = noImages,
                  fontSize = readingPreferences.quoteFontSize,
                  lineHeight = readingPreferences.quoteLineHeight,
                  fontFamily = readingPreferences.font.toComposeFontFamily(),
                )
              }
            }
          is cn.heyread.app.data.model.ArticleBlock.Image ->
            ArticleImageBlock(
              child,
              noImages,
              "quote-image",
              onOpenImage,
              childImageOffset,
              onOpenImageOccurrence,
            )
          is cn.heyread.app.data.model.ArticleBlock.Quote ->
            ArticleQuote(
              child,
              noImages,
              depth + 1,
              onOpenImage,
              gameState,
              onOpenGame,
              onRetryInfos,
              onOpenVideo,
              onOpenUrl,
              readingPreferences,
              childImageOffset,
              onOpenImageOccurrence,
            )
          is cn.heyread.app.data.model.ArticleBlock.UnavailableImage ->
            ArticleUnavailableImage(child, noImages)
          is cn.heyread.app.data.model.ArticleBlock.GameCard ->
            ArticleGameCard(
              child.appId,
              gameState.infos[child.appId],
              gameState.loadingInfos,
              gameState.infoError,
              noImages,
              onRetry = onRetryInfos,
              onClick = { onOpenGame(child.appId) },
            )
          is cn.heyread.app.data.model.ArticleBlock.Video ->
            ArticleVideoBlock(child, noImages, onOpenVideo)
          is cn.heyread.app.data.model.ArticleBlock.ExternalVideo ->
            androidx.compose.material3.TextButton(onClick = { onOpenUrl(child.url) }) {
              Text("在原站打开视频")
            }
          is cn.heyread.app.data.model.ArticleBlock.UnavailableVideo ->
            Text(child.title ?: "视频暂不可用", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

private fun flattenArticleBlocks(blocks: List<cn.heyread.app.data.model.ArticleBlock>): String =
  blocks
    .mapNotNull { block ->
      when (block) {
        is cn.heyread.app.data.model.ArticleBlock.Paragraph -> block.text
        is cn.heyread.app.data.model.ArticleBlock.ListItem -> "${block.marker} ${block.text}"
        is cn.heyread.app.data.model.ArticleBlock.Image ->
          block.caption.ifBlank { block.description }
        is cn.heyread.app.data.model.ArticleBlock.UnavailableImage ->
          block.caption.ifBlank { block.description }
        is cn.heyread.app.data.model.ArticleBlock.Quote -> flattenArticleBlocks(block.blocks)
        is cn.heyread.app.data.model.ArticleBlock.GameCard -> ""
        is cn.heyread.app.data.model.ArticleBlock.Video -> block.title.orEmpty()
        is cn.heyread.app.data.model.ArticleBlock.ExternalVideo -> block.title.orEmpty()
        is cn.heyread.app.data.model.ArticleBlock.UnavailableVideo -> block.title.orEmpty()
      }
    }
    .filter(String::isNotBlank)
    .joinToString("\n")

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MineScreen(
  ui: UiState,
  vm: AppViewModel,
  account: AccountUiState,
  accountViewModel: AccountViewModel,
  onLogin: () -> Unit,
  onOpenFavorites: () -> Unit,
  onAccountHistory: () -> Unit,
  onOpenOwnProfile: () -> Unit = {},
  onOpenSettingsCategory: ((SettingsCategory) -> Unit)? = null,
  selectedSettingsCategory: SettingsCategory? = null,
  isCurrentPage: Boolean = true,
  onOpen: (Story) -> Unit,
) {
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    MineSettingsContent(
      ui = ui,
      account = account,
      isCurrentPage = isCurrentPage,
      onLogin = onLogin,
      onLogout = accountViewModel::logout,
      onOpenFavorites = onOpenFavorites,
      onAccountHistory = onAccountHistory,
      onOpenOwnProfile = onOpenOwnProfile,
      onThemeChange = { vm.settings(theme = it) },
      onHomeLayoutChange = vm::setHomeLayout,
      onReadingPreferencesChange = vm::setReadingPreferences,
      onAccentChange = vm::setAccent,
      onMotionChange = vm::setMotionEnabled,
      onNoImagesChange = { vm.settings(noImages = it) },
      onGatewaySave = vm::testGateway,
      onClearCache = vm::clearCache,
      onOpenStory = onOpen,
      onClearHistory = vm::clearHistory,
      onOpenCategory = onOpenSettingsCategory,
      selectedCategory = selectedSettingsCategory,
    )
  }
}

@Composable
fun EmptyState(title: String, body: String, action: () -> Unit, actionLabel: String = "查看离线演示") {
  Column(
    Modifier.fillMaxWidth().padding(vertical = 42.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      Icons.Filled.Info,
      null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(42.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    TextButton(onClick = action) { Text(actionLabel) }
  }
}

@Composable
fun ErrorPage(text: String, back: () -> Unit, isCurrentPage: Boolean = true) {
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar(text, isRoot = false, onBack = back)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
      Text(text)
      TextButton(back) { Text("返回") }
    }
  }
}

private fun sourceLabel(s: Source) =
  when (s) {
    Source.STEAM -> "Steam"
    Source.HEYBOX -> "小黑盒"
    Source.DEMO -> "演示"
  }

@Preview(showBackground = true, name = "盒阅文章列表")
@Composable
private fun StoryRowPreview() {
  MaterialTheme {
    StoryRow(
      Story(
        "preview",
        Source.DEMO,
        "一座城市，如何成为最好的冒险地图",
        "开放世界的魅力藏在每个转角。",
        "演示文章内容",
        "盒阅编辑部",
        "演示数据 · 离线可读",
      ),
      true,
      onOpen = {},
    )
  }
}
