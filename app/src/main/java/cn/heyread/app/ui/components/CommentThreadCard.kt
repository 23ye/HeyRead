package cn.heyread.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.account.CommentImage
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.viewmodel.CommentSupportState

@Composable
fun CommentThreadCard(
    comment: RemoteComment,
    replies: List<RemoteComment>,
    replyLoading: Boolean,
    replyHasMore: Boolean,
    replyError: String?,
    noImages: Boolean,
    onReply: (RemoteComment, Boolean) -> Unit,
    onLoadReplies: () -> Unit,
    onMoreReplies: () -> Unit,
    onRetryReplies: () -> Unit,
    supportState: (String) -> CommentSupportState = { CommentSupportState() },
    onSupport: (RemoteComment) -> Unit = {},
    onImageClick: (CommentImage) -> Unit = {},
    authorUserId: String? = null,
    onProfileClick: ((cn.heyread.app.data.model.AuthorProfile) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        CommentEntry(
            comment,
            noImages,
            supportState(comment.id),
            authorUserId,
            onReply = { onReply(comment, false) },
            onSupport = { onSupport(comment) },
            onImageClick = onImageClick,
            onProfileClick = onProfileClick,
        )
        if (comment.childCount > 0 || replies.isNotEmpty() || replyLoading || replyError != null) {
            Surface(
                Modifier.fillMaxWidth().padding(start = 46.dp, top = 7.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(9.dp)) {
                    replies.forEach { reply ->
                        CommentEntry(
                            reply,
                            noImages,
                            supportState(reply.id),
                            authorUserId,
                            nested = true,
                            onReply = { onReply(reply, true) },
                            onSupport = { onSupport(reply) },
                            onImageClick = onImageClick,
                            onProfileClick = onProfileClick,
                        )
                    }
                    if (replyError != null) {
                        Text(
                            replyError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRetryReplies, enabled = !replyLoading) {
                            Text("重试读取回复")
                        }
                    } else if (replies.isEmpty() || replyHasMore) {
                        TextButton(
                            onClick = if (replies.isEmpty()) onLoadReplies else onMoreReplies,
                            enabled = !replyLoading,
                        ) {
                            Text(
                                if (replyLoading) "正在读取…"
                                else if (replies.isEmpty()) "查看 ${comment.childCount} 条回复"
                                else "更多回复"
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 9.dp))
    }
}

@Composable
private fun CommentEntry(
    comment: RemoteComment,
    noImages: Boolean,
    support: CommentSupportState,
    authorUserId: String?,
    nested: Boolean = false,
    onReply: () -> Unit,
    onSupport: () -> Unit,
    onImageClick: (CommentImage) -> Unit,
    onProfileClick: ((cn.heyread.app.data.model.AuthorProfile) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        ProfileRow(
            comment.profile,
            comment.author,
            noImages,
            compact = true,
            timestamp = commentDate(comment),
            showLevel = !nested,
            onProfileClick = onProfileClick,
            isAuthor =
                !comment.profile?.userId.isNullOrBlank() &&
                    !authorUserId.isNullOrBlank() &&
                    comment.profile?.userId == authorUserId,
        ) {
            if (!nested)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = onSupport,
                        enabled = support.supported != null && !support.busy,
                        modifier = Modifier.size(48.dp).testTag("comment-support-${comment.id}"),
                    ) {
                        if (support.busy)
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else
                            Icon(
                                Icons.Filled.ThumbUp,
                                contentDescription =
                                    if (support.supported == true) "取消点赞" else "点赞评论",
                                tint =
                                    if (support.supported == true) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    Text(
                        support.count?.toString() ?: "—",
                        fontSize = 11.sp,
                        modifier = Modifier.widthIn(max = 38.dp),
                    )
                }
        }
        Column(Modifier.fillMaxWidth().padding(start = 46.dp)) {
            Column(
                Modifier.fillMaxWidth()
                    .clickable { onReply() }
                    .testTag("comment-body-${comment.id}")
            ) {
                comment.replyTo?.takeIf(String::isNotBlank)?.let { target ->
                    SelectionContainer {
                        RichText(
                            text = "回复 $target：",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            noImages = noImages,
                        )
                    }
                }
                SelectionContainer {
                    RichText(
                        text = comment.text,
                        fontSize = if (nested) 13.sp else 15.sp,
                        lineHeight = if (nested) 20.sp else 23.sp,
                        noImages = noImages,
                    )
                }
            }
            if (comment.images.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    comment.images.forEachIndexed { index, image ->
                        CommentImageThumbnail(
                            image,
                            noImages,
                            "comment-image-${comment.id}-$index",
                        ) {
                            onImageClick(image)
                        }
                    }
                }
            }
            if (!nested && support.error != null)
                Text(
                    support.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}

private fun commentDate(comment: RemoteComment): String =
    if (comment.createdAt <= 0L) ""
    else
        runCatching {
                java.time.Instant.ofEpochSecond(comment.createdAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            }
            .getOrDefault("")
