package cn.heyread.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.ContentBlockingScope
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.components.ContentBlockingNotice
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.motion.HomeLayoutPaginationGate
import cn.heyread.app.ui.motion.LocalMotionEnabled
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.StaggeredCard
import cn.heyread.app.ui.motion.cancelSharedCoverOnScroll
import cn.heyread.app.ui.motion.pageInputGate
import cn.heyread.app.ui.motion.rememberFirstVisibleBatch
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.AccountViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun LoginScreen(viewModel: AccountViewModel, onBack: () -> Unit, isCurrentPage: Boolean = true) {
  val state by viewModel.state.collectAsState()
  val secondsLeft by
    produceState(0L, state.challenge?.expiresAtMillis) {
      while (state.challenge != null) {
        value =
          ((state.challenge!!.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L) /
            1000L)
        delay(1_000L)
      }
      value = 0L
    }
  val lifecycleOwner = LocalLifecycleOwner.current
  val context = LocalContext.current

  LaunchedEffect(Unit) { viewModel.startQr() }
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> viewModel.resumeQr()
        Lifecycle.Event.ON_STOP -> viewModel.pauseQr()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.stopQr()
    }
  }

  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar("登录小黑盒", isRoot = false, onBack = onBack)
    Column(
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(20.dp))
      Text(
        "扫码后阅读文章全文与评论",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "使用小黑盒官方 App 扫描并确认。无需把密码或 Cookie 提供给任何人。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
      Spacer(Modifier.height(24.dp))
      val qrBitmap = remember(state.challenge?.url) { state.challenge?.url?.let(::makeQrBitmap) }
      if (qrBitmap != null) {
        Image(
          bitmap = qrBitmap.asImageBitmap(),
          contentDescription = "小黑盒登录二维码",
          modifier = Modifier.size(256.dp).clip(RoundedCornerShape(18.dp)),
        )
        val shareLabel = "分享二维码图片"
        OutlinedButton(
          onClick = {
            runCatching {
              val folder = File(context.cacheDir, "shared_qr").apply { mkdirs() }
              val image = File(folder, "heybox-login.png")
              FileOutputStream(image).use { stream ->
                qrBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
              }
              val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", image)
              val send =
                Intent(Intent.ACTION_SEND)
                  .setType("image/png")
                  .putExtra(Intent.EXTRA_STREAM, uri)
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              context.startActivity(Intent.createChooser(send, shareLabel))
            }
          },
          modifier = Modifier.padding(top = 12.dp),
        ) {
          Icon(Icons.Default.Share, null)
          Spacer(Modifier.width(8.dp))
          Text(shareLabel)
        }
      } else if (state.loadingQr) {
        Box(Modifier.size(256.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else {
        Surface(
          modifier = Modifier.size(256.dp),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("二维码暂不可用", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      Text(
        state.message,
        modifier = Modifier.padding(top = 18.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (state.challenge != null && secondsLeft > 0L) {
        Text(
          "二维码剩余约 $secondsLeft 秒",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (state.session == null && !state.loadingQr) {
        Button(onClick = viewModel::startQr, modifier = Modifier.padding(top = 12.dp)) {
          Text("重新获取二维码")
        }
      }
      state.session?.let { session ->
        Text(
          session.nickname.ifBlank { "小黑盒用户" },
          modifier = Modifier.padding(top = 12.dp),
          fontWeight = FontWeight.SemiBold,
        )
        Text("账号 ID：${session.userId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountLibraryScreen(
  state: AccountUiState,
  account: AccountViewModel,
  onLogin: () -> Unit,
  onOpen: (Story) -> Unit,
  noImages: Boolean = false,
  homeLayout: HomeLayout = HomeLayout.A,
  onOpenSection: ((ArticleLabel) -> Unit)? = null,
  history: Boolean = false,
  isRoot: Boolean = !history,
  onBack: () -> Unit = {},
  isCurrentPage: Boolean = true,
  selectedStoryIdentity: String? = null,
) {
  var lastInitialLoadKey by rememberSaveable { mutableStateOf("") }
  val initialLoadKey = "${state.session?.userId.orEmpty()}:${state.revision}:$history"
  LaunchedEffect(initialLoadKey, isCurrentPage) {
    if (isCurrentPage && state.session != null && lastInitialLoadKey != initialLoadKey) {
      lastInitialLoadKey = initialLoadKey
      if (history) account.loadCloudHistory(0) else account.loadCloudFavorites()
    }
  }
  val title = if (history) "小黑盒浏览历史" else "收藏"
  val stories = if (history) state.cloudHistory else state.cloudFavorites
  val listState = rememberLazyListState()
  val storyIdsByListKey =
    remember(stories, history) {
      stories
        .filter { history && it.source == cn.heyread.app.data.model.Source.HEYBOX }
        .associate { "${it.source}:${it.id}" to it.id }
    }
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(
    history,
    listState,
    lifecycleOwner,
    state.session?.userId,
    state.revision,
    state.cloudMode,
    state.cloudLoading,
    state.historyAuthorGeneration,
    isCurrentPage,
    storyIdsByListKey,
  ) {
    if (
      !history ||
        !isCurrentPage ||
        state.session == null ||
        state.cloudMode != "history" ||
        state.cloudLoading
    )
      return@LaunchedEffect
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      snapshotFlow {
          val visibleKeys =
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
          visibleKeys.mapNotNull(storyIdsByListKey::get).toSet()
        }
        .distinctUntilChanged()
        .collectLatest { storyIds ->
          if (account.state.value.historyAuthorGeneration == state.historyAuthorGeneration)
            account.resolveHistoryAuthors(storyIds)
        }
    }
  }
  cancelSharedCoverOnScroll(
    listState,
    LocalSharedCoverEntryId.current,
    LocalSharedCoverCoordinator.current,
  )
  val motion = LocalMotionEnabled.current
  val listIdentity =
    "account:${state.session?.userId.orEmpty()}:${if (history) "history" else state.cloudFolderId.orEmpty()}"
  val blocking =
    rememberContentBlockingList(
      raw = stories,
      scope = if (history) ContentBlockingScope.HISTORY else ContentBlockingScope.FAVORITES,
      identity = listIdentity,
      isCurrentPage = isCurrentPage,
      listState = listState,
    )
  val storyKeys =
    remember(blocking.items) { blocking.items.map { "${it.source}:${it.id}" }.toSet() }
  val firstBatch = rememberFirstVisibleBatch(listIdentity, listState, storyKeys, motion)
  val paneResizeEpoch = LocalPaneResizeEpoch.current
  val layoutGateSaver =
    listSaver<HomeLayoutPaginationGate, String>(
      save = { listOf(it.observedLayout.name, it.suppressAutoPagination.toString()) },
      restore = {
        HomeLayoutPaginationGate(HomeLayout.valueOf(it[0]), it[1].toBooleanStrictOrNull() ?: false)
      },
    )
  var historyLayoutGate by
    rememberSaveable(listIdentity, stateSaver = layoutGateSaver) {
      mutableStateOf(HomeLayoutPaginationGate(homeLayout))
    }
  LaunchedEffect(homeLayout, history) {
    if (history) historyLayoutGate = historyLayoutGate.observeLayout(homeLayout)
  }
  var resizePaginationSuppressed by rememberSaveable(listIdentity) { mutableStateOf(false) }
  LaunchedEffect(paneResizeEpoch, history) {
    if (paneResizeEpoch > 0) {
      resizePaginationSuppressed = true
      if (history) historyLayoutGate = historyLayoutGate.suppressForPaneResize()
    }
  }
  LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { interaction ->
      if (interaction is DragInteraction.Start) {
        resizePaginationSuppressed = false
        historyLayoutGate = historyLayoutGate.copy(observedLayout = homeLayout).userScrolled()
      }
    }
  }
  val animatedKeys =
    rememberSaveable(
      listIdentity,
      saver = listSaver({ it.toList() }, { it.toMutableStateList() }),
    ) {
      mutableStateListOf<String>()
    }
  val hasMore = if (history) state.historyHasMore else state.favoritesHasMore
  LaunchedEffect(
    listState,
    stories.size,
    hasMore,
    state.cloudLoading,
    state.cloudError,
    blocking.canAutoPaginate,
    isCurrentPage,
    if (history) homeLayout else null,
    history,
    if (history) historyLayoutGate.observedLayout else null,
    if (history) historyLayoutGate.suppressAutoPagination else null,
  ) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
      .collect { last ->
        if (
          isCurrentPage &&
            !resizePaginationSuppressed &&
            (!history ||
              (historyLayoutGate.observedLayout == homeLayout &&
                !historyLayoutGate.suppressAutoPagination)) &&
            state.session != null &&
            stories.isNotEmpty() &&
            hasMore &&
            blocking.canAutoPaginate &&
            !state.cloudLoading &&
            state.cloudError == null &&
            listState.layoutInfo.totalItemsCount - last <= 3
        )
          account.loadMoreCloud()
      }
  }
  Column(
    Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .pageInputGate(isCurrentPage)
  ) {
    PageTopBar(title, isRoot = isRoot, onBack = onBack)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
      isRefreshing = state.cloudRefreshing,
      onRefresh = account::refreshCloud,
      modifier = Modifier.weight(1f),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (blocking.loading || blocking.error != null || blocking.matchedCount > 0) {
          item(key = "account-library-content-blocking-notice") { ContentBlockingNotice(blocking) }
        }
        if (state.session == null) {
          item {
            Text(
              "登录后读取小黑盒账号${if (history) "浏览历史" else "收藏夹"}。",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          item { Button(onClick = onLogin) { Text("登录小黑盒") } }
        } else {
          if (!history)
            item {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
              ) {
                FilterChip(
                  selected = state.cloudFolderId == null,
                  onClick = account::selectAllCloudFavorites,
                  label = { Text("全部收藏") },
                )
                state.folders.forEach { folder ->
                  FilterChip(
                    selected = state.cloudFolderId == folder.id,
                    onClick = { account.selectCloudFolder(folder) },
                    label = { Text(folder.name) },
                  )
                }
              }
            }
          item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                if (history) "来自小黑盒账号" else "来自小黑盒收藏夹",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (history && state.historyAuthorErrorIds.isNotEmpty())
                Text("部分作者资料暂不可用，下拉刷新重试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          state.cloudError?.let { error ->
            item {
              Text(error, color = MaterialTheme.colorScheme.error)
              TextButton(onClick = account::refreshCloud) { Text("重试") }
            }
          }
          if (state.cloudLoading && stories.isEmpty())
            item { CircularProgressIndicator(Modifier.padding(24.dp)) }
          if (!state.cloudLoading && state.cloudError == null && stories.isEmpty())
            item {
              Text(
                if (history) "账号历史为空。" else "这个收藏夹还没有内容。",
                modifier = Modifier.padding(vertical = 20.dp),
              )
            }
          itemsIndexed(blocking.items, key = { _, story -> "${story.source}:${story.id}" }) {
            index,
            story ->
            StaggeredCard("${story.source}:${story.id}", firstBatch, animatedKeys, motion) {
              val displayStory =
                when {
                  history && story.id in state.historyAuthorLoadingIds ->
                    story.copy(author = "作者资料加载中…")
                  history && story.id in state.historyAuthorErrorIds ->
                    story.copy(author = "作者资料暂不可用")
                  else -> story
                }
              HomeStoryCard(
                story = displayStory,
                layout = homeLayout,
                featured = index == 0,
                noImages = noImages,
                onOpen = { onOpen(story) },
                onOpenSection = onOpenSection,
                selected = "${story.source}:${story.id}" == selectedStoryIdentity,
              )
            }
          }
          if (hasMore && stories.isNotEmpty())
            item {
              if (state.cloudAppending)
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
              else
                TextButton(
                  onClick = {
                    resizePaginationSuppressed = false
                    historyLayoutGate =
                      historyLayoutGate.copy(observedLayout = homeLayout).userScrolled()
                    blocking.beforeManualLoad()
                    account.loadMoreCloud()
                  }
                ) {
                  Text("加载更多")
                }
            }
        }
      }
    }
  }
}

private fun makeQrBitmap(content: String): android.graphics.Bitmap? =
  runCatching {
      val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
      android.graphics.Bitmap.createBitmap(
          matrix.width,
          matrix.height,
          android.graphics.Bitmap.Config.ARGB_8888,
        )
        .apply {
          for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            setPixel(
              x,
              y,
              if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
          }
        }
    }
    .getOrNull()
