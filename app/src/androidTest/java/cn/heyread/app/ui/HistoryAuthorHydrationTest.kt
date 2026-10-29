package cn.heyread.app.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountRequestExecutor
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.account.SessionStore
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.LocalContentBlockingContext
import cn.heyread.app.ui.screens.AccountLibraryScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountViewModel
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryAuthorHydrationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val viewModelStore = ViewModelStore()
  private val fixtures = mutableListOf<Fixture>()
  private val independentScopes = mutableListOf<CoroutineScope>()
  private val independentJobs = mutableListOf<Job>()

  @After
  fun clearIsolatedSessionsAndViewModels() {
    independentJobs.forEach(Job::cancel)
    independentScopes.forEach { it.cancel() }
    viewModelStore.clear()
    fixtures.forEach { fixture ->
      fixture.repository.clearEphemeralCookies()
      SessionStore(fixture.application).clear()
    }
  }

  @Test
  fun visibleHistoryRendersBeforeAuthorInfoAndRequestsOnlyMissingAuthorsByLinkId() {
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val authorGate = CompletableDeferred<String>()
    val fixture =
      createFixture(
        historyBody = { historyResponse(missingEntry("missing-1"), knownEntry("known-2")) }
      ) { request ->
        authorRequests += request
        authorGate.await()
      }
    awaitSession(fixture)
    val currentPage = mutableStateOf(true)
    setHistoryScreen(fixture, currentPage)

    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.cloudHistory.size == 2 &&
        !fixture.viewModel.state.value.cloudLoading &&
        authorRequests.isNotEmpty()
    }
    val loadedMissingStory = fixture.viewModel.state.value.cloudHistory.first()
    assertEquals("missing-1", loadedMissingStory.id)
    assertEquals("作者未公开", loadedMissingStory.author)
    assertFalse(fixture.viewModel.state.value.cloudLoading)
    compose.onNodeWithTag("home-card-A-missing-1", useUnmergedTree = true).assertIsDisplayed()
    compose
      .onNodeWithText("等待作者资料的 history row missing-1", useUnmergedTree = true)
      .assertIsDisplayed()

    val request = synchronized(authorRequests) { authorRequests.first() }
    assertEquals("/bbs/app/link/author_info", request.url.encodedPath)
    assertEquals("missing-1", request.url.queryParameter("link_id"))
    assertFalse(request.url.queryParameterNames.contains("user_id"))
    assertFalse(request.url.queryParameterNames.contains("comment_id"))
    assertFalse(request.url.queryParameterNames.contains("type"))

    runBlocking { fixture.viewModel.resolveHistoryAuthors(setOf("known-2")) }
    assertTrue(
      "Existing history authors must not trigger author_info",
      synchronized(authorRequests) {
        authorRequests.none { it.url.queryParameter("link_id") == "known-2" }
      },
    )

    authorGate.complete(authorPayload("补全作者", "resolved-1"))
    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.cloudHistory.firstOrNull()?.author == "补全作者"
    }
    compose.onNodeWithContentDescription("作者：补全作者", useUnmergedTree = true).assertIsDisplayed()
    assertEquals(
      "history row title remains intact",
      "等待作者资料的 history row missing-1",
      fixture.viewModel.state.value.cloudHistory.first().title,
    )
    assertEquals(
      "https://images.invalid/missing-1.jpg",
      fixture.viewModel.state.value.cloudHistory.first().image,
    )
  }

  @Test
  fun historyScopeKeepsRawRowVisibleUntilRealAuthorIsHydratedThenFiltersOnlyPresentation() {
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val authorGate = CompletableDeferred<String>()
    val fixture =
      createFixture(historyBody = { historyResponse(missingEntry("filter-after-hydration")) }) {
        request ->
        authorRequests += request
        authorGate.await()
      }
    awaitSession(fixture)
    val rule =
      ContentBlockRule(
        id = "author-rule",
        kind = ContentBlockKind.AUTHOR,
        value = "7001",
        source = Source.HEYBOX,
      )
    val context =
      ContentBlockingContext(
        config = ContentBlockingConfig(enabled = true, applyToHistory = true, rules = listOf(rule)),
        ready = true,
        sessionKey = "reader-synthetic:1",
      )
    setHistoryScreen(fixture, mutableStateOf(true), mutableStateOf(context))

    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.cloudHistory.any { it.id == "filter-after-hydration" } &&
        authorRequests.isNotEmpty()
    }
    // The history envelope's outer userid is not treated as the post author; the actual row stays
    // mounted until the existing author_info(link_id) resolver returns its real profile ID.
    compose
      .onNodeWithTag("home-card-A-filter-after-hydration", useUnmergedTree = true)
      .assertIsDisplayed()
    assertEquals(1, fixture.viewModel.state.value.cloudHistory.size)
    assertEquals("filter-after-hydration", authorRequests.single().url.queryParameter("link_id"))

    authorGate.complete(authorPayload("补全作者", "7001"))
    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.cloudHistory.singleOrNull()?.author == "补全作者"
    }
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose
      .onNodeWithTag("home-card-A-filter-after-hydration", useUnmergedTree = true)
      .assertDoesNotExist()
    assertEquals(
      "Filtering must not mutate the account's raw history",
      1,
      fixture.viewModel.state.value.cloudHistory.size,
    )
  }

  @Test
  fun accountHistoryRuleIsDormantUntilItsHistoryScopeIsEnabled() {
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val fixture =
      createFixture(historyBody = { historyResponse(knownEntry("scope-switch")) }) { request ->
        authorRequests += request
        error("A card with an existing author must not trigger author_info")
      }
    awaitSession(fixture)
    val rule =
      ContentBlockRule(
        id = "history-title-rule",
        kind = ContentBlockKind.KEYWORD,
        value = "history row scope-switch",
      )
    val contextState =
      mutableStateOf(
        ContentBlockingContext(
          config =
            ContentBlockingConfig(enabled = true, applyToHistory = false, rules = listOf(rule)),
          ready = true,
          sessionKey = "reader-synthetic:1",
        )
      )
    setHistoryScreen(fixture, mutableStateOf(true), contextState)

    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.cloudHistory.any { it.id == "scope-switch" } &&
        !fixture.viewModel.state.value.cloudLoading
    }
    compose.onNodeWithTag("home-card-A-scope-switch", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-notice").assertDoesNotExist()

    compose.runOnIdle {
      contextState.value =
        contextState.value.copy(config = contextState.value.config.copy(applyToHistory = true))
    }
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose.onNodeWithTag("home-card-A-scope-switch", useUnmergedTree = true).assertDoesNotExist()
    assertEquals(
      "The history scope only changes the projection",
      1,
      fixture.viewModel.state.value.cloudHistory.size,
    )
    assertTrue("Known author metadata must not cause another request", authorRequests.isEmpty())
  }

  @Test
  fun hiddenHistoryDoesNotHydrateUntilThePageBecomesCurrent() {
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val firstAuthorGate = CompletableDeferred<String>()
    val authorCalls = AtomicInteger(0)
    val cancelledCalls = AtomicInteger(0)
    val fixture =
      createFixture(historyBody = { historyResponse(missingEntry("hidden-1")) }) { request ->
        authorRequests += request
        if (authorCalls.incrementAndGet() == 1) {
          try {
            firstAuthorGate.await()
          } catch (cancelled: CancellationException) {
            cancelledCalls.incrementAndGet()
            throw cancelled
          }
        } else {
          authorPayload("显示后补全", "visible-author")
        }
      }
    awaitSession(fixture)
    compose.runOnIdle { fixture.viewModel.loadCloudHistory(0) }
    awaitHistory(fixture, "hidden-1")

    val currentPage = mutableStateOf(false)
    setHistoryScreen(fixture, currentPage)
    compose.waitForIdle()
    assertTrue("A hidden account pane must not request author metadata", authorRequests.isEmpty())

    compose.runOnIdle { currentPage.value = true }
    compose.waitUntil(5_000) {
      authorCalls.get() == 1 &&
        fixture.viewModel.state.value.historyAuthorLoadingIds.contains("hidden-1")
    }
    compose.runOnIdle { currentPage.value = false }
    compose.waitUntil(5_000) {
      cancelledCalls.get() == 1 && fixture.viewModel.state.value.historyAuthorLoadingIds.isEmpty()
    }
    assertEquals("作者未公开", fixture.viewModel.state.value.cloudHistory.single().author)
    assertTrue(fixture.viewModel.state.value.historyAuthorErrorIds.isEmpty())

    compose.runOnIdle { currentPage.value = true }
    compose.waitUntil(5_000) {
      authorCalls.get() == 2 &&
        fixture.viewModel.state.value.cloudHistory.firstOrNull()?.author == "显示后补全"
    }
    assertTrue(
      synchronized(authorRequests) {
        authorRequests.size == 2 &&
          authorRequests.all { it.url.queryParameter("link_id") == "hidden-1" }
      }
    )
    compose.onNodeWithContentDescription("作者：显示后补全", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun authorHydrationUsesAtMostTwoConcurrentRequestsAndDoesNotRepeatCompletedIds() {
    val storyIds = listOf("cap-1", "cap-2", "cap-3", "cap-4")
    val gates = storyIds.associateWith { CompletableDeferred<String>() }
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val active = AtomicInteger(0)
    val maximumActive = AtomicInteger(0)
    val fixture =
      createFixture(
        historyBody = { historyResponse(*storyIds.map { missingEntry(it) }.toTypedArray()) }
      ) { request ->
        authorRequests += request
        val current = active.incrementAndGet()
        maximumActive.updateAndGet { previous -> maxOf(previous, current) }
        try {
          gates.getValue(request.url.queryParameter("link_id")!!).await()
        } finally {
          active.decrementAndGet()
        }
      }
    awaitSession(fixture)
    compose.runOnIdle { fixture.viewModel.loadCloudHistory(0) }
    awaitHistory(fixture, storyIds.first())

    val resolveJob = launchOnMain { fixture.viewModel.resolveHistoryAuthors(storyIds.toSet()) }
    compose.waitUntil(5_000) { synchronized(authorRequests) { authorRequests.size } == 2 }
    assertEquals(
      "The resolver should start two requests before waiting for another slot",
      2,
      active.get(),
    )
    assertEquals(2, synchronized(authorRequests) { authorRequests.size })

    val firstId =
      synchronized(authorRequests) { authorRequests.first().url.queryParameter("link_id")!! }
    gates.getValue(firstId).complete(authorPayload("作者 $firstId", firstId))
    compose.waitUntil(5_000) { synchronized(authorRequests) { authorRequests.size } == 3 }
    assertTrue("Concurrent author requests must stay at or below two", maximumActive.get() <= 2)

    storyIds.forEach { id -> gates.getValue(id).complete(authorPayload("作者 $id", id)) }
    compose.waitUntil(5_000) { resolveJob.isCompleted }
    runBlocking { resolveJob.join() }
    assertEquals(2, maximumActive.get())
    assertEquals(
      storyIds.toSet(),
      synchronized(authorRequests) {
        authorRequests.map { it.url.queryParameter("link_id") }.toSet()
      },
    )

    runBlocking { fixture.viewModel.resolveHistoryAuthors(storyIds.toSet()) }
    assertEquals(
      "Successful author IDs in this history generation are not re-requested",
      4,
      synchronized(authorRequests) { authorRequests.size },
    )
  }

  @Test
  fun loginFailureKeepsHistorySessionAndRefreshRetriesTheSameAuthor() {
    val authorCalls = AtomicInteger(0)
    val fixture =
      createFixture(historyBody = { historyResponse(missingEntry("retry-1")) }) { _ ->
        if (authorCalls.incrementAndGet() == 1) """{"status":"login","result":{}}"""
        else authorPayload("刷新后作者", "retry-author")
      }
    awaitSession(fixture)
    val currentPage = mutableStateOf(true)
    setHistoryScreen(fixture, currentPage)
    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.historyAuthorErrorIds.contains("retry-1")
    }
    val afterFailure = fixture.viewModel.state.value
    assertEquals("reader-synthetic", afterFailure.session?.userId)
    assertEquals(listOf("retry-1"), afterFailure.cloudHistory.map { it.id })
    assertFalse(afterFailure.cloudError != null)

    compose.runOnIdle { fixture.viewModel.refreshCloud() }
    compose.waitUntil(5_000) {
      authorCalls.get() == 2 &&
        fixture.viewModel.state.value.cloudHistory.firstOrNull()?.author == "刷新后作者"
    }
    val afterRetry = fixture.viewModel.state.value
    assertEquals("reader-synthetic", afterRetry.session?.userId)
    assertEquals(listOf("retry-1"), afterRetry.cloudHistory.map { it.id })
    assertTrue(afterRetry.historyAuthorErrorIds.isEmpty())
    assertEquals(2, authorCalls.get())
  }

  @Test
  fun sameIdResponseFromBeforeRefreshCannotOverwriteOrClearNewGenerationWork() {
    val oldResponse = CompletableDeferred<String>()
    val refreshedResponse = CompletableDeferred<String>()
    val authorCalls = AtomicInteger(0)
    val historyGeneration = AtomicInteger(0)
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val fixture =
      createFixture(
        historyBody = {
          if (historyGeneration.incrementAndGet() == 1)
            historyResponse(missingEntry("same-id", title = "旧列表标题"))
          else historyResponse(missingEntry("same-id", title = "刷新后的列表标题"))
        }
      ) { request ->
        authorRequests += request
        if (authorCalls.incrementAndGet() == 1) oldResponse.await() else refreshedResponse.await()
      }
    awaitSession(fixture)
    compose.runOnIdle { fixture.viewModel.loadCloudHistory(0) }
    awaitHistory(fixture, "same-id")

    val oldResolve = launchOnMain { fixture.viewModel.resolveHistoryAuthors(setOf("same-id")) }
    compose.waitUntil(5_000) { synchronized(authorRequests) { authorRequests.size } == 1 }

    compose.runOnIdle { fixture.viewModel.refreshCloud() }
    compose.waitUntil(5_000) {
      historyGeneration.get() == 2 &&
        !fixture.viewModel.state.value.cloudLoading &&
        fixture.viewModel.state.value.cloudHistory.firstOrNull()?.title == "刷新后的列表标题"
    }
    val newResolve = launchOnMain { fixture.viewModel.resolveHistoryAuthors(setOf("same-id")) }
    compose.waitUntil(5_000) { synchronized(authorRequests) { authorRequests.size } == 2 }

    oldResponse.complete(authorPayload("旧作者", "old-author"))
    compose.waitUntil(5_000) { oldResolve.isCompleted }
    runBlocking { oldResolve.join() }
    assertEquals(setOf("same-id"), fixture.viewModel.state.value.historyAuthorLoadingIds)

    runBlocking { fixture.viewModel.resolveHistoryAuthors(setOf("same-id")) }
    assertEquals(
      "A stale request must not clear the newer request's in-flight identity",
      2,
      synchronized(authorRequests) { authorRequests.size },
    )

    refreshedResponse.complete(authorPayload("当前作者", "current-author"))
    compose.waitUntil(5_000) { newResolve.isCompleted }
    runBlocking { newResolve.join() }
    val finalState = fixture.viewModel.state.value
    assertEquals("刷新后的列表标题", finalState.cloudHistory.single().title)
    assertEquals("当前作者", finalState.cloudHistory.single().author)
    assertTrue(finalState.historyAuthorLoadingIds.isEmpty())
    assertTrue(finalState.historyAuthorErrorIds.isEmpty())
  }

  @Test
  fun blockedAuthorResponseReleasedAfterLogoutCannotRestoreHistoryOrSession() {
    val authorGate = CompletableDeferred<String>()
    val authorRequests = Collections.synchronizedList(mutableListOf<Request>())
    val fixture =
      createFixture(historyBody = { historyResponse(missingEntry("logout-1")) }) { request ->
        authorRequests += request
        authorGate.await()
      }
    awaitSession(fixture)
    compose.runOnIdle { fixture.viewModel.loadCloudHistory(0) }
    awaitHistory(fixture, "logout-1")
    val oldRevision = fixture.viewModel.state.value.revision

    val resolveJob = launchOnMain { fixture.viewModel.resolveHistoryAuthors(setOf("logout-1")) }
    compose.waitUntil(5_000) {
      authorRequests.isNotEmpty() &&
        fixture.viewModel.state.value.historyAuthorLoadingIds.contains("logout-1")
    }

    compose.runOnIdle { fixture.viewModel.logout() }
    compose.waitUntil(5_000) {
      fixture.viewModel.state.value.session == null &&
        fixture.viewModel.state.value.cloudHistory.isEmpty() &&
        fixture.viewModel.state.value.historyAuthorLoadingIds.isEmpty() &&
        fixture.viewModel.state.value.revision > oldRevision
    }
    val loggedOutRevision = fixture.viewModel.state.value.revision
    authorGate.complete(authorPayload("退出后迟到作者", "late-author"))
    compose.waitUntil(5_000) { resolveJob.isCompleted }
    runBlocking { resolveJob.join() }

    val afterLateResponse = fixture.viewModel.state.value
    assertEquals(loggedOutRevision, afterLateResponse.revision)
    assertEquals(oldRevision + 1, loggedOutRevision)
    assertEquals(null, afterLateResponse.session)
    assertTrue(afterLateResponse.cloudHistory.isEmpty())
    assertTrue(afterLateResponse.historyAuthorLoadingIds.isEmpty())
    assertTrue(afterLateResponse.historyAuthorErrorIds.isEmpty())
    assertTrue(
      synchronized(authorRequests) {
        authorRequests.single().url.queryParameter("link_id") == "logout-1"
      }
    )
  }

  private fun launchOnMain(block: suspend () -> Unit): Job {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    independentScopes += scope
    return scope.launch { block() }.also(independentJobs::add)
  }

  private fun createFixture(
    historyBody: () -> String = { historyResponse(missingEntry("default-id")) },
    authorHandler: suspend (Request) -> String,
  ): Fixture {
    val application = IsolatedSessionApplication(compose.activity.applicationContext)
    val syntheticSession =
      AccountSession(
        "pkey=synthetic-only; heybox_id=reader-synthetic",
        "reader-synthetic",
        "合成读者",
        "",
      )
    SessionStore(application).save(syntheticSession)
    val historyCalls = AtomicInteger(0)
    val authorCalls = AtomicInteger(0)
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { request ->
            when (request.url.encodedPath) {
              "/bbs/app/profile/history/visit" -> {
                historyCalls.incrementAndGet()
                historyBody()
              }
              "/bbs/app/link/author_info" -> {
                authorCalls.incrementAndGet()
                authorHandler(request)
              }
              else -> error("Unexpected synthetic account request: ${request.url.encodedPath}")
            }
          }
      )
    val viewModel = AccountViewModel(application, repository)
    viewModelStore.put("history-author-hydration-${UUID.randomUUID()}", viewModel)
    return Fixture(application, repository, viewModel, historyCalls, authorCalls)
      .also(fixtures::add)
  }

  private fun awaitSession(fixture: Fixture) {
    compose.waitUntil(5_000) {
      !fixture.viewModel.state.value.loadingSession &&
        fixture.viewModel.state.value.session?.userId == "reader-synthetic"
    }
  }

  private fun awaitHistory(fixture: Fixture, storyId: String) {
    compose.waitUntil(5_000) {
      !fixture.viewModel.state.value.cloudLoading &&
        fixture.viewModel.state.value.cloudHistory.any { it.id == storyId }
    }
  }

  private fun setHistoryScreen(
    fixture: Fixture,
    currentPage: androidx.compose.runtime.MutableState<Boolean>,
    contentBlockingContext: androidx.compose.runtime.MutableState<ContentBlockingContext> =
      mutableStateOf(ContentBlockingContext()),
  ) {
    compose.setContent {
      val state = fixture.viewModel.state.collectAsState().value
      CompositionLocalProvider(LocalContentBlockingContext provides contentBlockingContext.value) {
        HeyReadTheme("light", motionEnabled = false) {
          AccountLibraryScreen(
            state = state,
            account = fixture.viewModel,
            onLogin = {},
            onOpen = {},
            noImages = true,
            homeLayout = HomeLayout.A,
            history = true,
            isRoot = false,
            isCurrentPage = currentPage.value,
          )
        }
      }
    }
  }

  private fun historyResponse(vararg entries: String): String =
    """{"status":"ok","result":{"has_more":false,"history_visit":[${entries.joinToString(",")}]}}"""

  private fun missingEntry(id: String, title: String = "等待作者资料的 history row $id"): String =
    """{"id":"outer-$id","userid":"reader-outer","type":"link","content":{"linkid":"$id","title":"$title","img":"https://images.invalid/$id.jpg"}}"""

  private fun knownEntry(id: String): String =
    """{"id":"outer-$id","userid":"reader-outer","type":"link","content":{"linkid":"$id","title":"已有作者的 history row $id","img":"https://images.invalid/$id.jpg","user":{"username":"已经存在","userid":"known-author"}}}"""

  private fun authorPayload(name: String, userId: String): String =
    """{"status":"ok","result":{"author_info":{"username":"$name","userid":"$userId","avartar":"https://images.invalid/avatar-$userId.jpg","ip_location":"合成地区"}}}"""

  private data class Fixture(
    val application: IsolatedSessionApplication,
    val repository: AccountRepository,
    val viewModel: AccountViewModel,
    val historyCalls: AtomicInteger,
    val authorCalls: AtomicInteger,
  )

  private class IsolatedSessionApplication(base: Context) : Application() {
    private val prefix = "history-author-hydration-${UUID.randomUUID()}-"

    init {
      attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
      baseContext.getSharedPreferences("$prefix$name", mode)
  }
}
