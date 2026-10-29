package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.account.FullArticle
import cn.heyread.app.data.account.ReplyTarget
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PostSocialState(
    val liked: Boolean? = null,
    val favorited: Boolean? = null,
    val likeCount: Int? = null,
    val commentCount: Int? = null,
    val likeBusy: Boolean = false,
    val favoriteBusy: Boolean = false,
    val commentBusy: Boolean = false,
    val commentDraft: String = "",
    val commentTarget: ReplyTarget = ReplyTarget.MainComment,
    val error: String? = null,
)

class SocialActionsViewModel(
    private val repository: AccountRepository,
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    private val mutableStates = MutableStateFlow<Map<String, PostSocialState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var sessionUserId: String? = null
    private var sessionRevision = Long.MIN_VALUE
    private val busy = WriteReservation()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var generation = 0L
    private val drafts = mutableMapOf<String, String>()

    fun bindSession(userId: String?, revision: Long) {
        if (userId == sessionUserId && revision == sessionRevision) return
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        busy.clear()
        sessionUserId = userId
        sessionRevision = revision
        busy.clear()
        mutableStates.value = emptyMap()
        drafts.clear()
    }

    fun syncArticle(story: Story, article: FullArticle?) {
        if (article == null || story.source != Source.HEYBOX) return
        val key = key(story)
        val old = mutableStates.value[key] ?: PostSocialState()
        mutableStates.value =
            mutableStates.value +
                (key to
                    old.copy(
                        liked = article.isLiked ?: old.liked,
                        favorited = article.isFavorited ?: old.favorited,
                        likeCount = article.likeCount ?: old.likeCount,
                        commentCount = article.commentCount ?: old.commentCount,
                    ))
    }

    fun draft(story: Story, value: String) {
        val state = mutableStates.value[key(story)] ?: return
        if (state.commentBusy) return
        val text = value.take(4000)
        drafts[draftKey(story, state.commentTarget)] = text
        update(story) { it.copy(commentDraft = text, error = null) }
    }

    fun selectCommentTarget(story: Story, target: ReplyTarget): Boolean {
        if (sessionUserId == null || story.source != Source.HEYBOX) return false
        val state = mutableStates.value[key(story)] ?: PostSocialState()
        if (state.commentBusy) return false
        drafts[draftKey(story, state.commentTarget)] = state.commentDraft
        val text = drafts[draftKey(story, target)].orEmpty()
        update(story) { it.copy(commentTarget = target, commentDraft = text, error = null) }
        return true
    }

    fun like(story: Story) {
        if (mutableStates.value[key(story)]?.liked == null) return
        mutate(story, "like") { old ->
            val target = old.liked != true
            repository.setLiked(story, target)
            old.copy(
                liked = target,
                likeCount = old.likeCount?.let { (it + if (target) 1 else -1).coerceAtLeast(0) },
                error = null,
            )
        }
    }

    fun favorite(story: Story, onChanged: (Boolean) -> Unit) {
        if (mutableStates.value[key(story)]?.favorited == null) return
        mutate(
            story,
            "favorite",
            onSuccess = { onChanged(mutableStates.value[key(story)]?.favorited == true) },
        ) { old ->
            val target = old.favorited != true
            repository.setCloudFavorite(story, target)
            old.copy(favorited = target, error = null)
        }
    }

    fun comment(story: Story, authCode: String?, onPublished: (ReplyTarget) -> Unit) {
        val current = mutableStates.value[key(story)] ?: return
        if (
            current.commentDraft.isBlank() ||
                sessionUserId == null ||
                !reserve(key(story), "comment")
        )
            return
        update(story) { it.copy(commentBusy = true, error = null) }
        val revision = sessionRevision
        val user = sessionUserId
        val token = generation
        val target = current.commentTarget
        val submittedDraft = current.commentDraft
        lateinit var job: kotlinx.coroutines.Job
        val jobKey = "${key(story)}:comment"
        job =
            viewModelScope.launch {
                try {
                    repository.createComment(story, submittedDraft, authCode, target)
                    if (token != generation || revision != sessionRevision || user != sessionUserId)
                        return@launch
                    update(story) {
                        it.copy(
                            commentBusy = false,
                            commentDraft = if (it.commentTarget == target) "" else it.commentDraft,
                            commentCount = it.commentCount?.plus(1),
                            error = null,
                        )
                    }
                    drafts.remove(draftKey(story, target))
                    onPublished(target)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (
                        token == generation && revision == sessionRevision && user == sessionUserId
                    ) {
                        if (error is AccountSessionExpiredException) onSessionExpired()
                        update(story) {
                            it.copy(
                                commentBusy = false,
                                error =
                                    if (error is IOException) "发送结果未确认，请先检查评论列表，再决定是否重试。"
                                    else error.message ?: "评论发布失败。",
                            )
                        }
                    }
                } finally {
                    if (token == generation) release(key(story), "comment")
                    if (jobs[jobKey] === job) jobs.remove(jobKey)
                }
            }
        jobs[jobKey] = job
    }

    private fun mutate(
        story: Story,
        action: String,
        onSuccess: (() -> Unit)? = null,
        operation: suspend (PostSocialState) -> PostSocialState,
    ) {
        if (story.source != Source.HEYBOX || sessionUserId == null) return
        val key = key(story)
        val old = mutableStates.value[key] ?: return
        val known =
            when (action) {
                "like" -> old.liked != null
                else -> old.favorited != null
            }
        if (!known || !reserve(key, action)) return
        update(story) {
            it.copy(
                likeBusy = it.likeBusy || action == "like",
                favoriteBusy = it.favoriteBusy || action == "favorite",
                error = null,
            )
        }
        val revision = sessionRevision
        val user = sessionUserId
        val token = generation
        lateinit var job: kotlinx.coroutines.Job
        val jobKey = "$key:$action"
        job =
            viewModelScope.launch {
                try {
                    val next = operation(old)
                    if (
                        token == generation && revision == sessionRevision && user == sessionUserId
                    ) {
                        update(story) { current ->
                            when (action) {
                                "like" ->
                                    current.copy(
                                        liked = next.liked,
                                        likeCount = next.likeCount,
                                        likeBusy = false,
                                        error = null,
                                    )
                                else ->
                                    current.copy(
                                        favorited = next.favorited,
                                        favoriteBusy = false,
                                        error = null,
                                    )
                            }
                        }
                        onSuccess?.invoke()
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (
                        token == generation && revision == sessionRevision && user == sessionUserId
                    ) {
                        if (error is AccountSessionExpiredException) onSessionExpired()
                        update(story) {
                            if (action == "like")
                                it.copy(likeBusy = false, error = error.message ?: "操作失败，请重试。")
                            else it.copy(favoriteBusy = false, error = error.message ?: "操作失败，请重试。")
                        }
                    }
                } finally {
                    if (token == generation) release(key, action)
                    if (jobs[jobKey] === job) jobs.remove(jobKey)
                }
            }
        jobs[jobKey] = job
    }

    private fun reserve(key: String, action: String): Boolean = busy.acquire("$key:$action")

    private fun release(key: String, action: String) {
        busy.release("$key:$action")
    }

    private fun update(story: Story, transform: (PostSocialState) -> PostSocialState) {
        val key = key(story)
        mutableStates.value =
            mutableStates.value + (key to transform(mutableStates.value[key] ?: PostSocialState()))
    }

    private fun draftKey(story: Story, target: ReplyTarget) =
        "${key(story)}:${target.rootCommentId.orEmpty()}:${target.replyCommentId.orEmpty()}"

    companion object {
        fun key(story: Story) = "${story.source}:${story.id}"
    }

    class Factory(
        private val repository: AccountRepository,
        private val onSessionExpired: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SocialActionsViewModel(repository, onSessionExpired) as T
    }
}

internal class WriteReservation {
    private val entries = mutableSetOf<String>()

    fun acquire(id: String): Boolean = synchronized(entries) { entries.add(id) }

    fun release(id: String) {
        synchronized(entries) { entries.remove(id) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
