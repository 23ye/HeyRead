package cn.heyread.app.data.rich

import android.content.Context
import cn.heyread.app.data.remote.WebRequestSigner
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Official exact shortcode-to-image mappings. No prefix or Unicode guessing is performed. */
object OfficialEmojiCatalog {
    private const val PATH = "/bbs/app/api/emojis/list"
    private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
    private const val SUCCESS_TTL_MS = 24 * 60 * 60 * 1000L
    private val signer = WebRequestSigner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val client =
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    private val _entries = MutableStateFlow<Map<String, String>>(emptyMap())
    val entries: StateFlow<Map<String, String>> = _entries.asStateFlow()

    private var loading = false
    private var lastFailureAt = 0L
    private var lastSuccessAt = 0L
    private var bundledAttempted = false

    fun loadBundled(context: Context) {
        synchronized(lock) {
            if (bundledAttempted) return
            bundledAttempted = true
        }
        try {
            val entries =
                context.applicationContext.assets
                    .open("official_emojis.json")
                    .bufferedReader()
                    .use { parseBundled(JSONObject(it.readText())) }
            if (entries.isNotEmpty()) {
                _entries.value = entries
                synchronized(lock) { lastSuccessAt = System.currentTimeMillis() }
            }
        } catch (_: Exception) {
            // Network refresh remains available; callers keep the original shortcode meanwhile.
        }
    }

    /** Loads the bundled factual map immediately, then attempts one signed, cookie-free refresh. */
    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (
                loading ||
                    now - lastFailureAt < FAILURE_COOLDOWN_MS ||
                    now - lastSuccessAt < SUCCESS_TTL_MS
            )
                return
            loading = true
        }
        scope.launch {
            try {
                val appContext = context.applicationContext
                if (_entries.value.isEmpty())
                    withContext(Dispatchers.IO) { loadBundled(appContext) }
                val refreshed = fetchOfficialCatalog()
                if (refreshed.isNotEmpty()) {
                    _entries.value = refreshed
                    synchronized(lock) {
                        lastFailureAt = 0L
                        lastSuccessAt = System.currentTimeMillis()
                    }
                } else {
                    synchronized(lock) { lastFailureAt = System.currentTimeMillis() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { lastFailureAt = System.currentTimeMillis() }
            } finally {
                synchronized(lock) { loading = false }
            }
        }
    }

    internal fun parseCatalog(root: JSONObject): Map<String, String> {
        if (!root.optString("status").equals("ok", true)) return emptyMap()
        val result = root.optJSONObject("result") ?: return emptyMap()
        val groups = result.optJSONArray("emoji_groups") ?: return emptyMap()
        val entries = linkedMapOf<String, String>()
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val groupCode = safeText(group.opt("group_code")) ?: continue
            if (!GROUP_CODE.matches(groupCode)) continue
            val emojis = group.optJSONArray("emojis") ?: continue
            for (emojiIndex in 0 until emojis.length()) {
                val emoji = emojis.optJSONObject(emojiIndex) ?: continue
                val code = safeText(emoji.opt("code")) ?: continue
                val image = safeText(emoji.opt("img")) ?: continue
                if (code.isBlank() || code.any(Char::isWhitespace)) continue
                val url = image.toHttpUrlOrNull() ?: continue
                if (!url.isHttps || url.host !in OFFICIAL_IMAGE_HOSTS) continue
                entries["[${groupCode}_${code}]"] = url.toString()
            }
        }
        return entries
    }

    internal fun parseBundled(root: JSONObject): Map<String, String> {
        val source = safeText(root.opt("source")) ?: return emptyMap()
        if (!source.startsWith("Official public GET ")) return emptyMap()
        val values = root.optJSONObject("entries") ?: return emptyMap()
        val entries = linkedMapOf<String, String>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!TOKEN.matches(key)) continue
            val rawUrl = safeText(values.opt(key)) ?: continue
            val url = rawUrl.toHttpUrlOrNull() ?: continue
            if (!url.isHttps || url.host !in OFFICIAL_IMAGE_HOSTS) continue
            entries[key] = url.toString()
        }
        return entries
    }

    fun tokenForImage(url: String): String? =
        _entries.value.entries.firstOrNull { it.value == url }?.key

    private fun fetchOfficialCatalog(): Map<String, String> {
        val signature = signer.create(PATH)
        val url = ("https://api.xiaoheihe.cn$PATH").toHttpUrl().newBuilder()
        linkedMapOf(
                "os_type" to "web",
                "app" to "heybox",
                "x_client_type" to "web",
                "x_os_type" to "Mac",
                "x_app" to "heybox",
                "version" to "999.0.4",
                "_time" to signature.time.toString(),
                "nonce" to signature.nonce,
                "hkey" to signature.hkey,
            )
            .forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request =
            Request.Builder()
                .url(url.build())
                .header("Accept", "application/json")
                .header("Referer", "https://www.xiaoheihe.cn/")
                .header("User-Agent", "HeyRead/1.0 (Android)")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            val body = response.body?.string() ?: return emptyMap()
            val json = JSONObject(body)
            if (!json.optString("status").equals("ok", true)) return emptyMap()
            return parseCatalog(json)
        }
    }

    private fun safeText(value: Any?): String? =
        when (value) {
            is String ->
                value.takeIf { it.isNotBlank() && it.lowercase() !in setOf("null", "undefined") }
            else -> null
        }

    private val GROUP_CODE = Regex("[A-Za-z0-9_-]{1,48}")
    private val TOKEN = Regex("\\[[A-Za-z0-9_-]{1,48}_[^\\[\\]\\s]{1,80}]")
    private val OFFICIAL_IMAGE_HOSTS = setOf("imgheybox.max-c.com", "cdn.max-c.com")
}
