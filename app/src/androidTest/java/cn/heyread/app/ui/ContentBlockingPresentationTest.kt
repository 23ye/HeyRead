package cn.heyread.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.ContentBlockingListState
import cn.heyread.app.ui.components.ContentBlockingNotice
import cn.heyread.app.ui.components.ContentBlockingScope
import cn.heyread.app.ui.components.LocalContentBlockingContext
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.theme.HeyReadTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingPresentationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun firstLoadHidesStoriesAndFirstReadFailureFailsOpenExplicitly() {
    val context = mutableStateOf(ContentBlockingContext(loading = true, ready = false))
    val raw = listOf(story("visible", "hello"), story("blocked", "spoiler"))
    val observed = mutableStateOf<ContentBlockingListState?>(null)
    showHarness(context, raw, observed)

    compose.onNodeWithTag("content-blocking-loading").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(0, observed.value?.items?.size)
      assertTrue(observed.value?.loading == true)
    }

    context.value =
      ContentBlockingContext(
        config = config("spoiler"),
        ready = false,
        loading = false,
        error = "local read failed",
      )
    compose.waitForIdle()
    compose.onNodeWithTag("content-blocking-unavailable").assertIsDisplayed()
    compose.onNodeWithText("count:2").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(raw, observed.value?.items)
      assertEquals(0, observed.value?.blockedCount)
    }

    context.value =
      ContentBlockingContext(
        config = config("spoiler"),
        ready = true,
        error = "settings write validation error",
      )
    compose.waitForIdle()
    compose.onNodeWithTag("content-blocking-unavailable").assertDoesNotExist()
    compose.onNodeWithText("count:1").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, observed.value?.blockedCount) }
  }

  @Test
  fun temporaryShowIsScopedToIdentityRulesSessionAndActivePage() {
    val context = mutableStateOf(ContentBlockingContext(config("spoiler")))
    val raw = listOf(story("blocked", "spoiler"), story("visible", "hello"))
    val active = mutableStateOf(true)
    val identity = mutableStateOf("test-list")
    val observed = mutableStateOf<ContentBlockingListState?>(null)
    showHarness(
      context,
      raw,
      observed,
      identity = { identity.value },
      isCurrentPage = { active.value },
    )

    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.runOnIdle { assertEquals(2, observed.value?.items?.size) }

    // Editing the label/UUID does not change the effective rules fingerprint.
    context.value =
      context.value.copy(
        config =
          context.value.config.copy(
            rules = context.value.config.rules.map { it.copy(id = "renamed", label = "new label") },
            applyToFavorites = true,
          )
      )
    compose.waitForIdle()
    compose.runOnIdle { assertTrue(observed.value?.isTemporarilyShowing == true) }

    context.value =
      context.value.copy(
        config =
          context.value.config.copy(
            rules = context.value.config.rules.map { it.copy(value = "hello") }
          )
      )
    compose.waitForIdle()
    compose.runOnIdle {
      assertFalse(observed.value?.isTemporarilyShowing == true)
      assertEquals(1, observed.value?.items?.size)
    }

    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.runOnIdle { assertTrue(observed.value?.isTemporarilyShowing == true) }
    identity.value = "different-list"
    compose.waitForIdle()
    compose.runOnIdle { assertFalse(observed.value?.isTemporarilyShowing == true) }

    // A different signed-in session starts with filtering active and no temporary reveal.
    context.value = context.value.copy(sessionKey = "user-2:revision-2")
    compose.waitForIdle()
    compose.runOnIdle {
      assertFalse(observed.value?.isTemporarilyShowing == true)
      assertEquals(1, observed.value?.items?.size)
      assertEquals(1, observed.value?.blockedCount)
    }

    compose.onNodeWithTag("content-blocking-temporary-toggle").performClick()
    compose.runOnIdle { assertTrue(observed.value?.isTemporarilyShowing == true) }
    active.value = false
    compose.waitForIdle()
    compose.runOnIdle { assertFalse(observed.value?.isTemporarilyShowing == true) }
  }

  @Test
  fun ruleChangeSuppressesAutoPaginationUntilARealListDrag() {
    val context = mutableStateOf(ContentBlockingContext(config = ContentBlockingConfig()))
    val raw =
      mutableStateOf(
        (0 until 14).map { index -> story("story-$index", if (index == 5) "spoiler" else "story") }
      )
    val hasMore = mutableStateOf(true)
    val observed = mutableStateOf<ContentBlockingListState?>(null)
    val listStateRef = mutableStateOf<androidx.compose.foundation.lazy.LazyListState?>(null)
    val scopeRef = mutableStateOf<CoroutineScope?>(null)
    val monitorAuto = mutableStateOf(false)
    val manualRequests = AtomicInteger()
    val autoRequests = AtomicInteger()
    val listTag = "cb-list"
    compose.setContent {
      HeyReadTheme("light") {
        CompositionLocalProvider(LocalContentBlockingContext provides context.value) {
          val listState = rememberLazyListState()
          val scope = rememberCoroutineScope()
          val state =
            rememberContentBlockingList(
              raw.value,
              ContentBlockingScope.FEED,
              identity = "feed:discover",
              isCurrentPage = true,
              listState = listState,
            )
          SideEffect { observed.value = state }
          SideEffect {
            listStateRef.value = listState
            scopeRef.value = scope
          }
          androidx.compose.runtime.LaunchedEffect(listState) {
            androidx.compose.runtime
              .snapshotFlow {
                listState.layoutInfo.totalItemsCount to
                  (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)
              }
              .collect { (total, last) ->
                if (
                  monitorAuto.value && hasMore.value && state.canAutoPaginate && total - last <= 3
                )
                  autoRequests.incrementAndGet()
              }
          }
          Column {
            TextButton(
              onClick = {
                state.beforeManualLoad()
                manualRequests.incrementAndGet()
                raw.value = raw.value + story("story-next", "next page")
                hasMore.value = true
              },
              modifier = Modifier.testTag("cb-manual-continue"),
            ) {
              Text("manual continue")
            }
            LazyColumn(Modifier.fillMaxSize().testTag(listTag), state = listState) {
              items(state.items, key = Story::id) { item -> Text(item.id, Modifier.height(72.dp)) }
            }
          }
        }
      }
    }
    compose.runOnIdle { assertTrue(observed.value?.canAutoPaginate == true) }

    context.value = ContentBlockingContext(config = config("spoiler"))
    compose.waitForIdle()
    compose.runOnIdle {
      assertEquals(13, observed.value?.items?.size)
      assertFalse(
        "a matching rule change pauses automatic pagination",
        observed.value?.canAutoPaginate == true,
      )
      assertFalse("raw tail is still visible", observed.value?.rawTailBlocked == true)
    }

    compose.onNodeWithTag(listTag).performTouchInput { swipeUp() }
    compose.waitForIdle()
    compose.runOnIdle {
      assertTrue(
        "a real drag releases the rule-change gate",
        observed.value?.canAutoPaginate == true,
      )
    }
    compose.runOnIdle { scopeRef.value?.launch { listStateRef.value?.scrollToItem(0) } }
    compose.waitForIdle()
    compose.runOnIdle { monitorAuto.value = true }
    compose.onNodeWithTag("cb-manual-continue").performClick()
    compose.waitUntil(5_000) { raw.value.size == 15 }
    compose.runOnIdle {
      scopeRef.value?.launch { listStateRef.value?.scrollToItem(raw.value.lastIndex) }
    }
    compose.waitUntil(5_000) {
      (listStateRef.value?.layoutInfo?.visibleItemsInfo?.lastOrNull()?.index ?: -1) >= 12
    }
    compose.runOnIdle {
      val stateCapturedByAnExistingCollector = requireNotNull(observed.value)
      assertEquals(14, stateCapturedByAnExistingCollector.items.size)
      assertFalse(
        "the already-captured helper state reads the manual gate after an immediate raw append",
        stateCapturedByAnExistingCollector.canAutoPaginate,
      )
      assertEquals("one explicit continuation", 1, manualRequests.get())
      assertEquals("no automatic follow-up while server still has more", 0, autoRequests.get())
      assertTrue("one manual page does not alter server hasMore", hasMore.value)
    }
  }

  @Test
  fun noticeWrapsInNarrowLargeFontLayoutAndKeepsTouchTarget() {
    val context = ContentBlockingContext(config("spoiler"))
    val raw = listOf(story("blocked", "spoiler"), story("visible", "hello"))
    val observed = mutableStateOf<ContentBlockingListState?>(null)
    compose.setContent {
      HeyReadTheme("dark") {
        CompositionLocalProvider(
          LocalContentBlockingContext provides context,
          LocalDensity provides Density(compose.activity.resources.displayMetrics.density, 1.3f),
        ) {
          Box(Modifier.width(210.dp).height(240.dp)) {
            val state =
              rememberContentBlockingList(
                raw,
                ContentBlockingScope.FEED,
                identity = "feed:narrow",
                isCurrentPage = true,
              )
            SideEffect { observed.value = state }
            Column {
              ContentBlockingNotice(state)
              Text("narrow host", Modifier.testTag("content-blocking-narrow-host"))
            }
          }
        }
      }
    }
    compose.onNodeWithTag("content-blocking-notice").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-temporary-toggle").assertHeightIsAtLeast(48.dp)
    compose.onNodeWithText("临时显示").assertIsDisplayed()
    compose.runOnIdle {
      assertNotNull(observed.value)
      assertTrue(observed.value?.blockedCount == 1)
    }
  }

  private fun showHarness(
    context: androidx.compose.runtime.State<ContentBlockingContext>,
    raw: List<Story>,
    observed: androidx.compose.runtime.MutableState<ContentBlockingListState?>,
    identity: () -> String = { "test-list" },
    isCurrentPage: () -> Boolean = { true },
  ) {
    compose.setContent {
      HeyReadTheme("light") {
        CompositionLocalProvider(LocalContentBlockingContext provides context.value) {
          val listState = rememberLazyListState()
          val state =
            rememberContentBlockingList(
              raw,
              ContentBlockingScope.FEED,
              identity = identity(),
              isCurrentPage = isCurrentPage(),
              listState = listState,
            )
          SideEffect { observed.value = state }
          Column {
            ContentBlockingNotice(state)
            Text("count:${state.items.size}")
          }
        }
      }
    }
  }

  private fun config(keyword: String) =
    ContentBlockingConfig(
      rules = listOf(ContentBlockRule("spoiler-rule", ContentBlockKind.KEYWORD, keyword))
    )

  private fun story(id: String, title: String) =
    Story(
      id = id,
      source = Source.HEYBOX,
      title = title,
      excerpt = "",
      body = "not used by matcher",
      author = "",
      date = "",
    )
}
