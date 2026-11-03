package cn.heyread.app.data.media

import cn.heyread.app.data.account.CommentImage
import cn.heyread.app.data.account.RemoteComment

/** Identifies one image occurrence in the currently loaded comment tree. */
data class CommentImageOccurrence(
    val rootCommentId: String,
    val commentId: String,
    val imageIndex: Int,
    val image: CommentImage,
)

/** Collects root images and the exact one-level reply list rendered for each root. */
fun collectCommentImageOccurrences(
    comments: List<RemoteComment>,
    repliesByRoot: Map<String, List<RemoteComment>>,
): List<CommentImageOccurrence> =
    buildList {
        comments.forEach { root ->
            root.images.forEachIndexed { imageIndex, image ->
                add(CommentImageOccurrence(root.id, root.id, imageIndex, image))
            }
            val displayedReplies = repliesByRoot[root.id] ?: root.replies
            displayedReplies.forEach { reply ->
                reply.images.forEachIndexed { imageIndex, image ->
                    add(CommentImageOccurrence(root.id, reply.id, imageIndex, image))
                }
            }
        }
    }

/** Returns the occurrence position for the exact rendered comment/image, or -1 when absent. */
fun commentImageOccurrenceIndex(
    occurrences: List<CommentImageOccurrence>,
    rootCommentId: String,
    commentId: String,
    imageIndex: Int,
): Int =
    occurrences.indexOfFirst {
        it.rootCommentId == rootCommentId && it.commentId == commentId && it.imageIndex == imageIndex
    }

fun CommentImageOccurrence.toMediaImage(): MediaImage {
    val commentImage = this.image
    return MediaImage(
        previewUrl = commentImage.thumbnailUrl ?: commentImage.url,
        originalUrl =
            commentImage.originalUrl
                ?: commentImage.url.takeIf {
                    commentImage.thumbnailUrl != null && commentImage.thumbnailUrl != commentImage.url
                },
        animationUrl =
            if (commentImage.animated) commentImage.originalUrl ?: commentImage.url else null,
        description = "评论图片",
    )
}
