package cn.heyread.app.data.account

import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.Story

data class RemoteComment(
    val id: String,
    val author: String,
    val text: String,
    val createdAt: Long,
    val likes: Int,
    val replyTo: String? = null,
    val childCount: Int = 0,
    val replies: List<RemoteComment> = emptyList(),
    val rootCommentId: String? = null,
    val profile: cn.heyread.app.data.model.AuthorProfile? = null,
    val isSupported: Boolean? = null,
    val supportCount: Int? = null,
    val images: List<CommentImage> = emptyList(),
)

data class CommentImage(
    val url: String,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null,
    val originalUrl: String? = null,
    val animated: Boolean = false,
)

data class FullArticle(
    val story: Story,
    val blocks: List<ArticleBlock>,
    val comments: List<RemoteComment>,
    val page: Int,
    val hasMoreComments: Boolean,
    val restrictedNotice: String? = null,
    val commentsError: String? = null,
    val isFavorited: Boolean? = null,
    val isLiked: Boolean? = null,
    val likeCount: Int? = null,
    val commentCount: Int? = null,
    val authCode: String? = null,
    val authorProfile: cn.heyread.app.data.model.AuthorProfile? = null,
    val sections: List<cn.heyread.app.data.model.ArticleLabel> = emptyList(),
    val hashtags: List<cn.heyread.app.data.model.ArticleLabel> = emptyList(),
    val authorFollowed: Boolean? = null,
)

data class ReplyTarget(
    val rootCommentId: String? = null,
    val replyCommentId: String? = null,
    val username: String? = null,
    val excerpt: String? = null,
) {
    val isReply: Boolean
        get() = rootCommentId != null && replyCommentId != null

    companion object {
        val MainComment = ReplyTarget()

        fun main(comment: RemoteComment) =
            ReplyTarget(comment.id, comment.id, comment.author, comment.text.take(80))

        fun nested(rootCommentId: String, comment: RemoteComment) =
            ReplyTarget(rootCommentId, comment.id, comment.author, comment.text.take(80))
    }
}

data class CloudFolder(val id: String, val name: String)

data class CloudListPage(val stories: List<Story>, val hasMore: Boolean)

data class CommentPage(
    val comments: List<RemoteComment>,
    val hasMore: Boolean,
    val lastValue: String? = null,
)

data class SearchPage(val stories: List<Story>, val rawCount: Int, val hasMore: Boolean)

object QrStateParser {
    fun parse(status: String, error: String?): QrPollResult? {
        if (!status.equals("ok", true) && !status.equals("success", true)) return null
        return when (error.orEmpty().lowercase()) {
            "ready" -> QrPollResult.Ready
            "cancel" -> QrPollResult.Cancelled
            "ok" -> null
            "login",
            "relogin",
            "lack_token",
            "show_captcha" -> QrPollResult.Rejected("登录暂不可用，请稍后重试")
            else -> QrPollResult.Waiting
        }
    }
}
