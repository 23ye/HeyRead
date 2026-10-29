package cn.heyread.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.saveTargetCacheScreenshot
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationMotionTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun forwardAndBackTransitionsRenderTwoRealPagesAndReleaseOutgoingEntry() {
    compose.mainClock.autoAdvance = false
    lateinit var nav: NavHostController
    val positions = ConcurrentHashMap<String, LayoutCoordinates>()
    compose.setContent {
      nav = rememberNavController()
      val records = remember {
        androidx.compose.runtime.mutableStateMapOf<String, PageTransitionRecord>()
      }
      CompositionLocalProvider(LocalPageTransitionRecords provides records) {
        NavHost(
          navController = nav,
          startDestination = "first",
          enterTransition = { pageEnter(true, records = records) },
          exitTransition = { pageExit(true, records = records) },
          popEnterTransition = { pagePopEnter(true, records = records) },
          popExitTransition = { pagePopExit(true, records = records) },
        ) {
          composable("first") { entry ->
            SharedMotionPage(
              this@composable,
              entry.id,
              entry.id,
              route = entry.destination.route,
              currentRoute = nav.currentBackStackEntry?.destination?.route,
              previousRoute = nav.previousBackStackEntry?.destination?.route,
              motionEnabled = true,
              isRtl = false,
            ) {
              Page("motion-first", positions) { nav.navigate("second") }
            }
          }
          composable("second") { entry ->
            SharedMotionPage(
              this@composable,
              entry.id,
              entry.id,
              route = entry.destination.route,
              currentRoute = nav.currentBackStackEntry?.destination?.route,
              previousRoute = nav.previousBackStackEntry?.destination?.route,
              motionEnabled = true,
              isRtl = false,
            ) {
              Page("motion-second", positions) { nav.popBackStack() }
            }
          }
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithTag("motion-first").assertExists()
    val originalLeft = positions.getValue("motion-first").positionInRoot().x

    compose.onNodeWithTag("motion-next").performClick()
    compose.mainClock.advanceTimeBy(260)
    compose.onNodeWithTag("motion-first").assertExists()
    compose.onNodeWithTag("motion-second").assertExists()
    val movingLeft = positions.getValue("motion-first").positionInRoot().x
    assertNotEquals("outgoing page must move during the real transition", originalLeft, movingLeft)
    val screenWidth = compose.onRoot().captureToImage().width.toFloat()
    val parallax = originalLeft - movingLeft
    assertTrue(
      "the outgoing predecessor should travel by roughly one quarter-width",
      parallax > screenWidth * 0.05f && parallax < screenWidth * 0.30f,
    )
    saveTargetCacheScreenshot("v116-page-mid.png")

    compose.mainClock.advanceTimeBy(300)
    saveTargetCacheScreenshot("v116-page-final.png")
    compose.onNodeWithTag("motion-first").assertDoesNotExist()
    compose.onNodeWithTag("motion-second").assertExists()

    compose.onNodeWithTag("motion-back").performClick()
    compose.mainClock.advanceTimeBy(260)
    compose.onNodeWithTag("motion-first").assertExists()
    compose.onNodeWithTag("motion-second").assertExists()
    compose.mainClock.advanceTimeBy(300)
    compose.onNodeWithTag("motion-second").assertDoesNotExist()
    compose.onNodeWithTag("motion-first").assertExists()
  }

  @Test
  fun rootTabOrderUsesFullWidthMotionInARealNavHost() {
    compose.mainClock.autoAdvance = false
    lateinit var nav: NavHostController
    val positions = ConcurrentHashMap<String, LayoutCoordinates>()
    compose.setContent {
      nav = rememberNavController()
      val records = remember {
        androidx.compose.runtime.mutableStateMapOf<String, PageTransitionRecord>()
      }
      CompositionLocalProvider(LocalPageTransitionRecords provides records) {
        NavHost(
          navController = nav,
          startDestination = "discover",
          enterTransition = { pageEnter(true, records = records) },
          exitTransition = { pageExit(true, records = records) },
          popEnterTransition = { pagePopEnter(true, records = records) },
          popExitTransition = { pagePopExit(true, records = records) },
        ) {
          composable("discover") { entry ->
            SharedMotionPage(
              this@composable,
              entry.id,
              entry.id,
              route = "discover",
              currentRoute = nav.currentBackStackEntry?.destination?.route,
              previousRoute = nav.previousBackStackEntry?.destination?.route,
            ) {
              Page("root-discover", positions) { nav.navigate("games") }
            }
          }
          composable("games") { entry ->
            SharedMotionPage(
              this@composable,
              entry.id,
              entry.id,
              route = "games",
              currentRoute = nav.currentBackStackEntry?.destination?.route,
              previousRoute = nav.previousBackStackEntry?.destination?.route,
            ) {
              Page("root-games", positions) { nav.navigate("dynamic") }
            }
          }
          composable("dynamic") { entry ->
            SharedMotionPage(
              this@composable,
              entry.id,
              entry.id,
              route = "dynamic",
              currentRoute = nav.currentBackStackEntry?.destination?.route,
              previousRoute = nav.previousBackStackEntry?.destination?.route,
            ) {
              Page("root-dynamic", positions) { nav.popBackStack("discover", false) }
            }
          }
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    val width = compose.onRoot().captureToImage().width.toFloat()
    val discoverStart = positions.getValue("root-discover").positionInRoot().x
    compose.onNodeWithTag("motion-next").performClick()
    compose.mainClock.advanceTimeBy(260)
    compose.onNodeWithTag("root-discover").assertExists()
    compose.onNodeWithTag("root-games").assertExists()
    val entering = positions.getValue("root-games").positionInRoot().x
    val exiting = positions.getValue("root-discover").positionInRoot().x
    assertTrue(
      "next root tab enters from the right across the full width",
      entering > width * 0.05f && entering < width * 0.95f,
    )
    assertTrue(
      "old root tab exits left across its full width",
      exiting < discoverStart - width * 0.05f,
    )
    saveTargetCacheScreenshot("v116-root-tabs-mid.png")
    compose.mainClock.advanceTimeBy(300)
    compose.onNodeWithTag("root-games").assertExists()
    compose.onNodeWithTag("root-discover").assertDoesNotExist()
  }

  @Test
  fun childReturnsFromDetailAndSectionUseRecordedPopDirection() {
    compose.mainClock.autoAdvance = false
    lateinit var nav: NavHostController
    lateinit var transitionRecords: MutableMap<String, PageTransitionRecord>
    val positions = ConcurrentHashMap<String, LayoutCoordinates>()
    compose.setContent {
      nav = rememberNavController()
      val records = remember {
        androidx.compose.runtime.mutableStateMapOf<String, PageTransitionRecord>()
      }
      transitionRecords = records
      CompositionLocalProvider(LocalPageTransitionRecords provides records) {
        NavHost(
          navController = nav,
          startDestination = "search",
          enterTransition = { pageEnter(true, records = records) },
          exitTransition = { pageExit(true, records = records) },
          popEnterTransition = { pagePopEnter(true, records = records) },
          popExitTransition = { pagePopExit(true, records = records) },
          sizeTransform = { null },
        ) {
          composable("search") { entry ->
            SharedMotionPage(this@composable, entry.id, entry.id, route = "search") {
              Page("child-search", positions) { nav.navigate("detail") }
            }
          }
          composable("detail") { entry ->
            SharedMotionPage(this@composable, entry.id, entry.id, route = "detail") {
              Page("child-detail", positions) { nav.popBackStack() }
            }
          }
          composable("section") { entry ->
            SharedMotionPage(this@composable, entry.id, entry.id, route = "section") {
              Page("child-section", positions) { nav.popBackStack() }
            }
          }
        }
      }
    }
    compose.mainClock.advanceTimeByFrame()
    val width = compose.onRoot().captureToImage().width.toFloat()

    compose.onNodeWithTag("motion-next").performClick()
    compose.mainClock.advanceTimeBy(600)
    val detailPopStartTime = compose.mainClock.currentTime
    compose.onNodeWithTag("motion-back").performClick()
    val detailSearchCoordinates = awaitAttachedPageCoordinates("child-search", positions)
    val detailPopStartElapsed = compose.mainClock.currentTime - detailPopStartTime
    val detailPopStart = detailSearchCoordinates.positionInRoot().x
    val detailPopMidpointAdvance = (260L - detailPopStartElapsed).coerceAtLeast(0L)
    compose.mainClock.advanceTimeBy(detailPopMidpointAdvance)
    val detailPopMidpointElapsed = compose.mainClock.currentTime - detailPopStartTime
    val searchAfterDetail = detailSearchCoordinates.positionInRoot().x
    val detailPopRecord =
      transitionRecords.values.firstOrNull { it.fromRoute == "detail" && it.toRoute == "search" }
    System.out.println("detail-pop record=$detailPopRecord x=$searchAfterDetail width=$width")
    assertTrue("NavHost marks detail-to-search as a pop", detailPopRecord?.isPop == true)
    val expectedDetailPopStartX = expectedPopX(width, detailPopStartElapsed)
    val expectedDetailPopX = expectedPopX(width, detailPopMidpointElapsed)
    assertTrue(
      "detail pop starts on the left quarter-width curve",
      detailPopStart < 0f &&
        kotlin.math.abs(detailPopStart - expectedDetailPopStartX) < width * 0.05f,
    )
    assertTrue(
      "detail pop moves toward zero on the emphasized 520ms curve",
      searchAfterDetail > detailPopStart && searchAfterDetail < 0f,
    )
    assertTrue(
      "detail pop midpoint follows its elapsed emphasized progress",
      kotlin.math.abs(searchAfterDetail - expectedDetailPopX) < width * 0.04f,
    )
    compose.mainClock.advanceTimeBy(300)
    compose.runOnIdle { nav.navigate("section") }
    compose.mainClock.advanceTimeBy(600)
    val sectionPopStartTime = compose.mainClock.currentTime
    compose.onNodeWithTag("motion-back").performClick()
    val sectionSearchCoordinates = awaitAttachedPageCoordinates("child-search", positions)
    val sectionPopStartElapsed = compose.mainClock.currentTime - sectionPopStartTime
    val sectionPopStart = sectionSearchCoordinates.positionInRoot().x
    val sectionPopMidpointAdvance = (260L - sectionPopStartElapsed).coerceAtLeast(0L)
    compose.mainClock.advanceTimeBy(sectionPopMidpointAdvance)
    val sectionPopMidpointElapsed = compose.mainClock.currentTime - sectionPopStartTime
    val searchAfterSection = sectionSearchCoordinates.positionInRoot().x
    val sectionPopRecord =
      transitionRecords.values.firstOrNull { it.fromRoute == "section" && it.toRoute == "search" }
    System.out.println("section-pop record=$sectionPopRecord x=$searchAfterSection width=$width")
    assertTrue("NavHost marks section-to-search as a pop", sectionPopRecord?.isPop == true)
    val expectedSectionPopStartX = expectedPopX(width, sectionPopStartElapsed)
    val expectedSectionPopX = expectedPopX(width, sectionPopMidpointElapsed)
    assertTrue(
      "section pop starts on the left quarter-width curve",
      sectionPopStart < 0f &&
        kotlin.math.abs(sectionPopStart - expectedSectionPopStartX) < width * 0.05f,
    )
    assertTrue(
      "section pop moves toward zero on the emphasized 520ms curve",
      searchAfterSection > sectionPopStart && searchAfterSection < 0f,
    )
    assertTrue(
      "section pop midpoint follows its elapsed emphasized progress",
      kotlin.math.abs(searchAfterSection - expectedSectionPopX) < width * 0.04f,
    )
    val sectionTransitionRemaining =
      (520L - (compose.mainClock.currentTime - sectionPopStartTime)).coerceAtLeast(0L)
    compose.mainClock.advanceTimeBy(sectionTransitionRemaining)
    compose.onNodeWithTag("child-search").assertExists()
    val (cleanupFrames, cleanupMillis) =
      awaitNodeRemovedWithinFrames("child-section", maxFrames = 4)
    System.out.println(
      "section-pop outgoing cleanup frames=$cleanupFrames elapsedMs=$cleanupMillis"
    )
    assertTrue("outgoing page cleans up within four frames after 520ms", cleanupFrames <= 4)
    compose.onNodeWithTag("child-section").assertDoesNotExist()
  }

  private fun awaitAttachedPageCoordinates(
    tag: String,
    positions: Map<String, LayoutCoordinates>,
  ): LayoutCoordinates {
    repeat(4) {
      val coordinates = positions[tag]
      val hasSemanticsNode =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
      if (hasSemanticsNode && coordinates?.isAttached == true) return coordinates
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    val coordinates = positions[tag]
    throw AssertionError(
      "page $tag did not become attached within four frames; coordinatesAttached=${coordinates?.isAttached}"
    )
  }

  private fun expectedPopX(width: Float, elapsedMillis: Long): Float {
    val progress = (elapsedMillis / 520f).coerceIn(0f, 1f)
    return -width *
      0.25f *
      (1f - cn.heyread.app.ui.motion.MotionTokens.EMPHASIZED.transform(progress))
  }

  private fun awaitNodeRemovedWithinFrames(tag: String, maxFrames: Int): Pair<Int, Long> {
    val startTime = compose.mainClock.currentTime
    repeat(maxFrames) { frame ->
      if (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
        return frame to (compose.mainClock.currentTime - startTime)
      }
      compose.mainClock.advanceTimeByFrame()
      compose.waitForIdle()
    }
    if (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
      throw AssertionError("page $tag remained composed after $maxFrames cleanup frames")
    }
    return maxFrames to (compose.mainClock.currentTime - startTime)
  }
}

@Composable
private fun Page(
  tag: String,
  positions: MutableMap<String, LayoutCoordinates>,
  onNavigate: () -> Unit,
) {
  Box(Modifier.fillMaxSize().testTag(tag).onGloballyPositioned { positions[tag] = it }) {
    Button(
      onClick = onNavigate,
      modifier =
        Modifier.testTag(
          if (tag.endsWith("first") || tag.endsWith("discover") || tag.endsWith("search"))
            "motion-next"
          else "motion-back"
        ),
    ) {
      Text("Navigate")
    }
  }
}
