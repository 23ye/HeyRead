package cn.heyread.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockTarget
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingScope
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.contentBlockStoryTargets
import cn.heyread.app.data.remote.DestinationData
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationPage
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.ContentBlockingFeedbackHost
import cn.heyread.app.ui.components.ContentBlockingQuickAdd
import cn.heyread.app.ui.components.LocalContentBlockingContext
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.screens.ContentBlockingSettings
import cn.heyread.app.ui.screens.DestinationScreen
import cn.heyread.app.ui.screens.HomeStoryCard
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.DestinationViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingQuickAddTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var fixture: Fixture? = null

  @After
  fun closeIsolatedStore() {
    fixture?.let { current ->
      compose.activityRule.scenario.onActivity { current.owner.clear() }
      current.storeScope.cancel()
    }
    fixture = null
  }

  @Test
  fun articleKeywordConfirmationCancelAndUndoPreserveOlderRules() {
    val current = createFixture()
    awaitReady(current)
    current.viewModel.onContentBlockingAction(ContentBlockingAction.AddKeywords("legacy-rule"))
    awaitRules(current, 1)
    current.viewModel.dismissContentBlockingFeedback()
    val legacy = current.viewModel.state.value.contentBlocking.rules.single()
    val openedStory = story("article", "spoiler title")
    showQuickAdd(current, openedStory, emptyList(), "detail:article")

    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithText("取消").performClick()
    compose.runOnIdle { assertEquals(1, current.viewModel.state.value.contentBlocking.rules.size) }

    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithTag("content-blocking-quick-keyword").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-quick-confirm").performClick()
    awaitRules(current, 2)
    compose.waitUntil(5_000) { current.viewModel.state.value.contentBlockingUndo.size == 1 }
    assertEquals(
      listOf("legacy-rule", "spoiler title"),
      current.viewModel.state.value.contentBlocking.rules.map { it.value },
    )
    compose.onNodeWithText(openedStory.title).assertDoesNotExist()
    compose.onNodeWithText("ordinary").assertIsDisplayed()
    compose.onNodeWithText("撤销").performClick()
    compose.waitUntil(5_000) {
      !current.viewModel.state.value.contentBlockingBusy &&
        current.viewModel.state.value.contentBlocking.rules.size == 1
    }
    assertEquals(listOf(legacy), current.viewModel.state.value.contentBlocking.rules)
    compose.onNodeWithText(openedStory.title).assertIsDisplayed()
  }

  @Test
  fun articleAuthorSectionAndTopicCandidatesUseOnlyCurrentHeyboxMetadata() {
    val current = createFixture()
    awaitReady(current)
    val realStory =
      story("article", "Title").copy(
        source = Source.HEYBOX,
        authorProfile = AuthorProfile("Writer", userId = "42"),
        sections = listOf(ArticleLabel("7", "Section")),
        hashtags = listOf(ArticleLabel("9", "Topic")),
      )
    val candidates = contentBlockStoryTargets(realStory)
    assertEquals(listOf(ContentBlockKind.AUTHOR, ContentBlockKind.SECTION, ContentBlockKind.TOPIC), candidates.map { it.kind })
    showQuickAdd(current, realStory, candidates, "detail:heybox-article")

    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithTag("content-blocking-quick-target-section-7").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-quick-target-topic-9").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-quick-target-author-42").performClick()
    compose.onNodeWithTag("content-blocking-quick-confirm").performClick()
    awaitRules(current, 1)
    assertEquals(ContentBlockKind.AUTHOR, current.viewModel.state.value.contentBlocking.rules.single().kind)
    assertEquals("42", current.viewModel.state.value.contentBlocking.rules.single().value)

    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithTag("content-blocking-quick-target-section-7").performClick()
    compose.onNodeWithTag("content-blocking-quick-confirm").performClick()
    awaitRules(current, 2)
    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithTag("content-blocking-quick-target-topic-9").performClick()
    compose.onNodeWithTag("content-blocking-quick-confirm").performClick()
    awaitRules(current, 3)
    assertEquals(
      listOf(ContentBlockKind.AUTHOR, ContentBlockKind.SECTION, ContentBlockKind.TOPIC),
      current.viewModel.state.value.contentBlocking.rules.map { it.kind },
    )
    assertEquals(listOf("42", "7", "9"), current.viewModel.state.value.contentBlocking.rules.map { it.value })
    compose.onNodeWithText("撤销").performClick()
    compose.waitUntil(5_000) { current.viewModel.state.value.contentBlocking.rules.size == 2 }
    assertEquals(listOf("42", "7"), current.viewModel.state.value.contentBlocking.rules.map { it.value })

    val nonHeybox = realStory.copy(source = Source.STEAM)
    assertTrue(contentBlockStoryTargets(nonHeybox).isEmpty())
    val missingAuthorId = realStory.copy(authorProfile = AuthorProfile("No ID"))
    assertFalse(contentBlockStoryTargets(missingAuthorId).any { it.kind == ContentBlockKind.AUTHOR })
  }

  @Test
  fun destinationTargetConfirmationCancelAndUndoKeepTargetPageVisible() {
    val current = createFixture()
    awaitReady(current)
    val target = DestinationTarget(DestinationType.HASHTAG, "9001", "测试话题")
    val destinationViewModel =
      DestinationViewModel(
        DestinationLoader { _, _, _, _, _, _, _, _, _, _ ->
          DestinationPage(
            data = DestinationData("合成话题"),
            stories = emptyList(),
            nextOffset = 0,
            hasMore = false,
          )
        }
      )
    compose.activityRule.scenario.onActivity { current.owner.put("content-blocking-quick-destination", destinationViewModel) }
    compose.setContent {
      val ui by current.viewModel.state.collectAsState()
      HeyReadTheme("light", motionEnabled = false) {
        val context =
          ContentBlockingContext(
            config = ui.contentBlocking,
            ready = ui.contentBlockingLoaded,
            loading = ui.contentBlockingLoading,
            error = ui.contentBlockingError,
          )
        val hostState = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalContentBlockingContext provides context) {
          Box(Modifier.fillMaxSize()) {
            DestinationScreen(
              target = target,
              viewModel = destinationViewModel,
              ui = ui,
              onBack = {},
              onOpenStory = {},
              onOpenSection = {},
              onContentBlockingAction = current.viewModel::onContentBlockingAction,
            )
            ContentBlockingFeedbackHost(
              ui,
              hostState,
              current.viewModel::onContentBlockingAction,
              current.viewModel::dismissContentBlockingFeedback,
            )
            SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter))
          }
        }
      }
    }
    compose.waitUntil(5_000) {
      destinationViewModel.state.value.target == target &&
        !destinationViewModel.state.value.loading &&
        destinationViewModel.state.value.data != null
    }
    compose.onNodeWithTag("content-blocking-quick-add-open").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithText("取消").performClick()
    compose.runOnIdle { assertTrue(current.viewModel.state.value.contentBlocking.rules.isEmpty()) }

    compose.onNodeWithTag("content-blocking-quick-add-open").performClick()
    compose.onNodeWithTag("content-blocking-quick-confirm").performClick()
    awaitRules(current, 1)
    compose.onNodeWithText("#合成话题").assertIsDisplayed()
    compose.onNodeWithText("撤销").performClick()
    compose.waitUntil(5_000) { current.viewModel.state.value.contentBlocking.rules.isEmpty() }
    compose.onNodeWithText("#合成话题").assertIsDisplayed()
  }

  @Test
  fun settingsLinkRequiresPreviewAndConfirmationAndUndoOnlyRemovesNewRule() {
    val current = createFixture()
    awaitReady(current)
    current.viewModel.onContentBlockingAction(ContentBlockingAction.AddKeywords("keep-me"))
    awaitRules(current, 1)
    current.viewModel.dismissContentBlockingFeedback()
    showSettings(current)

    compose.onNodeWithTag("content-blocking-kind-author").performScrollTo().performClick()
    compose
      .onNodeWithTag("content-blocking-id-input")
      .performScrollTo()
      .performTextInput("https://www.xiaoheihe.cn/app/user/profile/00042")
    compose.onNodeWithTag("content-blocking-label-input").performScrollTo().performTextInput("本机作者备注")
    compose.onNodeWithTag("content-blocking-add").performScrollTo().performClick()
    compose.onNodeWithTag("content-blocking-link-preview").assertIsDisplayed()
    compose.onNodeWithText("识别为作者，小黑盒ID 42").assertIsDisplayed()
    val previewLabel = compose.onNodeWithTag("content-blocking-link-label")
    previewLabel.performTextClearance()
    previewLabel.performTextInput("预览备注")
    compose.onNodeWithTag("content-blocking-link-confirm").performClick()
    awaitRules(current, 2)
    assertEquals("42", current.viewModel.state.value.contentBlocking.rules.last().value)
    assertEquals("预览备注", current.viewModel.state.value.contentBlocking.rules.last().label)
    compose.onNodeWithText("撤销").performClick()
    compose.waitUntil(5_000) { current.viewModel.state.value.contentBlocking.rules.size == 1 }
    current.viewModel.dismissContentBlockingFeedback()
    assertEquals("keep-me", current.viewModel.state.value.contentBlocking.rules.single().value)

    compose.onNodeWithTag("content-blocking-kind-section").performScrollTo().performClick()
    val mismatchedLinkInput = compose.onNodeWithTag("content-blocking-id-input").performScrollTo()
    mismatchedLinkInput.performTextClearance()
    mismatchedLinkInput.performTextInput("https://www.xiaoheihe.cn/app/user/profile/42")
    compose.onNodeWithTag("content-blocking-add").performScrollTo().performClick()
    compose.onNodeWithTag("content-blocking-link-error").assertIsDisplayed()
    assertEquals(1, current.viewModel.state.value.contentBlocking.rules.size)
  }

  private fun showQuickAdd(
    current: Fixture,
    story: Story,
    targets: List<ContentBlockTarget>,
    identity: String,
  ) {
    compose.setContent {
      val ui by current.viewModel.state.collectAsState()
      val blockingContext =
        ContentBlockingContext(
          config = ui.contentBlocking,
          ready = ui.contentBlockingLoaded,
          loading = ui.contentBlockingLoading,
          error = ui.contentBlockingError,
        )
      HeyReadTheme("light", motionEnabled = false) {
        val hostState = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalContentBlockingContext provides blockingContext) {
          Box(Modifier.fillMaxSize()) {
            Column {
              ContentBlockingQuickAdd(
                targets = targets,
                ui = ui,
                isCurrentPage = true,
                identityKey = identity,
                keywordPrefill = story.title,
                onAction = current.viewModel::onContentBlockingAction,
              )
              val projection =
                rememberContentBlockingList(
                  raw = listOf(story, story("other", "ordinary")),
                  scope = ContentBlockingScope.FEED,
                  identity = identity,
                  isCurrentPage = true,
                )
              projection.items.forEach { visibleStory ->
                HomeStoryCard(
                  story = visibleStory,
                  layout = HomeLayout.A,
                  featured = false,
                  noImages = true,
                  onOpen = {},
                )
              }
            }
            ContentBlockingFeedbackHost(
              ui,
              hostState,
              current.viewModel::onContentBlockingAction,
              current.viewModel::dismissContentBlockingFeedback,
            )
            SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter))
          }
        }
      }
    }
  }

  private fun showSettings(current: Fixture) {
    compose.setContent {
      val ui by current.viewModel.state.collectAsState()
      HeyReadTheme("light", motionEnabled = false) {
        val hostState = remember { SnackbarHostState() }
        Box(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ContentBlockingSettings(ui, current.viewModel::onContentBlockingAction, true)
          }
          ContentBlockingFeedbackHost(
            ui,
            hostState,
            current.viewModel::onContentBlockingAction,
            current.viewModel::dismissContentBlockingFeedback,
          )
          SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter))
        }
      }
    }
  }

  private fun createFixture(): Fixture {
    val isolatedRoot = temporaryFolder.newFolder("content-blocking-quick-add")
    val base = InstrumentationRegistry.getInstrumentation().context
    val context =
      object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(isolatedRoot, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(isolatedRoot, "cache").apply { mkdirs() }
      }
    val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(scope = storeScope) {
        File(isolatedRoot, "content-blocking.preferences_pb")
      }
    val fontsDirectory = File(isolatedRoot, "reading-fonts").apply { mkdirs() }
    val store = LibraryStore(context, fontsDirectory, dataStore)
    val owner = ViewModelStore()
    lateinit var appViewModel: AppViewModel
    compose.activityRule.scenario.onActivity {
      appViewModel = AppViewModel(store)
      owner.put("content-blocking-quick-add", appViewModel)
    }
    return Fixture(appViewModel, owner, storeScope).also { fixture = it }
  }

  private fun awaitReady(current: Fixture) {
    compose.waitUntil(10_000) { !current.viewModel.state.value.contentBlockingLoading }
    assertTrue(current.viewModel.state.value.contentBlockingLoaded)
  }

  private fun awaitRules(current: Fixture, count: Int) {
    compose.waitUntil(5_000) {
      !current.viewModel.state.value.contentBlockingBusy &&
        current.viewModel.state.value.contentBlocking.rules.size == count
    }
  }

  private data class Fixture(
    val viewModel: AppViewModel,
    val owner: ViewModelStore,
    val storeScope: CoroutineScope,
  )

  private fun story(id: String, title: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title = title,
      excerpt = "excerpt",
      body = "body",
      author = "writer",
      date = "today",
    )
}
