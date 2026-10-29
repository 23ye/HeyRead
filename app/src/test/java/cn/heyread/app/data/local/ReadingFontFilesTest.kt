package cn.heyread.app.data.local

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingFontFilesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun prepareHashesPrivateCopySanitizesNameAndLeavesSourceOpen() = runBlocking {
        val directory = temporaryFolder.newFolder("reading-fonts")
        val sourceBytes = byteArrayOf(0, 1, 0, 0, 1, 2, 3, 4)
        var sourceClosed = false
        val input =
            object : ByteArrayInputStream(sourceBytes) {
                override fun close() {
                    sourceClosed = true
                    super.close()
                }
            }
        var validatedFile: File? = null

        val prepared =
            ReadingFontFiles.prepare(input, "../../ My:Font.ttf ", directory) { validatedFile = it }

        assertEquals("MyFont", prepared.font.displayName)
        assertEquals(64, prepared.font.id.length)
        assertTrue(ReadingFontFiles.isValidId(prepared.font.id))
        assertEquals(sourceBytes.toList(), prepared.temporaryFile.readBytes().toList())
        assertEquals(prepared.temporaryFile, validatedFile)
        assertFalse("prepare does not take ownership of the input stream", sourceClosed)
        assertEquals(directory.canonicalFile, prepared.temporaryFile.parentFile)
        assertEquals(
            File(directory.canonicalFile, "${prepared.font.id}.font"),
            ReadingFontFiles.fileForId(directory, prepared.font.id),
        )
    }

    @Test
    fun invalidHeaderAndActualStreamLimitCleanTemporaryFiles() = runBlocking {
        val directory = temporaryFolder.newFolder("invalid-fonts")
        val invalid = runCatching {
            ReadingFontFiles.prepare(
                ByteArrayInputStream(
                    byteArrayOf(0x50, 0x4b, 0x03, 0x04).map { it.toByte() }.toByteArray()
                ),
                null,
                directory,
            ) {}
        }
        assertTrue("archive bytes are not a supported font", invalid.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        val oversized = runCatching {
            ReadingFontFiles.prepare(
                RepeatingFontStream(ReadingFontFiles.MAX_FONT_BYTES + 1L),
                null,
                directory,
            ) {}
        }
        assertTrue("the limit is applied to bytes actually read", oversized.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationClosesTheBlockedSourceAndDeletesOnlyThePendingCopy() = runBlocking {
        val directory = temporaryFolder.newFolder("cancelled-font")
        val readBlockedChunk = CountDownLatch(1)
        val releaseChunk = CountDownLatch(1)
        var reads = 0
        var sourceClosed = false
        val input =
            object : InputStream() {
                override fun read(): Int = -1

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    if (reads == 1) {
                        byteArrayOf(0, 1, 0, 0).copyInto(buffer, offset)
                        return 4
                    }
                    if (reads == 2) {
                        readBlockedChunk.countDown()
                        check(releaseChunk.await(5, TimeUnit.SECONDS))
                        buffer[offset] = 42
                        return 1
                    }
                    return -1
                }

                override fun close() {
                    sourceClosed = true
                }
            }
        val job =
            launch(Dispatchers.IO) {
                ReadingFontFiles.prepare(input, "cancelled.ttf", directory) {}
            }

        assertTrue(
            "copy reached the next stream chunk",
            readBlockedChunk.await(5, TimeUnit.SECONDS),
        )
        job.cancel()
        releaseChunk.countDown()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue("cancellation closes the blocked source stream", sourceClosed)
        assertTrue(
            "cancellation removes the only pending file",
            directory.listFiles().orEmpty().isEmpty(),
        )
    }

    private class RepeatingFontStream(private var remaining: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = byteAt(position++).toInt() and 0xff
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            repeat(count) { index -> buffer[offset + index] = byteAt(position++) }
            remaining -= count
            return count
        }

        private fun byteAt(index: Long): Byte =
            when (index) {
                0L -> 0
                1L -> 1
                2L,
                3L -> 0
                else -> 7
            }.toByte()
    }
}
