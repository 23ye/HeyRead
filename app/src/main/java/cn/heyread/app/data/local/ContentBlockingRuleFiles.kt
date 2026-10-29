package cn.heyread.app.data.local

import android.content.ContentResolver
import android.net.Uri
import cn.heyread.app.data.model.ContentBlockRule
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface ContentBlockingRuleFileAccess {
  suspend fun read(uri: Uri): ContentBlockingImportDocument

  suspend fun write(uri: Uri, rules: List<ContentBlockRule>): Int
}

class ContentBlockingRuleFileException(
  val safeMessage: String,
  cause: Throwable? = null,
) : IOException(safeMessage, cause)

/** System document-provider access for the bounded, allowlisted rule-transfer format. */
class ContentBlockingRuleFiles(private val contentResolver: ContentResolver) :
  ContentBlockingRuleFileAccess {
  override suspend fun read(uri: Uri): ContentBlockingImportDocument =
    withContext(Dispatchers.IO) {
      val input =
        try {
          contentResolver.openInputStream(uri) ?: throw ContentBlockingRuleFileException("无法读取所选规则文件。")
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          throw ContentBlockingRuleFileException("无法读取所选规则文件。", error)
        }
      val bytes =
        try {
          input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = stream.read(buffer)
              if (count < 0) break
              total += count
              if (total > CONTENT_BLOCKING_MAX_JSON_BYTES) {
                throw ContentBlockingRuleFileException("规则文件超过256KiB。")
              }
              output.write(buffer, 0, count)
            }
            output.toByteArray()
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: ContentBlockingRuleFileException) {
          throw error
        } catch (error: Exception) {
          throw ContentBlockingRuleFileException("无法读取所选规则文件。", error)
        }
      decodeContentBlockingRules(bytes)
    }

  override suspend fun write(uri: Uri, rules: List<ContentBlockRule>): Int =
    withContext(Dispatchers.IO) {
      currentCoroutineContext().ensureActive()
      val bytes = encodeContentBlockingRules(rules)
      val output =
        try {
          contentResolver.openOutputStream(uri, "wt")
            ?: throw ContentBlockingRuleFileException("无法写入所选位置。")
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          throw ContentBlockingRuleFileException("无法写入所选位置。", error)
        }
      try {
        output.use { stream ->
          var offset = 0
          while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(8 * 1024, bytes.size - offset)
            stream.write(bytes, offset, count)
            offset += count
          }
          stream.flush()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        throw ContentBlockingRuleFileException("无法写入所选位置。", error)
      }
      rules.size
    }
}
