package cn.heyread.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.media.MediaImage
import cn.heyread.app.ui.components.MediaPreviewDialog
import cn.heyread.app.ui.theme.HeyReadTheme
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Exercises pointer motion against the production dialog using only an in-memory synthetic image.
 */
class MediaPreviewPanGestureTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun zoomedImageFollowsAnUnboundedScreenDragNearOneToOne() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val imageUrl = "https://images.invalid/pan-tracking.png"
    val bitmap = trackingBitmap()
    val imageLoader = trackingImageLoader(context, imageUrl, bitmap)
    compose.setContent {
      CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        HeyReadTheme("light") {
          MediaPreviewDialog(
            MediaImage(previewUrl = imageUrl, description = "合成拖动标记"),
            onDismiss = {},
          )
        }
      }
    }

    val image = compose.onNodeWithTag("media-image")
    compose.waitUntil(5_000) { runCatching { image.captureToImage() }.isSuccess }
    image.performTouchInput { doubleClick(center) }
    compose.waitForIdle()
    val before = redStripeCenter(image.captureToImage().asAndroidBitmap())

    val requestedScreenDeltaPx = 100f
    image.performTouchInput {
      down(center)
      moveTo(center.copy(x = center.x + requestedScreenDeltaPx), delayMillis = 300)
      up()
    }
    compose.waitForIdle()
    val after = redStripeCenter(image.captureToImage().asAndroidBitmap())
    val displayedDeltaPx = after - before

    assertTrue(
      "Synthetic tracking stripe was not rendered: before=$before after=$after",
      before >= 0f && after >= 0f,
    )
    assertTrue(
      "A $requestedScreenDeltaPx px viewport drag moved the zoomed image only $displayedDeltaPx px",
      displayedDeltaPx in 75f..125f,
    )
  }

  private fun trackingBitmap(): Bitmap =
    Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply {
      eraseColor(android.graphics.Color.WHITE)
      Canvas(this)
        .drawRect(
          390f,
          0f,
          410f,
          800f,
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.RED },
        )
    }

  private fun trackingImageLoader(context: Context, imageUrl: String, bitmap: Bitmap): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
              if (data.toString() != imageUrl) return null
              return object : Fetcher {
                override suspend fun fetch(): FetchResult =
                  DrawableResult(
                    BitmapDrawable(context.resources, bitmap),
                    false,
                    DataSource.MEMORY,
                  )
              }
            }
          }
        )
      }
      .build()

  private fun redStripeCenter(bitmap: Bitmap): Float {
    val middleY = bitmap.height / 2
    var sum = 0L
    var count = 0
    for (x in 0 until bitmap.width) {
      val color = bitmap.getPixel(x, middleY)
      if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 110) {
        sum += x
        count++
      }
    }
    return if (count == 0) -1f else sum.toFloat() / count
  }
}
