package cn.heyread.app.ui.screens

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.ArticleGameRepository
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.AccountViewModel
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.ArticleGameViewModel
import cn.heyread.app.viewmodel.AuthorFollowViewModel
import cn.heyread.app.viewmodel.CommentActionsViewModel
import cn.heyread.app.viewmodel.DetailRequestPhase
import cn.heyread.app.viewmodel.DetailRequestState
import cn.heyread.app.viewmodel.SocialActionsViewModel
import cn.heyread.app.viewmodel.SteamTranslationViewModel
import cn.heyread.app.viewmodel.UiState
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailScreenGuestPagerTest {
  @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  @get:Rule val temporaryFolder = TemporaryFolder()

  private var testViewModelStore: ViewModelStore? = null
  private var testDataStoreScope: CoroutineScope? = null

  @After
  fun clearTestState() {
    compose.activityRule.scenario.onActivity { testViewModelStore?.clear() }
    testViewModelStore = null
    testDataStoreScope?.cancel()
    testDataStoreScope = null
  }

  @Test
  fun productionGuestDetailSwipesToLoginAndRestoresBodyScrollBeforeSingleBack() {
    val setup = prepareProductionFixtures()
    val loginCount = AtomicInteger()
    val backCount = AtomicInteger()

    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        Surface(Modifier.fillMaxSize()) {
          DetailScreen(
            s = setup.story,
            ui = setup.ui,
            vm = setup.appViewModel,
            account = AccountUiState(loadingSession = false, session = null),
            accountViewModel = setup.accountViewModel,
            socialViewModel = setup.socialViewModel,
            commentActionsViewModel = setup.commentActionsViewModel,
            authorFollowViewModel = setup.authorFollowViewModel,
            translationViewModel = setup.translationViewModel,
            articleGameViewModel = setup.articleGameViewModel,
            onBack = { backCount.incrementAndGet() },
            onLogin = { loginCount.incrementAndGet() },
            onShare = {},
            onOpenUrl = {},
            publicAttemptedForEntry = true,
          )
        }
      }
    }

    compose.onNodeWithTag("article-page-body").assertIsDisplayed()
    compose.onNodeWithTag("article-tab-comments").assertIsDisplayed()
    compose.onNodeWithTag("article-body-list").performScrollToIndex(21)
    compose
      .onNodeWithText("合成正文段落 18", substring = true, useUnmergedTree = true)
      .assertIsDisplayed()
    compose
      .onNodeWithText("合成正文段落 0", substring = true, useUnmergedTree = true)
      .assertDoesNotExist()

    compose.onNodeWithTag("article-page-body").performTouchInput { swipeLeft() }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("登录后查看评论").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("article-page-comments").assertIsDisplayed()
    compose.onNodeWithText("登录后查看评论").assertIsDisplayed().performClick()
    assertEquals("guest login CTA delegates to the existing login route", 1, loginCount.get())

    compose.onNodeWithTag("article-tab-body").performClick()
    compose.waitUntil(5_000) {
      compose
        .onAllNodesWithText("合成正文段落 18", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose
      .onNodeWithText("合成正文段落 18", substring = true, useUnmergedTree = true)
      .assertIsDisplayed()
    compose
      .onNodeWithText("合成正文段落 0", substring = true, useUnmergedTree = true)
      .assertDoesNotExist()

    compose.onNodeWithTag("article-tab-comments").performClick()
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag("article-page-comments").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("article-page-comments").performTouchInput { swipeRight() }
    compose.waitUntil(5_000) {
      compose
        .onAllNodesWithText("合成正文段落 18", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose
      .onNodeWithText("合成正文段落 18", substring = true, useUnmergedTree = true)
      .assertIsDisplayed()
    compose
      .onNodeWithText("合成正文段落 0", substring = true, useUnmergedTree = true)
      .assertDoesNotExist()

    compose.onNodeWithTag("page-back").performClick()
    assertEquals("detail Back exits once without an intermediate page step", 1, backCount.get())
    assertEquals("test fixture never sends a network request", 0, setup.requestCount.get())
  }

  private fun prepareProductionFixtures(): Fixtures {
    val base = InstrumentationRegistry.getInstrumentation().context
    val isolatedRoot = temporaryFolder.newFolder("detail-guest-pager")
    val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    testDataStoreScope = dataStoreScope
    val preferences =
      androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
        scope = dataStoreScope
      ) {
        File(isolatedRoot, "settings.preferences_pb")
      }
    val testContext =
      object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
      }
    val store = LibraryStore(testContext, File(isolatedRoot, "reading-fonts"), preferences)
    val appViewModel = AppViewModel(store)
    val owner = ViewModelStore()
    testViewModelStore = owner
    owner.put("detail-guest-pager-app", appViewModel)

    val requestCount = AtomicInteger()
    val safeRepository =
      AccountRepository(
        requestExecutor = { request: Request ->
          requestCount.incrementAndGet()
          error("Unexpected account request in guest pager test: ${request.url.encodedPath}")
        }
      )
    val accountViewModel = AccountViewModel(IsolatedSessionApplication(base))
    val socialViewModel = SocialActionsViewModel(safeRepository)
    val commentActionsViewModel = CommentActionsViewModel(safeRepository)
    val authorFollowViewModel = AuthorFollowViewModel(safeRepository)
    val translationViewModel = SteamTranslationViewModel()
    val articleGameViewModel =
      ArticleGameViewModel(
        ArticleGameRepository(
          execute = { request: Request ->
            requestCount.incrementAndGet()
            error("Unexpected game request in guest pager test: ${request.url.encodedPath}")
          }
        )
      )
    owner.put("detail-guest-pager-account", accountViewModel)
    owner.put("detail-guest-pager-social", socialViewModel)
    owner.put("detail-guest-pager-comment-actions", commentActionsViewModel)
    owner.put("detail-guest-pager-author-follow", authorFollowViewModel)
    owner.put("detail-guest-pager-translation", translationViewModel)
    owner.put("detail-guest-pager-games", articleGameViewModel)

    val story =
      Story(
        id = "guest-pager-story",
        source = Source.HEYBOX,
        title = "合成小黑盒正文",
        excerpt = "仪器测试合成内容",
        body = "合成文章正文",
        author = "测试作者",
        date = "2026-10-26",
        detailBlocks =
          (0..24).map { index ->
            ArticleBlock.Paragraph("合成正文段落 $index " + "用于滚动状态验证的文字。".repeat(18))
          },
      )
    val key = "${story.source}:${story.id}"
    val ui =
      UiState(
        noImages = true,
        motionEnabled = false,
        details = mapOf(key to story),
        detailRequests =
          mapOf(key to DetailRequestState(generation = 1, phase = DetailRequestPhase.READY)),
      )
    return Fixtures(
      story,
      ui,
      appViewModel,
      accountViewModel,
      socialViewModel,
      commentActionsViewModel,
      authorFollowViewModel,
      translationViewModel,
      articleGameViewModel,
      requestCount,
    )
  }

  private data class Fixtures(
    val story: Story,
    val ui: UiState,
    val appViewModel: AppViewModel,
    val accountViewModel: AccountViewModel,
    val socialViewModel: SocialActionsViewModel,
    val commentActionsViewModel: CommentActionsViewModel,
    val authorFollowViewModel: AuthorFollowViewModel,
    val translationViewModel: SteamTranslationViewModel,
    val articleGameViewModel: ArticleGameViewModel,
    val requestCount: AtomicInteger,
  )

  private class IsolatedSessionApplication(base: Context) : Application() {
    init {
      attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
      baseContext.getSharedPreferences("detail-guest-pager-${UUID.randomUUID()}-$name", mode)
  }
}
