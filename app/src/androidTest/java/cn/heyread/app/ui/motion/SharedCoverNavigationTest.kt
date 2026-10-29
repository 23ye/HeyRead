package cn.heyread.app.ui.motion

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.navigation.HeyReadBottomBar
import cn.heyread.app.navigation.LocalPageTransitionRecords
import cn.heyread.app.navigation.PageTransitionRecord
import cn.heyread.app.navigation.SharedMotionPage
import cn.heyread.app.navigation.pageEnter
import cn.heyread.app.navigation.pageExit
import cn.heyread.app.navigation.pagePopEnter
import cn.heyread.app.navigation.pagePopExit
import cn.heyread.app.ui.screens.Cover
import cn.heyread.app.ui.screens.HomeStoryCard
import cn.heyread.app.ui.screens.storyCoverSharedIdentity
import cn.heyread.app.ui.theme.HeyReadTheme
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedCoverNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun productionCoverPreservesUniformImagePixelsInCardAndDetail() {
    val imageColor = android.graphics.Color.rgb(52, 121, 168)
    val imageUrl = "https://images.invalid/uniform-cover.png"
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val loader = solidImageLoader(compose.activity, requested, imageColor)
    val story =
        Story(
            id = "uniform-cover",
            source = Source.HEYBOX,
            title = "Uniform cover",
            excerpt = "",
            body = "",
            author = "",
            date = "",
            image = imageUrl,
        )
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        CompositionLocalProvider(LocalImageLoader provides loader) {
          Column {
            Cover(story, false, Modifier.width(240.dp).height(130.dp).testTag("uniform-card-cover"))
            Cover(
                story,
                false,
                Modifier.width(240.dp).height(130.dp).testTag("uniform-detail-cover"),
            )
          }
        }
      }
    }
    compose.waitUntil(5_000) { requested.isNotEmpty() }
    compose.waitUntil(5_000) {
      val image = compose.onRoot().captureToImage().toPixelMap()
      val card = compose.onNodeWithTag("uniform-card-cover").fetchSemanticsNode().boundsInRoot
      val detail = compose.onNodeWithTag("uniform-detail-cover").fetchSemanticsNode().boundsInRoot
      val expected = androidx.compose.ui.graphics.Color(imageColor)
      listOf(card, detail).all { bounds ->
        listOf(0.22f, 0.5f, 0.78f).all { fraction ->
          val x = (bounds.left + bounds.width * 0.5f).toInt()
          val y = (bounds.top + bounds.height * fraction).toInt()
          colorDistance(image[x, y], expected) < 0.015f
        }
      }
    }
    val finalCapture = compose.onRoot().captureToImage()
    saveSharedCapture(
        "v1162-cover-uniform-card-detail.png",
        finalCapture,
    )
    val image = finalCapture.toPixelMap()
    val card = compose.onNodeWithTag("uniform-card-cover").fetchSemanticsNode().boundsInRoot
    val detail = compose.onNodeWithTag("uniform-detail-cover").fetchSemanticsNode().boundsInRoot
    listOf(card, detail).forEach { bounds ->
      val samples =
          listOf(0.22f, 0.5f, 0.78f).map { fraction ->
            val x = (bounds.left + bounds.width * 0.5f).toInt()
            val y = (bounds.top + bounds.height * fraction).toInt()
            image[x, y]
          }
      samples.forEach {
        assertEquals(
            "cover has no dark gradient overlay",
            0f,
            colorDistance(it, androidx.compose.ui.graphics.Color(imageColor)),
            0.015f,
        )
      }
      assertTrue("card and detail render the same unmodified image", samples.distinct().size == 1)
    }
  }

  @Test
  fun productionCoverCompletesThreeForwardAndReverseRoundsWithFreshGeneration() {
    compose.mainClock.autoAdvance = false
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val loader = imageLoader(compose.activity, requested)
    lateinit var state: ProductionNavigationState
    compose.setContent { ProductionNavigationHarness(loader) { state = it } }
    compose.waitUntil(5_000) { requested.isNotEmpty() }
    advanceFramesUntil { state.sourceList.firstVisibleItemIndex > 0 }
    compose.mainClock.advanceTimeBy(500)
    compose.runOnIdle {
      assertTrue(
          "the real source list was scrolled before opening",
          state.sourceList.firstVisibleItemIndex > 0,
      )
    }

    val layouts = listOf(HomeLayout.A, HomeLayout.B, HomeLayout.C)
    val sourceAspectRatios = mutableListOf<Float>()
    var previousGeneration = 0L
    layouts.forEachIndexed { index, layout ->
      compose.runOnIdle { state.layout.value = layout }
      waitForNode("home-card-${layout.name}-${state.story.id}")
      val sourceBounds =
          compose
              .onNodeWithTag("home-cover-${state.story.id}", useUnmergedTree = true)
              .fetchSemanticsNode()
              .boundsInRoot
      sourceAspectRatios += sourceBounds.width / sourceBounds.height
      saveSharedCapture(
          "v1161-shared-source-${layout.name.lowercase()}.png",
          compose.onRoot().captureToImage(),
      )
      compose.onNodeWithTag("home-card-${layout.name}-${state.story.id}").performClick()
      advanceFramesUntil {
        state.coordinator.transaction?.targetReady == true &&
            state.coordinator.transaction?.painter != null
      }
      val forward = state.coordinator.transaction!!
      assertTrue("each open owns a newer generation", forward.generation > previousGeneration)
      previousGeneration = forward.generation
      assertEquals(SharedCoverDirection.Forward, forward.direction)
      assertNear(
          "frozen source left matches the visible scrolled cover",
          sourceBounds.left,
          forward.sourceRect.left,
      )
      assertNear(
          "frozen source top matches the visible scrolled cover",
          sourceBounds.top,
          forward.sourceRect.top,
      )
      compose.mainClock.advanceTimeBy(240)
      compose.mainClock.advanceTimeByFrame()
      val midImage = compose.onRoot().captureToImage()
      val mid = magentaCircleBounds(midImage)
      assertRoundImage(mid)
      saveSharedCapture("v1161-shared-forward-mid-${layout.name.lowercase()}.png", midImage)
      compose.mainClock.advanceTimeBy(620)
      compose.mainClock.advanceTimeByFrame()
      compose.runOnIdle { assertNull("forward overlay finishes", state.coordinator.transaction) }
      compose.onNodeWithTag("shared-nav-detail-cover").assertExists()
      val finalDetailBounds =
          compose
              .onNodeWithTag("shared-nav-detail-cover", useUnmergedTree = true)
              .fetchSemanticsNode()
              .boundsInRoot
      assertRectNear(
          "forward target aligns with actual production detail Cover",
          forward.targetRect!!,
          finalDetailBounds,
      )
      assertMovesToward(mid, sourceBounds, finalDetailBounds)
      val detailImage = compose.onRoot().captureToImage()
      assertDetailCropColors(detailImage, finalDetailBounds)
      saveSharedCapture("v1161-shared-detail-final-${layout.name.lowercase()}.png", detailImage)
      compose.onNodeWithTag("heyread-bottom-tabs").assertDoesNotExist()

      if (index == 1) {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      } else {
        compose.onNodeWithTag("detail-top-back").performClick()
      }
      advanceFramesUntil {
        state.coordinator.transaction?.direction == SharedCoverDirection.Return &&
            state.coordinator.transaction?.targetReady == true
      }
      val reverse = state.coordinator.transaction!!
      assertTrue("return gets its own generation", reverse.generation > previousGeneration)
      previousGeneration = reverse.generation
      compose.mainClock.advanceTimeBy(240)
      compose.mainClock.advanceTimeByFrame()
      val returnMidImage = compose.onRoot().captureToImage()
      val returnMid = magentaCircleBounds(returnMidImage)
      assertRoundImage(returnMid)
      saveSharedCapture("v1161-shared-return-mid-${layout.name.lowercase()}.png", returnMidImage)
      compose.mainClock.advanceTimeBy(620)
      compose.mainClock.advanceTimeByFrame()
      compose.runOnIdle {
        assertNull("paired return releases its overlay", state.coordinator.transaction)
      }
      compose.onNodeWithTag("heyread-bottom-tabs").assertExists()
      compose.onNodeWithTag("home-card-${layout.name}-${state.story.id}").assertExists()
      val returnedCoverBounds =
          compose
              .onNodeWithTag("home-cover-${state.story.id}", useUnmergedTree = true)
              .fetchSemanticsNode()
              .boundsInRoot
      assertRectNear(
          "return target aligns with actual source Cover",
          reverse.targetRect!!,
          returnedCoverBounds,
      )
      assertMovesToward(returnMid, finalDetailBounds, returnedCoverBounds)
      saveSharedCapture(
          "v1161-shared-return-final-${layout.name.lowercase()}.png",
          compose.onRoot().captureToImage(),
      )
    }
    assertTrue(
        "A/B/C production covers exercise materially different source aspect ratios",
        sourceAspectRatios.maxOrNull()!! - sourceAspectRatios.minOrNull()!! > 0.5f,
    )

    compose.runOnIdle { state.layout.value = HomeLayout.B }
    waitForNode("home-card-B-${state.story.id}")
    compose.onNodeWithTag("home-card-B-${state.story.id}").performClick()
    advanceFramesUntil { state.coordinator.transaction?.targetReady == true }
    val interruptedGeneration = state.coordinator.transaction!!.generation
    compose.mainClock.advanceTimeBy(90)
    compose.onNodeWithTag("detail-top-back").performClick()
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    compose.runOnIdle {
      assertTrue(
          "rapid pop immediately cancels the stale forward or replaces it with a paired return",
          state.coordinator.transaction?.direction != SharedCoverDirection.Forward,
      )
    }
    compose.mainClock.advanceTimeBy(620)
    repeat(4) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle {
      assertEquals("rapid back returns to the source route", "discover", state.route.value)
      assertNull("rapid interruption cleans the old overlay", state.coordinator.transaction)
    }
    compose.runOnIdle { state.layout.value = HomeLayout.C }
    waitForNode("home-card-C-${state.story.id}")
    compose.onNodeWithTag("home-card-C-${state.story.id}").performClick()
    advanceFramesUntil { state.coordinator.transaction?.targetReady == true }
    assertTrue(
        "reopen after cancellation owns a fresh generation",
        state.coordinator.transaction!!.generation > interruptedGeneration,
    )
    compose.mainClock.advanceTimeBy(620)
    compose.onNodeWithTag("detail-top-back").performClick()
    advanceFramesUntil {
      state.coordinator.transaction?.direction == SharedCoverDirection.Return &&
          state.coordinator.transaction?.targetReady == true
    }
    compose.mainClock.advanceTimeBy(620)
    repeat(4) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle {
      assertNull("reopened transition also cleans up", state.coordinator.transaction)
    }
  }

  @Test
  fun realDetailSwipeCancelsOverlayAndOffscreenReturnFallsBack() {
    compose.mainClock.autoAdvance = false
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val loader = imageLoader(compose.activity, requested)
    lateinit var state: ProductionNavigationState
    compose.setContent {
      ProductionNavigationHarness(loader, scrollableDetail = true) { state = it }
    }
    compose.waitUntil(5_000) { requested.isNotEmpty() }
    advanceFramesUntil { state.sourceList.firstVisibleItemIndex > 0 }
    compose.mainClock.advanceTimeBy(500)

    compose.onNodeWithTag("home-card-A-${state.story.id}").performClick()
    advanceFramesUntil { state.coordinator.transaction?.targetReady == true }
    compose.onNodeWithTag("shared-nav-detail-list").performTouchInput { swipeUp() }
    advanceFramesUntil {
      state.detailList.firstVisibleItemIndex > 0 && state.coordinator.transaction == null
    }
    compose.runOnIdle {
      assertTrue(
          "a real touch scroll moved the detail list",
          state.detailList.firstVisibleItemIndex > 0,
      )
    }

    compose.onNodeWithTag("detail-scroll-top").performClick()
    advanceFramesUntil {
      state.detailList.firstVisibleItemIndex == 0 &&
          state.detailList.firstVisibleItemScrollOffset == 0 &&
          !state.detailList.isScrollInProgress
    }
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
    compose.runOnIdle {
      assertEquals(0, state.detailList.firstVisibleItemIndex)
      assertEquals(0, state.detailList.firstVisibleItemScrollOffset)
      assertTrue(
          "return begins after the detail list settles",
          !state.detailList.isScrollInProgress,
      )
    }
    compose.onNodeWithTag("detail-top-back").performClick()
    advanceFramesUntil {
      state.coordinator.transaction?.direction == SharedCoverDirection.Return &&
          state.coordinator.transaction?.targetReady == true
    }
    compose.mainClock.advanceTimeBy(600)
    repeat(4) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle {
      assertNull(
          "scroll cancellation retains the paired return anchor",
          state.coordinator.transaction,
      )
    }

    compose.onNodeWithTag("home-card-A-${state.story.id}").performClick()
    advanceFramesUntil {
      state.coordinator.transaction?.targetReady == true &&
          state.coordinator.transaction?.painter != null
    }
    compose.mainClock.advanceTimeBy(600)
    compose.onNodeWithTag("detail-scroll-offscreen").performClick()
    advanceFramesUntil { state.detailList.firstVisibleItemIndex > 2 }

    compose.onNodeWithTag("detail-top-back").performClick()
    repeat(8) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle {
      assertEquals(
          "offscreen detail cover falls back to ordinary navigation",
          "discover",
          state.route.value,
      )
      assertNull(state.coordinator.transaction)
    }
  }

  @Test
  fun coldCoilSuccessCompletesFrozenSourceAfterPageStartsLeaving() {
    compose.mainClock.autoAdvance = false
    val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    val releaseImage = CompletableDeferred<Unit>()
    val loader = imageLoader(compose.activity, requested, releaseImage)
    lateinit var state: ProductionNavigationState
    compose.setContent { ProductionNavigationHarness(loader, releaseImage) { state = it } }
    compose.waitUntil(5_000) { requested.isNotEmpty() }
    advanceFramesUntil { state.sourceList.firstVisibleItemIndex > 0 }
    compose.mainClock.advanceTimeBy(500)

    compose.onNodeWithTag("home-card-A-${state.story.id}").performClick()
    advanceFramesUntil {
      state.route.value.startsWith("detail") && state.coordinator.transaction?.targetReady == true
    }
    val frozen = state.coordinator.transaction!!.sourceRect
    assertNull("cold image has not produced a Painter yet", state.coordinator.transaction?.painter)
    releaseImage.complete(Unit)
    advanceFramesUntil { state.coordinator.transaction?.painter != null }
    val loaded = state.coordinator.transaction!!
    assertEquals("late success keeps the click-time source rectangle", frozen, loaded.sourceRect)
    assertTrue("same transaction completes once the real Painter is available", loaded.targetReady)
    compose.mainClock.advanceTimeBy(600)
    repeat(4) {
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    compose.runOnIdle { assertNull(state.coordinator.transaction) }
  }

  private fun advanceFramesUntil(maxFrames: Int = 48, predicate: () -> Boolean) {
    repeat(maxFrames) {
      if (predicate()) return
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    assertTrue("production navigation state did not reach the expected phase", predicate())
  }

  private fun waitForNode(tag: String) {
    advanceFramesUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
  }

  private fun capturePatternBounds(): PatternBounds {
    val image = compose.onRoot().captureToImage()
    return magentaCircleBounds(image)
  }

  private fun saveSharedCapture(name: String, image: ImageBitmap) {
    val file = java.io.File(compose.activity.cacheDir, name)
    file.outputStream().use { output ->
      check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
    }
  }

  private fun assertNear(message: String, expected: Float, actual: Float) {
    assertTrue(
        "$message (expected=$expected actual=$actual)",
        kotlin.math.abs(expected - actual) < 8f,
    )
  }

  private fun assertRoundImage(bounds: PatternBounds) {
    val ratio = bounds.width.toFloat() / bounds.height
    assertTrue(
        "the shared painter must remain uniformly scaled (ratio=$ratio)",
        ratio in 0.82f..1.18f,
    )
  }
}

private data class ProductionNavigationState(
    val coordinator: SharedCoverCoordinator,
    val story: Story,
    val sourceList: LazyListState,
    val detailList: LazyListState,
    val layout: androidx.compose.runtime.MutableState<HomeLayout>,
    val route: androidx.compose.runtime.MutableState<String>,
)

@Composable
private fun ProductionNavigationHarness(
    imageLoader: ImageLoader,
    imageGate: CompletableDeferred<Unit>? = null,
    scrollableDetail: Boolean = false,
    onState: (ProductionNavigationState) -> Unit,
) {
  val nav = rememberNavController()
  val current by nav.currentBackStackEntryAsState()
  val coordinator = remember { SharedCoverCoordinator() }
  val records = remember {
    androidx.compose.runtime.mutableStateMapOf<String, PageTransitionRecord>()
  }
  val story = remember {
    Story(
        id = "shared-nav-pattern",
        source = Source.HEYBOX,
        title = "多比例封面往返验收",
        excerpt = "合成图案用于检查Crop和往返位置。",
        body = "",
        author = "测试作者",
        date = "现在",
        image = "https://images.invalid/shared-pattern.png",
    )
  }
  val rootListState = rememberLazyListState()
  val detailListState = rememberLazyListState()
  val key = "${story.source}:${story.id}"
  val selectedLayout = remember { mutableStateOf(HomeLayout.A) }
  val animatedKeys =
      rememberSaveable(key, saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
      }
  val routeState = remember { mutableStateOf("discover") }
  val identityFor = { origin: String ->
    storyCoverSharedIdentity(story.source.name, story.id, story.image, origin, enabled = true)
  }
  LaunchedEffect(rootListState) { rootListState.scrollToItem(8) }
  LaunchedEffect(current?.id, coordinator) {
    coordinator.setCurrentEntry(current?.id)
    routeState.value = current?.destination?.route ?: "discover"
  }
  androidx.compose.runtime.SideEffect {
    onState(
        ProductionNavigationState(
            coordinator,
            story,
            rootListState,
            detailListState,
            selectedLayout,
            routeState,
        ))
  }
  CompositionLocalProvider(LocalImageLoader provides imageLoader) {
    HeyReadTheme("light", motionEnabled = true) {
      Scaffold(
          bottomBar = {
            if (routeState.value == "discover")
                HeyReadBottomBar("discover", motionEnabled = true) {}
          }) { padding ->
            CompositionLocalProvider(
                LocalSharedCoverCoordinator provides coordinator,
                LocalPageTransitionRecords provides records,
            ) {
              NavHost(
                  navController = nav,
                  startDestination = "discover",
                  modifier = Modifier.padding(padding),
                  enterTransition = { pageEnter(true, records = records) },
                  exitTransition = { pageExit(true, records = records) },
                  popEnterTransition = { pagePopEnter(true, records = records) },
                  popExitTransition = { pagePopExit(true, records = records) },
                  sizeTransform = { null },
              ) {
                composable("discover") { entry ->
                  val isCurrent = current?.id == entry.id
                  SharedMotionPage(
                      this,
                      entry.id,
                      entry.id,
                      route = entry.destination.route,
                      currentRoute = current?.destination?.route,
                      previousRoute = nav.previousBackStackEntry?.destination?.route,
                      isCurrentPage = isCurrent,
                      motionEnabled = true,
                      isRtl = false,
                  ) {
                    cancelSharedCoverOnScroll(rootListState, entry.id, coordinator)
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("shared-nav-source-list"),
                        state = rootListState,
                    ) {
                      items(8) { Spacer(Modifier.height(150.dp)) }
                      item(key = key) {
                        StaggeredCard(
                            key = key,
                            batch = FirstVisibleBatch(listOf(key), true, rootListState, setOf(key)),
                            animatedKeys = animatedKeys,
                            enabled = true,
                        ) {
                          HomeStoryCard(
                              story = story,
                              layout = selectedLayout.value,
                              featured = selectedLayout.value == HomeLayout.A,
                              noImages = false,
                              onOpen = {
                                val id = entry.id
                                coordinator.begin(identityFor(id), id, id)
                                nav.navigate("detail?origin=${Uri.encode(id)}")
                                coordinator.bindExpectedTarget(nav.currentBackStackEntry?.id)
                              },
                          )
                        }
                      }
                      items(16) { Spacer(Modifier.height(100.dp)) }
                    }
                  }
                }
                composable(
                    route = "detail?origin={origin}",
                    arguments =
                        listOf(
                            navArgument("origin") {
                              type = NavType.StringType
                              defaultValue = ""
                            }),
                ) { entry ->
                  val origin = entry.arguments?.getString("origin").orEmpty()
                  val isCurrent = current?.id == entry.id
                  val identity = identityFor(origin)
                  val scope = rememberCoroutineScope()
                  val onBack: () -> Unit =
                      remember(coordinator, identity, origin, entry.id, nav) {
                        {
                          coordinator.beginReturn(identity, origin, entry.id)
                          nav.popBackStack()
                          Unit
                        }
                      }
                  BackHandler(enabled = isCurrent, onBack = onBack)
                  SharedMotionPage(
                      this,
                      entry.id,
                      origin,
                      isDetail = true,
                      route = entry.destination.route,
                      currentRoute = current?.destination?.route,
                      previousRoute = nav.previousBackStackEntry?.destination?.route,
                      isCurrentPage = isCurrent,
                      motionEnabled = true,
                      isRtl = false,
                  ) {
                    Column(Modifier.fillMaxSize()) {
                      Row(
                          Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                          horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        TextButton(
                            onClick = onBack, modifier = Modifier.testTag("detail-top-back")) {
                              Text("返回")
                            }
                        Text("详情")
                        if (scrollableDetail) {
                          TextButton(
                              onClick = { scope.launch { detailListState.animateScrollToItem(0) } },
                              modifier = Modifier.testTag("detail-scroll-top"),
                          ) {
                            Text("回到顶部")
                          }
                          TextButton(
                              onClick = { scope.launch { detailListState.animateScrollToItem(4) } },
                              modifier = Modifier.testTag("detail-scroll-offscreen"),
                          ) {
                            Text("滚离封面")
                          }
                        }
                      }
                      val cover =
                          @Composable {
                            Cover(
                                story,
                                noImages = false,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(220.dp)
                                        .testTag("shared-nav-detail-cover"),
                            )
                          }
                      if (scrollableDetail) {
                        cancelSharedCoverOnScroll(detailListState, entry.id, coordinator)
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("shared-nav-detail-list"),
                            state = detailListState,
                        ) {
                          item { cover() }
                          items(20) { index -> Text("详情行 $index", Modifier.height(70.dp)) }
                        }
                      } else Box(Modifier.fillMaxSize()) { cover() }
                    }
                  }
                }
              }
              SharedCoverOverlayHost(coordinator)
            }
          }
    }
  }
}

private fun imageLoader(
    context: Context,
    requested: MutableList<String>,
    imageGate: CompletableDeferred<Unit>? = null,
): ImageLoader =
    ImageLoader.Builder(context)
        .components {
          add(
              object : Fetcher.Factory<Uri> {
                override fun create(
                    data: Uri,
                    options: Options,
                    imageLoader: ImageLoader
                ): Fetcher? {
                  if (!data.toString().startsWith("https://images.invalid/")) return null
                  return object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                      requested += data.toString()
                      imageGate?.await()
                      return DrawableResult(
                          android.graphics.drawable.BitmapDrawable(
                              context.resources, patternBitmap()),
                          false,
                          DataSource.MEMORY,
                      )
                    }
                  }
                }
              })
        }
        .build()

private fun solidImageLoader(
    context: Context,
    requested: MutableList<String>,
    color: Int,
): ImageLoader =
    ImageLoader.Builder(context)
        .components {
          add(
              object : Fetcher.Factory<Uri> {
                override fun create(
                    data: Uri,
                    options: Options,
                    imageLoader: ImageLoader
                ): Fetcher? {
                  if (!data.toString().startsWith("https://images.invalid/")) return null
                  return object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                      requested += data.toString()
                      val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                      bitmap.eraseColor(color)
                      return DrawableResult(
                          android.graphics.drawable.BitmapDrawable(context.resources, bitmap),
                          false,
                          DataSource.MEMORY,
                      )
                    }
                  }
                }
              })
        }
        .build()

private fun colorDistance(
    first: androidx.compose.ui.graphics.Color,
    second: androidx.compose.ui.graphics.Color,
): Float =
    kotlin.math.abs(first.red - second.red) +
        kotlin.math.abs(first.green - second.green) +
        kotlin.math.abs(first.blue - second.blue)

private fun patternBitmap(): Bitmap {
  val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
  val canvas = android.graphics.Canvas(bitmap)
  val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  canvas.drawColor(android.graphics.Color.rgb(25, 40, 55))
  paint.color = android.graphics.Color.rgb(230, 60, 55)
  canvas.drawRect(0f, 0f, 80f, 180f, paint)
  paint.color = android.graphics.Color.rgb(45, 175, 110)
  canvas.drawRect(80f, 0f, 160f, 180f, paint)
  paint.color = android.graphics.Color.rgb(60, 100, 220)
  canvas.drawRect(160f, 0f, 240f, 180f, paint)
  paint.color = android.graphics.Color.rgb(230, 180, 35)
  canvas.drawRect(240f, 0f, 320f, 180f, paint)
  paint.color = android.graphics.Color.MAGENTA
  canvas.drawCircle(160f, 90f, 26f, paint)
  paint.color = android.graphics.Color.CYAN
  canvas.drawRect(0f, 0f, 18f, 18f, paint)
  canvas.drawRect(302f, 0f, 320f, 18f, paint)
  canvas.drawRect(0f, 162f, 18f, 180f, paint)
  canvas.drawRect(302f, 162f, 320f, 180f, paint)
  return bitmap
}

private data class PatternBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
  val centerX: Float
    get() = left + width / 2f

  val centerY: Float
    get() = top + height / 2f
}

private fun magentaCircleBounds(image: androidx.compose.ui.graphics.ImageBitmap): PatternBounds {
  val pixels = image.toPixelMap()
  var left = pixels.width
  var right = -1
  var top = pixels.height
  var bottom = -1
  for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
    val color = pixels[x, y]
    if (color.red > 0.65f && color.blue > 0.65f && color.green < 0.35f && color.alpha > 0.7f) {
      left = minOf(left, x)
      right = maxOf(right, x)
      top = minOf(top, y)
      bottom = maxOf(bottom, y)
    }
  }
  assertTrue("pattern circle remains painted", right >= left && bottom >= top)
  return PatternBounds(left, top, right - left + 1, bottom - top + 1)
}

private fun assertMovesToward(pattern: PatternBounds, start: Rect, end: Rect) {
  val startDistance =
      kotlin.math.hypot(pattern.centerX - start.center.x, pattern.centerY - start.center.y)
  val endDistance =
      kotlin.math.hypot(pattern.centerX - end.center.x, pattern.centerY - end.center.y)
  assertTrue(
      "the actual circle is closer to the destination than the origin",
      endDistance < startDistance,
  )
}

private fun assertRectNear(message: String, expected: Rect, actual: Rect) {
  assertTrue(
      "$message (expected=$expected actual=$actual)",
      kotlin.math.abs(expected.left - actual.left) < 4f &&
          kotlin.math.abs(expected.top - actual.top) < 4f &&
          kotlin.math.abs(expected.width - actual.width) < 4f &&
          kotlin.math.abs(expected.height - actual.height) < 4f,
  )
}

private fun assertDetailCropColors(image: androidx.compose.ui.graphics.ImageBitmap, bounds: Rect) {
  val pixels = image.toPixelMap()
  fun sample(fraction: Float): androidx.compose.ui.graphics.Color =
      pixels[
          (bounds.left + bounds.width * fraction).toInt().coerceIn(0, pixels.width - 1),
          (bounds.top + bounds.height * 0.2f).toInt().coerceIn(0, pixels.height - 1),
      ]
  val red = sample(0.1f)
  val green = sample(0.38f)
  val blue = sample(0.63f)
  val yellow = sample(0.9f)
  assertTrue("left edge retains red crop stripe", red.red > 0.65f && red.green < 0.4f)
  assertTrue("left interior retains green stripe", green.green > 0.5f && green.red < 0.4f)
  assertTrue("right interior retains blue stripe", blue.blue > 0.6f && blue.red < 0.45f)
  assertTrue("right edge retains yellow stripe", yellow.red > 0.65f && yellow.green > 0.45f)
}
