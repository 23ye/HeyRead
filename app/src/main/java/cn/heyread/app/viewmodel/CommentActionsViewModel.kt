package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.account.RemoteComment
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommentSupportState(
    val supported: Boolean? = null,
    val count: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class CommentActionsViewModel(
    private val repository: AccountRepository,
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    private val mutableStates = MutableStateFlow<Map<String, CommentSupportState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var userId: String? = null
    private var sessionRevision = Long.MIN_VALUE
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val busyIds = mutableSetOf<String>()
    private val acknowledged = mutableSetOf<String>()

    fun bindSession(userId: String?, revision: Long) {
        if (this.userId == userId && sessionRevision == revision) return
        generation++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        busyIds.clear()
        acknowledged.clear()
        this.userId = userId
        sessionRevision = revision
        mutableStates.value = emptyMap()
    }

    fun syncComments(comments: List<RemoteComment>) {
        flatten(comments).forEach { comment ->
            val id = comment.id
            if (id.isBlank() || id in acknowledged) return@forEach
            val current = mutableStates.value[id] ?: CommentSupportState()
            if (current.busy) return@forEach
            mutableStates.value =
                mutableStates.value +
                    (id to
                        current.copy(
                            supported = comment.isSupported ?: current.supported,
                            count = comment.supportCount ?: current.count,
                            error = null,
                        ))
        }
    }

    fun stateFor(commentId: String): CommentSupportState =
        mutableStates.value[commentId] ?: CommentSupportState()

    fun support(comment: RemoteComment, story: Story) {
        val account = userId ?: return
        val id = comment.id
        val current = stateFor(id)
        val supported = current.supported ?: return
        if (id.isBlank() || story.source.name != "HEYBOX" || !busyIds.add(id)) return
        val revision = sessionRevision
        val token = generation
        mutableStates.value = mutableStates.value + (id to current.copy(busy = true, error = null))
        lateinit var job: Job
        job =
            viewModelScope.launch {
                try {
                    repository.supportComment(id, !supported, story.hSrc)
                    if (!sessionMatches(token, revision, account)) return@launch
                    val now = stateFor(id)
                    mutableStates.value =
                        mutableStates.value +
                            (id to
                                now.copy(
                                    supported = !supported,
                                    count =
                                        now.count?.let {
                                            (it + if (!supported) 1 else -1).coerceAtLeast(0)
                                        },
                                    busy = false,
                                    error = null,
                                ))
                    acknowledged += id
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (sessionMatches(token, revision, account)) {
                        if (error is AccountSessionExpiredException) onSessionExpired()
                        val now = stateFor(id)
                        mutableStates.value =
                            mutableStates.value +
                                (id to
                                    now.copy(
                                        busy = false,
                                        error = error.message ?: "评论赞操作失败，请稍后重试。",
                                    ))
                    }
                } finally {
                    if (token == generation) busyIds.remove(id)
                    if (jobs[id] === job) jobs.remove(id)
                }
            }
        jobs[id] = job
    }

    private fun sessionMatches(token: Long, revision: Long, account: String) =
        token == generation && revision == sessionRevision && account == userId

    private fun flatten(comments: List<RemoteComment>): List<RemoteComment> =
        comments.flatMap { listOf(it) + flatten(it.replies) }

    class Factory(
        private val repository: AccountRepository,
        private val onSessionExpired: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CommentActionsViewModel(repository, onSessionExpired) as T
    }
}
