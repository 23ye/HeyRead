package cn.heyread.app.data.local

import android.graphics.Typeface
import cn.heyread.app.data.model.ImportedReadingFont
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PreparedReadingFont(
    val font: ImportedReadingFont,
    val temporaryFile: File,
)

/** Bounded, private-file helpers for user-selected Android fonts. */
internal object ReadingFontFiles {
  const val MAX_FONT_BYTES = 32 * 1024 * 1024
  const val MAX_IMPORTED_FONTS = 8
  private const val BUFFER_SIZE = 32 * 1024
  private val fontIdPattern = Regex("[0-9a-f]{64}")

  fun isValidId(id: String): Boolean = fontIdPattern.matches(id)

  fun fileForId(directory: File, id: String): File {
    require(isValidId(id)) { "字体标识无效" }
    val canonicalDirectory = directory.canonicalFile
    val file = File(canonicalDirectory, "$id.font").canonicalFile
    require(file.parentFile == canonicalDirectory) { "字体路径无效" }
    return file
  }

  fun safeDisplayName(rawName: String?, idFallback: String): String {
    val candidate =
        rawName.orEmpty().take(256).trim().substringAfterLast('/').substringAfterLast('\\')
    val withoutExtension = candidate.replace(Regex("(?i)\\.(ttf|otf|ttc)$"), "")
    val cleaned =
        withoutExtension
            .filter { it.code >= 0x20 && it.code !in 0x7f..0x9f && it !in "/\\:*?\"<>|" }
            .trim()
            .take(48)
            .trim()
    return cleaned.ifBlank { "导入字体 ${idFallback.take(8).lowercase(Locale.ROOT)}" }
  }

  /**
   * Copies and validates one font into a temporary file in [directory]. The caller owns [input].
   * The temporary file is removed on every failure, including coroutine cancellation.
   */
  @OptIn(InternalCoroutinesApi::class)
  suspend fun prepare(
      input: InputStream,
      displayName: String?,
      directory: File,
      typefaceValidator: (File) -> Unit = { file ->
        validatePlatformTypeface(file)
        Unit
      },
  ): PreparedReadingFont {
    require(directory.exists() || directory.mkdirs()) { "无法创建字体目录" }
    val canonicalDirectory = directory.canonicalFile
    require(canonicalDirectory.isDirectory) { "字体目录不可用" }
    val temporary = File.createTempFile("font-", ".pending", canonicalDirectory)
    val job = currentCoroutineContext()[Job]
    // ContentResolver streams can block inside read(). Closing from the job's cancellation
    // callback releases the provider descriptor so cancellation does not wait on another read.
    val cancellationHandle =
        job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
          if (cause is CancellationException) runCatching { input.close() }
        }
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      val header = ByteArray(4)
      var headerSize = 0
      var totalBytes = 0L
      val buffer = ByteArray(BUFFER_SIZE)
      FileOutputStream(temporary).use { output ->
        while (true) {
          currentCoroutineContext().ensureActive()
          val count = input.read(buffer)
          if (count < 0) break
          if (count == 0) continue
          totalBytes += count
          require(totalBytes <= MAX_FONT_BYTES) { "字体文件超过32 MiB限制" }
          if (headerSize < header.size) {
            val copyCount = minOf(header.size - headerSize, count)
            buffer.copyInto(header, headerSize, 0, copyCount)
            headerSize += copyCount
          }
          digest.update(buffer, 0, count)
          output.write(buffer, 0, count)
        }
        output.fd.sync()
      }
      require(isSupportedFontHeader(header, headerSize)) { "文件不是受支持的TTF、OTF或TTC字体" }
      val id = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
      typefaceValidator(temporary)
      return PreparedReadingFont(
          ImportedReadingFont(id, safeDisplayName(displayName, id)), temporary)
    } catch (error: Throwable) {
      temporary.delete()
      currentCoroutineContext().ensureActive()
      throw error
    } finally {
      cancellationHandle?.dispose()
    }
  }

  fun validatePlatformTypeface(file: File): Typeface {
    require(file.isFile && file.length() in 1..MAX_FONT_BYTES.toLong()) { "字体文件大小无效" }
    FileInputStream(file).use { input ->
      val header = ByteArray(4)
      var headerSize = 0
      while (headerSize < header.size) {
        val count = input.read(header, headerSize, header.size - headerSize)
        if (count < 0) break
        headerSize += count
      }
      require(isSupportedFontHeader(header, headerSize)) { "文件不是受支持的TTF、OTF或TTC字体" }
    }
    val builtTypeface =
        if (isCollection(file)) Typeface.Builder(file).setTtcIndex(0).build()
        else Typeface.Builder(file).build()
    return requireNotNull(builtTypeface) { "字体文件损坏或不受支持" }
  }

  private fun isCollection(file: File): Boolean =
      FileInputStream(file).use { input ->
        val header = ByteArray(4)
        var count = 0
        while (count < header.size) {
          val read = input.read(header, count, header.size - count)
          if (read < 0) break
          count += read
        }
        count == header.size && header.contentEquals(byteArrayOf(0x74, 0x74, 0x63, 0x66))
      }

  private fun isSupportedFontHeader(header: ByteArray, size: Int): Boolean {
    if (size < 4) return false
    val ttf = header.contentEquals(byteArrayOf(0, 1, 0, 0))
    val otf = header.contentEquals(byteArrayOf(0x4f, 0x54, 0x54, 0x4f))
    val ttc = header.contentEquals(byteArrayOf(0x74, 0x74, 0x63, 0x66))
    return ttf || otf || ttc
  }
}
