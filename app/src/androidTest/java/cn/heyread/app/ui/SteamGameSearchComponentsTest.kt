package cn.heyread.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.SteamGame
import cn.heyread.app.ui.screens.FeedScreen
import cn.heyread.app.ui.screens.SteamGameSearchSection
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.FeedPageState
import cn.heyread.app.viewmodel.FeedTab
import cn.heyread.app.viewmodel.SteamGameSearchState
import cn.heyread.app.viewmodel.UiState
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamGameSearchComponentsTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var testViewModelStore: ViewModelStore? = null
  private var testDataStoreScope: CoroutineScope? = null

  @After
  fun clearTestState() {
    testViewModelStore?.clear()
    testViewModelStore = null
    testDataStoreScope?.cancel()
    testDataStoreScope = null
  }

  @Test
  fun querySubmitsOnlyFromButtonOrSearchImeAction() {
    val state = mutableStateOf(SteamGameSearchState())
    val submitted = mutableListOf<String>()
    compose.setContent {
      HeyReadTheme("light") {
        SteamGameSearchSection(
          state = state.value,
          noImages = true,
          active = true,
          onQueryChange = { state.value = state.value.copy(query = it) },
          onSubmit = { submitted += state.value.query },
          onClear = { state.value = SteamGameSearchState() },
          onSelect = {},
        )
      }
    }

    val query = compose.onNodeWithTag("steam-game-search-query")
    query.performTextInput("Hades")
    assertTrue(submitted.isEmpty())
    compose.onNodeWithTag("steam-game-search-submit").performClick()
    assertEquals(listOf("Hades"), submitted)

    query.performTextClearance()
    query.performTextInput("367520")
    query.performImeAction()
    assertEquals(listOf("Hades", "367520"), submitted)
  }

  @Test
  fun candidateDisplaysAndReturnsItsActualSteamIdentity() {
    val imageUrl = "https://cdn.invalid/hollow-knight.jpg"
    val game = SteamGame(367520, "Hollow Knight", imageUrl)
    val selected = mutableListOf<SteamGame>()
    compose.setContent {
      HeyReadTheme("light") {
        SteamGameSearchSection(
          state =
            SteamGameSearchState(query = "Hollow", results = listOf(game), hasSearched = true),
          noImages = true,
          active = true,
          onQueryChange = {},
          onSubmit = {},
          onClear = {},
          onSelect = selected::add,
        )
      }
    }

    compose.onNodeWithText(game.name).assertIsDisplayed()
    compose.onNodeWithText("AppID ${game.appId}").assertIsDisplayed()
    compose.onNodeWithTag("steam-game-result-${game.appId}").performClick()
    assertEquals(listOf(game), selected)
  }

  @Test
  fun errorCanRetryAndClearAndEmptySearchHasItsOwnState() {
    val state =
      mutableStateOf(
        SteamGameSearchState(query = "broken query", error = "Steam 搜索暂时失败。", hasSearched = true)
      )
    var submits = 0
    compose.setContent {
      HeyReadTheme("light") {
        SteamGameSearchSection(
          state = state.value,
          noImages = true,
          active = true,
          onQueryChange = { state.value = state.value.copy(query = it) },
          onSubmit = { submits++ },
          onClear = { state.value = SteamGameSearchState() },
          onSelect = {},
        )
      }
    }

    compose.onNodeWithTag("steam-game-search-error").assertIsDisplayed()
    compose.onNodeWithText("重试").performClick()
    assertEquals(1, submits)
    compose.onNodeWithTag("steam-game-search-clear").performClick()
    compose.onNodeWithTag("steam-game-search-error").assertDoesNotExist()

    compose.runOnIdle {
      state.value = SteamGameSearchState(query = "unmatched title", hasSearched = true)
    }
    compose.onNodeWithTag("steam-game-search-empty").assertIsDisplayed()
    compose.onNodeWithText("没有匹配的 Steam 游戏，可尝试英文名或AppID。").assertIsDisplayed()
  }

  @Test
  fun narrowLargeTextWrapsWithoutLoadingImagesAndKeepsTouchTargets() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val imageLoader = requestRecordingImageLoader(context, requested)
    val game =
      SteamGame(
        1234567,
        "An Extremely Long Synthetic Steam Game Name That Must Wrap in a Narrow Column",
        "https://cdn.invalid/synthetic-game.jpg",
      )
    val baseDensity = compose.density
    compose.setContent {
      CompositionLocalProvider(
        LocalImageLoader provides imageLoader,
        LocalDensity provides Density(baseDensity.density, fontScale = 2f),
      ) {
        HeyReadTheme("light") {
          Column(Modifier.width(210.dp).verticalScroll(rememberScrollState())) {
            SteamGameSearchSection(
              state = SteamGameSearchState(results = listOf(game), hasSearched = true),
              noImages = true,
              active = true,
              onQueryChange = {},
              onSubmit = {},
              onClear = {},
              onSelect = {},
            )
          }
        }
      }
    }

    compose.onNodeWithTag("steam-game-search-query").assertIsDisplayed()
    compose.onNodeWithTag("steam-game-search-query").assertHeightIsAtLeast(48.dp)
    compose.onNodeWithTag("steam-game-search-submit").assertHeightIsAtLeast(48.dp)
    compose.onNodeWithText(game.name, useUnmergedTree = true).assertIsDisplayed()
    assertTrue(textLayout(compose.onNodeWithText(game.name, useUnmergedTree = true)).lineCount >= 2)
    compose.onNodeWithText("AppID ${game.appId}", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag("steam-game-result-${game.appId}").assertHeightIsAtLeast(48.dp)
    compose
      .onNodeWithTag("steam-game-result-image-${game.appId}", useUnmergedTree = true)
      .assertDoesNotExist()
    compose.waitForIdle()
    assertTrue("noImages=true must prevent thumbnail requests", requested.isEmpty())
  }

  @Test
  fun thumbnailRequestsRequireBothImagesEnabledAndActivePage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val imageLoader = requestRecordingImageLoader(context, requested)
    val imageUrl = "https://cdn.invalid/hollow-knight.jpg"
    val game = SteamGame(367520, "Hollow Knight", imageUrl)
    val noImages = mutableStateOf(true)
    val active = mutableStateOf(true)
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        HeyReadTheme("light") {
          Column(Modifier.width(500.dp).verticalScroll(rememberScrollState())) {
            SteamGameSearchSection(
              state = SteamGameSearchState(results = listOf(game), hasSearched = true),
              noImages = noImages.value,
              active = active.value,
              onQueryChange = {},
              onSubmit = {},
              onClear = {},
              onSelect = {},
            )
          }
        }
      }
    }

    compose.waitForIdle()
    assertTrue("noImages=true must suppress an otherwise eligible thumbnail", requested.isEmpty())

    compose.runOnIdle {
      noImages.value = false
      active.value = false
    }
    compose.waitForIdle()
    assertTrue("inactive pages must not request thumbnails", requested.isEmpty())

    compose.runOnIdle { active.value = true }
    compose.waitUntil(5_000) { requested.contains(imageUrl) }
    assertEquals(listOf(imageUrl), requested.toList())
  }

  @Test
  fun gameFeedHasNoSourceChipsAndItsSearchEntryDoesNotUsePublicPostSearch() {
    val appViewModel = createOfflineAppViewModel()
    appViewModel.selectSource(FeedTab.GAMES, Source.DEMO)
    var publicSearchRequests = 0
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        FeedScreen(
          title = "游戏",
          ui = UiState(noImages = true, gamesFeed = FeedPageState(Source.DEMO)),
          vm = appViewModel,
          onOpen = {},
          onSearch = { publicSearchRequests++ },
          games = true,
          isCurrentPage = true,
          onSteamGameQueryChange = {},
          onSteamGameSearchSubmit = {},
          onSteamGameSearchClear = {},
          onSteamGameSelect = {},
        )
      }
    }

    compose.onNodeWithText("小黑盒", useUnmergedTree = true).assertDoesNotExist()
    compose.onNodeWithText("Steam", useUnmergedTree = true).assertDoesNotExist()
    compose.onNodeWithText("演示", useUnmergedTree = true).assertDoesNotExist()
    compose.onNodeWithTag("steam-game-search-section").assertIsDisplayed()
    compose.onNodeWithContentDescription("搜索 Steam 游戏").performClick()
    compose.onNodeWithTag("steam-game-search-query").assertIsFocused()
    assertEquals("game search stays inside the game feed", 0, publicSearchRequests)
  }

  private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
    assertEquals(1, results.size)
    return results.single()
  }

  private fun requestRecordingImageLoader(
    context: Context,
    requested: MutableList<String>,
  ): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher =
              object : Fetcher {
                override suspend fun fetch(): FetchResult {
                  requested += data.toString()
                  throw java.io.IOException("Unexpected image request in no-images test")
                }
              }
          }
        )
      }
      .build()

  private fun createOfflineAppViewModel(): AppViewModel {
    val base = InstrumentationRegistry.getInstrumentation().context
    val isolatedRoot = temporaryFolder.newFolder("steam-game-feed")
    val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    testDataStoreScope = dataStoreScope
    val preferences =
      PreferenceDataStoreFactory.create(scope = dataStoreScope) {
        File(isolatedRoot, "settings.preferences_pb")
      }
    val context =
      object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
      }
    val store = LibraryStore(context, File(isolatedRoot, "reading-fonts"), preferences)
    val owner = ViewModelStore()
    testViewModelStore = owner
    val appViewModel = AppViewModel(store)
    owner.put("steam-game-feed", appViewModel)
    return appViewModel
  }
}
