package cn.heyread.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountRequestExecutor
import cn.heyread.app.data.account.AccountSession
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.ui.screens.AccountLibraryScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.AccountViewModel
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryMetadataCardsTest {
  @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private val viewModelStore = ViewModelStore()

  @After
  fun clearViewModels() {
    viewModelStore.clear()
  }

  @Test
  fun officialHistoryMetadataFlowsIntoSharedCardsAndNoImagesStopsCoverRequests() = runBlocking {
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    val repository =
      AccountRepository(
        requestExecutor =
          AccountRequestExecutor { request ->
            requests += request
            HISTORY_RESPONSE
          }
      )
    repository.restoreSession("pkey=synthetic; heybox_id=reader-1", "reader-1")

    val page = repository.loadCloudHistory(0)
    assertEquals(2, page.stories.size)
    val authorStory = page.stories[0]
    val userStory = page.stories[1]
    assertEquals("history-author", authorStory.id)
    assertEquals("历史作者甲", authorStory.author)
    assertEquals("https://images.invalid/history-author.jpg", authorStory.image)
    assertEquals("history-user", userStory.id)
    assertEquals("历史作者乙", userStory.author)
    assertEquals("https://images.invalid/history-user.jpg", userStory.image)
    assertTrue(
      "The outer history reader ID must not become either post author",
      page.stories.all { it.author != "reader-outer-marker" },
    )
    assertEquals(1, requests.size)
    assertEquals("/bbs/app/profile/history/visit", requests.single().url.encodedPath)
    assertEquals("link", requests.single().url.queryParameter("type"))
    assertEquals("0", requests.single().url.queryParameter("offset"))

    val context =
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val imageRequests = Collections.synchronizedList(mutableListOf<String>())
    val imageLoader = historyImageLoader(context, imageRequests)
    val account =
      AccountViewModel(IsolatedSessionApplication(compose.activity.applicationContext)).also {
        viewModelStore.put("history-metadata-account-view-model", it)
      }
    // The repository fixture above supplies the actual parsed Story values. The screen state is
    // synthetic; its isolated AccountViewModel has no stored session and cannot read an account.
    val state =
      AccountUiState(
        session = AccountSession("pkey=synthetic-ui-only", "reader-ui", "合成读者", ""),
        loadingSession = false,
        cloudHistory = page.stories,
        historyHasMore = false,
        cloudMode = "history",
      )
    val layout = mutableStateOf(HomeLayout.A)
    val noImages = mutableStateOf(true)
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        HeyReadTheme("light", motionEnabled = false) {
          AccountLibraryScreen(
            state = state,
            account = account,
            onLogin = {},
            onOpen = {},
            noImages = noImages.value,
            homeLayout = layout.value,
            history = true,
            selectedStoryIdentity = null,
          )
        }
      }
    }

    compose.waitForIdle()
    assertTrue("A fresh no-images screen must not request covers", imageRequests.isEmpty())
    for (story in page.stories) {
      compose.onNodeWithTag("home-cover-${story.id}", useUnmergedTree = true).assertDoesNotExist()
    }
    compose.runOnIdle { noImages.value = false }

    for (homeLayout in HomeLayout.entries) {
      compose.runOnIdle { layout.value = homeLayout }
      compose.waitForIdle()
      for (story in page.stories) {
        compose
          .onNodeWithTag("home-card-${homeLayout.name}-${story.id}", useUnmergedTree = true)
          .performScrollTo()
          .assertIsDisplayed()
        compose
          .onNodeWithContentDescription("作者：${story.author}", useUnmergedTree = true)
          .assertIsDisplayed()
        compose.waitUntil(5_000) { imageRequests.contains(story.image) }
        val cover = compose.onNodeWithTag("home-cover-${story.id}", useUnmergedTree = true)
        cover.assertIsDisplayed()
        val expected =
          if (story.id == "history-author") Color.rgb(26, 168, 82) else Color.rgb(40, 90, 210)
        compose.waitUntil(5_000) {
          runCatching {
              val rendered = cover.captureToImage().asAndroidBitmap()
              val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
              colorDistance(center, expected) < 30
            }
            .getOrDefault(false)
        }
        val rendered = cover.captureToImage().asAndroidBitmap()
        val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
        assertTrue(
          "${story.id} should render its synthetic cover bitmap",
          colorDistance(center, expected) < 30,
        )
      }
    }

    val requestCountBeforeNoImages = imageRequests.size
    compose.runOnIdle { noImages.value = true }
    compose.waitForIdle()
    assertEquals(requestCountBeforeNoImages, imageRequests.size)
    for (story in page.stories) {
      compose.onNodeWithTag("home-cover-${story.id}", useUnmergedTree = true).assertDoesNotExist()
    }
  }

  private fun historyImageLoader(context: Context, requested: MutableList<String>): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
              val url = data.toString()
              if (!url.startsWith("https://images.invalid/history-")) return null
              return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                  requested += url
                  val color =
                    if (url.contains("history-author")) Color.rgb(26, 168, 82)
                    else Color.rgb(40, 90, 210)
                  val bitmap =
                    Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                      eraseColor(color)
                    }
                  return DrawableResult(
                    BitmapDrawable(context.resources, bitmap),
                    false,
                    DataSource.MEMORY,
                  )
                }
              }
            }
          }
        )
      }
      .build()

  private fun colorDistance(left: Int, right: Int): Int =
    kotlin.math.abs(Color.red(left) - Color.red(right)) +
      kotlin.math.abs(Color.green(left) - Color.green(right)) +
      kotlin.math.abs(Color.blue(left) - Color.blue(right))

  private class IsolatedSessionApplication(base: Context) : Application() {
    private val prefix = "history-metadata-${UUID.randomUUID()}-"

    init {
      attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
      baseContext.getSharedPreferences("$prefix$name", mode)
  }

  private companion object {
    const val HISTORY_RESPONSE =
      """
      {
        "status":"ok",
        "result":{
          "has_more":false,
          "history_visit":[
            {
              "id":"outer-history-entry-1",
              "userid":"reader-outer-marker",
              "type":"link",
              "content":{
                "linkid":"history-author",
                "title":"作者对象字段的历史帖子",
                "description":"合成正文甲",
                "img":"https://images.invalid/history-author.jpg",
                "author":{"nickname":"历史作者甲","avatar":"https://images.invalid/avatar-author.jpg"}
              }
            },
            {
              "id":"outer-history-entry-2",
              "userid":"reader-outer-marker",
              "type":"link",
              "content":{
                "linkid":"history-user",
                "title":"用户资料字段的历史帖子",
                "description":"合成正文乙",
                "image":"https://images.invalid/history-user.jpg",
                "user":{"userid":"post-author-id","display_name":"历史作者乙","avatar":"https://images.invalid/avatar-user.jpg"}
              }
            }
          ]
        }
      }
      """
  }
}
