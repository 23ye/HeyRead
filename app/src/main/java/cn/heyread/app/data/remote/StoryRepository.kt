package cn.heyread.app.data.remote

import cn.heyread.app.data.account.SharedArticleContentParser
import cn.heyread.app.data.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class StoryRepository(private val gateway: () -> String, cacheDir: File) {
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .cache(okhttp3.Cache(cacheDir.resolve("http"), 8L * 1024 * 1024))
      .build()
  private val cursors = mutableMapOf<Int, Long>()
  private val signer = WebRequestSigner()

  suspend fun load(source: Source, channel: Channel, page: Int = 0, appId: Int = 570): StoryPage =
    withContext(Dispatchers.IO) {
      when (source) {
        Source.DEMO ->
          demos
            .drop(page * 10)
            .take(10)
            .filter { channel != Channel.COMMUNITY || it.id.endsWith("2") }
            .let { StoryPage(it, it.size == 10) }
        Source.STEAM -> steam(page, appId)
        Source.HEYBOX -> heybox(channel, page)
      }
    }

  private suspend fun steam(page: Int, app: Int): StoryPage {
    if (page == 0) cursors.remove(app)
    val enddate = cursors[app]
    val raw =
      get(
        "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/?appid=$app&count=10&maxlength=5000&feeds=steam_community_announcements&l=schinese${enddate?.let { "&enddate=$it" } ?: ""}"
      )
    val arr =
      JSONObject(raw).optJSONObject("appnews")?.optJSONArray("newsitems") ?: error("Steam 返回格式无效")
    val gameNames =
      mapOf(
        570 to "Dota 2",
        730 to "Counter-Strike 2",
        578080 to "PUBG",
        1086940 to "博德之门 3",
        1172470 to "Apex Legends",
      )
    val items =
      (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { n ->
          val text = org.jsoup.Jsoup.parse(n.optString("contents")).text()
          val id = n.optString("gid")
          val published = n.optLong("date").takeIf { it > 0L }
          published?.let { cursors[app] = minOf(cursors[app] ?: Long.MAX_VALUE, it - 1) }
          val date =
            published?.let { SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(it * 1000)) }
              ?: "Steam 社区"
          Story(
            "$app:$id",
            Source.STEAM,
            n.optString("title").ifBlank { "${gameNames[app] ?: "Steam"} 社区公告" },
            text.take(140).ifBlank { "查看 Steam 社区公告全文。" },
            text.ifBlank { "请打开原文查看公告。" },
            gameNames[app] ?: "Steam 社区",
            date,
            "https://cdn.akamai.steamstatic.com/steam/apps/$app/header.jpg",
            n.optString("url"),
            app,
            published,
          )
        }
      }
    return StoryPage(items, arr.length() >= 10)
  }

  private suspend fun heybox(channel: Channel, page: Int): StoryPage {
    val base = gateway().trimEnd('/').ifBlank { "https://api.xiaoheihe.cn" }
    require(base.startsWith("https://", true)) { "兼容网关必须使用 HTTPS" }
    val path = "/bbs/app/feeds"
    val params =
      linkedMapOf(
        "pull" to "0",
        "offset" to (page * 10).toString(),
        "limit" to "10",
        "dw" to "304",
        "os_type" to "web",
        "app" to "heybox",
        "x_client_type" to "web",
        "x_os_type" to "Mac",
        "x_app" to "heybox",
        "version" to "999.0.4",
      )
    val arr = HeyboxAdapter.validate(JSONObject(signedGet(base, path, params)))
    val items =
      arr.mapNotNull(::parseHeyboxFeedStory).filter {
        channel == Channel.RECOMMEND || it.channel == channel
      }
    return StoryPage(items, arr.size >= 10)
  }

  /** Loads the web application's public recommendation stream with its observed page size. */
  suspend fun loadRecommendedFeed(offset: Int, displayWidth: Int = 604): StoryPage =
    withContext(Dispatchers.IO) {
      val base = gateway().trimEnd('/').ifBlank { "https://api.xiaoheihe.cn" }
      require(base.startsWith("https://", true)) { "兼容网关必须使用 HTTPS" }
      val params = recommendedDynamicFeedParameters(offset, displayWidth)
      parseRecommendedDynamicFeedResponse(signedGet(base, "/bbs/app/feeds", params))
    }

  internal fun recommendedDynamicFeedParameters(
    offset: Int,
    displayWidth: Int,
  ): Map<String, String> {
    require(offset >= 0 && offset % 30 == 0) { "推荐动态分页游标无效" }
    require(displayWidth in 1..4096) { "推荐动态显示宽度无效" }
    return linkedMapOf(
      "pull" to "0",
      "offset" to offset.toString(),
      "dw" to displayWidth.toString(),
      "os_type" to "web",
      "app" to "heybox",
      "x_client_type" to "web",
      "x_os_type" to "Mac",
      "x_app" to "heybox",
      "version" to "999.0.4",
    )
  }

  internal fun parseRecommendedDynamicFeedResponse(raw: String): StoryPage {
    val response = JSONObject(raw)
    require(response.optString("status") == "ok") { "小黑盒推荐动态暂不可用，请稍后重试。" }
    val result = response.optJSONObject("result") ?: error("推荐动态响应缺少 result")
    val rows = result.optJSONArray("links") ?: error("推荐动态响应缺少 links")
    val items =
      (0 until rows.length())
        .asSequence()
        .mapNotNull(rows::optJSONObject)
        .filter { it.optInt("content_type", -1) != 18 }
        .mapNotNull(::parseHeyboxFeedStory)
        .toList()
    return StoryPage(items, rows.length() > 0)
  }

  private fun parseHeyboxFeedStory(obj: JSONObject): Story? {
    val id = obj.optString("link_id", obj.optString("linkid", obj.optString("id")))
    val body =
      obj.optString("description").ifBlank { obj.optString("content", obj.optString("text")) }
    val title = obj.optString("title").ifBlank { body.take(50) }
    val image =
      obj
        .optString("cover")
        .ifBlank {
          obj.optJSONArray("imgs")?.optJSONObject(0)?.optString("url")
            ?: obj.optJSONArray("imgs")?.optString(0)
            ?: ""
        }
        .ifBlank { null }
    if (id.isBlank() || title.isBlank()) return null
    val metadata = parseFeedMetadata(obj)
    val share =
      obj.optString("share_url").takeIf { it.startsWith("https://", true) }
        ?: "https://www.xiaoheihe.cn/app/bbs/link/$id"
    return Story(
      id,
      Source.HEYBOX,
      title,
      body.take(160),
      body,
      parseFeedAuthor(obj),
      parseFeedDate(obj),
      image,
      share,
      publishedAt = obj.optLong("create_at").takeIf { it > 0 },
      channel = if (obj.optInt("use_concept_type", 0) == 0) Channel.NEWS else Channel.COMMUNITY,
      hSrc = obj.optString("h_src").takeIf(String::isNotBlank),
      authorProfile = metadata.profile,
      sections = metadata.sections,
      hashtags = metadata.hashtags,
      feedVideo = SharedArticleContentParser.publicVideo(obj),
    )
  }

  suspend fun loadDetail(story: Story): Story =
    withContext(Dispatchers.IO) {
      if (story.source != Source.HEYBOX) return@withContext story
      val base = gateway().trimEnd('/').ifBlank { "https://api.xiaoheihe.cn" }
      require(base.startsWith("https://", true)) { "兼容网关必须使用 HTTPS" }
      val params =
        linkedMapOf(
          "link_id" to story.id,
          "pull" to "0",
          "dw" to "304",
          "os_type" to "web",
          "app" to "heybox",
          "x_client_type" to "web",
          "x_os_type" to "Mac",
          "x_app" to "heybox",
          "version" to "999.0.4",
        )
      val tree =
        try {
          JSONObject(signedGet(base, "/bbs/app/link/tree", params))
        } catch (error: kotlinx.coroutines.CancellationException) {
          throw error
        } catch (_: Exception) {
          null
        }
      val treeResult =
        tree
          ?.takeIf { response ->
            response.optString("status").equals("ok", true) ||
              response.optString("status").equals("success", true)
          }
          ?.optJSONObject("result")
      val treeLink = treeResult?.optJSONObject("link")
      val body = treeLink?.optJSONObject("body")
      val webParams =
        params.toMutableMap().apply {
          put("return_json", "1")
          put("in_topic", "1")
        }
      val webLink =
        try {
          val response = JSONObject(signedGet(base, "/bbs/app/link/web/view", webParams))
          if (
            !response.optString("status").equals("ok", true) &&
              !response.optString("status").equals("success", true)
          )
            null
          else response.optJSONObject("result")?.optJSONObject("link")
        } catch (error: kotlinx.coroutines.CancellationException) {
          throw error
        } catch (_: Exception) {
          null
        }
      val profile =
        parseProfile(body?.optJSONObject("user"), body?.optString("ip_location"))
          ?: parseProfile(webLink?.optJSONObject("poster"), webLink?.optString("ip_location"))
          ?: story.authorProfile
      val sections =
        parseLabels(webLink?.optJSONArray("topics"), "topic_id", "pic_url").ifEmpty {
          story.sections
        }
      val hashtags =
        parseLabels(webLink?.optJSONArray("hashtags"), "hashtag_id", "").ifEmpty { story.hashtags }
      val webBlocks = SharedArticleContentParser.parse(webLink?.optJSONArray("content"))
      val baseBlocks =
        if (webBlocks.isNotEmpty()) webBlocks
        else
          SharedArticleContentParser.fallback(
            treeLink ?: webLink ?: JSONObject(),
            body ?: JSONObject(),
          )
      val parsedBlocks =
        SharedArticleContentParser.withPublicVideo(baseBlocks, treeLink, body, webLink)
      val parsedBlocksWithFeedVideo =
        SharedArticleContentParser.withFeedVideo(parsedBlocks, story.feedVideo)
      val detailBlocks = parsedBlocksWithFeedVideo
      val hasReadableBlocks = detailBlocks.any(::isReadableArticleBlock)
      val bodyText =
        detailBlocks
          .flatMap { block ->
            when (block) {
              is cn.heyread.app.data.model.ArticleBlock.Paragraph -> listOf(block.text)
              is cn.heyread.app.data.model.ArticleBlock.ListItem ->
                listOf("${"  ".repeat(block.depth)}${block.marker} ${block.text}")
              is cn.heyread.app.data.model.ArticleBlock.Quote ->
                block.blocks
                  .filterIsInstance<cn.heyread.app.data.model.ArticleBlock.Paragraph>()
                  .map { it.text }
              else -> emptyList()
            }
          }
          .filter(String::isNotBlank)
          .joinToString("\n\n")
      val metadataStory =
        story.copy(
          author = profile?.name?.takeIf(String::isNotBlank) ?: story.author,
          authorProfile = profile,
          sections = sections,
          hashtags = hashtags,
          url = webLink?.optString("share_url")?.takeIf { it.startsWith("https://") } ?: story.url,
          body = bodyText.ifBlank { story.body },
          detailBlocks = detailBlocks.takeIf { it.isNotEmpty() },
          detailNotice = if (hasReadableBlocks) null else story.detailNotice,
        )
      val statusOk =
        tree?.optString("status").let { it.equals("ok", true) || it.equals("success", true) }
      val link =
        (if (statusOk) treeLink else null)
          ?: return@withContext metadataStory.copy(
            detailNotice = if (hasReadableBlocks) null else "暂时无法读取正文资料，已显示公开作者与分区信息。"
          )
      if (link.optInt("view_limit", 0) == 1)
        return@withContext metadataStory.copy(
          detailNotice = if (hasReadableBlocks) "当前显示官方公开正文，更多内容可打开原文查看。" else "该内容当前仅提供摘要，打开原文查看。"
        )
      if (!hasReadableBlocks)
        return@withContext metadataStory.copy(detailNotice = "暂未提供全文，当前展示列表摘要。请打开原文继续阅读。")
      metadataStory.copy(detailNotice = null)
    }

  private fun isReadableArticleBlock(block: cn.heyread.app.data.model.ArticleBlock): Boolean =
    when (block) {
      is cn.heyread.app.data.model.ArticleBlock.Paragraph -> block.text.isNotBlank()
      is cn.heyread.app.data.model.ArticleBlock.ListItem -> block.text.isNotBlank()
      is cn.heyread.app.data.model.ArticleBlock.Image -> true
      is cn.heyread.app.data.model.ArticleBlock.UnavailableImage ->
        block.caption.isNotBlank() || block.description.isNotBlank()
      is cn.heyread.app.data.model.ArticleBlock.Quote -> block.blocks.any(::isReadableArticleBlock)
      is cn.heyread.app.data.model.ArticleBlock.GameCard,
      is cn.heyread.app.data.model.ArticleBlock.Video,
      is cn.heyread.app.data.model.ArticleBlock.ExternalVideo,
      is cn.heyread.app.data.model.ArticleBlock.UnavailableVideo -> true
    }

  private fun parseProfile(user: JSONObject?, location: String?): AuthorProfile? {
    if (user == null) return null
    val name = firstScalar(user, "username", "nickname").orEmpty()
    val userId = firstScalar(user, "userid", "user_id", "heybox_id")
    if (name.isBlank() && userId == null) return null
    val avatar =
      user
        .optString("avatar")
        .cleanJson()
        .ifBlank { user.optString("avartar").cleanJson() }
        .ifBlank { user.optString("avatar_url").cleanJson() }
        .takeIf { it.startsWith("https://", true) }
    val level =
      user
        .optJSONObject("level_info")
        ?.let { info ->
          info.optString("level").cleanJson().ifBlank { info.optString("level_name").cleanJson() }
        }
        ?.takeIf(String::isNotBlank)
        ?.let { if (it.startsWith("Lv", true)) it else "Lv.$it" }
    return AuthorProfile(
      name,
      avatar,
      level,
      location?.cleanJson()?.takeIf(String::isNotBlank),
      userId,
    )
  }

  internal fun parseFeedAuthor(link: JSONObject): String {
    val profiles = listOfNotNull(link.optJSONObject("user"), link.optJSONObject("poster"))
    for (profile in profiles) {
      firstScalar(profile, "username", "nickname")?.let {
        return it
      }
    }
    return firstScalar(link, "username") ?: "作者未公开"
  }

  internal data class FeedMetadata(
    val profile: AuthorProfile?,
    val sections: List<ArticleLabel>,
    val hashtags: List<ArticleLabel>,
  )

  internal fun parseFeedMetadata(link: JSONObject): FeedMetadata {
    val directId = firstScalar(link, "userid")
    val user = link.optJSONObject("user") ?: link.optJSONObject("poster")
    val parsed = parseProfile(user, firstScalar(link, "ip_location"))
    val profile =
      parsed?.copy(userId = parsed.userId ?: directId)
        ?: directId?.let { AuthorProfile("", userId = it) }
    return FeedMetadata(
      profile,
      parseLabels(link.optJSONArray("topics"), "topic_id", "pic_url"),
      parseLabels(link.optJSONArray("hashtags"), "hashtag_id", ""),
    )
  }

  internal fun parseFeedDate(link: JSONObject): String {
    val display = link.optString("create_str").takeUnless { it.isBlank() || it == "null" }
    if (display != null) return display
    val timestamp = link.optLong("create_at").takeIf { it > 0L } ?: return "时间未公开"
    return runCatching {
        java.time.Instant.ofEpochSecond(timestamp)
          .atZone(java.time.ZoneId.systemDefault())
          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"))
      }
      .getOrDefault("时间未公开")
  }

  private fun parseLabels(
    array: org.json.JSONArray?,
    idKey: String,
    imageKey: String,
  ): List<ArticleLabel> {
    if (array == null) return emptyList()
    return (0 until array.length())
      .mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = firstScalar(item, "name") ?: return@mapNotNull null
        val id =
          item
            .opt(idKey)
            .let { value ->
              when (value) {
                is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                is Number -> value.toString()
                else -> null
              }
            }
            .orEmpty()
        val image =
          imageKey.takeIf(String::isNotBlank)?.let(item::optString)?.takeIf {
            it.startsWith("https://", true)
          }
        ArticleLabel(id, name, image)
      }
      .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
  }

  private fun firstScalar(value: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
      when (val field = value.opt(key)) {
        is String -> field.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
        is Number -> field.toString()
        else -> null
      }
    }

  private fun String.cleanJson(): String = takeUnless { it.equals("null", true) }.orEmpty()

  private suspend fun get(url: String): String {
    val request =
      Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "HeyRead/1.0 (Android)")
        .build()
    return await(request, "网络请求失败")
  }

  private suspend fun signedGet(base: String, path: String, params: Map<String, String>): String {
    val sig = signer.create(path)
    val builder = (base + path).toHttpUrl().newBuilder()
    params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    builder
      .addQueryParameter("_time", sig.time.toString())
      .addQueryParameter("nonce", sig.nonce)
      .addQueryParameter("hkey", sig.hkey)
    val request =
      Request.Builder()
        .url(builder.build())
        .header("Accept", "application/json")
        .header("Referer", "https://www.xiaoheihe.cn/")
        .header("User-Agent", "HeyRead/1.0 (Android)")
        .build()
    return await(request, "小黑盒请求失败")
  }

  private suspend fun await(request: Request, failure: String): String =
    suspendCancellableCoroutine { continuation ->
      val call = client.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : okhttp3.Callback {
          override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
          }

          override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            try {
              response.use { r ->
                if (!continuation.isActive) return
                if (!r.isSuccessful)
                  continuation.resumeWithException(IllegalStateException("$failure：HTTP ${r.code}"))
                else continuation.resume(r.body?.string() ?: "")
              }
            } catch (error: Exception) {
              if (continuation.isActive) continuation.resumeWithException(error)
            }
          }
        }
      )
    }

  suspend fun clearCache() = withContext(Dispatchers.IO) { client.cache?.evictAll() }

  companion object {
    val demos =
      listOf(
        Story(
          "demo-1",
          Source.DEMO,
          "一座城市，如何成为最好的冒险地图",
          "从巷口的招牌到远处的天际线，开放世界的魅力藏在每个转角。",
          "优秀的游戏世界不只是地图，它更像一座被时间磨亮的城市。玩家循着街角的光、风声和偶然相遇，慢慢读懂这里的生活。本文为盒阅离线演示文章，用于体验阅读、收藏与历史功能。",
          "盒阅编辑部",
          "演示内容 · 离线可读",
          "https://cdn.akamai.steamstatic.com/steam/apps/1172470/header.jpg",
          null,
          1172470,
        ),
        Story(
          "demo-2",
          Source.DEMO,
          "本周值得关注的三条游戏消息",
          "更新、开发者日志与社区故事，一份轻量游戏周报。",
          "第一条：关注你正在玩的游戏近期更新。第二条：开发者日志往往藏着下一阶段的方向。第三条：社区创作让一款游戏持续生长。此内容为本地演示，不代表实时资讯。",
          "盒阅编辑部",
          "演示内容 · 离线可读",
          "https://cdn.akamai.steamstatic.com/steam/apps/570/header.jpg",
          null,
          570,
        ),
        Story(
          "demo-3",
          Source.DEMO,
          "把收藏留给值得重读的内容",
          "一份安静的阅读清单，也可以成为你的游戏记忆。",
          "登录小黑盒后可查看账号收藏。浏览历史保存在本机，切换来源后会显示清晰的内容标记。",
          "盒阅编辑部",
          "演示内容 · 离线可读",
          null,
        ),
      )
  }
}
