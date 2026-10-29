package cn.heyread.app.data.account

import cn.heyread.app.data.media.MediaUrlPolicy
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Channel
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.WebRequestSigner
import cn.heyread.app.data.rich.OfficialEmojiCatalog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_QUOTE_DEPTH = 4
private const val MAX_CONTENT_JSON_DEPTH = 6
private const val MAX_CONTENT_JSON_NODES = 512
private val RECOGNIZED_BLOCK_TYPES =
  setOf(
    "text",
    "paragraph",
    "html",
    "img",
    "image",
    "game",
    "game_card",
    "video",
    "quote",
    "blockquote",
    "event_entry",
  )

fun interface AccountRequestExecutor {
  suspend fun execute(request: Request): String
}

class AccountRepository(
  initialCookieJar: OfficialCookieJar = OfficialCookieJar(),
  private val requestExecutor: AccountRequestExecutor? = null,
) {
  private data class Transport(val cookieJar: OfficialCookieJar, val client: OkHttpClient)

  private val signer = WebRequestSigner()
  @Volatile private var accountId: String = ""
  @Volatile private var transport = newTransport(initialCookieJar)

  suspend fun getQrChallenge(now: Long = System.currentTimeMillis()): QrChallenge {
    val body =
      JSONObject(get(QR_URL, mapOf("app" to "web", "heybox_id" to ""), accountContext = false))
    require(body.optString("status").isSuccessful()) { "二维码服务暂不可用" }
    val result = body.optJSONObject("result") ?: error("二维码服务没有返回内容")
    val qrUrl = result.optString("qr_url")
    require(qrUrl.startsWith("https://", true)) { "二维码链接无效" }
    val parsedQrUrl = qrUrl.toHttpUrl()
    require(parsedQrUrl.host.equals(OfficialCookieJar.API_HOST, true)) { "二维码服务返回了非官方域名" }
    val key =
      parsedQrUrl.queryParameter("qr")?.takeIf(String::isNotBlank)
        ?: result.optString("qr").takeIf(String::isNotBlank)
        ?: error("二维码响应缺少校验参数")
    val expirySeconds = result.optLong("expire", 300L).coerceIn(1L, 300L)
    return QrChallenge(qrUrl, key, now + expirySeconds * 1000L)
  }

  suspend fun poll(challenge: QrChallenge): QrPollResult {
    if (System.currentTimeMillis() >= challenge.expiresAtMillis) {
      transport.cookieJar.clear()
      return QrPollResult.Rejected("二维码已过期，请重新获取")
    }
    val body =
      JSONObject(
        get(QR_STATE, mapOf("app" to "web", "qr" to challenge.key), accountContext = false)
      )
    if (!body.optString("status").isSuccessful()) {
      return QrPollResult.Rejected(safeMessage(body.optString("msg")))
    }
    val result = body.optJSONObject("result") ?: return QrPollResult.Waiting
    val state = QrStateParser.parse(body.optString("status"), result.optString("error"))
    return state ?: parseAuthenticatedSession(result)
  }

  internal fun parseAuthenticatedSession(result: JSONObject): QrPollResult {
    val activeJar = transport.cookieJar
    val account =
      result.optJSONObject("account_detail") ?: result.optJSONObject("user") ?: JSONObject()
    firstValue(result, "cookie", "Cookie")
      .takeIf(String::isNotBlank)
      ?.let(activeJar::mergeExplicitCookieHeader)
    val explicitPkey =
      firstValue(result, "pkey", "user_pkey", "x_pkey").ifBlank {
        firstValue(account, "pkey", "user_pkey", "x_pkey")
      }
    if (explicitPkey.isNotBlank()) {
      activeJar.mergeExplicitCookieHeader("pkey=$explicitPkey; Secure; Path=/")
    }
    val cookieHeader = activeJar.cookieHeader()
    val hasAuthPkey =
      cookieHeader.split(';').any {
        it.substringBefore('=').trim() in setOf("pkey", "user_pkey", "x_pkey") &&
          it.substringAfter('=', "").isNotBlank()
      }
    val cookieValues =
      cookieHeader
        .split(';')
        .mapNotNull { part ->
          val split = part.indexOf('=')
          if (split <= 0) null
          else part.substring(0, split).trim() to part.substring(split + 1).trim()
        }
        .toMap()
    val userId =
      firstValue(result, "heyboxid", "userid", "heybox_id", "user_heybox_id", "x_heybox_id")
        .ifBlank {
          firstValue(account, "heyboxid", "userid", "heybox_id", "user_heybox_id", "x_heybox_id")
        }
        .ifBlank {
          listOf("heybox_id", "user_heybox_id", "x_heybox_id", "userid", "heyboxid")
            .firstNotNullOfOrNull(cookieValues::get)
            .orEmpty()
        }
    if (!hasAuthPkey || userId.isBlank()) {
      transport.cookieJar.clear()
      return QrPollResult.Rejected("服务未返回完整的账号凭据，请重新扫码")
    }
    return QrPollResult.LoggedIn(
      AccountSession(
        cookieHeader = normalizedAuthCookie(cookieHeader, explicitPkey, userId),
        userId = userId,
        nickname =
          firstValue(result, "nickname", "username").ifBlank {
            firstValue(account, "nickname", "username")
          },
        avatarUrl =
          firstValue(result, "avatar", "avatar_url").ifBlank {
            firstValue(account, "avatar", "avatar_url")
          },
      )
    )
  }

  @Synchronized
  fun clearEphemeralCookies() {
    val previous = transport
    transport = newTransport(OfficialCookieJar())
    accountId = ""
    previous.client.dispatcher.cancelAll()
  }

  @Synchronized
  fun restoreSession(cookieHeader: String, userId: String) {
    accountId = userId
    transport.cookieJar.restoreSessionCookieHeader(cookieHeader)
  }

  @Synchronized
  fun activateSession(session: AccountSession) {
    accountId = session.userId
    transport.cookieJar.restoreSessionCookieHeader(session.cookieHeader)
  }

  suspend fun loadFullArticle(story: Story, page: Int = 1): FullArticle {
    require(story.source == Source.HEYBOX) { "账号全文只适用于小黑盒文章" }
    val tree =
      successfulJson(
        TREE_V2,
        linkedMapOf(
            "link_id" to story.id,
            "page" to page.toString(),
            "limit" to "20",
            "is_first" to if (page == 1) "1" else "0",
            "sort_filter" to "hot",
            "owner_only" to "0",
            "hide_cy" to "0",
            "check_commented" to "0",
          )
          .apply { story.hSrc?.takeIf(String::isNotBlank)?.let { put("h_src", it) } },
      )
    val result = tree.optJSONObject("result") ?: JSONObject()
    val link = result.optJSONObject("link") ?: JSONObject()
    val webLink =
      try {
        successfulJson(
            WEB_VIEW,
            linkedMapOf("link_id" to story.id, "return_json" to "1", "in_topic" to "1"),
          )
          .optJSONObject("result")
          ?.optJSONObject("link") ?: JSONObject()
      } catch (error: AccountSessionExpiredException) {
        throw error
      } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
      } catch (_: Exception) {
        JSONObject()
      }
    val body = link.optJSONObject("body") ?: JSONObject()
    val blocks = parseArticleBlocks(webLink.optJSONArray("content"))
    val fallback =
      if (hasReadableContent(blocks)) emptyList() else fallbackArticleBlocks(link, body)
    val articleContent = blocks.ifEmpty { fallback }
    val blocksWithVideo =
      withFeedVideo(withPublicVideo(articleContent, link, body, webLink), story.feedVideo)
    val authorProfile =
      parseProfile(body.optJSONObject("user"), body.optString("ip_location"))
        ?: parseProfile(webLink.optJSONObject("poster"), webLink.optString("ip_location"))
        ?: story.authorProfile
    val sections =
      parseLabels(webLink.optJSONArray("topics"), listOf("topic_id", "id"), "pic_url").ifEmpty {
        story.sections
      }
    val hashtags =
      parseLabels(webLink.optJSONArray("hashtags"), listOf("hashtag_id", "id"), "").ifEmpty {
        story.hashtags
      }
    val webContentAvailable = articleContent.isNotEmpty()
    val resolvedBlocks = blocksWithVideo
    val hasContent = hasReadableContent(resolvedBlocks)
    val finalStory =
      story.copy(
        body =
          resolvedBlocks.filterIsInstance<ArticleBlock.Paragraph>().joinToString("\n\n") {
            it.text
          },
        detailNotice = if (hasContent) null else "官方接口当前没有返回可读取的文章正文。",
        url = webLink.optString("share_url").takeIf { it.startsWith("https://") } ?: story.url,
        author = authorProfile?.name ?: story.author,
        authorProfile = authorProfile,
        sections = sections,
        hashtags = hashtags,
      )
    val commentObject = result.optJSONObject("comment")
    val commentArray = commentObject?.optJSONArray("comments")
    val commentConfig = commentObject?.optJSONObject("comment_config")
    val stats = link.optJSONObject("stats") ?: JSONObject()
    val root = tree.optJSONObject("root") ?: JSONObject()
    val hasMoreComments =
      commentConfig?.takeIf { it.has("has_more_floors") }?.optBoolean("has_more_floors")
        ?: ((commentArray?.length() ?: 0) >= PAGE_SIZE)
    return FullArticle(
      story = finalStory,
      blocks = resolvedBlocks,
      comments = parseComments(commentArray),
      page = page,
      hasMoreComments = hasMoreComments,
      restrictedNotice = if (!webContentAvailable) "暂时无法获取完整内容，请打开原文继续阅读。" else null,
      commentsError = if (commentArray == null) "评论暂不可用" else null,
      isFavorited = stats.optBooleanOrNull("is_favour"),
      isLiked =
        stats.optBooleanOrNull("is_award_link")
          ?: stats.optBooleanOrNull("is_award")
          ?: stats.optBooleanOrNull("is_liked"),
      likeCount =
        stats.optIntOrNull("link_award_num")
          ?: stats.optIntOrNull("award_num")
          ?: stats.optIntOrNull("like_num"),
      commentCount = stats.optIntOrNull("comment_num") ?: link.optIntOrNull("comment_num"),
      authCode =
        firstValue(link, "auth_code")
          .ifBlank { firstValue(result, "auth_code") }
          .ifBlank { firstValue(root, "auth_code") }
          .takeIf(String::isNotBlank),
      authorProfile = authorProfile,
      sections = sections,
      hashtags = hashtags,
      authorFollowed = parseFollowStatus(stats.opt("follow_status")),
    )
  }

  suspend fun loadAuthorFollowStatus(story: Story, authorUserId: String): Boolean {
    require(story.source == Source.HEYBOX)
    require(authorUserId.isNotBlank())
    val params = linkedMapOf("link_id" to story.id, "return_json" to "1", "in_topic" to "1")
    story.hSrc?.takeIf(String::isNotBlank)?.let { params["h_src"] = it }
    val body = successfulJson(WEB_VIEW, params)
    val result = body.optJSONObject("result") ?: error("缺少作者关系信息")
    val poster = result.optJSONObject("link")?.optJSONObject("poster") ?: error("缺少文章作者信息")
    val returnedUserId = firstValue(poster, "userid", "user_id", "heybox_id")
    require(returnedUserId == authorUserId) { "返回的作者与当前文章不一致" }
    return parseFollowStatus(result.optJSONObject("current_user")?.opt("follow_status"))
      ?: error("官方暂未返回关注关系")
  }

  suspend fun followUser(authorUserId: String, hSrc: String?) {
    require(authorUserId.isNotBlank())
    write(FOLLOW_USER, mapOf("following_id" to authorUserId), hSrc)
  }

  suspend fun cancelFollowUser(authorUserId: String, hSrc: String?) {
    require(authorUserId.isNotBlank())
    write(CANCEL_FOLLOW_USER, mapOf("following_id" to authorUserId), hSrc)
  }

  suspend fun supportComment(commentId: String, supported: Boolean, hSrc: String?) {
    require(commentId.isNotBlank())
    write(
      COMMENT_SUPPORT,
      mapOf("comment_id" to commentId, "support_type" to if (supported) "1" else "2"),
      hSrc,
    )
  }

  suspend fun loadCommentsPage(story: Story, page: Int): CommentPage {
    val body =
      successfulJson(
        TREE_V2,
        linkedMapOf(
            "link_id" to story.id,
            "page" to page.toString(),
            "limit" to PAGE_SIZE.toString(),
            "is_first" to if (page == 1) "1" else "0",
            "sort_filter" to "hot",
            "owner_only" to "0",
            "hide_cy" to "0",
            "check_commented" to "0",
          )
          .apply { story.hSrc?.takeIf(String::isNotBlank)?.let { put("h_src", it) } },
      )
    val comment = body.optJSONObject("result")?.optJSONObject("comment")
    val array = comment?.optJSONArray("comments")
    val config = comment?.optJSONObject("comment_config")
    val hasMore =
      config?.takeIf { it.has("has_more_floors") }?.optBoolean("has_more_floors")
        ?: ((array?.length() ?: 0) >= PAGE_SIZE)
    return CommentPage(parseComments(array), hasMore)
  }

  suspend fun loadSubComments(rootCommentId: String, lastValue: String? = null): CommentPage {
    val params = linkedMapOf("root_comment_id" to rootCommentId, "hide_cy" to "0")
    lastValue?.takeIf(String::isNotBlank)?.let { params["lastval"] = it }
    val body = successfulJson(SUB_COMMENTS, params)
    val result = body.optJSONObject("result") ?: JSONObject()
    val array = findKnownArray(result, listOf("comments", "comment", "list", "replies", "items"))
    return CommentPage(
      comments = parseCommentRows(array, rootCommentId),
      hasMore = result.optBoolean("has_more", false),
      lastValue = result.optString("lastval").takeIf(String::isNotBlank),
    )
  }

  suspend fun loadFollowedActivity(
    expectedUserId: String,
    offset: Int,
    lastValue: String?,
  ): FollowedActivityPage {
    require(expectedUserId.isNotBlank()) { "登录后才能读取关注动态。" }
    require(offset >= 0 && offset % FOLLOWED_ACTIVITY_PAGE_SIZE == 0) { "关注动态分页游标无效。" }
    val (requestUserId, cookieSnapshot, activeTransport) =
      synchronized(this) {
        if (accountId.isBlank()) throw AccountSessionRequiredException()
        if (accountId != expectedUserId) throw AccountSessionChangedException()
        Triple(accountId, transport.cookieJar.cookieHeader(), transport)
      }
    val hasPkey =
      cookieSnapshot.split(';').any {
        it.substringBefore('=').trim() in setOf("pkey", "user_pkey", "x_pkey") &&
          it.substringAfter('=', "").isNotBlank()
      }
    if (!hasPkey) throw AccountSessionRequiredException()
    val parameters =
      linkedMapOf("offset" to offset.toString(), "limit" to FOLLOWED_ACTIVITY_PAGE_SIZE.toString())
        .apply { lastValue?.takeIf(String::isNotEmpty)?.let { put("lastval", it) } }
    val requestCookieJar = OfficialCookieJar().also { it.restoreSessionCookieHeader(cookieSnapshot) }
    val requestTransport =
      Transport(
        requestCookieJar,
        activeTransport.client.newBuilder().cookieJar(requestCookieJar).build(),
      )
    val signature = signer.create(FOLLOWED_EVENTS)
    val request =
      Request.Builder()
        .url(signedUrl(FOLLOWED_EVENTS, parameters, signature, accountUserId = requestUserId))
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .header("Referer", "https://www.xiaoheihe.cn/")
        .header("User-Agent", "HeyRead/1.1 (Android)")
        .build()
    val raw =
      try {
        execute(request, requestTransport)
      } finally {
        requestCookieJar.clear()
      }
    return withContext(Dispatchers.IO) {
      val body = JSONObject(raw)
      requireBusinessSuccess(body)
      val result = body.optJSONObject("result") ?: error("关注动态响应缺少 result")
      when (val loginCardValue = result.opt("login_card")) {
        null,
        JSONObject.NULL -> Unit
        is JSONObject ->
          if (loginCardValue.length() > 0) throw AccountFollowingRestrictedException()
        else -> error("关注动态响应 login_card 格式无效")
      }
      val moments = result.optJSONArray("moments") ?: error("关注动态响应缺少 moments")
      val stories =
        (0 until moments.length())
          .mapNotNull { index -> normalizePost(moments.optJSONObject(index), depth = 0) }
          .distinctBy { "${it.source}:${it.id}" }
      val nextLastValue = result.opt("lastval") as? String
      FollowedActivityPage(stories, moments.length(), nextLastValue)
    }
  }

  suspend fun loadFollowedUsers(expectedUserId: String): FollowedUsersPage {
    require(expectedUserId.isNotBlank()) { "登录后才能读取关注用户。" }
    val (requestUserId, cookieSnapshot, activeTransport) =
      synchronized(this) {
        if (accountId.isBlank()) throw AccountSessionRequiredException()
        if (accountId != expectedUserId) throw AccountSessionChangedException()
        Triple(accountId, transport.cookieJar.cookieHeader(), transport)
      }
    val hasPkey =
      cookieSnapshot.split(';').any {
        it.substringBefore('=').trim() in setOf("pkey", "user_pkey", "x_pkey") &&
          it.substringAfter('=', "").isNotBlank()
      }
    if (!hasPkey) throw AccountSessionRequiredException()

    val requestCookieJar =
      OfficialCookieJar().also { it.restoreSessionCookieHeader(cookieSnapshot) }
    val requestTransport =
      Transport(
        requestCookieJar,
        activeTransport.client.newBuilder().cookieJar(requestCookieJar).build(),
      )
    val signature = signer.create(FOLLOWING_SIMPLE_LIST)
    val request =
      Request.Builder()
        .url(
          signedUrl(
            FOLLOWING_SIMPLE_LIST,
            mapOf("userid" to requestUserId),
            signature,
            accountUserId = requestUserId,
          )
        )
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .header("Referer", "https://www.xiaoheihe.cn/")
        .header("User-Agent", "HeyRead/1.1 (Android)")
        .build()
    val raw =
      try {
        execute(request, requestTransport)
      } finally {
        requestCookieJar.clear()
      }
    return withContext(Dispatchers.IO) {
      val body = JSONObject(raw)
      requireBusinessSuccess(body)
      val result = body.optJSONObject("result") ?: error("关注用户响应缺少 result")
      val rows = result.optJSONArray("follow_list") ?: error("关注用户响应缺少 follow_list")
      val users =
        (0 until rows.length()).mapNotNull { index ->
          val row = rows.optJSONObject(index) ?: return@mapNotNull null
          val userId = firstScalar(row, "userid") ?: return@mapNotNull null
          val displayName = firstScalar(row, "username", "display_name").orEmpty()
          val avatarUrl =
            firstScalar(row, "avartar")?.takeIf(::isHttps)
              ?: firstScalar(row, "avatar")?.takeIf(::isHttps)
          FollowedUser(
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            unread = strictInteger(row.opt("unread"))?.takeIf { it > 0 } ?: 0,
          )
        }.distinctBy(FollowedUser::userId)
      if (rows.length() > 0 && users.isEmpty()) error("关注用户响应没有有效用户")
      val hasMoreValue =
        if (result.has("has_more") && !result.isNull("has_more")) {
          strictInteger(result.opt("has_more")) ?: error("关注用户响应 has_more 格式无效")
        } else 0
      if (hasMoreValue < 0) error("关注用户响应 has_more 格式无效")
      FollowedUsersPage(users, hasMoreValue > 0)
    }
  }

  suspend fun loadHistoryAuthor(story: Story, expectedUserId: String): AuthorProfile {
    require(story.source == Source.HEYBOX && story.id.isNotBlank()) { "历史作者目标无效。" }
    require(expectedUserId.isNotBlank()) { "登录后才能读取历史作者资料。" }
    val (requestUserId, cookieSnapshot, activeTransport) =
      synchronized(this) {
        if (accountId.isBlank()) throw AccountSessionRequiredException()
        if (accountId != expectedUserId) throw AccountSessionChangedException()
        Triple(accountId, transport.cookieJar.cookieHeader(), transport)
      }
    val hasPkey =
      cookieSnapshot.split(';').any {
        it.substringBefore('=').trim() in setOf("pkey", "user_pkey", "x_pkey") &&
          it.substringAfter('=', "").isNotBlank()
      }
    if (!hasPkey) throw AccountSessionRequiredException()

    val requestCookieJar =
      OfficialCookieJar().also { it.restoreSessionCookieHeader(cookieSnapshot) }
    val requestTransport =
      Transport(
        requestCookieJar,
        activeTransport.client.newBuilder().cookieJar(requestCookieJar).build(),
      )
    val raw =
      try {
        val signature = signer.create(AUTHOR_INFO)
        val request =
          Request.Builder()
            .url(
              signedUrl(
                AUTHOR_INFO,
                mapOf("link_id" to story.id),
                signature,
                accountUserId = requestUserId,
              )
            )
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Referer", "https://www.xiaoheihe.cn/")
            .header("User-Agent", "HeyRead/1.1 (Android)")
            .build()
        execute(request, requestTransport)
      } finally {
        requestCookieJar.clear()
      }
    val body = JSONObject(raw)
    requireBusinessSuccess(body)
    val result = body.optJSONObject("result") ?: error("历史作者资料响应缺少 result。")
    val authorInfo = result.optJSONObject("author_info") ?: error("历史作者资料响应缺少 author_info。")
    val name = firstStringValue(authorInfo, "username") ?: error("历史作者资料没有有效姓名。")
    val profile =
      parseProfile(authorInfo, firstStringValue(authorInfo, "ip_location"))
        ?: error("历史作者资料无效。")
    return profile.copy(
      name = name,
      avatarUrl = firstHttpsString(authorInfo, "avartar", "avatar"),
    )
  }

  suspend fun loadFavoriteFolders(): List<CloudFolder> {
    val body = successfulJson(FAVORITE_TABS, emptyMap())
    val result = body.optJSONObject("result") ?: JSONObject()
    val array =
      findKnownArray(
        result,
        listOf(
          "tab_list",
          "tabs",
          "folders",
          "folder_list",
          "fav_folders",
          "favorite_folders",
          "collect_folders",
          "collections",
          "list",
          "items",
          "data",
        ),
      )
    return (0 until (array?.length() ?: 0)).mapNotNull { index ->
      array
        ?.optJSONObject(index)
        ?.let { unwrapFolder(it) }
        ?.let { folder ->
          val id =
            firstValue(
              folder,
              "folder_id",
              "folderid",
              "fav_folder_id",
              "collect_folder_id",
              "collection_id",
              "id",
              "fid",
            )
          val name =
            firstValue(folder, "folder_name", "name", "title", "tab_name", "label").ifBlank {
              "收藏夹"
            }
          id.takeIf(String::isNotBlank)?.let { CloudFolder(it, name) }
        }
    }
  }

  suspend fun loadCloudFavorites(folderId: String?, page: Int): CloudListPage {
    val params =
      linkedMapOf(
        "offset" to (page * PAGE_SIZE).toString(),
        "limit" to PAGE_SIZE.toString(),
        "enable_new_style_collect" to "1",
      )
    folderId?.let { params["folder_id"] = it }
    val body = successfulJson(FAVORITE_LINKS, params)
    return parseCloudPage(body)
  }

  suspend fun loadCloudHistory(page: Int): CloudListPage =
    parseCloudPage(
      successfulJson(
        HISTORY,
        mapOf(
          "type" to "link",
          "offset" to (page * PAGE_SIZE).toString(),
          "limit" to PAGE_SIZE.toString(),
        ),
      )
    )

  suspend fun setCloudFavorite(story: Story, saved: Boolean) {
    require(story.source == Source.HEYBOX)
    write(
      FAVOUR,
      mapOf("link_id" to story.id, "favour_type" to if (saved) "1" else "2"),
      story.hSrc,
    )
  }

  suspend fun setLiked(story: Story, liked: Boolean) {
    require(story.source == Source.HEYBOX)
    write(
      AWARD_LINK,
      mapOf("link_id" to story.id, "award_type" to if (liked) "1" else "0"),
      story.hSrc,
    )
  }

  suspend fun createComment(
    story: Story,
    text: String,
    authCode: String?,
    replyTarget: ReplyTarget = ReplyTarget.MainComment,
  ) {
    require(story.source == Source.HEYBOX)
    val cleanText = text.trim()
    require(cleanText.isNotEmpty()) { "评论内容不能为空" }
    require(
      (replyTarget.rootCommentId == null && replyTarget.replyCommentId == null) ||
        (!replyTarget.rootCommentId.isNullOrBlank() && !replyTarget.replyCommentId.isNullOrBlank())
    ) {
      "回复目标不完整"
    }
    val formBuilder =
      FormBody.Builder()
        .add("link_id", story.id)
        .add("text", cleanText)
        .add("imgs", "")
        .add("is_cy", "0")
    if (replyTarget.isReply) {
      formBuilder
        .add("root_id", requireNotNull(replyTarget.rootCommentId))
        .add("reply_id", requireNotNull(replyTarget.replyCommentId))
    }
    val form = formBuilder.build()
    val params = buildMap {
      story.hSrc?.takeIf(String::isNotBlank)?.let { put("h_src", it) }
      authCode?.takeIf(String::isNotBlank)?.let { put("auth_code", it) }
    }
    writeForm(COMMENT_CREATE, params, form)
  }

  private suspend fun write(path: String, formValues: Map<String, String>, hSrc: String? = null) {
    val form =
      FormBody.Builder().apply { formValues.forEach { (key, value) -> add(key, value) } }.build()
    val params = hSrc?.takeIf(String::isNotBlank)?.let { mapOf("h_src" to it) } ?: emptyMap()
    writeForm(path, params, form)
  }

  private suspend fun writeForm(path: String, params: Map<String, String>, form: FormBody) {
    val response =
      execute(
        Request.Builder()
          .url(signedUrl(path, params, signer.create(path)))
          .post(form)
          .header("Accept", "application/json")
          .header("Cache-Control", "no-store")
          .header("Referer", "https://www.xiaoheihe.cn/")
          .header("User-Agent", "HeyRead/1.2 (Android)")
          .build()
      )
    requireBusinessSuccess(JSONObject(response))
  }

  suspend fun search(query: String, offset: Int, limit: Int = 20): SearchPage {
    require(query.isNotBlank()) { "搜索内容不能为空" }
    require(offset >= 0 && limit in 1..50)
    val body =
      successfulJson(
        SEARCH,
        mapOf(
          "q" to query.trim(),
          "search_type" to "link",
          "offset" to offset.toString(),
          "limit" to limit.toString(),
        ),
      )
    val result = body.optJSONObject("result") ?: error("搜索响应缺少结果")
    val rows = result.optJSONArray("items") ?: error("搜索响应缺少帖子列表")
    val stories =
      (0 until rows.length())
        .mapNotNull { index ->
          val row = rows.optJSONObject(index) ?: return@mapNotNull null
          if (row.optString("type") != "link") return@mapNotNull null
          normalizePost(row.optJSONObject("info"))
        }
        .distinctBy { "${it.source}:${it.id}" }
    return SearchPage(
      stories = stories,
      rawCount = rows.length(),
      hasMore = result.optBoolean("has_more", result.optBoolean("hasMore", stories.isNotEmpty())),
    )
  }

  private suspend fun successfulJson(path: String, params: Map<String, String>): JSONObject {
    val body = JSONObject(get(path, params))
    requireBusinessSuccess(body)
    return body
  }

  private fun requireBusinessSuccess(body: JSONObject) {
    val status = body.optString("status")
    if (
      status.equals("login", true) ||
        status.equals("relogin", true) ||
        body.optString("msg").equals("login", true)
    ) {
      throw AccountSessionExpiredException()
    }
    if (!status.equals("ok", true) && !status.equals("success", true)) {
      val serverMessage = body.optString("msg").lowercase()
      val userMessage =
        when {
          serverMessage.contains("lack_token") ||
            serverMessage.contains("show_captcha") ||
            status.contains("lack_token", true) ||
            status.contains("show_captcha", true) -> "小黑盒要求额外验证，请在官方客户端完成验证后重试。"
          serverMessage.contains("name_verify") || status.contains("name_verify", true) ->
            "小黑盒要求完成实名认证后才能操作。"
          status == "429" || body.optInt("code") == 429 -> "操作过于频繁，请稍后重试。"
          else -> "小黑盒操作失败，请稍后重试。"
        }
      throw IllegalStateException(userMessage)
    }
  }

  private suspend fun parseCloudPage(body: JSONObject): CloudListPage {
    val result = body.optJSONObject("result") ?: JSONObject()
    val array =
      findKnownArray(
        result,
        listOf(
          "links",
          "list",
          "history_visit",
          "visits",
          "history",
          "items",
          "rows",
          "records",
          "favorites",
          "collects",
          "link_list",
          "links_list",
          "moments",
          "data",
        ),
      )
    val stories =
      (0 until (array?.length() ?: 0))
        .mapNotNull { index -> normalizePost(array?.optJSONObject(index), depth = 0) }
        .distinctBy { "${it.source}:${it.id}" }
    val hasMore =
      result.optBoolean(
        "has_more",
        result.optBoolean("hasMore", (array?.length() ?: 0) >= PAGE_SIZE),
      )
    return CloudListPage(stories, hasMore)
  }

  internal fun normalizePost(value: JSONObject?, depth: Int = 0): Story? {
    if (value == null || depth > 4) return null
    val wrapped =
      listOf(
          "link",
          "link_info",
          "link_detail",
          "moment",
          "post",
          "favorite",
          "fav",
          "record",
          "target",
          "source",
          "obj",
          "content",
          "data",
          "item",
        )
        .firstNotNullOfOrNull { value.optJSONObject(it) }
    if (wrapped != null) return normalizePost(wrapped, depth + 1)
    val id = firstValue(value, "linkid", "link_id", "linkId", "id", "post_id", "moment_id")
    if (id.isBlank()) return null
    val userCandidates =
      listOfNotNull(
        value.optJSONObject("user"),
        value.optJSONObject("poster"),
        value.optJSONObject("user_info"),
      )
    val user =
      userCandidates.firstOrNull {
        firstStringValue(it, "username", "nickname", "display_name") != null
      }
        ?: userCandidates.firstOrNull {
          firstHttpsString(it, "avatar", "avartar", "avatar_url") != null ||
            firstValue(it, "userid", "user_id", "heybox_id", "userId").isNotBlank()
        }
    val text = firstValue(value, "description", "text", "content", "body", "summary", "abstract")
    val title =
      org.jsoup.Jsoup.parse(firstValue(value, "title", "link_title")).text().ifBlank {
        org.jsoup.Jsoup.parse(text).text().take(48)
      }
    if (title.isBlank()) return null
    val image =
      firstHttpsString(value, "cover")
        ?: firstHttpsString(value, "img", "image")
        ?: firstCloudImage(value)
    val date =
      firstValue(value, "create_str", "date").ifBlank {
        value.optLong("create_at").takeIf { it > 0 }?.let(::formatDate).orEmpty()
      }
    val author = value.optJSONObject("author")
    val userName = firstStringValue(user ?: JSONObject(), "username", "nickname", "display_name")
    val postName = firstStringValue(value, "username")
    val authorNameFallback = firstStringValue(author ?: JSONObject(), "nickname")
    val userAvatar = firstHttpsString(user ?: JSONObject(), "avatar", "avartar", "avatar_url")
    val authorAvatarFallback = firstHttpsString(author ?: JSONObject(), "avatar")
    val authorFallbackUsed =
      userName == null &&
        postName == null &&
        (authorNameFallback != null || authorAvatarFallback != null)
    val authorName = userName ?: postName ?: authorNameFallback ?: "作者未公开"
    val authorAvatar = if (authorFallbackUsed) authorAvatarFallback else userAvatar
    val authorUserId =
      if (authorFallbackUsed) null
      else
        firstValue(user ?: JSONObject(), "userid", "user_id", "heybox_id", "userId")
          .ifBlank { firstValue(value, "userid", "user_id", "heybox_id") }
          .takeIf(String::isNotBlank)
    val authorProfile =
      if (authorName != "作者未公开" || authorUserId != null || authorAvatar != null) {
        AuthorProfile(
          name = authorName,
          avatarUrl = authorAvatar,
          location = firstValue(value, "ip_location", "location").takeIf(String::isNotBlank),
          userId = authorUserId,
        )
      } else null
    return Story(
      id = id,
      source = Source.HEYBOX,
      title = title,
      excerpt = org.jsoup.Jsoup.parse(text).text().take(160),
      body = org.jsoup.Jsoup.parse(text).text(),
      author = authorName,
      date = date,
      image = image,
      url =
        value.optString("share_url").takeIf { it.startsWith("https://") }
          ?: "https://www.xiaoheihe.cn/app/bbs/link/$id",
      publishedAt = value.optLong("create_at").takeIf { it > 0 },
      channel = Channel.RECOMMEND,
      hSrc = value.optString("h_src").takeIf(String::isNotBlank),
      authorProfile = authorProfile,
      sections = parseActivityLabels(value.optJSONArray("topics"), "topic_id"),
      hashtags = parseActivityLabels(value.optJSONArray("hashtags"), "hashtag_id"),
      feedVideo = SharedArticleContentParser.publicVideo(value),
    )
  }

  private fun firstStringValue(json: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
      val candidate = json.opt(key) as? String ?: return@firstNotNullOfOrNull null
      candidate.trim().takeIf { it.isNotBlank() && it.lowercase() !in setOf("null", "undefined") }
    }

  private fun firstHttpsString(json: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
      val candidate = json.opt(key) as? String ?: return@firstNotNullOfOrNull null
      candidate.takeIf {
        it.isNotBlank() &&
          it.lowercase() !in setOf("null", "undefined") &&
          it.startsWith("https://", ignoreCase = true)
      }
    }

  private fun firstCloudImage(value: JSONObject): String? {
    for (key in listOf("imgs", "images", "image_list")) {
      val rows = value.optJSONArray(key) ?: continue
      for (index in 0 until rows.length()) {
        val item = rows.opt(index)
        val candidate =
          when (item) {
            is String -> item.takeIf(String::isNotBlank)
            is JSONObject -> firstHttpsString(item, "url", "src", "image_url")
            else -> null
          }
        candidate
          ?.takeIf { it.startsWith("https://", ignoreCase = true) }
          ?.let {
            return it
          }
      }
    }
    return null
  }

  private fun parseActivityLabels(array: JSONArray?, idKey: String): List<ArticleLabel> {
    if (array == null) return emptyList()
    return (0 until array.length())
      .mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = firstValue(item, "name").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val id = firstValue(item, idKey)
        val image =
          firstValue(item, "pic_url", "image_url").takeIf { it.startsWith("https://", true) }
        ArticleLabel(id, name, image)
      }
      .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
  }

  internal fun unwrapFolder(value: JSONObject, depth: Int = 0): JSONObject? {
    if (depth > 4) return null
    if (
      firstValue(
          value,
          "folder_id",
          "folderid",
          "fav_folder_id",
          "collect_folder_id",
          "collection_id",
          "id",
          "fid",
        )
        .isNotBlank()
    )
      return value
    val wrapped =
      listOf("folder", "folder_info", "fav_folder", "collect_folder", "collection", "item", "data")
        .firstNotNullOfOrNull { value.optJSONObject(it) }
    return wrapped?.let { unwrapFolder(it, depth + 1) }
  }

  internal fun findKnownArray(
    root: JSONObject?,
    keys: List<String>,
    depth: Int = 0,
  ): org.json.JSONArray? {
    if (depth > 5) return null
    if (root == null) return null
    keys.forEach { key ->
      root.optJSONArray(key)?.let {
        return it
      }
    }
    listOf(
        "data",
        "result",
        "list_data",
        "favorite_data",
        "history_data",
        "folder",
        "folder_info",
        "fav_folder",
        "collect_folder",
        "collection",
        "item",
      )
      .forEach { key ->
        root.optJSONObject(key)?.let {
          findKnownArray(it, keys, depth + 1)?.let { array ->
            return array
          }
        }
      }
    return null
  }

  internal fun parseArticleBlocks(content: org.json.JSONArray?): List<ArticleBlock> {
    if (content == null) return emptyList()
    data class ParsedRow(val type: String, val blocks: List<ArticleBlock>)
    fun ParsedRow.isAttachment(): Boolean = type == "img" || type == "image"

    val rows = mutableListOf<ParsedRow>()
    for (index in 0 until content.length()) {
      val item = content.optJSONObject(index) ?: continue
      val type = jsonText(item, "type", "block_type", "content_type").lowercase()
      if (type == "event_entry") continue
      val blocks =
        when (type) {
          "img",
          "image" -> listOfNotNull(parseImageBlock(item))
          "game",
          "game_card" -> listOfNotNull(parseGameCardBlock(item))
          "video" -> listOf(parseVideoBlock(item))
          "html" ->
            jsonText(item, "text", "html", "content")
              .takeIf(String::isNotBlank)
              ?.let(::htmlBlocks)
              .orEmpty()
          "text",
          "paragraph" ->
            jsonText(item, "text", "content", "value")
              .takeIf(String::isNotBlank)
              ?.let(::decodeTextPayload)
              .orEmpty()
          "quote",
          "blockquote" -> {
            val value = jsonText(item, "text", "html", "content")
            val children = if (value.isBlank()) emptyList() else decodeTextPayload(value)
            if (children.isEmpty()) emptyList() else listOf(ArticleBlock.Quote(children))
          }
          else -> {
            val nested =
              if (item.has("content") || item.has("blocks"))
                decodeStructuredContent(item, 0, intArrayOf(MAX_CONTENT_JSON_NODES))
              else null
            if (nested != null) nested
            else {
              // Preserve known text fields on unfamiliar wrappers without
              // stringifying JSON.
              val fallback = jsonText(item, "text", "html", "content", "description")
              if (fallback.isBlank()) emptyList() else decodeTextPayload(fallback)
            }
          }
        }
      rows += ParsedRow(type, blocks)
    }

    val attachments =
      rows.filter { it.isAttachment() }.flatMap { it.blocks.filterIsInstance<ArticleBlock.Image>() }
    val inlineImages =
      rows.filterNot { it.isAttachment() }.flatMap { row -> row.blocks.flatMap(::articleImages) }
    val inlineKeys = inlineImages.flatMap(::articleImageIdentityKeys).toSet()
    val supplementaryAttachments =
      attachments.filter { attachment ->
        articleImageIdentityKeys(attachment).any(inlineKeys::contains)
      }

    return rows.flatMap { row ->
      if (row.isAttachment()) {
        row.blocks.filterNot { block ->
          block is ArticleBlock.Image && articleImageIdentityKeys(block).any(inlineKeys::contains)
        }
      } else {
        row.blocks.map { block -> enrichInlineImages(block, supplementaryAttachments) }
      }
    }
  }

  private fun articleImages(block: ArticleBlock): List<ArticleBlock.Image> =
    when (block) {
      is ArticleBlock.Image -> listOf(block)
      is ArticleBlock.Quote -> block.blocks.flatMap(::articleImages)
      else -> emptyList()
    }

  /** Decodes only recognized, bounded block JSON. Ordinary text is never unescaped recursively. */
  internal fun decodeTextPayload(raw: String): List<ArticleBlock> {
    return decodeTextPayload(raw, 0, intArrayOf(MAX_CONTENT_JSON_NODES))
  }

  private fun decodeTextPayload(raw: String, depth: Int, remaining: IntArray): List<ArticleBlock> {
    if (depth > MAX_CONTENT_JSON_DEPTH || remaining[0] <= 0) return emptyList()
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val candidate = normalized.trimStart()
    if (candidate.startsWith("[") || candidate.startsWith("{")) {
      val parsed: Any? =
        when {
          candidate.startsWith("[") -> runCatching { JSONArray(candidate) }.getOrNull()
          else -> runCatching { JSONObject(candidate) }.getOrNull()
        }
      if (parsed != null) {
        val decoded = decodeStructuredContent(parsed, depth, remaining)
        if (decoded != null) return decoded
      }
    }
    if (HTML_START.containsMatchIn(candidate)) return htmlBlocks(normalized)
    return if (normalized.isBlank()) emptyList() else listOf(ArticleBlock.Paragraph(normalized))
  }

  private fun decodeStructuredContent(
    value: Any?,
    depth: Int,
    remaining: IntArray,
  ): List<ArticleBlock>? {
    if (depth > MAX_CONTENT_JSON_DEPTH || --remaining[0] < 0) return emptyList()
    if (value is JSONObject) {
      val nested = value.optJSONArray("content") ?: value.optJSONArray("blocks")
      if (nested != null) return decodeStructuredContent(nested, depth + 1, remaining)
      val type = jsonText(value, "type", "block_type", "content_type").lowercase()
      return when (type) {
        "event_entry" -> emptyList()
        "text",
        "paragraph" -> {
          val text = jsonText(value, "text", "value")
          if (text.isBlank()) emptyList() else decodeTextPayload(text, depth + 1, remaining)
        }
        "html" -> htmlBlocks(jsonText(value, "text", "html"))
        "img",
        "image" -> listOfNotNull(parseImageBlock(value))
        "game",
        "game_card" -> listOfNotNull(parseGameCardBlock(value))
        "video" -> listOf(parseVideoBlock(value))
        "quote",
        "blockquote" -> {
          val children =
            decodeTextPayload(jsonText(value, "text", "content", "html"), depth + 1, remaining)
          if (children.isEmpty()) emptyList() else listOf(ArticleBlock.Quote(children))
        }
        else -> null
      }
    }
    if (value is JSONArray) {
      val rows = mutableListOf<ArticleBlock>()
      var recognized = false
      for (index in 0 until value.length()) {
        if (--remaining[0] < 0) break
        when (val item = value.opt(index)) {
          is JSONObject -> {
            val type = jsonText(item, "type", "block_type", "content_type").lowercase()
            if (type in RECOGNIZED_BLOCK_TYPES) recognized = true
            val blocks = decodeStructuredContent(item, depth + 1, remaining)
            if (blocks != null) {
              recognized = true
              rows += blocks
            }
          }
          is JSONArray ->
            decodeStructuredContent(item, depth + 1, remaining)?.let {
              recognized = true
              rows.addAll(it)
            }
        }
      }
      return if (recognized) rows else null
    }
    return null
  }

  private fun enrichInlineImages(
    block: ArticleBlock,
    attachments: List<ArticleBlock.Image>,
  ): ArticleBlock =
    when (block) {
      is ArticleBlock.Image -> {
        val keys = articleImageIdentityKeys(block)
        val supplements =
          attachments.filter { attachment ->
            articleImageIdentityKeys(attachment).any(keys::contains)
          }
        supplements.fold(block) { image, supplement ->
          image.copy(
            description = image.description.ifBlank { supplement.description },
            originalUrl = image.originalUrl ?: supplement.originalUrl,
            caption = image.caption.ifBlank { supplement.caption },
            mimeType = image.mimeType ?: supplement.mimeType,
            animated = image.animated || supplement.animated,
          )
        }
      }
      is ArticleBlock.Quote ->
        block.copy(blocks = block.blocks.map { enrichInlineImages(it, attachments) })
      else -> block
    }

  private fun articleImageIdentityKeys(image: ArticleBlock.Image): Set<String> =
    listOfNotNull(image.url, image.originalUrl).mapNotNull(::articleImageIdentityKey).toSet()

  private fun articleImageIdentityKey(rawUrl: String): String? {
    val normalized = MediaUrlPolicy.originalOrPreview(rawUrl) ?: return null
    val url = normalized.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val host =
      when (url.host) {
        "imgheybox.max-c.com",
        "imgheybox1.max-c.com" -> "imgheybox.max-c.com"
        else -> url.host
      }
    return buildString {
      append(host)
      if (url.port != 443) append(':').append(url.port)
      append(url.encodedPath)
      url.encodedQuery?.let { append('?').append(it) }
      url.encodedFragment?.let { append('#').append(it) }
    }
  }

  internal fun fallbackArticleBlocks(link: JSONObject, body: JSONObject): List<ArticleBlock> {
    val blocks =
      sequenceOf(
          jsonText(link, "text", "content"),
          jsonText(body, "text", "content"),
          jsonText(link, "description"),
          jsonText(body, "description"),
        )
        .map { it.replace("\r\n", "\n").replace('\r', '\n') }
        .filter(String::isNotBlank)
        .map(::parseContentFallback)
        .firstOrNull(::hasReadableContent)
        .orEmpty()
    return withPublicVideo(blocks, link, body)
  }

  private fun parseContentFallback(raw: String): List<ArticleBlock> {
    val candidate = raw.trimStart()
    if (candidate.startsWith("[") || candidate.startsWith("{")) {
      val parsed: Any? =
        if (candidate.startsWith("[")) runCatching { JSONArray(candidate) }.getOrNull()
        else runCatching { JSONObject(candidate) }.getOrNull()
      if (parsed != null) {
        val decoded = decodeStructuredContent(parsed, 0, intArrayOf(MAX_CONTENT_JSON_NODES))
        if (decoded != null) return decoded
        return emptyList()
      }
    }
    return decodeTextPayload(raw)
  }

  internal fun withPublicVideo(
    blocks: List<ArticleBlock>,
    vararg sources: JSONObject?,
  ): List<ArticleBlock> {
    val candidates = sources.mapNotNull { it?.let(::parsePublicVideo) }
    return mergeVideoCandidates(blocks, candidates)
  }

  internal fun withFeedVideo(
    blocks: List<ArticleBlock>,
    video: ArticleBlock.Video?,
  ): List<ArticleBlock> = mergeVideoCandidates(blocks, listOfNotNull(video))

  private fun mergeVideoCandidates(
    blocks: List<ArticleBlock>,
    candidates: List<ArticleBlock.Video>,
  ): List<ArticleBlock> {
    if (candidates.isEmpty()) return blocks
    val existing = blocks.flatMap(::articleVideos)
    fun merge(first: ArticleBlock.Video, next: ArticleBlock.Video) =
      first.copy(
        url = if (next.postLevelVideo) next.url ?: first.url else first.url ?: next.url,
        thumbnailUrl =
          if (next.postLevelVideo) next.thumbnailUrl ?: first.thumbnailUrl
          else first.thumbnailUrl ?: next.thumbnailUrl,
        title = if (next.postLevelVideo) next.title ?: first.title else first.title ?: next.title,
        durationSeconds =
          if (next.postLevelVideo) next.durationSeconds ?: first.durationSeconds
          else first.durationSeconds ?: next.durationSeconds,
        width = if (next.postLevelVideo) next.width ?: first.width else first.width ?: next.width,
        height =
          if (next.postLevelVideo) next.height ?: first.height else first.height ?: next.height,
        postLevelVideo = first.postLevelVideo || next.postLevelVideo,
      )
    val valid = candidates.filter { it.url != null }
    val mergedCandidates =
      if (valid.isEmpty()) listOf(candidates.first())
      else
        valid.groupBy { it.url }.values.map { group -> group.drop(1).fold(group.first(), ::merge) }
    val remaining = mergedCandidates.toMutableList()
    fun mergeExisting(block: ArticleBlock): ArticleBlock =
      when (block) {
        is ArticleBlock.Video -> {
          val candidateIndex =
            remaining
              .indexOfFirst {
                if (block.url != null) it.url == block.url else block.sourcePlaceholder
              }
              .takeIf { it >= 0 }
              ?: remaining.indexOfFirst { block.postLevelVideo && it.postLevelVideo }
          if (candidateIndex < 0) block
          else {
            val combined = merge(block, remaining.removeAt(candidateIndex))
            combined.copy(sourcePlaceholder = combined.sourcePlaceholder && combined.url == null)
          }
        }
        is ArticleBlock.Quote -> block.copy(blocks = block.blocks.map(::mergeExisting))
        else -> block
      }
    val mapped = blocks.map(::mergeExisting)
    val additions =
      remaining.filter { candidate ->
        candidate.url == null || existing.none { it.url == candidate.url }
      }
    return mapped + additions
  }

  private fun articleVideos(block: ArticleBlock): List<ArticleBlock.Video> =
    when (block) {
      is ArticleBlock.Video -> listOf(block)
      is ArticleBlock.Quote -> block.blocks.flatMap(::articleVideos)
      else -> emptyList()
    }

  internal fun htmlBlocks(
    html: String,
    officialEmojiUrls: Map<String, String> = OfficialEmojiCatalog.entries.value,
  ): List<ArticleBlock> {
    val document = org.jsoup.Jsoup.parseBodyFragment(html)
    document.select("script,style,object,embed,form").remove()
    return htmlNodesToBlocks(
      document.body().childNodes(),
      0,
      officialEmojiUrls.entries.associate { it.value to it.key },
    )
  }

  private val HTML_START =
    Regex(
      "^</?(?:a|strong|b|em|i|code|video|iframe|section|article|p|br|div|span|img|figure|figcaption|blockquote|ul|ol|li|pre|h[1-6])\\b(?:\\s|/?>)",
      RegexOption.IGNORE_CASE,
    )

  private fun htmlNodesToBlocks(
    nodes: List<org.jsoup.nodes.Node>,
    quoteDepth: Int,
    emojiTokensByUrl: Map<String, String>,
    figureCaption: String? = null,
  ): List<ArticleBlock> {
    val result = mutableListOf<ArticleBlock>()
    val paragraph = StringBuilder()
    fun flush() {
      val text =
        paragraph
          .toString()
          .replace('\u00a0', '\uE001')
          .replace(Regex("[\\t\\n\\r\\f ]+"), " ")
          .replace('\uE000', '\n')
          .trim(' ', '\n')
          .replace('\uE001', '\u00a0')
      if (text.isNotBlank()) result += ArticleBlock.Paragraph(text)
      paragraph.clear()
    }
    fun walk(node: org.jsoup.nodes.Node, imageCaption: String? = figureCaption) {
      if (node is org.jsoup.nodes.TextNode) {
        paragraph.append(node.wholeText)
        return
      }
      val element =
        node as? org.jsoup.nodes.Element
          ?: run {
            node.childNodes().forEach { walk(it, imageCaption) }
            return
          }
      val tag = element.tagName().lowercase()
      if (tag in setOf("script", "style", "object", "embed", "form", "svg", "canvas")) return
      if (tag == "pre") {
        flush()
        val text = element.wholeText().replace("\r\n", "\n").replace('\r', '\n')
        if (text.isNotEmpty()) result += ArticleBlock.Paragraph(text)
        return
      }
      if (tag == "li") {
        flush()
        val depth =
          element.parents().count { it.tagName().lowercase() in setOf("ul", "ol") }.coerceIn(1, 4) -
            1
        val ordered = element.parent()?.tagName()?.equals("ol", ignoreCase = true) == true
        val ordinal = if (ordered) element.elementSiblingIndex() + 1 else 0
        val marker = if (ordered) "$ordinal." else "•"
        val inlineNodes = mutableListOf<org.jsoup.nodes.Node>()
        var emittedItemText = false
        fun flushListRun() {
          if (inlineNodes.isEmpty()) return
          val inlineBlocks =
            htmlNodesToBlocks(inlineNodes.toList(), quoteDepth, emojiTokensByUrl, imageCaption)
          inlineBlocks.forEach { child ->
            when (child) {
              is ArticleBlock.Paragraph ->
                if (child.text.isNotBlank())
                  result +=
                    ArticleBlock.ListItem(child.text, depth, if (emittedItemText) "" else marker)
                      .also { emittedItemText = true }
              else -> result += child
            }
          }
          inlineNodes.clear()
        }
        element.childNodes().forEach { child ->
          val nestedList = child as? org.jsoup.nodes.Element
          if (nestedList != null && nestedList.tagName().lowercase() in setOf("ul", "ol")) {
            flushListRun()
            walk(nestedList, imageCaption)
          } else inlineNodes += child
        }
        flushListRun()
        return
      }
      if (tag == "iframe") {
        flush()
        val source = firstHttps(element, "src")
        if (source != null && isKnownVideoHost(source))
          result += ArticleBlock.ExternalVideo(source, firstValue(element, "title"))
        else {
          val label = firstValue(element, "title", "aria-label").ifBlank { "嵌入内容未加载" }
          result += ArticleBlock.Paragraph(label)
        }
        return
      }
      if (tag == "video") {
        flush()
        val source =
          sequenceOf(element)
            .plus(element.select("source").asSequence())
            .mapNotNull { firstHttps(it, "src", "data-src") }
            .firstOrNull()
        val poster = firstHttps(element, "poster")
        result +=
          ArticleBlock.Video(
            url = source,
            thumbnailUrl = poster,
            title = firstValue(element, "title", "aria-label").ifBlank { null },
            durationSeconds = durationValue(element.attr("data-duration")),
            width = element.attr("width").toIntOrNull()?.takeIf { it > 0 },
            height = element.attr("height").toIntOrNull()?.takeIf { it > 0 },
          )
        return
      }
      if (tag == "figcaption") {
        flush()
        return
      }
      if (tag == "img") {
        val game = parseGameCardElement(element)
        if (game != null) {
          flush()
          result += game
          return
        }
        val image = parseImageElement(element, imageCaption)
        val emojiToken = (image as? ArticleBlock.Image)?.let { emojiTokensByUrl[it.url] }
        if (emojiToken != null) {
          paragraph.append(emojiToken)
        } else {
          flush()
          image?.let(result::add)
        }
        return
      }
      if (tag == "blockquote") {
        flush()
        if (quoteDepth < MAX_QUOTE_DEPTH) {
          val children =
            htmlNodesToBlocks(element.childNodes(), quoteDepth + 1, emojiTokensByUrl, imageCaption)
          if (children.isNotEmpty()) result += ArticleBlock.Quote(children)
        } else {
          val text = element.text().trim()
          if (text.isNotBlank()) result += ArticleBlock.Paragraph(text)
        }
        return
      }
      if (tag == "figure") {
        flush()
        val caption =
          element
            .children()
            .firstOrNull { it.tagName().equals("figcaption", true) }
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        var captionApplied = false
        element.childNodes().forEach { child ->
          if (child is org.jsoup.nodes.Element && child.tagName().equals("figcaption", true))
            return@forEach
          val childElement = child as? org.jsoup.nodes.Element
          val hasImage =
            childElement?.let { it.tagName() == "img" || it.selectFirst("img") != null } == true
          val childCaption =
            if (hasImage && !captionApplied) caption.also { captionApplied = true }
            else imageCaption
          walk(child, childCaption)
        }
        flush()
        return
      }
      if (tag == "h4" && element.classNames().contains("img-desc")) {
        flush()
        val caption = element.text().trim()
        val previous = result.lastOrNull() as? ArticleBlock.Image
        if (caption.isNotBlank() && previous != null && previous.caption.isBlank()) {
          result[result.lastIndex] = previous.copy(caption = caption)
          return
        }
      }
      if (tag == "br") {
        paragraph.append('\uE000')
        return
      }
      val block = tag in setOf("p", "div", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6")
      if (block) flush()
      element.childNodes().forEach { walk(it, imageCaption) }
      if (block) flush()
    }
    nodes.forEach(::walk)
    flush()
    return result
  }

  private fun parseImageBlock(value: JSONObject): ArticleBlock? {
    val preview = firstHttps(value, "url", "src", "image_url", "preview_url", "thumb")
    val original =
      firstHttps(value, "data-original", "original_url", "origin_url", "full_url", "raw_url")
    val mime = firstValue(value, "mime_type", "mimetype", "content_type")
    val description = jsonText(value, "alt", "title")
    val caption = jsonText(value, "caption", "image_caption", "figcaption", "description", "desc")
    val display = preview ?: original
    if (display == null) {
      return if (description.isNotBlank() || caption.isNotBlank())
        ArticleBlock.UnavailableImage(description, caption)
      else null
    }
    return ArticleBlock.Image(
      url = display,
      description = description,
      originalUrl = original,
      caption = caption,
      mimeType = mime.takeIf(String::isNotBlank),
      animated =
        isAnimatedImage(mime, display, original.orEmpty()) ||
          listOf("animated", "is_animated", "is_gif").any { key ->
            explicitAnimated(value.opt(key))
          },
    )
  }

  private fun parseGameCardBlock(value: JSONObject): ArticleBlock.GameCard? {
    val info =
      value.optJSONObject("app_info")
        ?: value.optJSONObject("game_info")
        ?: value.optJSONObject("game")
    val appId =
      listOfNotNull(
          scalarNumberOrString(info?.opt("steam_appid")),
          scalarNumberOrString(info?.opt("appid")),
          scalarNumberOrString(value.opt("steam_appid")),
          scalarNumberOrString(value.opt("appid")),
          scalarNumberOrString(value.opt("gameid")),
        )
        .firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }
    return appId?.let(ArticleBlock::GameCard)
  }

  private fun parseGameCardElement(element: org.jsoup.nodes.Element): ArticleBlock.GameCard? {
    val appId = element.attr("data-gameid").toIntOrNull()?.takeIf { it > 0 } ?: return null
    return ArticleBlock.GameCard(appId)
  }

  private fun parseVideoBlock(value: JSONObject): ArticleBlock.Video {
    val info = value.optJSONObject("video_info") ?: value.optJSONObject("video") ?: value
    return ArticleBlock.Video(
      url = firstHttps(info, "url", "video_url", "play_url", "src"),
      thumbnailUrl = firstHttps(info, "thumb", "video_thumb", "thumbnail", "poster"),
      title = jsonText(info, "title", "name").ifBlank { null },
      durationSeconds = durationValue(info.opt("duration") ?: info.opt("duration_seconds")),
      width = firstValue(info, "width").toIntOrNull()?.takeIf { it > 0 },
      height = firstValue(info, "height").toIntOrNull()?.takeIf { it > 0 },
    )
  }

  internal fun parsePublicVideo(value: JSONObject): ArticleBlock.Video? {
    val info = value.optJSONObject("video_info")
    val direct = firstHttps(value, "video_url")
    val nested = info?.let { firstHttps(it, "url", "video_url", "play_url") }
    val hasVideo = value.optInt("has_video", 0) == 1 || value.optBoolean("has_video", false)
    if (!hasVideo && direct == null && nested == null) return null
    val source = info ?: value
    return ArticleBlock.Video(
      url = direct ?: nested,
      thumbnailUrl =
        firstHttps(value, "video_thumb") ?: firstHttps(source, "thumb", "video_thumb", "thumbnail"),
      title = jsonText(source, "title", "name").ifBlank { null },
      durationSeconds =
        durationValue(value.opt("duration")) ?: durationValue(source.opt("duration")),
      width = firstValue(source, "width").toIntOrNull()?.takeIf { it > 0 },
      height = firstValue(source, "height").toIntOrNull()?.takeIf { it > 0 },
      sourcePlaceholder = hasVideo && direct == null && nested == null,
      postLevelVideo = true,
    )
  }

  private fun durationValue(value: Any?): Long? {
    val raw = scalarNumberOrString(value) ?: return null
    val seconds =
      raw.toLongOrNull()
        ?: run {
          val parts = raw.split(':')
          if (parts.size !in 2..3) return null
          parts
            .map { it.toLongOrNull()?.takeIf { number -> number >= 0 } ?: return null }
            .fold(0L) { total, part -> total * 60 + part }
        }
    return seconds.takeIf { it > 0 }
  }

  private fun scalarNumberOrString(value: Any?): String? =
    when (value) {
      is String -> value.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
      is Number -> value.toString()
      else -> null
    }

  private fun isKnownVideoHost(raw: String): Boolean {
    val host = runCatching { raw.toHttpUrl().host.lowercase() }.getOrNull() ?: return false
    return host in
      setOf(
        "www.youtube.com",
        "youtube.com",
        "youtu.be",
        "player.bilibili.com",
        "www.bilibili.com",
        "bilibili.com",
        "v.qq.com",
        "open.iqiyi.com",
        "player.youku.com",
      )
  }

  private fun parseImageElement(
    element: org.jsoup.nodes.Element,
    figureCaption: String?,
  ): ArticleBlock? {
    val original =
      firstHttps(element, "data-original", "data-full-url", "data-origin", "data-raw-url")
    val preview = firstHttps(element, "src", "data-src", "data-lazy-src")
    val mime = firstValue(element, "type", "data-mime-type")
    val caption =
      figureCaption?.takeIf(String::isNotBlank)
        ?: firstValue(element, "data-caption", "data-description", "caption", "description", "desc")
    val description = firstValue(element, "alt", "title")
    val display = preview ?: original
    if (display == null) {
      return if (description.isNotBlank() || caption.isNotBlank())
        ArticleBlock.UnavailableImage(description, caption)
      else null
    }
    return ArticleBlock.Image(
      url = display,
      description = description,
      originalUrl = original,
      caption = caption,
      mimeType = mime.takeIf(String::isNotBlank),
      animated =
        isAnimatedImage(mime, display, original.orEmpty()) ||
          listOf("data-animated", "data-is-animated", "data-is-gif").any { key ->
            explicitAnimated(element.attr(key))
          },
    )
  }

  private fun isAnimatedImage(mime: String, vararg urls: String): Boolean {
    val normalizedMime = mime.substringBefore(';').trim().lowercase()
    return normalizedMime == "image/gif" ||
      normalizedMime == "gif" ||
      normalizedMime.contains("animated") ||
      urls.any { it.substringBefore('?').substringBefore('#').endsWith(".gif", true) }
  }

  private fun explicitAnimated(value: Any?): Boolean =
    when (value) {
      is Boolean -> value
      is Number -> value.toInt() == 1
      is String -> value.trim().lowercase() in setOf("true", "1", "yes")
      else -> false
    }

  private fun jsonText(json: JSONObject, vararg keys: String): String =
    keys
      .firstNotNullOfOrNull { key ->
        if (!json.has(key) || json.isNull(key)) null
        else
          when (val value = json.opt(key)) {
            is String ->
              value.takeIf { it.isNotBlank() && it.lowercase() !in setOf("null", "undefined") }
            else -> null
          }
      }
      .orEmpty()

  private fun firstHttps(json: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
      val value = json.opt(key) as? String
      value?.takeIf(::isHttps)
    }

  private fun firstHttps(element: org.jsoup.nodes.Element, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> element.attr(key).takeIf(::isHttps) }

  private fun firstValue(element: org.jsoup.nodes.Element, vararg keys: String): String =
    keys
      .firstNotNullOfOrNull { key ->
        element.attr(key).takeIf {
          it.isNotBlank() && it.lowercase() !in setOf("null", "undefined")
        }
      }
      .orEmpty()

  internal fun parseCommentText(
    raw: String,
    officialEmojiUrls: Map<String, String> = OfficialEmojiCatalog.entries.value,
  ): String {
    val document = org.jsoup.Jsoup.parseBodyFragment(raw)
    document.select("script,style,iframe,object,embed,form").remove()
    val output = StringBuilder()
    fun walk(node: org.jsoup.nodes.Node) {
      when (node) {
        is org.jsoup.nodes.TextNode -> output.append(node.wholeText)
        is org.jsoup.nodes.Element -> {
          val tag = node.tagName().lowercase()
          when (tag) {
            "img" -> {
              val source = firstHttps(node, "src", "data-src", "data-original")
              val token =
                source?.let { url ->
                  officialEmojiUrls.entries.firstOrNull { it.value == url }?.key
                }
              output.append(token ?: firstValue(node, "alt", "title").ifBlank { "[图片]" })
            }
            "br" -> output.append('\n')
            else -> {
              val block = tag in setOf("p", "div", "li", "blockquote")
              if (block) output.append('\n')
              node.childNodes().forEach(::walk)
              if (block) output.append('\n')
            }
          }
        }
      }
    }
    document.body().childNodes().forEach(::walk)
    return output
      .toString()
      .replace('\u00a0', ' ')
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex("\\n{3,}"), "\n\n")
      .trim()
  }

  private fun hasReadableContent(blocks: List<ArticleBlock>): Boolean =
    blocks.any { block ->
      when (block) {
        is ArticleBlock.Paragraph -> block.text.isNotBlank()
        is ArticleBlock.ListItem -> block.text.isNotBlank()
        is ArticleBlock.Image -> true
        is ArticleBlock.UnavailableImage ->
          block.description.isNotBlank() || block.caption.isNotBlank()
        is ArticleBlock.Quote -> hasReadableContent(block.blocks)
        is ArticleBlock.GameCard,
        is ArticleBlock.Video,
        is ArticleBlock.ExternalVideo,
        is ArticleBlock.UnavailableVideo -> true
      }
    }

  internal fun parseComments(wrappers: org.json.JSONArray?): List<RemoteComment> =
    parseCommentRows(wrappers).distinctBy(RemoteComment::id)

  internal fun parseCommentRows(
    rows: org.json.JSONArray?,
    rootCommentId: String? = null,
  ): List<RemoteComment> {
    if (rows == null) return emptyList()
    val output = mutableListOf<RemoteComment>()
    for (index in 0 until rows.length()) {
      val wrapper = rows.optJSONObject(index) ?: continue
      val nested = wrapper.optJSONArray("comment")
      if (nested != null && nested.length() > 0) {
        val main = nested.optJSONObject(0) ?: continue
        val mainComment =
          commentFromJson(main).copy(rootCommentId = rootCommentId ?: commentFromJson(main).id)
        val replies =
          (1 until nested.length()).mapNotNull {
            nested.optJSONObject(it)?.let { row ->
              commentFromJson(row).copy(rootCommentId = mainComment.id)
            }
          }
        output += mainComment.copy(replies = replies)
      } else {
        val comment = commentFromJson(wrapper)
        output += comment.copy(rootCommentId = rootCommentId ?: comment.id)
      }
    }
    return output.distinctBy(RemoteComment::id)
  }

  private fun commentFromJson(value: JSONObject): RemoteComment {
    val user = value.optJSONObject("user") ?: JSONObject()
    val replyUser = value.optJSONObject("replyuser")
    return RemoteComment(
      id = firstValue(value, "commentid", "comment_id", "id"),
      author = firstValue(user, "username", "nickname").ifBlank { "小黑盒用户" },
      text = parseCommentText(jsonText(value, "text", "content", "description")),
      createdAt = value.optLong("create_at"),
      likes = value.optInt("up"),
      replyTo =
        replyUser?.let { firstValue(it, "username", "nickname") }?.takeIf(String::isNotBlank),
      childCount = value.optInt("child_num"),
      isSupported = parseCommentSupport(value),
      supportCount = firstInt(value, "comment_award_num", "award_num", "up"),
      images =
        parseCommentImages(
          value.optJSONArray("imgs")
            ?: value.optJSONArray("images")
            ?: value.optJSONArray("pictures")
        ),
      rootCommentId = firstValue(value, "root_id", "root_comment_id").takeIf(String::isNotBlank),
      profile = parseProfile(user, value.optString("ip_location")),
    )
  }

  internal fun parseProfile(user: JSONObject?, location: String? = null): AuthorProfile? {
    if (user == null) return null
    val name = firstScalar(user, "username", "nickname", "name").orEmpty()
    val userId = firstScalar(user, "userid", "user_id", "heybox_id")
    if (name.isBlank() && userId == null) return null
    val avatar =
      firstScalar(user, "avatar", "avatar_url", "avartar", "head_img").orEmpty().takeIf {
        it.startsWith("https://", true)
      }
    val levelInfo = user.optJSONObject("level_info") ?: JSONObject()
    val level =
      firstScalar(levelInfo, "level", "name", "level_name")?.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("Lv", true)) it else "Lv.$it"
      }
    return AuthorProfile(
      name,
      avatar,
      level,
      location?.takeIf { it.isNotBlank() && !it.equals("null", true) },
      userId,
      firstScalar(user, "signature"),
    )
  }

  internal fun parseCommentSupport(comment: JSONObject): Boolean? {
    if (comment.has("is_support") && !comment.isNull("is_support")) {
      return when (val value = comment.opt("is_support")) {
        is Number ->
          when (value.toInt()) {
            1 -> true
            2 -> false
            else -> null
          }
        is Boolean -> value
        is String ->
          when (value.trim().lowercase()) {
            "1",
            "true" -> true
            "2",
            "false" -> false
            else -> null
          }
        else -> null
      }
    }
    for (field in listOf("is_supported", "is_liked", "is_up")) {
      if (!comment.has(field) || comment.isNull(field)) continue
      when (val value = comment.opt(field)) {
        is Boolean -> return value
        is Number -> if (value.toInt() == 1) return true else if (value.toInt() == 0) return false
        is String ->
          when (value.trim().lowercase()) {
            "1",
            "true" -> return true
            "0",
            "false" -> return false
          }
      }
    }
    return null
  }

  private fun parseCommentImages(array: org.json.JSONArray?): List<CommentImage> {
    if (array == null) return emptyList()
    return (0 until array.length())
      .mapNotNull { index ->
        val row = array.optJSONObject(index)
        val url =
          row?.let { firstHttps(it, "url", "image_url", "src", "original_url", "original") }
            ?: (array.opt(index) as? String)?.takeIf(::isHttps)
            ?: return@mapNotNull null
        val thumb = row?.let { firstHttps(it, "thumb", "thumb_url", "thumbnail", "thumbnail_url") }
        val original =
          row?.let { firstHttps(it, "original", "original_url", "origin_url", "full_url") }
        CommentImage(
          url = url,
          thumbnailUrl = thumb,
          width = row?.let { firstInt(it, "width", "w") },
          height = row?.let { firstInt(it, "height", "h") },
          mimeType =
            row
              ?.let { firstValue(it, "mimetype", "mime_type", "type") }
              ?.takeIf(String::isNotBlank),
          originalUrl = original,
          animated =
            row?.let {
              isAnimatedImage(
                firstValue(it, "mimetype", "mime_type", "content_type", "type"),
                url,
                thumb.orEmpty(),
                original.orEmpty(),
              ) ||
                listOf("animated", "is_animated", "is_gif").any { key ->
                  explicitAnimated(it.opt(key))
                }
            } ?: isAnimatedImage("", url),
        )
      }
      .distinctBy(CommentImage::url)
  }

  private fun firstInt(json: JSONObject, vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> json.optIntOrNull(key) }

  internal fun parseFollowStatus(value: Any?): Boolean? =
    when (value) {
      is Number ->
        when (value.toInt()) {
          0,
          2 -> false
          1,
          3 -> true
          else -> null
        }
      is String ->
        when (value.trim()) {
          "0",
          "2" -> false
          "1",
          "3" -> true
          else -> null
        }
      else -> null
    }

  internal fun parseLabels(
    array: org.json.JSONArray?,
    idKeys: List<String>,
    imageKey: String,
  ): List<ArticleLabel> {
    if (array == null) return emptyList()
    return (0 until array.length())
      .mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = firstScalar(item, "name", "title") ?: return@mapNotNull null
        val id = idKeys.firstNotNullOfOrNull { scalar(item.opt(it)) }.orEmpty()
        val image =
          imageKey.takeIf(String::isNotBlank)?.let(item::optString)?.takeIf {
            it.startsWith("https://", true)
          }
        ArticleLabel(id, name, image)
      }
      .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
  }

  private fun isHttps(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.username.isEmpty() && url.password.isEmpty()
  }

  private fun strictInteger(value: Any?): Int? {
    val number = value as? Number ?: return null
    val decimal = number.toString().toBigDecimalOrNull() ?: return null
    return runCatching { decimal.stripTrailingZeros().intValueExact() }.getOrNull()
  }

  private fun firstScalar(json: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { scalar(json.opt(it)) }

  private fun scalar(value: Any?): String? =
    when (value) {
      is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
      is Number -> value.toString()
      else -> null
    }

  private fun formatDate(epochSeconds: Long): String =
    runCatching {
        java.time.Instant.ofEpochSecond(epochSeconds)
          .atZone(java.time.ZoneId.systemDefault())
          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"))
      }
      .getOrDefault("")

  private fun normalizedAuthCookie(raw: String, receivedPkey: String, userId: String): String {
    val values = linkedMapOf<String, String>()
    raw.split(';').forEach { part ->
      val split = part.indexOf('=')
      if (split > 0) {
        val name = part.substring(0, split).trim()
        val value = part.substring(split + 1).trim()
        if (name.isNotBlank() && value.isNotBlank()) values[name] = value
      }
    }
    val pkey =
      receivedPkey.ifBlank {
        listOf("pkey", "user_pkey", "x_pkey").firstNotNullOfOrNull(values::get).orEmpty()
      }
    if (pkey.isNotBlank())
      listOf("pkey", "user_pkey", "x_pkey").forEach { values.putIfAbsent(it, pkey) }
    if (userId.isNotBlank())
      listOf("heybox_id", "user_heybox_id", "x_heybox_id").forEach {
        values.putIfAbsent(it, userId)
      }
    return values.entries.joinToString("; ") { (key, value) -> "$key=$value" }
  }

  private fun signedUrl(
    path: String,
    parameters: Map<String, String>,
    signature: WebRequestSigner.Signature,
    accountContext: Boolean = true,
    accountUserId: String = accountId,
  ): okhttp3.HttpUrl {
    val builder = ("https://${OfficialCookieJar.API_HOST}$path").toHttpUrl().newBuilder()
    val common =
      linkedMapOf(
        "os_type" to "web",
        "app" to "heybox",
        "x_client_type" to "web",
        "x_os_type" to if (accountUserId.isBlank()) "Mac" else "Windows",
        "version" to "999.0.4",
      )
    if (accountContext && accountUserId.isNotBlank()) {
      common["client_type"] = "web"
      common["web_version"] = "2.5"
      common["device_info"] = "Edge"
      common["x_app"] = "heybox_website"
      common["heybox_id"] = accountUserId
      common["userid"] = accountUserId
    } else {
      common["x_app"] = "heybox"
    }
    common.plus(parameters).forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder
      .addQueryParameter("_time", signature.time.toString())
      .addQueryParameter("nonce", signature.nonce)
      .addQueryParameter("hkey", signature.hkey)
      .build()
  }

  internal suspend fun execute(request: Request): String {
    requestExecutor?.let {
      return it.execute(request)
    }
    return executeNetwork(request, transport)
  }

  private suspend fun execute(request: Request, selectedTransport: Transport): String {
    requestExecutor?.let {
      return it.execute(request)
    }
    return executeNetwork(request, selectedTransport)
  }

  private suspend fun executeNetwork(request: Request, selectedTransport: Transport): String =
    suspendCancellableCoroutine { continuation ->
      val call = selectedTransport.client.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : okhttp3.Callback {
          override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
          }

          override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            try {
              response.use { r ->
                check(
                  r.request.url.host.equals(OfficialCookieJar.API_HOST, true) &&
                    r.request.url.isHttps
                ) {
                  "账号请求发生跨域或非 HTTPS 跳转，已阻止"
                }
                if (!continuation.isActive) return
                if (r.code == 401) {
                  continuation.resumeWithException(AccountSessionExpiredException())
                  return
                }
                if (r.code == 429) {
                  continuation.resumeWithException(IllegalStateException("操作过于频繁，请稍后重试。"))
                  return
                }
                if (!r.isSuccessful) {
                  continuation.resumeWithException(IllegalStateException("账号服务 HTTP ${r.code}"))
                } else {
                  continuation.resume(r.body?.string() ?: "{}")
                }
              }
            } catch (error: Exception) {
              if (continuation.isActive) continuation.resumeWithException(error)
            }
          }
        }
      )
    }

  private suspend fun get(
    path: String,
    parameters: Map<String, String>,
    accountContext: Boolean = true,
  ): String {
    val signature = signer.create(path)
    val request =
      Request.Builder()
        .url(signedUrl(path, parameters, signature, accountContext))
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .header("Referer", "https://www.xiaoheihe.cn/")
        .header("User-Agent", "HeyRead/1.1 (Android)")
        .build()
    return execute(request)
  }

  private fun String.isSuccessful() = equals("ok", true) || equals("success", true)

  private fun safeMessage(value: String) =
    when (value.lowercase()) {
      "login",
      "relogin" -> "登录已失效，请重新扫码"
      "lack_token",
      "show_captcha" -> "服务要求额外验证，请稍后再试"
      else -> "暂时无法获取二维码，请稍后重试"
    }

  private fun firstValue(json: JSONObject, vararg keys: String): String =
    keys
      .firstNotNullOfOrNull { key ->
        if (!json.has(key) || json.isNull(key)) null
        else
          when (val value = json.opt(key)) {
            is String ->
              value.takeIf { it.isNotBlank() && it.lowercase() !in setOf("null", "undefined") }
            is Number -> value.toString()
            else -> null
          }
      }
      .orEmpty()

  private fun newTransport(jar: OfficialCookieJar): Transport =
    Transport(
      cookieJar = jar,
      client =
        OkHttpClient.Builder()
          .cookieJar(jar)
          .cache(null)
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(12, TimeUnit.SECONDS)
          .build(),
    )

  companion object {
    private const val QR_URL = "/account/get_qrcode_url/"
    private const val QR_STATE = "/account/qr_state/"
    private const val TREE_V2 = "/bbs/app/link/tree/v2"
    private const val WEB_VIEW = "/bbs/app/link/web/view"
    private const val SUB_COMMENTS = "/bbs/app/comment/sub/comments"
    private const val FAVORITE_TABS = "/bbs/app/profile/fav/tab_list"
    private const val FAVORITE_LINKS = "/bbs/app/profile/fav/folder/v2/links"
    private const val HISTORY = "/bbs/app/profile/history/visit"
    private const val FOLLOWED_EVENTS = "/bbs/app/profile/subscribed/events"
    private const val FOLLOWING_SIMPLE_LIST = "/bbs/app/profile/following/simple_list"
    private const val AUTHOR_INFO = "/bbs/app/link/author_info"
    private const val FAVOUR = "/bbs/app/link/favour"
    private const val AWARD_LINK = "/bbs/app/profile/award/link"
    private const val COMMENT_CREATE = "/bbs/app/comment/create"
    private const val COMMENT_SUPPORT = "/bbs/app/comment/support"
    private const val FOLLOW_USER = "/bbs/app/profile/follow/user"
    private const val CANCEL_FOLLOW_USER = "/bbs/app/profile/follow/user/cancel"
    private const val SEARCH = "/bbs/app/api/general/search/v1"
    private const val PAGE_SIZE = 20
    const val FOLLOWED_ACTIVITY_PAGE_SIZE = 30
  }
}

data class FollowedActivityPage(
  val stories: List<Story>,
  val rawCount: Int,
  val lastValue: String?,
)

data class FollowedUser(
  val userId: String,
  val displayName: String,
  val avatarUrl: String?,
  val unread: Int,
)

data class FollowedUsersPage(val users: List<FollowedUser>, val hasMore: Boolean)

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
  when (val value = opt(key)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String ->
      when (value.lowercase()) {
        "true",
        "1",
        "yes" -> true
        "false",
        "0",
        "no" -> false
        else -> null
      }
    else -> null
  }

private fun JSONObject.optIntOrNull(key: String): Int? =
  if (has(key) && !isNull(key)) optInt(key) else null

class AccountSessionExpiredException : IllegalStateException("小黑盒登录已失效，请重新扫码")

class AccountSessionRequiredException : IllegalStateException("登录后才能读取关注动态。")

class AccountSessionChangedException : IllegalStateException("账号状态已变化，请刷新关注动态。")

class AccountFollowingRestrictedException : IllegalStateException("关注内容暂不可用，请确认当前账号登录状态后重试。")
