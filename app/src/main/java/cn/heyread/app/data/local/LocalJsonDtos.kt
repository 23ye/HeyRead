package cn.heyread.app.data.local

import cn.heyread.app.data.model.*
import kotlinx.serialization.Serializable

@Serializable
internal data class HistorySnapshotDto(
  val id: String,
  val source: String,
  val title: String,
  val excerpt: String = "",
  val body: String = "",
  val author: String = "",
  val date: String = "",
  val image: String? = null,
  val url: String? = null,
  val appId: Int? = null,
  val publishedAt: Long? = null,
  val detailNotice: String? = null,
  val channel: String? = null,
  val authorProfile: HistoryAuthorDto? = null,
  val sections: List<HistoryLabelDto> = emptyList(),
  val hashtags: List<HistoryLabelDto> = emptyList(),
)

@Serializable
internal data class HistoryAuthorDto(
  val name: String,
  val avatarUrl: String? = null,
  val level: String? = null,
  val location: String? = null,
  val userId: String? = null,
  val bio: String? = null,
)

@Serializable
internal data class HistoryLabelDto(val id: String, val name: String, val imageUrl: String? = null)

internal fun Story.toHistoryDto() =
  HistorySnapshotDto(
    id,
    source.name,
    title,
    excerpt,
    body,
    author,
    date,
    image,
    url,
    appId,
    publishedAt,
    detailNotice,
    channel?.name,
    authorProfile?.let {
      HistoryAuthorDto(it.name, it.avatarUrl, it.level, it.location, it.userId, it.bio)
    },
    sections.map { HistoryLabelDto(it.id, it.name, it.imageUrl) },
    hashtags.map { HistoryLabelDto(it.id, it.name, it.imageUrl) },
  )

internal fun HistorySnapshotDto.toStory(): Story? =
  runCatching {
      Story(
        id,
        Source.valueOf(source),
        title,
        excerpt,
        body,
        author,
        date,
        image,
        url,
        appId?.takeIf { it > 0 },
        publishedAt?.takeIf { it > 0 },
        detailNotice,
        channel?.let(Channel::valueOf),
        authorProfile =
          authorProfile?.let {
            AuthorProfile(it.name, it.avatarUrl, it.level, it.location, it.userId, it.bio)
          },
        sections =
          sections
            .map { ArticleLabel(it.id, it.name, it.imageUrl) }
            .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" },
        hashtags =
          hashtags
            .map { ArticleLabel(it.id, it.name, it.imageUrl) }
            .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" },
      )
    }
    .getOrNull()

@Serializable
internal data class TransferDocumentDto(
  val format: String,
  val version: Int,
  val rules: List<TransferRuleDto>,
)

@Serializable
internal data class TransferRuleDto(
  val kind: String,
  val value: String,
  val label: String,
  val enabled: Boolean,
  val source: String?,
)
