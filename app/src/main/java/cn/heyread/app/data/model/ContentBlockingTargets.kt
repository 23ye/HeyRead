package cn.heyread.app.data.model

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ContentBlockTarget(
    val kind: ContentBlockKind,
    val targetId: String,
    val label: String = "",
)

/** Validates and normalizes a positive decimal identity ID without converting it to a number. */
fun canonicalContentBlockTargetId(raw: String): String {
  require(raw.length in 1..CONTENT_BLOCKING_TARGET_MAX_ID_LENGTH && raw.all { it in '0'..'9' }) {
    "ID须为最多32位的正十进制数字。"
  }
  val canonical = raw.dropWhile { it == '0' }
  require(canonical.isNotEmpty()) { "ID不能为0。" }
  return canonical
}

fun parseContentBlockIdentityInput(
    kind: ContentBlockKind,
    input: String,
    label: String = "",
): ContentBlockTarget {
  require(kind != ContentBlockKind.KEYWORD) { "关键词不使用身份ID或链接。" }
  val cleanLabel = label.trim()
  require(cleanLabel.length <= CONTENT_BLOCKING_TARGET_MAX_LABEL_LENGTH) {
    "备注最多60个字符。"
  }
  val value = input.trim()
  require(value.isNotEmpty()) { "请输入小黑盒ID或已支持的主页链接。" }
  if (value.length > CONTENT_BLOCKING_TARGET_MAX_LINK_LENGTH) {
    throw IllegalArgumentException("链接不得超过2048个字符。")
  }

  val id =
      if (value.all { it in '0'..'9' }) {
        canonicalContentBlockTargetId(value)
      } else {
        parseSupportedIdentityLink(kind, value)
      }
  return ContentBlockTarget(kind, id, cleanLabel)
}

fun contentBlockStoryTargets(story: Story): List<ContentBlockTarget> {
  if (story.source != Source.HEYBOX) return emptyList()
  return buildList {
    story.authorProfile?.let { profile ->
      profile.userId?.let { rawId -> addTarget(ContentBlockKind.AUTHOR, rawId, profile.name) }
    }
    story.sections.forEach { addTarget(ContentBlockKind.SECTION, it.id, it.name) }
    story.hashtags.forEach { addTarget(ContentBlockKind.TOPIC, it.id, it.name) }
  }.distinctBy { it.kind to it.targetId }
}

private fun MutableList<ContentBlockTarget>.addTarget(
    kind: ContentBlockKind,
    rawId: String,
    label: String,
) {
  val id = runCatching { canonicalContentBlockTargetId(rawId) }.getOrNull() ?: return
  add(ContentBlockTarget(kind, id, label.trim()))
}

private fun parseSupportedIdentityLink(kind: ContentBlockKind, value: String): String {
  require(value.none { it.isWhitespace() || it.isISOControl() } && '\\' !in value) {
    "链接格式无效。"
  }
  val uri =
      runCatching { URI(value) }.getOrNull()
          ?: throw IllegalArgumentException("链接格式无效。")
  require(uri.scheme.equals("https", ignoreCase = true)) { "仅支持小黑盒官方HTTPS主页链接。" }
  require(uri.rawUserInfo == null) { "链接不能包含用户名或密码。" }
  val authority = uri.rawAuthority ?: throw IllegalArgumentException("链接格式无效。")
  require(
      authority.equals(CONTENT_BLOCKING_LINK_HOST, ignoreCase = true) ||
          authority.equals("$CONTENT_BLOCKING_LINK_HOST:443", ignoreCase = true),
  ) {
    "仅支持小黑盒官方HTTPS主页链接。"
  }
  require(uri.port == -1 || uri.port == 443) { "链接端口无效。" }

  val rawPath = uri.rawPath ?: throw IllegalArgumentException("链接格式无效。")
  val authorMatch = CONTENT_BLOCKING_AUTHOR_PATH_REGEX.matchEntire(rawPath)
  val sectionMatch = CONTENT_BLOCKING_SECTION_PATH_REGEX.matchEntire(rawPath)
  val (expectedKind, id) =
      when {
        authorMatch != null -> ContentBlockKind.AUTHOR to authorMatch.groupValues[1]
        sectionMatch != null -> ContentBlockKind.SECTION to sectionMatch.groupValues[1]
        else ->
            throw IllegalArgumentException("该小黑盒链接类型尚不支持，请输入ID或从页面快捷添加。")
      }
  require(expectedKind == kind) { "链接对应${expectedKind.label}，请切换规则类型后再确认。" }

  val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("链接格式无效。")
  require(url.isHttps && url.host.equals(CONTENT_BLOCKING_LINK_HOST, ignoreCase = true)) {
    "仅支持小黑盒官方HTTPS主页链接。"
  }
  return canonicalContentBlockTargetId(id)
}

private const val CONTENT_BLOCKING_LINK_HOST = "www.xiaoheihe.cn"
private const val CONTENT_BLOCKING_TARGET_MAX_ID_LENGTH = 32
private const val CONTENT_BLOCKING_TARGET_MAX_LABEL_LENGTH = 60
private const val CONTENT_BLOCKING_TARGET_MAX_LINK_LENGTH = 2048
private val CONTENT_BLOCKING_AUTHOR_PATH_REGEX = Regex("^/app/user/profile/([0-9]{1,32})/?$")
private val CONTENT_BLOCKING_SECTION_PATH_REGEX = Regex("^/app/topic/link/([0-9]{1,32})/?$")
