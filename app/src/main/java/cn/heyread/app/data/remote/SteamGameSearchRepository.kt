package cn.heyread.app.data.remote

import cn.heyread.app.data.model.SteamGame
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject

fun interface SteamGameSearchSource {
  suspend fun search(query: String): List<SteamGame>
}

class SteamGameSearchRepository(client: OkHttpClient = publicSteamSearchClient()) :
  SteamGameSearchSource {
  private val client =
    client
      .newBuilder()
      .cookieJar(CookieJar.NO_COOKIES)
      .cache(null)
      .followRedirects(false)
      .followSslRedirects(false)
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .build()

  override suspend fun search(query: String): List<SteamGame> {
    val term = query.trim()
    require(term.isNotEmpty()) { "请输入游戏名称或AppID。" }
    require(term.length <= MAX_QUERY_LENGTH) { "搜索内容过长。" }
    val appId = appIdForQuery(term)
    return withContext(Dispatchers.IO) {
      if (appId != null) {
        parseAppDetails(request(appDetailsUrl(appId)), appId)?.let(::listOf) ?: emptyList()
      } else {
        parseStoreSearch(request(storeSearchUrl(term)) ?: error("Steam搜索响应为空。"))
      }
    }
  }

  internal fun storeSearchUrl(term: String): HttpUrl =
    "https://store.steampowered.com/api/storesearch/"
      .toHttpUrl()
      .newBuilder()
      .addQueryParameter("term", term)
      .addQueryParameter("l", "schinese")
      .addQueryParameter("cc", "CN")
      .build()

  internal fun appDetailsUrl(appId: Int): HttpUrl =
    "https://store.steampowered.com/api/appdetails/"
      .toHttpUrl()
      .newBuilder()
      .addQueryParameter("appids", appId.toString())
      .addQueryParameter("l", "schinese")
      .addQueryParameter("cc", "CN")
      .build()

  internal fun appIdForQuery(query: String): Int? {
    if (query.matches(Regex("-[0-9]+"))) error("AppID必须是正整数。")
    if (!query.matches(Regex("[0-9]+"))) return null
    return query.toIntOrNull()?.takeIf { it > 0 } ?: error("AppID必须是正整数。")
  }

  internal fun parseStoreSearch(raw: String): List<SteamGame> {
    val items = JSONObject(raw).optJSONArray("items") ?: error("Steam搜索响应缺少items。")
    val seen = mutableSetOf<Int>()
    var appRows = 0
    var objectRows = 0
    var malformedRows = 0
    val games = buildList {
      for (index in 0 until items.length()) {
        val item = items.optJSONObject(index)
        if (item == null) {
          malformedRows++
          continue
        }
        objectRows++
        when (item.opt("type")) {
          "bundle",
          "sub" -> continue
          "app" -> appRows++
          else -> {
            malformedRows++
            continue
          }
        }
        val appId = item.strictPositiveInt("id") ?: continue
        val name = (item.opt("name") as? String)?.trim()?.takeIf(String::isNotEmpty) ?: continue
        if (!seen.add(appId)) continue
        add(SteamGame(appId, name, item.optString("tiny_image").safeSteamImageUrl()))
        if (size >= MAX_RESULTS) break
      }
    }
    if (
      items.length() > 0 &&
        (objectRows == 0 || appRows > 0 && games.isEmpty() || games.isEmpty() && malformedRows > 0)
    )
      error("Steam搜索结果格式无效。")
    return games
  }

  internal fun parseAppDetails(raw: String, appId: Int): SteamGame? {
    require(appId > 0) { "AppID必须是正整数。" }
    val result = JSONObject(raw).optJSONObject(appId.toString()) ?: error("Steam游戏详情响应格式无效。")
    when (result.opt("success")) {
      false -> return null
      true -> Unit
      else -> error("Steam游戏详情success字段无效。")
    }
    val data = result.optJSONObject("data") ?: error("Steam游戏详情缺少data。")
    val returnedId = data.strictPositiveInt("steam_appid") ?: error("Steam游戏详情AppID无效。")
    if (returnedId != appId) error("Steam游戏详情AppID不匹配。")
    val name =
      (data.opt("name") as? String)?.trim()?.takeIf(String::isNotEmpty) ?: error("Steam游戏详情名称缺失。")
    return SteamGame(appId, name, data.optString("header_image").safeSteamImageUrl())
  }

  private suspend fun request(url: HttpUrl): String {
    require(url.isHttps && url.host == STORE_HOST) { "Steam搜索地址无效。" }
    val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
    return client.newCall(request).awaitBoundedResponse()
  }

  private suspend fun Call.awaitBoundedResponse(): String =
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { cancel() }
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, error: IOException) {
            continuation.resumeWith(Result.failure(error))
          }

          override fun onResponse(call: Call, response: Response) {
            try {
              val text =
                response.use {
                  if (!it.isSuccessful) error("Steam请求失败（HTTP ${it.code}）。")
                  val body = it.body ?: error("Steam响应缺少内容。")
                  if ((body.contentLength() > MAX_RESPONSE_BYTES)) error("Steam响应超出允许范围。")
                  readBoundedUtf8(body.source()) {
                    if (call.isCanceled()) throw IOException("Steam请求已取消。")
                  }
                }
              continuation.resumeWith(Result.success(text))
            } catch (error: Exception) {
              continuation.resumeWith(Result.failure(error))
            }
          }
        }
      )
    }

  private fun JSONObject.strictPositiveInt(key: String): Int? {
    val value = opt(key) as? Number ?: return null
    val long = value.toLong()
    if (value.toDouble() != long.toDouble() || long !in 1..Int.MAX_VALUE.toLong()) return null
    return long.toInt()
  }

  private fun String?.safeSteamImageUrl(): String? {
    val url =
      this?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { it.toHttpUrl() }.getOrNull() }
        ?: return null
    return url
      .takeIf {
        it.isHttps &&
          it.username.isEmpty() &&
          it.password.isEmpty() &&
          (it.host == STORE_HOST || it.host.endsWith(".steamstatic.com"))
      }
      ?.toString()
  }

  companion object {
    const val MAX_RESULTS = 20
    private const val MAX_QUERY_LENGTH = 120
    internal const val MAX_RESPONSE_BYTES = 512 * 1024L
    private const val STORE_HOST = "store.steampowered.com"

    private fun publicSteamSearchClient() =
      OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
  }
}

internal fun readBoundedUtf8(
  source: BufferedSource,
  maxBytes: Long = SteamGameSearchRepository.MAX_RESPONSE_BYTES,
  beforeRead: () -> Unit = {},
): String {
  require(maxBytes >= 0)
  val buffer = Buffer()
  var bytesRead = 0L
  while (true) {
    beforeRead()
    val remaining = maxBytes + 1L - bytesRead
    val read = source.read(buffer, minOf(8192L, remaining))
    if (read == -1L) break
    bytesRead += read
    if (bytesRead > maxBytes) error("Steam响应超出允许范围。")
  }
  return buffer.readString(Charsets.UTF_8)
}
