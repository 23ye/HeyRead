package cn.heyread.app.data.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class MediaResponse(
    val status: Int,
    val mimeType: String?,
    val input: InputStream,
    private val closeResponse: () -> Unit = { input.close() },
) : Closeable {
    override fun close() = closeResponse()
}

fun interface MediaTransport {
    fun open(url: String): MediaResponse
}

class OkHttpMediaTransport : MediaTransport {
    private val client =
        OkHttpClient.Builder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    override fun open(url: String): MediaResponse {
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        val body = response.body
        return MediaResponse(
            response.code,
            body?.contentType()?.type?.let { type ->
                "$type/${body.contentType()?.subtype.orEmpty()}"
            },
            body?.byteStream()
                ?: run {
                    response.close()
                    throw IllegalStateException("图片响应为空")
                },
        ) {
            response.close()
        }
    }
}

data class StagedMedia(val file: File, val mimeType: String, val displayName: String)

class MediaExporter(
    private val context: Context,
    private val transport: MediaTransport = OkHttpMediaTransport(),
    private val maxBytes: Long = 25L * 1024 * 1024,
) {
    suspend fun prepare(url: String): StagedMedia =
        withContext(Dispatchers.IO) {
            val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("图片地址无效")
            require(parsed.isHttps) { "仅支持 HTTPS 图片" }
            val directory = File(context.cacheDir, "shared_images").apply { mkdirs() }
            directory
                .listFiles()
                ?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 86400000 }
                ?.forEach(File::delete)
            val temporary = File(directory, "${UUID.randomUUID()}.part")
            var completed: File? = null
            try {
                transport.open(url).use { response ->
                    require(response.status in 200..299) { "图片下载失败（HTTP ${response.status}）" }
                    val mime = response.mimeType?.substringBefore(';')?.lowercase()
                    require(mime?.startsWith("image/") == true) { "服务器没有返回图片" }
                    var count = 0L
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = response.input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= maxBytes) { "图片超过 25 MB，无法导出" }
                            output.write(buffer, 0, read)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    require(count > 0 && isImageBytes(temporary, mime!!)) { "下载内容不是有效图片" }
                    val extension = extension(mime, temporary)
                    val target = File(directory, "${UUID.randomUUID()}.$extension")
                    check(temporary.renameTo(target)) { "无法准备图片文件" }
                    completed = target
                    currentCoroutineContext().ensureActive()
                    StagedMedia(target, mime, "heyread-${System.currentTimeMillis()}.$extension")
                }
            } catch (error: Throwable) {
                temporary.delete()
                completed?.delete()
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw error
            }
        }

    suspend fun saveToMediaStore(staged: StagedMedia): Uri =
        withContext(Dispatchers.IO) {
            check(Build.VERSION.SDK_INT >= 29) { "当前系统请使用文档保存" }
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, staged.displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, staged.mimeType)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/HeyRead",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            val resolver = context.contentResolver
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建图片文件")
            try {
                resolver.openOutputStream(uri, "w")!!.use { output ->
                    staged.file.inputStream().use { input -> copyCancellable(input, output) }
                }
                currentCoroutineContext().ensureActive()
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(resolver.update(uri, ready, null, null) == 1) { "无法完成图片保存" }
                staged.file.delete()
                uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

    suspend fun copyToUri(staged: StagedMedia, destination: Uri) =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(destination, "w")!!.use { output ->
                    staged.file.inputStream().use { input -> copyCancellable(input, output) }
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                context.contentResolver.delete(destination, null, null)
                throw error
            }
            staged.file.delete()
        }

    private suspend fun copyCancellable(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    fun shareUri(staged: StagedMedia): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", staged.file)

    fun shareIntent(staged: StagedMedia): Intent {
        val uri = shareUri(staged)
        return Intent(Intent.ACTION_SEND).apply {
            type = staged.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "HeyRead 图片", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun isImageBytes(file: File, mime: String): Boolean {
        val bytes =
            file.inputStream().use { input ->
                val buffer = ByteArray(16)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
        return when (mime) {
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp" -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == mime
            }
            "image/avif" -> {
                val brand = if (bytes.size >= 12) String(bytes, 8, 4, Charsets.US_ASCII) else ""
                val avifBrand = brand == "avif" || brand == "avis"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                avifBrand &&
                    bounds.outWidth > 0 &&
                    bounds.outHeight > 0 &&
                    bounds.outMimeType == mime
            }
            else -> false
        }
    }

    private fun extension(mime: String, @Suppress("UNUSED_PARAMETER") file: File) =
        when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            else -> "img"
        }
}
