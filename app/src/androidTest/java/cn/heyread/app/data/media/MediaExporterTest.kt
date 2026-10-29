package cn.heyread.app.data.media

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaExporterTest {
    private fun jpeg(): ByteArray =
        ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(200, 100, 30))
                compress(Bitmap.CompressFormat.JPEG, 90, output)
                recycle()
            }
            output.toByteArray()
        }

    @Test
    fun mediaStoreAndFileProviderPreserveDownloadedImageBytes() = runBlocking {
        val bytes = jpeg()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exporter =
            MediaExporter(
                context,
                MediaTransport { MediaResponse(200, "image/jpeg", ByteArrayInputStream(bytes)) },
            )
        val staged = exporter.prepare("https://images.invalid/synthetic.jpg")
        assertArrayEquals(bytes, staged.file.readBytes())
        val shareIntent = exporter.shareIntent(staged)
        assertEquals(android.content.Intent.ACTION_SEND, shareIntent.action)
        assertEquals("image/jpeg", shareIntent.type)
        assertTrue(shareIntent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val chooser = android.content.Intent.createChooser(shareIntent, "分享图片")
        assertEquals("android.intent.action.CHOOSER", chooser.action)
        val shareUri =
            shareIntent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertEquals("image/jpeg", context.contentResolver.getType(shareUri))
        assertArrayEquals(
            bytes,
            context.contentResolver.openInputStream(shareUri)!!.use { it.readBytes() },
        )
        val saved = exporter.saveToMediaStore(staged)
        assertArrayEquals(
            bytes,
            context.contentResolver.openInputStream(saved)!!.use { it.readBytes() },
        )
        assertTrue(context.contentResolver.delete(saved, null, null) > 0)
    }

    @Test
    fun gifExportKeepsAnimatedSourceBytesAndMimeForSharingAndSaving() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("v17-two-frames.gif")
                .use { it.readBytes() }
        val exporter =
            MediaExporter(
                context,
                MediaTransport { MediaResponse(200, "image/gif", ByteArrayInputStream(bytes)) },
            )
        val staged = exporter.prepare("https://images.invalid/two-frames")
        assertEquals("image/gif", staged.mimeType)
        assertEquals("gif", staged.displayName.substringAfterLast('.'))
        assertArrayEquals(bytes, staged.file.readBytes())
        val intent = exporter.shareIntent(staged)
        assertEquals("image/gif", intent.type)
        val saved = exporter.saveToMediaStore(staged)
        assertArrayEquals(
            bytes,
            context.contentResolver.openInputStream(saved)!!.use { it.readBytes() },
        )
        assertTrue(context.contentResolver.delete(saved, null, null) > 0)
    }

    @Test
    fun htmlErrorResponseCannotBecomeAnExportedImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exporter =
            MediaExporter(
                context,
                MediaTransport {
                    MediaResponse(200, "text/html", ByteArrayInputStream("error".toByteArray()))
                },
            )
        val failure =
            runCatching { exporter.prepare("https://images.invalid/error") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun httpFailureOversizeAndTruncatedImageAreRejected() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val httpError =
            MediaExporter(
                context,
                MediaTransport { MediaResponse(503, "image/jpeg", ByteArrayInputStream(jpeg())) },
            )
        assertTrue(runCatching { httpError.prepare("https://images.invalid/503") }.isFailure)

        val oversized =
            MediaExporter(
                context,
                MediaTransport {
                    MediaResponse(200, "image/jpeg", ByteArrayInputStream(ByteArray(128)))
                },
                maxBytes = 64,
            )
        assertTrue(runCatching { oversized.prepare("https://images.invalid/large") }.isFailure)

        val truncated =
            MediaExporter(
                context,
                MediaTransport {
                    MediaResponse(
                        200,
                        "image/jpeg",
                        ByteArrayInputStream(
                            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
                        ),
                    )
                },
            )
        assertTrue(runCatching { truncated.prepare("https://images.invalid/truncated") }.isFailure)
        val cache = java.io.File(context.cacheDir, "shared_images")
        assertTrue(cache.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
