package cn.heyread.app.data.local

import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class SearchHistoryEntry(val query: String, val scope: SearchScope) {
    val key: String
        get() = scope.key(query)
}

interface SearchHistoryStore {
    suspend fun searchHistory(): List<SearchHistoryEntry>

    suspend fun recordSearch(entry: SearchHistoryEntry)

    suspend fun removeSearch(entry: SearchHistoryEntry)

    suspend fun clearSearchHistory()
}

internal fun encodeSearchHistory(entries: List<SearchHistoryEntry>): String =
    JSONArray()
        .apply {
            entries.take(MAX_SEARCH_HISTORY).forEach { entry ->
                put(
                    JSONObject()
                        .put("query", entry.query.trim().take(MAX_SEARCH_QUERY_LENGTH))
                        .put("scopeType", entry.scope.type.name)
                        .put("scopeId", entry.scope.id)
                        .put("scopeName", entry.scope.name)
                )
            }
        }
        .toString()

internal fun decodeSearchHistory(raw: String?): List<SearchHistoryEntry> =
    runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length())
                .mapNotNull { index ->
                    val value = array.optJSONObject(index) ?: return@mapNotNull null
                    val query =
                        strictHistoryString(value.opt("query"))
                            ?.take(MAX_SEARCH_QUERY_LENGTH)
                            ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val type =
                        strictHistoryString(value.opt("scopeType"))?.let {
                            runCatching { SearchScopeType.valueOf(it) }.getOrNull()
                        } ?: return@mapNotNull null
                    val id = safeHistoryString(value.opt("scopeId")).orEmpty()
                    val name = safeHistoryString(value.opt("scopeName")).orEmpty()
                    if (type != SearchScopeType.GLOBAL && id.isBlank()) return@mapNotNull null
                    SearchHistoryEntry(
                        query,
                        SearchScope(
                            type,
                            id,
                            name.ifBlank { if (type == SearchScopeType.GLOBAL) "全站" else "" },
                        ),
                    )
                }
                .distinctBy(SearchHistoryEntry::key)
                .take(MAX_SEARCH_HISTORY)
        }
        .getOrDefault(emptyList())

internal fun searchHistoryKey(entry: SearchHistoryEntry): String =
    "${entry.scope.type.name}:${if (entry.scope.type == SearchScopeType.GLOBAL) "" else entry.scope.id}:${entry.query.trim().lowercase(Locale.ROOT)}"

private fun safeHistoryString(value: Any?): String? =
    when (value) {
        null,
        JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
        is Number -> value.toString()
        else -> null
    }

private fun strictHistoryString(value: Any?): String? =
    (value as? String)?.trim()?.takeIf(String::isNotBlank)

const val MAX_SEARCH_HISTORY = 30
const val MAX_SEARCH_QUERY_LENGTH = 120
