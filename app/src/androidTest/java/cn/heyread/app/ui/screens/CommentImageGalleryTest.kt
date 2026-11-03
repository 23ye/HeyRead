package cn.heyread.app.ui.screens

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.account.CommentImage
import cn.heyread.app.data.account.FullArticle
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.ArticleGameRepository
import cn.heyread.app.ui.components.MediaScaleSemanticsKey
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
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentImageGalleryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private var owner: ViewModelStore? = null
  private var loader: ImageLoader? = null
  private var fixtureRoot: File? = null
  private var dataStoreScope: CoroutineScope? = null

  @After
  fun cleanup() {
    compose.activityRule.scenario.onActivity { owner?.clear() }
    owner = null
    dataStoreScope?.cancel()
    dataStoreScope = null
    loader?.shutdown()
    loader = null
    fixtureRoot?.deleteRecursively()
    fixtureRoot = null
  }

  @Test
  fun productionRootAndReplyImagesOpenTheirExactRepeatedOccurrencesWithoutReplyOrLike() {
    val fixture = fixtures()
    compose.setContent {
      androidx.compose.runtime.CompositionLocalProvider(LocalImageLoader provides fixture.loader) {
        HeyReadTheme("light", motionEnabled = false) {
          DetailScreen(
            s = fixture.story,
            ui = fixture.ui,
            vm = fixture.app,
            account = fixture.account,
            accountViewModel = fixture.accountVm,
            socialViewModel = fixture.socialVm,
            commentActionsViewModel = fixture.commentsVm,
            authorFollowViewModel = fixture.followVm,
            translationViewModel = fixture.translationVm,
            articleGameViewModel = fixture.gameVm,
            onBack = {},
            onLogin = {},
            onShare = {},
            onOpenUrl = {},
          )
        }
      }
    }

    compose.onNodeWithTag("article-tab-comments").performClick()
    compose.onNodeWithTag("comment-image-root-2").performClick()
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("3/4")
    saveGalleryScreenshot("v133-comment-gallery-3-of-4.png")
    compose.onNodeWithTag("media-close").performClick()
    compose.onNodeWithTag("comment-image-reply-0").performClick()
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("4/4")
    saveGalleryScreenshot("v133-comment-gallery-4-of-4.png")
    compose.onNodeWithText("发布评论").assertDoesNotExist()
    compose.onNodeWithText("写下你的评论").assertDoesNotExist()
    compose.onNodeWithText("发送回复").assertDoesNotExist()
    compose.onNodeWithText("写下回复").assertDoesNotExist()
    assertEquals(
      "comment image taps do not submit or open reply controls",
      0,
      fixture.requestCount.get(),
    )
  }

  @Test
  fun productionCommentGallerySwipesBothWaysAndZoomRestoresPaging() {
    val fixture = fixtures()
    compose.setContent {
      androidx.compose.runtime.CompositionLocalProvider(LocalImageLoader provides fixture.loader) {
        HeyReadTheme("light", motionEnabled = false) {
          DetailScreen(
            s = fixture.story,
            ui = fixture.ui,
            vm = fixture.app,
            account = fixture.account,
            accountViewModel = fixture.accountVm,
            socialViewModel = fixture.socialVm,
            commentActionsViewModel = fixture.commentsVm,
            authorFollowViewModel = fixture.followVm,
            translationViewModel = fixture.translationVm,
            articleGameViewModel = fixture.gameVm,
            onBack = {},
            onLogin = {},
            onShare = {},
            onOpenUrl = {},
          )
        }
      }
    }
    compose.onNodeWithTag("article-tab-comments").performClick()
    compose.onNodeWithTag("comment-image-root-2").performClick()
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("3/4")
    val image = compose.onNodeWithTag("media-image")
    image.performTouchInput { swipeLeft() }
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("4/4")
    image.performTouchInput { swipeLeft() }
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("4/4")
    image.performTouchInput { swipeRight() }
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("3/4")
    image.performTouchInput {
      val y = height / 2f
      down(0, androidx.compose.ui.geometry.Offset(width * .42f, y))
      down(1, androidx.compose.ui.geometry.Offset(width * .58f, y))
      moveTo(0, androidx.compose.ui.geometry.Offset(width * .28f, y), delayMillis = 200)
      moveTo(1, androidx.compose.ui.geometry.Offset(width * .72f, y), delayMillis = 200)
      up(0)
      up(1)
    }
    assertTrue(image.fetchSemanticsNode().config[MediaScaleSemanticsKey] > 1f)
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("3/4")
    image.performTouchInput { swipeLeft() }
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("3/4")
    image.performTouchInput { doubleClick(center) }
    assertEquals(1f, image.fetchSemanticsNode().config[MediaScaleSemanticsKey], .01f)
    image.performTouchInput { swipeLeft() }
    compose.onNodeWithTag("media-page-indicator").assertTextEquals("4/4")
  }

  @Test
  fun productionCommentPreviewClosesWhenRouteIsHiddenOrImagesAreDisabled() {
    val fixture = fixtures()
    val ui = mutableStateOf(fixture.ui)
    val currentPage = mutableStateOf(true)
    compose.setContent {
      androidx.compose.runtime.CompositionLocalProvider(LocalImageLoader provides fixture.loader) {
        HeyReadTheme("light", motionEnabled = false) {
          DetailScreen(
            s = fixture.story,
            ui = ui.value,
            vm = fixture.app,
            account = fixture.account,
            isCurrentPage = currentPage.value,
            accountViewModel = fixture.accountVm,
            socialViewModel = fixture.socialVm,
            commentActionsViewModel = fixture.commentsVm,
            authorFollowViewModel = fixture.followVm,
            translationViewModel = fixture.translationVm,
            articleGameViewModel = fixture.gameVm,
            onBack = {},
            onLogin = {},
            onShare = {},
            onOpenUrl = {},
          )
        }
      }
    }
    compose.onNodeWithTag("article-tab-comments").performClick()
    compose.onNodeWithTag("comment-image-root-0").performClick()
    compose.onNodeWithTag("media-preview").assertIsDisplayed()
    compose.runOnIdle { currentPage.value = false }
    compose.onNodeWithTag("media-preview").assertDoesNotExist()
    compose.runOnIdle { currentPage.value = true }
    compose.onNodeWithTag("comment-image-root-0").performClick()
    compose.onNodeWithTag("media-preview").assertIsDisplayed()
    compose.runOnIdle { ui.value = ui.value.copy(noImages = true) }
    compose.onNodeWithTag("media-preview").assertDoesNotExist()
  }

  private fun fixtures(): Fixtures {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val base = instrumentation.context
    val root =
      File(instrumentation.targetContext.cacheDir, "comment-gallery-${UUID.randomUUID()}").apply {
        mkdirs()
      }
    fixtureRoot = root
    val testContext =
      object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
      }
    val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dataStoreScope = preferencesScope
    val preferences =
      PreferenceDataStoreFactory.create(scope = preferencesScope) {
        File(root, "settings.preferences_pb")
      }
    val app = AppViewModel(LibraryStore(testContext, File(root, "reading-fonts"), preferences))
    val store = ViewModelStore().also { owner = it }
    store.put("comment-gallery-app", app)
    val requestCount = AtomicInteger()
    val safeRepository =
      AccountRepository(
        requestExecutor = { request: Request ->
          requestCount.incrementAndGet()
          error("Unexpected request in comment gallery test: ${request.url.encodedPath}")
        }
      )
    val accountVm = AccountViewModel(IsolatedApplication(base))
    val socialVm = SocialActionsViewModel(safeRepository)
    val commentsVm = CommentActionsViewModel(safeRepository)
    val followVm = AuthorFollowViewModel(safeRepository)
    val translationVm = SteamTranslationViewModel()
    val gameVm =
      ArticleGameViewModel(
        ArticleGameRepository(
          execute = { request: Request ->
            requestCount.incrementAndGet()
            error("Unexpected game request: ${request.url.encodedPath}")
          }
        )
      )
    store.put("comment-gallery-account", accountVm)
    store.put("comment-gallery-social", socialVm)
    store.put("comment-gallery-comments", commentsVm)
    store.put("comment-gallery-follow", followVm)
    store.put("comment-gallery-translation", translationVm)
    store.put("comment-gallery-games", gameVm)

    val story = Story("comment-gallery", Source.HEYBOX, "合成评论图库", "", "正文", "作者", "2026-10-30")
    val repeated = CommentImage("https://images.invalid/repeated.png")
    val rootComment =
      RemoteComment(
        "root",
        "主评",
        "主评论文本",
        0,
        0,
        childCount = 1,
        images = listOf(repeated, CommentImage("https://images.invalid/middle.png"), repeated),
      )
    val reply =
      RemoteComment(
        "reply",
        "回复",
        "回复评论文本",
        0,
        0,
        rootCommentId = "root",
        images = listOf(repeated),
      )
    val article = FullArticle(story, emptyList(), listOf(rootComment), 1, false)
    val session = AccountSession("pkey=test", "test-user", "测试账号", "")
    val account =
      AccountUiState(
        session = session,
        loadingSession = false,
        fullArticles = mapOf("${story.source}:${story.id}" to article),
        replies = mapOf("root" to listOf(reply)),
      )
    val ui =
      UiState(
        noImages = false,
        motionEnabled = false,
        details = mapOf("${story.source}:${story.id}" to story),
        detailRequests =
          mapOf(
            "${story.source}:${story.id}" to
              DetailRequestState(generation = 1, phase = DetailRequestPhase.READY)
          ),
      )
    val fetcher =
      ImageLoader.Builder(instrumentation.targetContext)
        .components {
          add(
            object : Fetcher.Factory<Uri> {
              override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                if (data.host != "images.invalid") return null
                return object : Fetcher {
                  override suspend fun fetch(): FetchResult =
                    DrawableResult(
                      BitmapDrawable(instrumentation.targetContext.resources, markerBitmap()),
                      false,
                      DataSource.MEMORY,
                    )
                }
              }
            }
          )
        }
        .build()
    loader = fetcher
    return Fixtures(
      story,
      ui,
      account,
      app,
      accountVm,
      socialVm,
      commentsVm,
      followVm,
      translationVm,
      gameVm,
      fetcher,
      requestCount,
    )
  }

  private fun markerBitmap(): Bitmap =
    Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
      eraseColor(android.graphics.Color.WHITE)
      Canvas(this)
        .drawRect(280f, 0f, 320f, 500f, Paint().apply { color = android.graphics.Color.RED })
    }

  private fun saveGalleryScreenshot(fileName: String) {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    val file = File(targetContext.cacheDir, fileName)
    file.outputStream().use { output ->
      check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    screenshot.recycle()
  }

  private data class Fixtures(
    val story: Story,
    val ui: UiState,
    val account: AccountUiState,
    val app: AppViewModel,
    val accountVm: AccountViewModel,
    val socialVm: SocialActionsViewModel,
    val commentsVm: CommentActionsViewModel,
    val followVm: AuthorFollowViewModel,
    val translationVm: SteamTranslationViewModel,
    val gameVm: ArticleGameViewModel,
    val loader: ImageLoader,
    val requestCount: AtomicInteger,
  )

  private class IsolatedApplication(base: Context) : Application() {
    init {
      attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
      baseContext.getSharedPreferences("comment-gallery-${UUID.randomUUID()}-$name", mode)
  }
}
