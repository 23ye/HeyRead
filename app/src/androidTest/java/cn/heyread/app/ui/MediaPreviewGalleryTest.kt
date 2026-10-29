package cn.heyread.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.media.MediaImage
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.articleImageOffsets
import cn.heyread.app.ui.components.MediaPreviewDialog
import cn.heyread.app.ui.components.MediaScaleSemanticsKey
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.theme.HeyReadTheme
import coil.EventListener
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPreviewGalleryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private var imageLoader: ImageLoader? = null
  private var requestedUrls = CopyOnWriteArrayList<String>()
  private val gifFixtures = mutableListOf<File>()

  @After
  fun closeImageLoader() {
    imageLoader?.shutdown()
    imageLoader = null
    requestedUrls.clear()
    gifFixtures.forEach(File::delete)
    gifFixtures.clear()
  }

  @Test
  fun clickingRepeatedArticleImageOpensItsExactOccurrence() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = "https://images.invalid/repeated.png"
    val loader = syntheticLoader(context, requestedUrls)
    imageLoader = loader
    val openedOccurrence = mutableStateOf<Pair<Int, MediaImage>?>(null)
    val blocks =
      listOf(
        ArticleBlock.Image(source, description = "identical occurrence"),
        ArticleBlock.Image("https://images.invalid/other.png", description = "middle"),
        ArticleBlock.Quote(listOf(ArticleBlock.Image(source, description = "identical occurrence"))),
      )
    val imageOffsets = articleImageOffsets(blocks)

    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            blocks.forEachIndexed { index, block ->
              ArticleBlockContent(
                block = block,
                noImages = false,
                onOpenImage = {},
                imageOccurrenceStart = imageOffsets[index],
                onOpenImageOccurrence = { occurrence, image ->
                  openedOccurrence.value = occurrence to image
                },
              )
            }
          }
        }
      }
    }

    compose.onNodeWithTag("quote-image", useUnmergedTree = true).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(2, openedOccurrence.value?.first)
      assertEquals(
        MediaImage(previewUrl = source, description = "identical occurrence"),
        openedOccurrence.value?.second,
      )
    }
  }

  @Test
  fun galleryStartsAtMiddleSwipesBothWaysAndClampsAtEnds() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val loader = syntheticLoader(context, requestedUrls)
    imageLoader = loader
    val pages = galleryImages()
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          MediaPreviewDialog(image = pages[1], onDismiss = {}, gallery = pages, initialIndex = 1)
        }
      }
    }
    awaitImageLoaded(pages[1].previewUrl, requestedUrls)
    pageIndicator().assertTextEquals("2/3")

    val image = compose.onNodeWithTag("media-image")
    image.performTouchInput { swipeLeft() }
    awaitImageLoaded(pages[2].previewUrl, requestedUrls)
    pageIndicator().assertTextEquals("3/3")
    image.performTouchInput { swipeLeft() }
    pageIndicator().assertTextEquals("3/3")
    image.performTouchInput { swipeRight() }
    pageIndicator().assertTextEquals("2/3")
    image.performTouchInput { swipeRight() }
    pageIndicator().assertTextEquals("1/3")
    image.performTouchInput { swipeRight() }
    pageIndicator().assertTextEquals("1/3")
  }

  @Test
  fun reversedDragAndPinchKeepPageThenDoubleTapRestoresPaging() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val loader = syntheticLoader(context, requestedUrls)
    imageLoader = loader
    val pages = galleryImages()
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          MediaPreviewDialog(image = pages[1], onDismiss = {}, gallery = pages, initialIndex = 1)
        }
      }
    }
    awaitImageLoaded(pages[1].previewUrl, requestedUrls)
    val image = compose.onNodeWithTag("media-image")
    pageIndicator().assertTextEquals("2/3")

    image.performTouchInput {
      val start = Offset(width * 0.82f, height / 2f)
      down(start)
      moveTo(Offset(width * 0.52f, start.y), delayMillis = 160)
      moveTo(Offset(width * 0.75f, start.y), delayMillis = 160)
      up()
    }
    pageIndicator().assertTextEquals("2/3")

    image.performTouchInput {
      val y = height / 2f
      down(0, Offset(width * 0.42f, y))
      down(1, Offset(width * 0.58f, y))
      moveTo(0, Offset(width * 0.28f, y), delayMillis = 240)
      moveTo(1, Offset(width * 0.72f, y), delayMillis = 240)
      up(0)
      up(1)
    }
    pageIndicator().assertTextEquals("2/3")
    assertTrue("Pinch should zoom the current page", imageScale() > 1f)

    image.performTouchInput { swipeLeft() }
    pageIndicator().assertTextEquals("2/3")

    image.performTouchInput { doubleClick(center) }
    assertEquals(1f, imageScale(), 0.01f)
    pageIndicator().assertTextEquals("2/3")
    image.performTouchInput { swipeLeft() }
    pageIndicator().assertTextEquals("3/3")
  }

  @Test
  fun originalFailureAndReturnStayBoundToCurrentPage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val loader = syntheticLoader(context, requestedUrls, failOriginal = true)
    imageLoader = loader
    val first = MediaImage("https://images.invalid/first.png", description = "first")
    val second =
      MediaImage(
        "https://images.invalid/second.png",
        originalUrl = "https://images.invalid/fail-original.png",
        description = "second",
      )
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          MediaPreviewDialog(
            image = first,
            onDismiss = {},
            gallery = listOf(first, second),
            initialIndex = 1,
          )
        }
      }
    }
    awaitImageLoaded(second.previewUrl, requestedUrls)
    pageIndicator().assertTextEquals("2/2")
    listOf("media-close", "media-original-toggle", "media-save", "media-share").forEach {
      assertToolbarTargetWithinPreview(it)
    }

    compose.onNodeWithText("查看原图").performClick()
    val originalUrl = requireNotNull(second.originalUrl)
    compose.waitUntil(5_000) { requestedUrls.contains(originalUrl) }
    compose.onNodeWithText("图片加载失败").assertExists()
    compose.onNodeWithText("返回预览").performClick()
    awaitImageLoaded(second.previewUrl, requestedUrls)
    pageIndicator().assertTextEquals("2/2")
    assertTrue(requestedUrls.contains(second.previewUrl))
  }

  @Test
  fun galleryOnlyRunsCurrentGifAndPausesAndResumesWithLifecycle() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val firstGif = copyGif("v127-gallery-first.gif")
    val secondGif = copyGif("v127-gallery-second.gif")
    val firstUrl = Uri.fromFile(firstGif).toString()
    val secondUrl = Uri.fromFile(secondGif).toString()
    val drawables = ConcurrentHashMap<String, Animatable>()
    val loader =
      ImageLoader.Builder(context)
        .components {
          if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
          else add(GifDecoder.Factory())
        }
        .eventListenerFactory {
          object : EventListener {
            override fun onSuccess(
              request: coil.request.ImageRequest,
              result: coil.request.SuccessResult,
            ) {
              (result.drawable as? Animatable)?.let { drawables[request.data.toString()] = it }
            }
          }
        }
        .build()
    imageLoader = loader
    val pages =
      listOf(
        MediaImage(firstUrl, description = "GIF 1", animationUrl = firstUrl),
        MediaImage(secondUrl, description = "GIF 2", animationUrl = secondUrl),
      )
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides loader) {
        HeyReadTheme("light") {
          MediaPreviewDialog(image = pages[0], onDismiss = {}, gallery = pages, initialIndex = 0)
        }
      }
    }

    compose.waitUntil(10_000) { drawables[firstUrl]?.isRunning == true }
    assertFrameChanges("media-image-page-0")
    assertTrue("Only the selected GIF should be running", drawables[firstUrl]?.isRunning == true)
    assertTrue("The unselected GIF should not be running", drawables[secondUrl]?.isRunning != true)

    compose.onNodeWithTag("media-image").performTouchInput { swipeLeft() }
    pageIndicator().assertTextEquals("2/2")
    compose.waitUntil(10_000) {
      drawables[secondUrl]?.isRunning == true && drawables[firstUrl]?.isRunning == false
    }
    assertFrameChanges("media-image-page-1")

    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
    compose.waitUntil(5_000) { drawables[secondUrl]?.isRunning == false }
    Thread.sleep(500)
    assertTrue(
      "GIF continued animating after lifecycle stop",
      drawables[secondUrl]?.isRunning == false,
    )

    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    compose.waitUntil(5_000) { drawables[secondUrl]?.isRunning == true }
    assertFrameChanges("media-image-page-1")
  }

  private fun pageIndicator() = compose.onNodeWithTag("media-page-indicator")

  private fun assertToolbarTargetWithinPreview(tag: String) {
    val previewBounds = compose.onNodeWithTag("media-preview").getBoundsInRoot()
    val target = compose.onNodeWithTag(tag)
    target.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    val bounds = target.getBoundsInRoot()
    assertTrue(
      "$tag extends beyond the left edge of the preview",
      bounds.left >= previewBounds.left,
    )
    assertTrue("$tag extends beyond the top edge of the preview", bounds.top >= previewBounds.top)
    assertTrue(
      "$tag extends beyond the right edge of the preview",
      bounds.right <= previewBounds.right,
    )
    assertTrue(
      "$tag extends beyond the bottom edge of the preview",
      bounds.bottom <= previewBounds.bottom,
    )
  }

  private fun imageScale(): Float =
    compose.onNodeWithTag("media-image").fetchSemanticsNode().config[MediaScaleSemanticsKey]

  private fun awaitImageLoaded(url: String, requests: List<String>) {
    compose.waitUntil(5_000) {
      if (!requests.contains(url)) return@waitUntil false
      runCatching {
          hasRedMarker(compose.onNodeWithTag("media-image").captureToImage().asAndroidBitmap())
        }
        .getOrDefault(false)
    }
    assertTrue("Synthetic Coil image request was not observed for $url", requests.contains(url))
  }

  private fun hasRedMarker(bitmap: Bitmap): Boolean {
    val y = bitmap.height / 2
    for (x in 0 until bitmap.width) {
      val pixel = bitmap.getPixel(x, y)
      if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) < 110)
        return true
    }
    return false
  }

  private fun galleryImages() =
    listOf(
      MediaImage("https://images.invalid/page-1.png", description = "page-1"),
      MediaImage("https://images.invalid/page-2.png", description = "page-2"),
      MediaImage("https://images.invalid/page-3.png", description = "page-3"),
    )

  private fun syntheticLoader(
    context: Context,
    requestedUrls: MutableList<String> = CopyOnWriteArrayList(),
    failOriginal: Boolean = false,
  ): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
              if (data.host != "images.invalid") return null
              return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                  val url = data.toString()
                  requestedUrls.add(url)
                  if (failOriginal && url.contains("fail-original"))
                    error("Synthetic original image failure")
                  return DrawableResult(
                    BitmapDrawable(context.resources, markerBitmap()),
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

  private fun markerBitmap(): Bitmap =
    Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888).apply {
      eraseColor(android.graphics.Color.WHITE)
      Canvas(this)
        .drawRect(
          490f,
          0f,
          510f,
          700f,
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.RED },
        )
    }

  private fun copyGif(name: String): java.io.File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = java.io.File(context.cacheDir, name)
    InstrumentationRegistry.getInstrumentation().context.assets.open("v17-two-frames.gif").use {
      input ->
      file.outputStream().use { input.copyTo(it) }
    }
    gifFixtures += file
    return file
  }

  private fun assertFrameChanges(tag: String) {
    val signatures = mutableSetOf<Long>()
    repeat(12) {
      Thread.sleep(110)
      signatures += frameSignature(tag)
      if (signatures.size >= 2) return
    }
    assertTrue("Decoded GIF pixels did not change in $tag", signatures.size >= 2)
  }

  private fun frameSignature(tag: String): Long {
    val bitmap =
      compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
    return try {
      var hash = 1125899906842597L
      val stepX = (bitmap.width / 12).coerceAtLeast(1)
      val stepY = (bitmap.height / 12).coerceAtLeast(1)
      for (y in stepY until bitmap.height step stepY) {
        for (x in stepX until bitmap.width step stepX) hash = hash * 31 + bitmap.getPixel(x, y)
      }
      hash
    } finally {
      bitmap.recycle()
    }
  }
}
