package cn.heyread.app.data.local

import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class SearchHistoryEntry(val query: String, val scope: SearchScope) {
  val key: String
    get() = scope.key(query)
}

interface SearchHistoryStore {
  suspend fun searchHistory(): List<SearchHistoryEntry>

    fun observeSearchHistory(): Flow<List<SearchHistoryEntry>> = flow { emit(searchHistory()) }

  suspend fun recordSearch(entry: SearchHistoryEntry)

  suspend fun removeSearch(entry: SearchHistoryEntry)

  suspend fun clearSearchHistory()
}

@Serializable
private data class SearchHistoryDto(
  val query: String,
  val scopeType: String,
  val scopeId: String,
  val scopeName: String,
)

private val searchJson = Json {
  encodeDefaults = true
  explicitNulls = true
}

internal fun encodeSearchHistory(entries: List<SearchHistoryEntry>): String =
  searchJson.encodeToString(
    JsonArray(
      entries.take(MAX_SEARCH_HISTORY).map { e ->
        searchJson.encodeToJsonElement(
          SearchHistoryDto.serializer(),
          SearchHistoryDto(
            e.query.trim().take(MAX_SEARCH_QUERY_LENGTH),
            e.scope.type.name,
            e.scope.id,
            e.scope.name,
          ),
        )
      }
    )
  )

internal fun decodeSearchHistory(raw: String?): List<SearchHistoryEntry> =
  runCatching {
      val array = searchJson.parseToJsonElement(raw ?: "[]") as? JsonArray ?: return emptyList()
      array
        .mapNotNull { item ->
          val value = item as? JsonObject ?: return@mapNotNull null
          val query =
            (value["query"] as? JsonPrimitive)
              ?.takeIf { it.isString }
              ?.content
              ?.trim()
              ?.take(MAX_SEARCH_QUERY_LENGTH)
              ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
          val type =
            (value["scopeType"] as? JsonPrimitive)
              ?.takeIf { it.isString }
              ?.content
              ?.trim()
              ?.let { runCatching { SearchScopeType.valueOf(it) }.getOrNull() }
              ?: return@mapNotNull null
          val id = legacySearchString(value["scopeId"]).orEmpty()
          val name = legacySearchString(value["scopeName"]).orEmpty()
          if (type != SearchScopeType.GLOBAL && id.isBlank()) return@mapNotNull null
          val dtoElement = buildJsonObject {
            put("query", query)
            put("scopeType", type.name)
            put("scopeId", id)
            put("scopeName", name.ifBlank { if (type == SearchScopeType.GLOBAL) "全站" else "" })
          }
          val dto = searchJson.decodeFromJsonElement<SearchHistoryDto>(dtoElement)
          SearchHistoryEntry(dto.query, SearchScope(type, dto.scopeId, dto.scopeName))
        }
        .distinctBy(SearchHistoryEntry::key)
        .take(MAX_SEARCH_HISTORY)
    }
    .getOrDefault(emptyList())

internal fun searchHistoryKey(entry: SearchHistoryEntry): String =
  "${entry.scope.type.name}:${if (entry.scope.type == SearchScopeType.GLOBAL) "" else entry.scope.id}:${entry.query.trim().lowercase(Locale.ROOT)}"

private fun legacySearchString(value: JsonElement?): String? =
  when (value) {
    null,
    JsonNull -> null
    is JsonPrimitive ->
      if (value.isString || value.longOrNull != null || value.doubleOrNull != null)
        value.content.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
      else null
    else -> null
  }

const val MAX_SEARCH_HISTORY = 30
const val MAX_SEARCH_QUERY_LENGTH = 120
