package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.Channel
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FeedTab {
  DISCOVER,
  GAMES,
}

data class FeedPageState(
  val source: Source,
  val channel: Channel = Channel.RECOMMEND,
  val steamAppId: Int = 570,
  val steamGameName: String? = null,
  val items: List<Story> = emptyList(),
  val page: Int = -1,
  val loading: Boolean = false,
  val error: String? = null,
  val hasMore: Boolean = true,
  val hasRequested: Boolean = false,
  val refreshing: Boolean = false,
  val loadingMore: Boolean = false,
)

data class FeedStates(
  val discover: FeedPageState = FeedPageState(Source.HEYBOX),
  val games: FeedPageState = FeedPageState(Source.STEAM),
) {
  fun forTab(tab: FeedTab): FeedPageState =
    when (tab) {
      FeedTab.DISCOVER -> discover
      FeedTab.GAMES -> games
    }

  internal fun replacing(tab: FeedTab, page: FeedPageState): FeedStates =
    when (tab) {
      FeedTab.DISCOVER -> copy(discover = page)
      FeedTab.GAMES -> copy(games = page)
    }
}

fun interface FeedLoader {
  suspend fun load(source: Source, channel: Channel, page: Int, appId: Int): StoryPage
}

class FeedCoordinator(private val scope: CoroutineScope, private val loader: FeedLoader) {
  private val mutableStates = MutableStateFlow(FeedStates())
  val states = mutableStates.asStateFlow()

  private val jobs = mutableMapOf<FeedTab, Job>()
  private val generations = mutableMapOf(FeedTab.DISCOVER to 0L, FeedTab.GAMES to 0L)

  fun ensureLoaded(tab: FeedTab) {
    if (!mutableStates.value.forTab(tab).hasRequested) loadPage(tab, 0)
  }

  fun selectSource(tab: FeedTab, source: Source, force: Boolean = false) {
    val current = mutableStates.value.forTab(tab)
    if (!force && current.source == source) return
    update(tab) {
      it.copy(
        source = source,
        items = emptyList(),
        page = -1,
        loading = false,
        error = null,
        hasMore = true,
        hasRequested = false,
        refreshing = false,
        loadingMore = false,
      )
    }
    loadPage(tab, 0)
  }

  fun selectChannel(channel: Channel) {
    val current = mutableStates.value.discover
    if (current.channel == channel) return
    update(FeedTab.DISCOVER) {
      it.copy(
        channel = channel,
        items = emptyList(),
        page = -1,
        loading = false,
        error = null,
        hasMore = true,
        hasRequested = false,
        refreshing = false,
        loadingMore = false,
      )
    }
    loadPage(FeedTab.DISCOVER, 0)
  }

  fun selectGame(appId: Int, name: String? = null) {
    require(appId > 0) { "Steam AppID必须是正整数。" }
    val current = mutableStates.value.games
    val requestedName = name?.trim()?.takeIf(String::isNotEmpty)
    if (current.steamAppId == appId && current.source == Source.STEAM) {
      if (requestedName != null && current.steamGameName != requestedName) {
        update(FeedTab.GAMES) { page ->
          page.copy(
            steamGameName = requestedName,
            items =
              page.items.map { story ->
                if (story.source == Source.STEAM && story.appId == appId)
                  story.copy(author = requestedName)
                else story
              },
          )
        }
      }
      if (!current.hasRequested) loadPage(FeedTab.GAMES, 0)
      return
    }
    update(FeedTab.GAMES) {
      it.copy(
        source = Source.STEAM,
        steamAppId = appId,
        steamGameName = requestedName,
        items = emptyList(),
        page = -1,
        loading = false,
        error = null,
        hasMore = true,
        hasRequested = false,
        refreshing = false,
        loadingMore = false,
      )
    }
    loadPage(FeedTab.GAMES, 0)
  }

  fun refresh(tab: FeedTab = FeedTab.DISCOVER) = loadPage(tab, 0)

  fun loadMore(tab: FeedTab = FeedTab.DISCOVER) {
    val page = mutableStates.value.forTab(tab)
    if (page.loading || page.refreshing || page.loadingMore || !page.hasMore) return
    loadPage(tab, if (page.page < 0) 0 else page.page + 1)
  }

  private fun loadPage(tab: FeedTab, pageNumber: Int) {
    jobs[tab]?.cancel()
    val generation = generations.getValue(tab) + 1
    generations[tab] = generation
    val snapshot = mutableStates.value.forTab(tab)
    update(tab) {
      it.copy(
        hasRequested = true,
        loading = pageNumber == 0 && snapshot.items.isEmpty(),
        refreshing = pageNumber == 0 && snapshot.items.isNotEmpty(),
        loadingMore = pageNumber > 0,
        error = null,
      )
    }
    jobs[tab] =
      scope.launch {
        try {
          val result =
            loader.load(snapshot.source, snapshot.channel, pageNumber, snapshot.steamAppId)
          if (generations[tab] != generation) return@launch
          val current = mutableStates.value.forTab(tab)
          if (!sameSelection(current, snapshot)) return@launch
          val normalizedItems =
            result.items.map { story ->
              if (
                story.source == Source.STEAM &&
                  story.appId == current.steamAppId &&
                  current.steamGameName != null
              )
                story.copy(author = current.steamGameName)
              else story
            }
          val merged =
            if (pageNumber == 0) normalizedItems
            else (current.items + normalizedItems).distinctBy { "${it.source}:${it.id}" }
          val added = merged.size - if (pageNumber == 0) 0 else current.items.size
          update(tab) {
            it.copy(
              items = merged,
              page = pageNumber,
              loading = false,
              refreshing = false,
              loadingMore = false,
              error = null,
              // A successful empty page can be caused by a channel filter, and the
              // server may still have later pages. Only stop on a non-empty page
              // that contributes no new stories (a repeated page).
              hasMore = result.hasMore && (result.items.isEmpty() || added > 0),
            )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (generations[tab] != generation) return@launch
          val current = mutableStates.value.forTab(tab)
          if (!sameSelection(current, snapshot)) return@launch
          update(tab) {
            it.copy(
              loading = false,
              refreshing = false,
              loadingMore = false,
              error = feedErrorMessage(error),
            )
          }
        }
      }
  }

  private fun sameSelection(left: FeedPageState, right: FeedPageState) =
    left.source == right.source &&
      left.channel == right.channel &&
      left.steamAppId == right.steamAppId

  private inline fun update(tab: FeedTab, crossinline transform: (FeedPageState) -> FeedPageState) {
    mutableStates.update { it.replacing(tab, transform(it.forTab(tab))) }
  }
}

internal fun feedErrorMessage(error: Exception): String =
  when (error) {
    is SocketTimeoutException -> "请求超时，请稍后重试。"
    is UnknownHostException,
    is IOException -> "网络暂时不可用，请检查连接后重试。"
    else ->
      error.message?.takeIf { !it.contains("Exception") && !it.contains(" at ") } ?: "内容读取失败，请稍后重试。"
  }
