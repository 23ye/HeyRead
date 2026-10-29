package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.Channel
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.StoryPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedCoordinatorTest {
  @Test
  fun discoverAndGamesKeepIndependentSourcesAndItems() = runTest {
    val calls = mutableListOf<Pair<Source, Int>>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, page, _ ->
          calls += source to page
          StoryPage(listOf(story("${source.name}-$page", source)), hasMore = true)
        },
      )

    coordinator.ensureLoaded(FeedTab.DISCOVER)
    coordinator.ensureLoaded(FeedTab.GAMES)
    runCurrent()

    assertEquals(listOf(Source.HEYBOX, Source.STEAM), calls.map { it.first })
    assertEquals(listOf("HEYBOX-0"), coordinator.states.value.discover.items.map { it.id })
    assertEquals(listOf("STEAM-0"), coordinator.states.value.games.items.map { it.id })

    coordinator.loadMore(FeedTab.GAMES)
    runCurrent()
    assertEquals(listOf("HEYBOX-0"), coordinator.states.value.discover.items.map { it.id })
    assertEquals(listOf("STEAM-0", "STEAM-1"), coordinator.states.value.games.items.map { it.id })
  }

  @Test
  fun requestsCanFinishOutOfOrderAndEnsureLoadedDoesNotReload() = runTest {
    val discoverResult = CompletableDeferred<StoryPage>()
    val gamesResult = CompletableDeferred<StoryPage>()
    var discoverCalls = 0
    var gamesCalls = 0
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, _, _ ->
          if (source == Source.HEYBOX) {
            discoverCalls++
            discoverResult.await()
          } else {
            gamesCalls++
            gamesResult.await()
          }
        },
      )

    coordinator.ensureLoaded(FeedTab.DISCOVER)
    coordinator.ensureLoaded(FeedTab.GAMES)
    coordinator.ensureLoaded(FeedTab.GAMES)
    runCurrent()
    gamesResult.complete(StoryPage(listOf(story("game", Source.STEAM)), true))
    runCurrent()
    assertEquals(listOf("game"), coordinator.states.value.games.items.map { it.id })
    assertTrue(coordinator.states.value.discover.items.isEmpty())

    discoverResult.complete(StoryPage(listOf(story("discover", Source.HEYBOX)), true))
    runCurrent()
    assertEquals(1, discoverCalls)
    assertEquals(1, gamesCalls)
    assertEquals(listOf("discover"), coordinator.states.value.discover.items.map { it.id })
    assertEquals(listOf("game"), coordinator.states.value.games.items.map { it.id })
  }

  @Test
  fun successfulEmptyPagesAdvanceAndKeepServerHasMore() = runTest {
    val requestedPages = mutableListOf<Int>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, page, _ ->
          requestedPages += page
          when (page) {
            0,
            1 -> StoryPage(emptyList(), hasMore = true)
            else -> StoryPage(listOf(story("visible", source)), hasMore = false)
          }
        },
      )

    coordinator.ensureLoaded(FeedTab.DISCOVER)
    runCurrent()
    assertTrue(coordinator.states.value.discover.hasMore)
    assertEquals(0, coordinator.states.value.discover.page)

    coordinator.loadMore(FeedTab.DISCOVER)
    runCurrent()
    assertTrue(coordinator.states.value.discover.hasMore)
    assertEquals(1, coordinator.states.value.discover.page)

    coordinator.loadMore(FeedTab.DISCOVER)
    runCurrent()
    assertEquals(listOf(0, 1, 2), requestedPages)
    assertEquals(listOf("visible"), coordinator.states.value.discover.items.map { it.id })
    assertFalse(coordinator.states.value.discover.hasMore)
  }

  @Test
  fun pageFailureCanRetrySamePageWithoutDroppingExistingItems() = runTest {
    val requestedPages = mutableListOf<Int>()
    var failNext = false
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, page, _ ->
          requestedPages += page
          if (failNext) {
            failNext = false
            error("temporary")
          }
          StoryPage(listOf(story("page-$page", source)), hasMore = true)
        },
      )

    coordinator.ensureLoaded(FeedTab.DISCOVER)
    runCurrent()
    failNext = true
    coordinator.loadMore(FeedTab.DISCOVER)
    runCurrent()
    assertEquals("temporary", coordinator.states.value.discover.error)
    assertEquals(listOf("page-0"), coordinator.states.value.discover.items.map { it.id })

    coordinator.loadMore(FeedTab.DISCOVER)
    runCurrent()
    assertEquals(listOf(0, 1, 1), requestedPages)
    assertEquals(listOf("page-0", "page-1"), coordinator.states.value.discover.items.map { it.id })
  }

  @Test
  fun lateOldChannelResultCannotReplaceNewChannel() = runTest {
    val oldResult = CompletableDeferred<StoryPage>()
    val started = CompletableDeferred<Unit>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, channel, _, _ ->
          if (channel == Channel.RECOMMEND) {
            started.complete(Unit)
            withContext(NonCancellable) { oldResult.await() }
          } else StoryPage(listOf(story("${channel.name}-new", source)), hasMore = false)
        },
      )

    coordinator.ensureLoaded(FeedTab.DISCOVER)
    runCurrent()
    started.await()
    coordinator.selectChannel(Channel.NEWS)
    runCurrent()
    oldResult.complete(StoryPage(listOf(story("stale", Source.HEYBOX)), hasMore = false))
    runCurrent()

    assertEquals(Channel.NEWS, coordinator.states.value.discover.channel)
    assertEquals(listOf("NEWS-new"), coordinator.states.value.discover.items.map { it.id })
  }

  @Test
  fun lateOldGameFailureCannotReplaceNewGameSelection() = runTest {
    val oldResult = CompletableDeferred<StoryPage>()
    val oldStarted = CompletableDeferred<Unit>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, _, appId ->
          if (appId == 570) {
            oldStarted.complete(Unit)
            withContext(NonCancellable) { oldResult.await() }
          } else StoryPage(listOf(story("app-$appId", source)), hasMore = true)
        },
      )

    coordinator.ensureLoaded(FeedTab.GAMES)
    runCurrent()
    oldStarted.await()
    coordinator.selectGame(730)
    runCurrent()
    oldResult.completeExceptionally(IllegalStateException("stale failure"))
    runCurrent()

    val games = coordinator.states.value.games
    assertEquals(730, games.steamAppId)
    assertEquals(listOf("app-730"), games.items.map { it.id })
    assertEquals(null, games.error)
  }

  @Test
  fun lateOldGamePageCannotReplaceNewAppAndItsDisplayName() = runTest {
    val oldResult = CompletableDeferred<StoryPage>()
    val oldStarted = CompletableDeferred<Unit>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, _, appId ->
          if (appId == 570) {
            oldStarted.complete(Unit)
            withContext(NonCancellable) { oldResult.await() }
          } else StoryPage(listOf(story("app-$appId", source).copy(appId = appId)), true)
        },
      )

    coordinator.selectGame(570, "Dota 2")
    runCurrent()
    oldStarted.await()
    coordinator.selectGame(367520, "Hollow Knight")
    runCurrent()
    oldResult.complete(StoryPage(listOf(story("stale", Source.STEAM).copy(appId = 570)), true))
    runCurrent()

    val games = coordinator.states.value.games
    assertEquals(367520, games.steamAppId)
    assertEquals("Hollow Knight", games.steamGameName)
    assertEquals(listOf("app-367520"), games.items.map { it.id })
  }

  @Test
  fun selectedSteamNameUpdatesCurrentStoriesWithoutReloadingSameApp() = runTest {
    val loads = mutableListOf<Int>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, _, appId ->
          loads += appId
          StoryPage(listOf(story("app-$appId", source).copy(appId = appId)), true)
        },
      )

    coordinator.selectGame(367520, "Hollow Knight")
    runCurrent()
    assertEquals(listOf(367520), loads)
    assertEquals("Hollow Knight", coordinator.states.value.games.items.single().author)

    coordinator.selectGame(367520, "Hollow Knight: Silksong")
    runCurrent()
    assertEquals(listOf(367520), loads)
    assertEquals("Hollow Knight: Silksong", coordinator.states.value.games.steamGameName)
    assertEquals("Hollow Knight: Silksong", coordinator.states.value.games.items.single().author)
    assertEquals(0, coordinator.states.value.games.page)
  }

  @Test
  fun paginationIsMutuallyExclusiveAndRepeatedPageStops() = runTest {
    val requestedPages = mutableListOf<Int>()
    val coordinator =
      FeedCoordinator(
        this,
        FeedLoader { source, _, page, _ ->
          requestedPages += page
          StoryPage(listOf(story("same", source)), hasMore = true)
        },
      )

    coordinator.ensureLoaded(FeedTab.DISCOVER)
    runCurrent()
    coordinator.loadMore(FeedTab.DISCOVER)
    coordinator.loadMore(FeedTab.DISCOVER)
    runCurrent()

    assertEquals(listOf(0, 1), requestedPages)
    assertEquals(listOf("same"), coordinator.states.value.discover.items.map { it.id })
    assertFalse(coordinator.states.value.discover.hasMore)
  }

  private fun story(id: String, source: Source) =
    Story(id = id, source = source, title = id, excerpt = "", body = "", author = "", date = "")
}
