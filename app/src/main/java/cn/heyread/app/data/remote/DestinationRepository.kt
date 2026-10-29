package cn.heyread.app.data.remote

import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class DestinationType {
    SECTION,
    HASHTAG,
    USER,
}

enum class DestinationFeedType {
    MOMENTS,
    ARTICLES,
}

data class DestinationTarget(
    val type: DestinationType,
    val id: String,
    val name: String = "",
    val iconUrl: String? = null,
)

data class DestinationData(
    val title: String,
    val iconUrl: String? = null,
    val profile: AuthorProfile? = null,
    val description: String? = null,
    val notice: String? = null,
    val profileStats: UserProfileStats? = null,
)

data class UserProfileStats(
    val following: Long?,
    val followers: Long?,
    val receivedLikesAndFavorites: Long?,
)

data class DestinationPage(
    val data: DestinationData,
    val stories: List<Story>,
    val nextOffset: Int,
    val lastValue: String? = null,
    val hasMore: Boolean,
    val rawCount: Int = stories.size,
    val rawIds: List<String> = stories.map { it.id },
)

/** A short-lived CAPTCHA receipt. Its token fields never appear in toString or UI state. */
class CaptchaProof internal constructor(internal val ticket: String, internal val randstr: String) {
    private val consumed = AtomicBoolean(false)

    internal fun consumeOnce(): Boolean = consumed.compareAndSet(false, true)

    override fun toString(): String = "CaptchaProof(<redacted>)"
}

data class CaptchaChallenge(
    val path: String,
    val parameters: Map<String, String>,
    val appId: String,
) {
    override fun toString(): String = "CaptchaChallenge(path=$path, appId=$appId)"
}

class CaptchaRequiredException(
    val challenge: CaptchaChallenge,
    val partialData: DestinationData? = null,
    val verificationMessage: String? = null,
) : IllegalStateException(verificationMessage ?: "小黑盒需要人机验证")

fun interface DestinationLoader {
    suspend fun load(
        target: DestinationTarget,
        offset: Int,
        limit: Int,
        lastValue: String?,
        knownProfile: AuthorProfile?,
        feedType: DestinationFeedType,
        reloadHeader: Boolean,
        knownData: DestinationData?,
        captchaChallenge: CaptchaChallenge?,
        captchaProof: CaptchaProof?,
    ): DestinationPage
}

suspend fun DestinationLoader.load(
    target: DestinationTarget,
    offset: Int,
    limit: Int,
    lastValue: String?,
    knownProfile: AuthorProfile?,
    feedType: DestinationFeedType,
    reloadHeader: Boolean,
    knownData: DestinationData? = null,
    captchaChallenge: CaptchaChallenge? = null,
    captchaProof: CaptchaProof? = null,
): DestinationPage =
    load(
        target,
        offset,
        limit,
        lastValue,
        knownProfile,
        feedType,
        reloadHeader,
        knownData,
        captchaChallenge,
        captchaProof,
    )

class DestinationRepository(
    private val signer: WebRequestSigner = WebRequestSigner(),
    private val execute: suspend (Request) -> String = ::executePublicRequest,
) : DestinationLoader {
    override suspend fun load(
        target: DestinationTarget,
        offset: Int,
        limit: Int,
        lastValue: String?,
        knownProfile: AuthorProfile?,
        feedType: DestinationFeedType,
        reloadHeader: Boolean,
        knownData: DestinationData?,
        captchaChallenge: CaptchaChallenge?,
        captchaProof: CaptchaProof?,
    ): DestinationPage =
        withContext(Dispatchers.IO) {
            require(target.id.isNotBlank()) { "缺少有效的页面 ID" }
            val (path, params) =
                when (target.type) {
                    DestinationType.SECTION ->
                        "/bbs/app/topic/feeds" to
                            linkedMapOf(
                                "topic_id" to target.id,
                                "offset" to offset.toString(),
                                "limit" to limit.toString(),
                                "lastval" to lastValue.orEmpty(),
                                "dw" to "304",
                            )
                    DestinationType.HASHTAG ->
                        "/bbs/app/hashtag/feeds" to
                            linkedMapOf(
                                    "hashtag_id" to target.id,
                                    "offset" to offset.toString(),
                                    "limit" to limit.toString(),
                                )
                                .apply { lastValue?.let { put("lastval", it) } }
                    DestinationType.USER ->
                        if (feedType == DestinationFeedType.MOMENTS) {
                            "/bbs/app/profile/events" to
                                linkedMapOf(
                                    "userid" to target.id,
                                    "list_type" to "moment",
                                    "dw" to "304",
                                    "lastval" to lastValue.orEmpty(),
                                )
                        } else
                            "/bbs/app/profile/user/link/list_v2" to
                                linkedMapOf(
                                    "userid" to target.id,
                                    "list_type" to "article",
                                    "offset" to offset.toString(),
                                    "limit" to limit.toString(),
                                    "dw" to "304",
                                )
                }
            var profileNotice: String? = null
            var profileStats: UserProfileStats? = null
            val profile =
                if (target.type == DestinationType.USER && reloadHeader) {
                    try {
                        val profileResult =
                            request(
                                    "/bbs/app/profile/user/profile",
                                    mapOf("userid" to target.id),
                                    captchaChallenge,
                                    captchaProof,
                                )
                                .optJSONObject("result") ?: error("用户资料响应缺少 result")
                        val accountDetail =
                            profileResult.optJSONObject("account_detail")
                                ?: error("用户资料响应缺少 account_detail")
                        profileStats =
                            parseProfileStats(accountDetail?.optJSONObject("bbs_info"))
                                ?: UserProfileStats(null, null, null)
                        parseProfile(accountDetail)
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        if (failure is CaptchaRequiredException) throw failure
                        profileNotice = "用户资料读取失败：${failure.message.orEmpty()}"
                        null
                    }
                } else knownProfile
            val sectionData =
                if (
                    target.type == DestinationType.SECTION &&
                        offset == 0 &&
                        (knownData == null || captchaChallenge?.path == "/bbs/app/topic/menu")
                ) {
                    try {
                        val menuResult =
                            request(
                                    "/bbs/app/topic/menu",
                                    mapOf("topic_id" to target.id),
                                    captchaChallenge,
                                    captchaProof,
                                )
                                .optJSONObject("result") ?: error("分区资料响应缺少 result")
                        val topic = menuResult.optJSONObject("topic") ?: error("分区资料响应缺少 topic")
                        DestinationData(
                            firstScalar(topic, "name") ?: target.name,
                            https(firstScalar(topic, "pic_url")) ?: target.iconUrl,
                        )
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        if (failure is CaptchaRequiredException) throw failure
                        DestinationData(
                            target.name.ifBlank { "分区" },
                            target.iconUrl,
                            notice = "分区资料读取失败：${failure.message.orEmpty()}",
                        )
                    }
                } else if (target.type == DestinationType.SECTION) knownData else null
            val profileData =
                if (target.type == DestinationType.USER && reloadHeader) {
                    val currentProfile = profile
                    DestinationData(
                        currentProfile?.name?.ifBlank { target.name }
                            ?: target.name.ifBlank { "用户主页" },
                        currentProfile?.avatarUrl,
                        currentProfile,
                        currentProfile?.bio,
                        profileNotice,
                        profileStats,
                    )
                } else knownData
            val json =
                try {
                    request(path, params, captchaChallenge, captchaProof)
                } catch (captcha: CaptchaRequiredException) {
                    throw CaptchaRequiredException(
                        captcha.challenge,
                        sectionData ?: profileData,
                        captcha.verificationMessage,
                    )
                }
            val result = json.optJSONObject("result") ?: error("响应缺少 result")
            when (target.type) {
                DestinationType.SECTION ->
                    parseFeedResult(result, target, null, "links", offset, limit, sectionData)
                DestinationType.HASHTAG ->
                    parseFeedResult(result, target, "hashtag", "links", offset, limit)
                DestinationType.USER ->
                    if (feedType == DestinationFeedType.MOMENTS)
                        parseMomentsResult(
                            result,
                            target,
                            lastValue,
                            profile,
                            profileNotice,
                            profileStats,
                        )
                    else
                        parseUserResult(
                            result,
                            target,
                            offset,
                            limit,
                            profile,
                            profileNotice,
                            profileStats,
                        )
            }
        }

    private fun parseFeedResult(
        result: JSONObject,
        target: DestinationTarget,
        metadataKey: String?,
        listKey: String,
        offset: Int,
        limit: Int,
        suppliedData: DestinationData? = null,
    ): DestinationPage {
        val metadata = metadataKey?.let(result::optJSONObject)
        if (metadataKey != null) require(metadata != null) { "响应缺少 $metadataKey 资料" }
        val title = firstScalar(metadata, "name", "display_name") ?: target.name.ifBlank { "内容" }
        val icon = https(firstScalar(metadata, "pic_url"))
        val rows = requiredArray(result, listKey)
        val stories =
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parseStory) }
        val lastValue = nullableScalar(result.opt("lastval"))
        return DestinationPage(
            suppliedData ?: DestinationData(title, icon),
            stories,
            offset + rows.length(),
            lastValue,
            rows.length() > 0,
            rows.length(),
            (0 until rows.length()).mapNotNull { index ->
                rows.optJSONObject(index)?.let { firstScalar(it, "linkid", "link_id", "id") }
            },
        )
    }

    private fun parseUserResult(
        result: JSONObject,
        target: DestinationTarget,
        offset: Int,
        limit: Int,
        profile: AuthorProfile?,
        profileNotice: String?,
        profileStats: UserProfileStats?,
    ): DestinationPage {
        val listUser = parseProfile(result.optJSONObject("user"))
        val resolvedProfile = profile ?: listUser
        val title =
            resolvedProfile?.name?.takeIf(String::isNotBlank) ?: target.name.ifBlank { "用户主页" }
        val rows = requiredArray(result, "post_links")
        val stories =
            (0 until rows.length())
                .mapNotNull { rows.optJSONObject(it)?.let(::parseStory) }
                .map { story ->
                    if (story.authorProfile == null && resolvedProfile != null)
                        story.copy(author = resolvedProfile.name, authorProfile = resolvedProfile)
                    else story
                }
        val totalPages = result.optInt("total_page", 0)
        require(totalPages >= 0) { "用户文章列表页数无效" }
        val nextOffset = offset + limit
        return DestinationPage(
            DestinationData(
                title,
                resolvedProfile?.avatarUrl,
                resolvedProfile,
                resolvedProfile?.bio,
                profileNotice,
                profileStats,
            ),
            stories,
            nextOffset,
            hasMore = totalPages > 0 && offset / limit + 1 < totalPages && rows.length() > 0,
        )
    }

    private fun parseMomentsResult(
        result: JSONObject,
        target: DestinationTarget,
        previousCursor: String?,
        profile: AuthorProfile?,
        profileNotice: String?,
        profileStats: UserProfileStats?,
    ): DestinationPage {
        val rows = requiredArray(result, "moments")
        val resolvedProfile = profile ?: parseProfile(result.optJSONObject("user"))
        val stories =
            (0 until rows.length())
                .mapNotNull { index ->
                    val moment = rows.optJSONObject(index) ?: return@mapNotNull null
                    val content = JSONObject((moment.optJSONObject("link") ?: moment).toString())
                    if (firstScalar(content, "link_id", "linkid", "id") == null) {
                        firstScalar(moment, "link_id", "linkid", "id")?.let {
                            content.put("linkid", it)
                        }
                    }
                    if (content.optJSONObject("user") == null) {
                        val author = moment.optJSONObject("user") ?: result.optJSONObject("user")
                        if (author != null) content.put("user", author)
                    }
                    val description = firstScalar(content, "description", "content", "text")
                    val title =
                        firstScalar(content, "title", "link_title") ?: description?.take(120)
                    if (title != null) content.put("title", title)
                    parseStory(content)
                }
                .map { story ->
                    if (story.authorProfile == null && resolvedProfile != null)
                        story.copy(author = resolvedProfile.name, authorProfile = resolvedProfile)
                    else story
                }
        val rawCursor = result.opt("lastval")
        require(
            rawCursor == null ||
                rawCursor == JSONObject.NULL ||
                rawCursor is String ||
                rawCursor is Number
        ) {
            "动态响应 lastval 类型无效"
        }
        val cursor = nullableScalar(rawCursor)
        return DestinationPage(
            DestinationData(
                resolvedProfile?.name?.ifBlank { target.name } ?: target.name.ifBlank { "用户主页" },
                resolvedProfile?.avatarUrl,
                resolvedProfile,
                resolvedProfile?.bio,
                profileNotice,
                profileStats,
            ),
            stories,
            0,
            cursor,
            !cursor.isNullOrBlank() && cursor != previousCursor && stories.isNotEmpty(),
        )
    }

    private fun parseProfileStats(value: JSONObject?): UserProfileStats? {
        if (value == null) return null
        val following = strictCount(value.opt("follow_num"))
        val followers = strictCount(value.opt("fan_num"))
        val awarded = strictCount(value.opt("awd_num"))
        val savedOnPosts = strictCount(value.opt("be_favoured_num"))
        val combined =
            if (awarded != null && savedOnPosts != null)
                runCatching { Math.addExact(awarded, savedOnPosts) }.getOrNull()
            else null
        return UserProfileStats(following, followers, combined)
    }

    private fun strictCount(value: Any?): Long? {
        if (value !is Number) return null
        return value.toString().toLongOrNull()?.takeIf { it >= 0 }
    }

    private suspend fun request(
        path: String,
        params: Map<String, String>,
        challenge: CaptchaChallenge? = null,
        proof: CaptchaProof? = null,
    ): JSONObject {
        val signature = signer.create(path)
        var proofSubmitted = false
        val url =
            "https://api.xiaoheihe.cn$path"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    mapOf(
                            "os_type" to "web",
                            "app" to "heybox",
                            "x_client_type" to "web",
                            "x_os_type" to "Mac",
                            "x_app" to "heybox",
                            "version" to "999.0.4",
                        )
                        .forEach { (key, value) -> addQueryParameter(key, value) }
                    params.forEach { (key, value) -> addQueryParameter(key, value) }
                    val exactChallenge =
                        challenge?.takeIf { it.path == path && it.parameters == params }
                    if (exactChallenge != null && proof?.consumeOnce() == true) {
                        proofSubmitted = true
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
                .header("User-Agent", "HeyRead/1.0 (Android)")
                .build()
        val json = JSONObject(execute(request))
        val status = json.optString("status")
        if (status.equals("show_captcha", true)) {
            val appId =
                resolveCaptchaAppId(
                    json.opt("appid"),
                    json.optJSONObject("result")?.opt("appid"),
                    path,
                )
            throw CaptchaRequiredException(
                CaptchaChallenge(path, params.toMap(), appId),
                verificationMessage =
                    if (proofSubmitted) "验证后仍需要完成验证，请重新尝试。" else null,
            )
        }
        if (proofSubmitted && status.equals("failed", true))
            throw CaptchaRequiredException(
                requireNotNull(challenge),
                verificationMessage = "验证后读取仍失败(status=failed)，请重新验证或稍后重试",
            )
        require(status.equals("ok", true) || status.equals("success", true)) {
            "小黑盒公开内容接口返回失败（status=$status）"
        }
        return json
    }

    internal fun parseStory(value: JSONObject): Story? {
        val id = firstScalar(value, "link_id", "linkid", "id") ?: return null
        val user = value.optJSONObject("user") ?: value.optJSONObject("poster")
        val title = cleanSearchMarkup(firstScalar(value, "title", "link_title").orEmpty())
        val body = cleanSearchMarkup(firstScalar(value, "description", "content", "text").orEmpty())
        val displayTitle =
            title
                .ifBlank { body.take(160) }
                .ifBlank {
                    return null
                }
        val author =
            firstScalar(user, "username", "nickname") ?: firstScalar(value, "username") ?: "作者未公开"
        val profile = parseProfile(user)
        val firstImage = value.optJSONArray("imgs")?.opt(0)
        val firstImageUrl =
            when (firstImage) {
                is String -> firstImage
                is JSONObject -> firstScalar(firstImage, "url", "origin_url")
                else -> null
            }
        val cover = https(firstScalar(value, "cover")) ?: https(firstImageUrl)
        val topics = parseLabels(value.optJSONArray("topics"), "topic_id", "pic_url")
        val hashtags = parseLabels(value.optJSONArray("hashtags"), "hashtag_id", null)
        return Story(
            id = id,
            source = Source.HEYBOX,
            title = displayTitle,
            excerpt = body.take(160),
            body = body,
            author = author,
            date =
                firstScalar(value, "create_str")
                    ?: value.optLong("create_at").takeIf { it > 0 }?.let(::formatDate).orEmpty(),
            image = cover,
            url =
                https(firstScalar(value, "share_url"))
                    ?: "https://www.xiaoheihe.cn/app/bbs/link/$id",
            publishedAt = value.optLong("create_at").takeIf { it > 0 },
            authorProfile = profile,
            sections = topics,
            hashtags = hashtags,
            feedVideo = cn.heyread.app.data.account.SharedArticleContentParser.publicVideo(value),
        )
    }

    private fun cleanSearchMarkup(value: String): String =
        org.jsoup.Jsoup.parseBodyFragment(value).text().trim()

    private fun parseProfile(value: JSONObject?): AuthorProfile? {
        if (value == null) return null
        val userId = firstScalar(value, "userid", "user_id", "heybox_id")
        val name =
            firstScalar(value, "username", "nickname") ?: if (userId != null) "" else return null
        val levelInfo = value.optJSONObject("level_info")
        val level =
            firstScalar(levelInfo, "level")?.let { if (it.startsWith("Lv", true)) it else "Lv.$it" }
        return AuthorProfile(
            name = name,
            avatarUrl = https(firstScalar(value, "avatar", "avatar_url", "avartar")),
            level = level,
            location = firstScalar(value, "ip_location"),
            userId = userId,
            bio = firstScalar(value, "signature"),
        )
    }

    private fun parseLabels(
        array: JSONArray?,
        idKey: String,
        imageKey: String?,
    ): List<ArticleLabel> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val id = firstScalar(row, idKey).orEmpty()
                val name = firstScalar(row, "name") ?: return@mapNotNull null
                ArticleLabel(id, name, imageKey?.let { https(firstScalar(row, it)) })
            }
            .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
    }

    private fun firstScalar(value: JSONObject?, vararg keys: String): String? =
        value?.let { obj -> keys.firstNotNullOfOrNull { nullableScalar(obj.opt(it)) } }

    private fun nullableScalar(value: Any?): String? =
        when (value) {
            null,
            JSONObject.NULL -> null
            is String -> value.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
            is Number -> value.toString()
            else -> null
        }

    private fun https(value: String?): String? = value?.takeIf { it.startsWith("https://", true) }

    private fun requiredArray(result: JSONObject, key: String): JSONArray {
        val value = result.opt(key)
        require(value is JSONArray) { "响应缺少有效的 $key 列表" }
        return value
    }

    private fun formatDate(timestamp: Long): String =
        java.time.Instant.ofEpochSecond(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"))
}

private val publicClient =
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .cache(null)
        .build()

internal suspend fun executePublicRequest(request: Request): String =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = publicClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        try {
                            response.use { result ->
                                if (!result.isSuccessful) error("HTTP ${result.code}")
                                val body = result.body?.string() ?: error("响应为空")
                                if (continuation.isActive) continuation.resume(body)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }
    }
