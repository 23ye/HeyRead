package cn.heyread.app.data.remote

import cn.heyread.app.data.model.ArticleLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class PublicGameInfo(
    val appId: Int,
    val name: String,
    val imageUrl: String?,
    val score: Double?,
    val followerCount: Long?,
    val currentPrice: String?,
    val originalPrice: String?,
    val discountPercent: Int?,
    val isFree: Boolean,
    val isLowest: Boolean,
    val isNewLowest: Boolean,
    val couponPrice: String?,
    val priceSource: PriceSource?,
)

enum class PriceSource {
    HEYBOX,
    STEAM,
    PLATFORM,
}

data class ResolvedGameTopic(val label: ArticleLabel)

class ArticleGameRepository(
    private val execute: suspend (Request) -> String = ::executePublicRequest,
    private val signer: WebRequestSigner = WebRequestSigner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun loadInfos(
        appIds: List<Int>,
        challenge: CaptchaChallenge? = null,
        proof: CaptchaProof? = null,
    ): Map<Int, PublicGameInfo> {
        val ids = appIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return buildMap {
            ids.chunked(MAX_APP_IDS).forEach { batch ->
                val params = mapOf("appids" to batch.joinToString(","))
                val json = request(GAME_INFO_PATH, params, challenge, proof)
                val result = json.optJSONObject("result") ?: error("游戏资料响应缺少 result")
                val rows = result.opt("base_infos")
                require(rows is JSONArray) { "游戏资料响应缺少 base_infos" }
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val info = parseGameInfo(row) ?: continue
                    if (info.appId in batch) put(info.appId, info)
                }
            }
        }
    }

    suspend fun resolveTopic(
        appId: Int,
        challenge: CaptchaChallenge? = null,
        proof: CaptchaProof? = null,
    ): ResolvedGameTopic {
        require(appId > 0) { "缺少有效游戏 ID" }
        val json = request(TOPIC_MENU_PATH, mapOf("appid" to appId.toString()), challenge, proof)
        val topic = json.optJSONObject("result")?.optJSONObject("topic") ?: error("游戏分区响应缺少 topic")
        val topicId =
            scalar(topic.opt("topic_id"))?.takeIf {
                it.toLongOrNull()?.let { id -> id > 0 } == true
            } ?: error("该游戏暂未返回有效分区 ID")
        val name = scalar(topic.opt("name"))?.takeIf(String::isNotBlank) ?: "游戏分区"
        val icon = scalar(topic.opt("pic_url"))?.takeIf(::isHttps)
        return ResolvedGameTopic(ArticleLabel(topicId, name, icon))
    }

    internal fun parseGameInfo(row: JSONObject): PublicGameInfo? {
        val appId = scalar(row.opt("steam_appid"))?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val name = scalar(row.opt("name"))?.takeIf(String::isNotBlank) ?: return null
        val heybox = row.optJSONObject("heybox_price")
        val steamPrice = row.optJSONObject("price")
        val platformPrice =
            row.optJSONArray("platform_infos")?.optJSONObject(0)?.optJSONObject("price")
        val useHeybox = heybox != null
        val selected = heybox ?: steamPrice ?: platformPrice
        val style = selected?.optInt("style", -1) ?: -1
        val couponInfo = selected?.optJSONObject("coupon_info")
        val couponPrice =
            if (useHeybox && style in setOf(110, 111)) decimal(couponInfo?.opt("final_price"))
            else null
        val free = row.opt("is_free").let(::strictFlag) == true
        val current =
            when {
                free -> "免费"
                couponPrice != null -> money(couponPrice)
                useHeybox -> coinMoney(selected?.opt("cost_coin"))
                steamPrice != null -> decimal(steamPrice.opt("current"))?.let(::money)
                else -> decimal(platformPrice?.opt("current"))
            }
        val original =
            when {
                useHeybox -> coinMoney(selected?.opt("original_coin"))
                steamPrice != null -> decimal(steamPrice.opt("initial"))?.let(::money)
                else -> decimal(platformPrice?.opt("initial"))
            }
        val discount =
            selected?.opt("discount")?.let(::integerValue)?.takeIf { !free && it in 1..99 }
        val score = scalar(row.opt("score"))?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
        val followCount = row.opt("follow_num").let(::longValue)?.takeIf { it >= 0L }
        return PublicGameInfo(
            appId = appId,
            name = name,
            imageUrl = scalar(row.opt("image"))?.takeIf(::isHttps),
            score = score,
            followerCount = followCount,
            currentPrice = current,
            originalPrice =
                original?.takeIf {
                    current != null && !free && priceValue(it) > priceValue(current)
                },
            discountPercent = discount,
            isFree = free,
            isLowest = selected?.opt("is_lowest").let(::strictFlag) == true,
            isNewLowest = selected?.opt("new_lowest").let(::strictFlag) == true,
            couponPrice = couponPrice?.let(::money),
            priceSource =
                when {
                    useHeybox -> PriceSource.HEYBOX
                    steamPrice != null -> PriceSource.STEAM
                    platformPrice != null -> PriceSource.PLATFORM
                    else -> null
                },
        )
    }

    private suspend fun request(
        path: String,
        params: Map<String, String>,
        challenge: CaptchaChallenge?,
        proof: CaptchaProof?,
    ): JSONObject =
        withContext(ioDispatcher) {
            val signature = signer.create(path)
            val url = API_BASE.toHttpUrl().newBuilder().addPathSegments(path.removePrefix("/"))
            COMMON_PARAMS.forEach { (key, value) -> url.addQueryParameter(key, value) }
            params.forEach { (key, value) -> url.addQueryParameter(key, value) }
            val exactChallenge = challenge?.takeIf { it.path == path && it.parameters == params }
            if (exactChallenge != null && proof?.consumeOnce() == true) {
                url.addQueryParameter("captcha_type", "confirm")
                    .addQueryParameter("ticket", proof.ticket)
                    .addQueryParameter("randstr", proof.randstr)
            }
            url.addQueryParameter("_time", signature.time.toString())
                .addQueryParameter("nonce", signature.nonce)
                .addQueryParameter("hkey", signature.hkey)
            val request =
                Request.Builder()
                    .url(url.build())
                    .header("Accept", "application/json")
                    .header("Referer", "https://www.xiaoheihe.cn/")
                    .header("User-Agent", "HeyRead/1.0 (Android)")
                    .build()
            val json = JSONObject(execute(request))
            val status = json.optString("status")
            if (status.equals("show_captcha", true)) {
                val appId = resolveCaptchaAppId(json.opt("appid"), path)
                throw CaptchaRequiredException(CaptchaChallenge(path, params.toMap(), appId))
            }
            require(status.equals("ok", true) || status.equals("success", true)) {
                "小黑盒游戏接口返回失败（status=$status）"
            }
            json
        }

    private fun coinMoney(value: Any?): String? {
        val amount = longValue(value) ?: return null
        if (amount < 0L) return null
        val yuan = amount / 1000.0
        return "¥" + String.format(java.util.Locale.ROOT, "%.2f", yuan).trimEnd('0').trimEnd('.')
    }

    private fun money(value: String): String? {
        val number = decimal(value) ?: return null
        val normalized = if (number.contains('.')) number.trimEnd('0').trimEnd('.') else number
        return "¥" + normalized.ifEmpty { "0" }
    }

    private fun decimal(value: Any?): String? =
        scalar(value)?.let { raw ->
            val normalized = raw.removePrefix("¥").trim()
            normalized.takeIf {
                it.matches(Regex("\\d+(?:\\.\\d{1,2})?")) && it.toBigDecimalOrNull() != null
            }
        }

    private fun priceValue(value: String): java.math.BigDecimal =
        value.removePrefix("¥").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO

    private fun strictFlag(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            is Number ->
                when (value.toDouble()) {
                    0.0 -> false
                    1.0 -> true
                    else -> null
                }
            is String ->
                when (value.trim().lowercase()) {
                    "0",
                    "false" -> false
                    "1",
                    "true" -> true
                    else -> null
                }
            else -> null
        }

    private fun integerValue(value: Any?): Int? = scalar(value)?.toDoubleOrNull()?.toInt()

    private fun longValue(value: Any?): Long? = scalar(value)?.toLongOrNull()

    private fun scalar(value: Any?): String? =
        when (value) {
            null,
            JSONObject.NULL -> null
            is String -> value.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
            is Number -> value.toString()
            else -> null
        }

    private fun isHttps(value: String) =
        runCatching {
                value.toHttpUrl().isHttps &&
                    value.toHttpUrl().username.isEmpty() &&
                    value.toHttpUrl().password.isEmpty()
            }
            .getOrDefault(false)

    companion object {
        const val GAME_INFO_PATH = "/game/get_game_infos/"
        const val TOPIC_MENU_PATH = "/bbs/app/topic/menu"
        const val MAX_APP_IDS = 24
        const val API_BASE = "https://api.xiaoheihe.cn"
        private val COMMON_PARAMS =
            mapOf(
                "os_type" to "web",
                "app" to "heybox",
                "x_client_type" to "web",
                "x_os_type" to "Mac",
                "x_app" to "heybox",
                "version" to "999.0.4",
            )
    }
}
