package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.AccountSessionExpiredException
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthorFollowState(
    val followed: Boolean? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class AuthorFollowViewModel(
    private val repository: AccountRepository,
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    private val mutableStates = MutableStateFlow<Map<String, AuthorFollowState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var userId: String? = null
    private var sessionRevision = Long.MIN_VALUE
    private var generation = 0L
    private val generations = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
    private val acknowledged = mutableSetOf<String>()

    fun bindSession(userId: String?, revision: Long) {
        if (this.userId == userId && sessionRevision == revision) return
        generation++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        generations.clear()
        acknowledged.clear()
        this.userId = userId
        sessionRevision = revision
        mutableStates.value = emptyMap()
    }

    fun stateFor(authorId: String): AuthorFollowState =
        mutableStates.value[authorId] ?: AuthorFollowState()

    fun seedFromArticle(profile: AuthorProfile, followed: Boolean?) {
        val account = userId ?: return
        val authorId = profile.userId?.takeIf(String::isNotBlank) ?: return
        val current = stateFor(authorId)
        if (
            authorId == account ||
                followed == null ||
                authorId in acknowledged ||
                current.followed != null ||
                current.busy ||
                current.loading
        )
            return
        mutableStates.value =
            mutableStates.value + (authorId to current.copy(followed = followed, error = null))
    }

    fun refresh(profile: AuthorProfile, story: Story) {
        val account = userId ?: return
        val authorId = profile.userId?.takeIf(String::isNotBlank) ?: return
        if (authorId == account || stateFor(authorId).busy) return
        val token = (generations[authorId] ?: 0L) + 1L
        generations[authorId] = token
        jobs[authorId]?.cancel()
        val revision = sessionRevision
        val requestGeneration = generation
        mutableStates.value =
            mutableStates.value +
                (authorId to stateFor(authorId).copy(loading = true, error = null))
        lateinit var job: Job
        job =
            viewModelScope.launch {
                try {
                    val followed = repository.loadAuthorFollowStatus(story, authorId)
                    if (!sessionMatches(authorId, token, requestGeneration, revision, account))
                        return@launch
                    mutableStates.value =
                        mutableStates.value +
                            (authorId to
                                stateFor(authorId)
                                    .copy(followed = followed, loading = false, error = null))
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (sessionMatches(authorId, token, requestGeneration, revision, account)) {
                        if (error is AccountSessionExpiredException) onSessionExpired()
                        mutableStates.value =
                            mutableStates.value +
                                (authorId to
                                    stateFor(authorId)
                                        .copy(
                                            loading = false,
                                            error = error.message ?: "暂时无法读取关注关系。",
                                        ))
                    }
                } finally {
                    if (jobs[authorId] === job) jobs.remove(authorId)
                }
            }
        jobs[authorId] = job
    }

    fun toggle(profile: AuthorProfile, story: Story) {
        val account = userId ?: return
        val authorId = profile.userId?.takeIf(String::isNotBlank) ?: return
        if (authorId == account) return
        val current = stateFor(authorId)
        val followed = current.followed ?: return
        if (current.busy || current.loading) return
        val revision = sessionRevision
        val token = generation
        val requestGeneration = (generations[authorId] ?: 0L) + 1L
        generations[authorId] = requestGeneration
        jobs[authorId]?.cancel()
        mutableStates.value =
            mutableStates.value + (authorId to current.copy(busy = true, error = null))
        lateinit var job: Job
        job =
            viewModelScope.launch {
                try {
                    if (followed) repository.cancelFollowUser(authorId, story.hSrc)
                    else repository.followUser(authorId, story.hSrc)
                    if (!sessionMatches(authorId, requestGeneration, token, revision, account))
                        return@launch
                    mutableStates.value =
                        mutableStates.value +
                            (authorId to
                                stateFor(authorId)
                                    .copy(followed = !followed, busy = false, error = null))
                    acknowledged += authorId
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (sessionMatches(authorId, requestGeneration, token, revision, account)) {
                        if (error is AccountSessionExpiredException) onSessionExpired()
                        mutableStates.value =
                            mutableStates.value +
                                (authorId to
                                    stateFor(authorId)
                                        .copy(
                                            busy = false,
                                            error = error.message ?: "关注操作失败，请稍后重试。",
                                        ))
                    }
                } finally {
                    if (jobs[authorId] === job) jobs.remove(authorId)
                }
            }
        jobs[authorId] = job
    }

    private fun sessionMatches(
        authorId: String,
        authorToken: Long,
        token: Long,
        revision: Long,
        account: String,
    ) =
        token == generation &&
            revision == sessionRevision &&
            account == userId &&
            generations[authorId] == authorToken

    class Factory(
        private val repository: AccountRepository,
        private val onSessionExpired: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthorFollowViewModel(repository, onSessionExpired) as T
    }
}
