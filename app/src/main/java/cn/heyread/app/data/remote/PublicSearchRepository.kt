package cn.heyread.app.data.remote

import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Story
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class OnlineSearchBatch(
    val stories: List<Story>,
    val rawCount: Int,
    val rawIds: List<String>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val nextCursor: String? = null,
)

fun interface OnlineSearchLoader {
    suspend fun load(
        query: String,
        scope: SearchScope,
        offset: Int,
        cursor: String?,
        challenge: CaptchaChallenge?,
        proof: CaptchaProof?,
    ): OnlineSearchBatch
}

class PublicSearchRepository(
    private val execute: suspend (Request) -> String = ::executePublicRequest,
    private val hashtagFeeds: DestinationLoader = DestinationRepository(),
    private val storyParser: DestinationRepository = DestinationRepository(),
) : OnlineSearchLoader {
    override suspend fun load(
        query: String,
        scope: SearchScope,
        offset: Int,
        cursor: String?,
        challenge: CaptchaChallenge?,
        proof: CaptchaProof?,
    ): OnlineSearchBatch {
        require(scope.isValid) { "搜索范围缺少有效 ID" }
        require(query.isNotBlank() && query.length <= 120) { "搜索词无效" }
        require(offset >= 0 && (scope.type == SearchScopeType.HASHTAG || offset % PAGE_SIZE == 0)) {
            "搜索位置无效"
        }
        return when (scope.type) {
            SearchScopeType.HASHTAG -> loadHashtag(query, scope, offset, cursor, challenge, proof)
            SearchScopeType.GLOBAL,
            SearchScopeType.SECTION -> loadGeneral(query, scope, offset, challenge, proof)
        }
    }

    private suspend fun loadGeneral(
        query: String,
        scope: SearchScope,
        offset: Int,
        challenge: CaptchaChallenge?,
        proof: CaptchaProof?,
    ): OnlineSearchBatch =
        withContext(Dispatchers.IO) {
            val path = GENERAL_SEARCH
            val params =
                linkedMapOf(
                    "q" to query,
                    "search_type" to "link",
                    "offset" to offset.toString(),
                    "limit" to PAGE_SIZE.toString(),
                )
            if (scope.type == SearchScopeType.SECTION) params["topic_id"] = scope.id
            val signature = WebRequestSigner().create(path)
            val url =
                "https://api.xiaoheihe.cn$path"
                    .toHttpUrl()
                    .newBuilder()
                    .apply {
                        PUBLIC_PARAMS.forEach { (key, value) -> addQueryParameter(key, value) }
                        params.forEach { (key, value) -> addQueryParameter(key, value) }
                        if (
                            challenge?.path == path &&
                                challenge.parameters == params &&
                                proof?.consumeOnce() == true
                        ) {
                            addQueryParameter("captcha_type", "confirm")
                            addQueryParameter("ticket", proof.ticket)
                            addQueryParameter("randstr", proof.randstr)
                        }
                        addQueryParameter("_time", signature.time.toString())
                        addQueryParameter("nonce", signature.nonce)
                        addQueryParameter("hkey", signature.hkey)
                    }
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Referer", "https://www.xiaoheihe.cn/")
                    .header("User-Agent", "HeyRead/1.10 (Android)")
                    .build()
            val body = JSONObject(execute(request))
            val status = body.optString("status")
            if (status.equals("show_captcha", true))
                throw CaptchaRequiredException(
                    CaptchaChallenge(
                        path,
                        params.toMap(),
                        resolveCaptchaAppId(body.opt("appid"), path),
                    )
                )
            require(status.equals("ok", true) || status.equals("success", true)) {
                "小黑盒搜索接口返回失败（status=$status）"
            }
            val result = body.optJSONObject("result") ?: error("搜索响应缺少 result")
            val rows = result.opt("items") as? JSONArray ?: error("搜索响应缺少有效的 items 列表")
            val rawIds = mutableListOf<String>()
            val stories =
                (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONObject(index) ?: return@mapNotNull null
                    if (scalar(row.opt("type")) != "link") return@mapNotNull null
                    val info = row.optJSONObject("info") ?: return@mapNotNull null
                    (scalar(info.opt("linkid"))
                            ?: scalar(info.opt("link_id"))
                            ?: scalar(info.opt("id")))
                        ?.let(rawIds::add)
                    val story = storyParser.parseStory(info) ?: return@mapNotNull null
                    if (
                        scope.type == SearchScopeType.SECTION &&
                            hasExplicitSection(story) &&
                            story.sections.none { it.id == scope.id }
                    )
                        error("搜索结果与所选分区不符，请稍后重试")
                    else story
                }
            require(rows.length() == 0 || rawIds.isNotEmpty()) { "搜索结果包含内容但缺少有效帖子 ID" }
            val moreValue =
                result.opt("has_more")?.takeUnless { it === JSONObject.NULL }
                    ?: result.opt("hasMore")?.takeUnless { it === JSONObject.NULL }
            val hasMore = moreValue?.let(::strictBoolean) ?: (rows.length() > 0)
            OnlineSearchBatch(
                stories.distinctBy { it.id },
                rows.length(),
                rawIds,
                offset + PAGE_SIZE,
                hasMore,
            )
        }

    private suspend fun loadHashtag(
        query: String,
        scope: SearchScope,
        offset: Int,
        cursor: String?,
        challenge: CaptchaChallenge?,
        proof: CaptchaProof?,
    ): OnlineSearchBatch {
        val page =
            hashtagFeeds.load(
                DestinationTarget(DestinationType.HASHTAG, scope.id, scope.name),
                offset,
                PAGE_SIZE,
                cursor,
                null,
                DestinationFeedType.ARTICLES,
                false,
                null,
                challenge,
                proof,
            )
        val needle = query.lowercase(Locale.ROOT)
        val stories =
            page.stories.filter { story ->
                val labelsWithIds = story.hashtags.filter { it.id.isNotBlank() }
                if (labelsWithIds.isNotEmpty() && labelsWithIds.none { it.id == scope.id })
                    error("话题列表返回了不属于当前话题的内容")
                "${story.title} ${story.body}".lowercase(Locale.ROOT).contains(needle)
            }
        require(page.rawCount == 0 || page.rawIds.isNotEmpty()) { "话题列表包含内容但缺少有效帖子 ID" }
        return OnlineSearchBatch(
            stories,
            page.rawCount,
            page.rawIds,
            page.nextOffset,
            page.hasMore,
            page.lastValue,
        )
    }

    private fun hasExplicitSection(story: Story) = story.sections.any { it.id.isNotBlank() }

    private fun strictBoolean(value: Any): Boolean =
        when (value) {
            is Boolean -> value
            is Number ->
                when (value.toDouble()) {
                    0.0 -> false
                    1.0 -> true
                    else -> error("搜索has_more数值无效")
                }
            is String ->
                when (value.lowercase(Locale.ROOT)) {
                    "true",
                    "1" -> true
                    "false",
                    "0" -> false
                    else -> error("搜索has_more文本无效")
                }
            else -> error("搜索has_more类型无效")
        }

    private fun scalar(value: Any?): String? =
        when (value) {
            null,
            JSONObject.NULL -> null
            is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
            is Number -> value.toString()
            else -> null
        }

    companion object {
        const val PAGE_SIZE = 20
        const val GENERAL_SEARCH = "/bbs/app/api/general/search/v1"
        private val PUBLIC_PARAMS =
            linkedMapOf(
                "os_type" to "web",
                "app" to "heybox",
                "x_client_type" to "web",
                "x_os_type" to "Mac",
                "x_app" to "heybox",
                "version" to "999.0.4",
            )
    }
}
