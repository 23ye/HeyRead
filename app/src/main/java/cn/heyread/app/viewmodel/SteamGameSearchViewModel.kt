package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.model.SteamGame
import cn.heyread.app.data.remote.SteamGameSearchRepository
import cn.heyread.app.data.remote.SteamGameSearchSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SteamGameSearchState(
  val query: String = "",
  val results: List<SteamGame> = emptyList(),
  val loading: Boolean = false,
  val error: String? = null,
  val hasSearched: Boolean = false,
)

class SteamGameSearchViewModel(
  private val repository: SteamGameSearchSource = SteamGameSearchRepository()
) : ViewModel() {
  private val mutableState = MutableStateFlow(SteamGameSearchState())
  val state = mutableState.asStateFlow()

  private var generation = 0L
  private var searchJob: Job? = null

  fun updateQuery(query: String) {
    if (query == mutableState.value.query) return
    cancelSearch()
    mutableState.value = SteamGameSearchState(query = query.take(MAX_QUERY_LENGTH))
  }

  fun submit() {
    cancelSearch()
    val query = mutableState.value.query.trim()
    val token = generation
    if (query.isEmpty()) {
      mutableState.value =
        mutableState.value.copy(loading = false, error = "请输入游戏名称或AppID。", hasSearched = true)
      return
    }
    mutableState.value =
      mutableState.value.copy(
        results = emptyList(),
        loading = true,
        error = null,
        hasSearched = false,
      )
    searchJob =
      viewModelScope.launch {
        try {
          val results = repository.search(query)
          if (!isCurrent(token, query)) return@launch
          mutableState.value =
            mutableState.value.copy(
              results = results.take(SteamGameSearchRepository.MAX_RESULTS),
              loading = false,
              error = null,
              hasSearched = true,
            )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (!isCurrent(token, query)) return@launch
          mutableState.value =
            mutableState.value.copy(
              results = emptyList(),
              loading = false,
              error = error.message?.take(160) ?: "Steam搜索失败，请稍后重试。",
              hasSearched = true,
            )
        }
      }
  }

  fun clear() {
    cancelSearch()
    mutableState.value = SteamGameSearchState()
  }

  fun pause() {
    if (searchJob?.isActive != true) return
    cancelSearch()
    mutableState.value = mutableState.value.copy(loading = false)
  }

  private fun cancelSearch() {
    generation++
    searchJob?.cancel()
    searchJob = null
  }

  private fun isCurrent(token: Long, query: String): Boolean =
    generation == token && mutableState.value.query.trim() == query

  override fun onCleared() {
    cancelSearch()
    super.onCleared()
  }

  class Factory(private val repository: SteamGameSearchRepository = SteamGameSearchRepository()) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      SteamGameSearchViewModel(repository) as T
  }

  private companion object {
    const val MAX_QUERY_LENGTH = 120
  }
}
