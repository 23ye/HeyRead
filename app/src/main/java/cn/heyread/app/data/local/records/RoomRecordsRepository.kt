package cn.heyread.app.data.local.records

import androidx.room.withTransaction
import cn.heyread.app.data.local.MAX_SEARCH_HISTORY
import cn.heyread.app.data.local.MAX_SEARCH_QUERY_LENGTH
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.local.SearchHistoryStore
import cn.heyread.app.data.local.decodeHistoryArray
import cn.heyread.app.data.local.decodeHistoryStory
import cn.heyread.app.data.local.decodeSearchHistory
import cn.heyread.app.data.local.encodeHistoryStory
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

data class LegacyRecordsSnapshot(val historyJson: String?, val searchHistoryJson: String?)

interface LocalRecordsStore : SearchHistoryStore {
  suspend fun history(): List<Story>

  fun observeHistory(): Flow<List<Story>>

  suspend fun addHistory(story: Story)

  suspend fun clearHistory()

  override fun observeSearchHistory(): Flow<List<SearchHistoryEntry>>
}

class RoomRecordsRepository(
    private val db: HeyReadRecordsDatabase,
    private val legacySource: suspend () -> LegacyRecordsSnapshot,
    private val onBeforeMigrationCommit: suspend () -> Unit = {},
) : LocalRecordsStore {
  private val dao = db.recordsDao()
  private val mutationMutex = Mutex()

  override suspend fun history(): List<Story> = withInitialized {
    readHistoryRows(dao.readHistory())
  }

  override fun observeHistory(): Flow<List<Story>> =
      flow {
            withInitialized { Unit }
            emitAll(dao.observeHistory().map(::readHistoryRows))
          }
          .flowOn(Dispatchers.IO)

  override suspend fun addHistory(story: Story) = withInitialized {
    db.withTransaction {
      val next = nextHistoryPosition()
      dao.insertHistory(
          ReadingHistoryEntity(story.source.name, story.id, encodeHistoryStory(story), next))
      dao.trimHistory(MAX_LOCAL_HISTORY)
    }
  }

  override suspend fun clearHistory() = withInitialized {
    db.withTransaction { dao.clearHistory() }
  }

  override suspend fun searchHistory(): List<SearchHistoryEntry> = withInitialized {
    readSearchRows(dao.readSearchHistory())
  }

  override fun observeSearchHistory(): Flow<List<SearchHistoryEntry>> =
      flow {
            withInitialized { Unit }
            emitAll(dao.observeSearchHistory().map(::readSearchRows))
          }
          .flowOn(Dispatchers.IO)

  override suspend fun recordSearch(entry: SearchHistoryEntry) {
    val query = entry.query.trim().take(MAX_SEARCH_QUERY_LENGTH)
    require(query.isNotBlank() && entry.scope.isValid) { "无效的搜索记录" }
    val normalized = entry.copy(query = query)
    withInitialized {
      db.withTransaction {
        dao.insertSearch(
            SearchHistoryEntity(
                normalized.key,
                query,
                normalized.scope.type.name,
                normalized.scope.id,
                normalized.scope.name,
                nextSearchPosition(),
            ))
        dao.trimSearch(MAX_SEARCH_HISTORY)
      }
    }
  }

  override suspend fun removeSearch(entry: SearchHistoryEntry) = withInitialized {
    db.withTransaction { dao.deleteSearch(entry.key) }
  }

  override suspend fun clearSearchHistory() = withInitialized {
    db.withTransaction { dao.clearSearchHistory() }
  }

  private suspend fun <T> withInitialized(block: suspend () -> T): T {
    mutationMutex.lock()
    try {
      return withContext(Dispatchers.IO) {
        ensureMigrated()
        block()
      }
    } finally {
      mutationMutex.unlock()
    }
  }

  private suspend fun ensureMigrated() {
    if (dao.metadataValue(MIGRATION_KEY) == MIGRATION_COMPLETE) return
    val legacy = legacySource()
    db.withTransaction {
      if (dao.metadataValue(MIGRATION_KEY) == MIGRATION_COMPLETE) return@withTransaction
      val oldHistory = decodeLegacyHistory(legacy.historyJson)
      val oldSearch = decodeLegacySearch(legacy.searchHistoryJson)
      val historyRows = oldHistory.distinctBy { it.source.name to it.id }.take(MAX_LOCAL_HISTORY)
      val searchRows = oldSearch.distinctBy(SearchHistoryEntry::key).take(MAX_SEARCH_HISTORY)
      dao.insertHistory(
          historyRows.mapIndexed { index, story ->
            ReadingHistoryEntity(
                story.source.name,
                story.id,
                encodeHistoryStory(story),
                historyRows.size.toLong() - index,
            )
          })
      dao.insertSearch(
          searchRows.mapIndexed { index, entry ->
            SearchHistoryEntity(
                entry.key,
                entry.query,
                entry.scope.type.name,
                entry.scope.id,
                entry.scope.name,
                searchRows.size.toLong() - index,
            )
          })
      dao.insertMetadata(RecordMetadataEntity(MIGRATION_KEY, MIGRATION_COMPLETE))
      onBeforeMigrationCommit()
    }
  }

  private suspend fun nextHistoryPosition(): Long {
    val max = dao.maxHistoryPosition() ?: 0L
    if (max < Long.MAX_VALUE) return max + 1
    val rows = dao.readHistory()
    dao.clearHistory()
    val compacted =
        rows.mapIndexed { index, row -> row.copy(position = rows.size.toLong() - index) }
    dao.insertHistory(compacted)
    return compacted.size.toLong() + 1
  }

  private suspend fun nextSearchPosition(): Long {
    val max = dao.maxSearchPosition() ?: 0L
    if (max < Long.MAX_VALUE) return max + 1
    val rows = dao.readSearchHistory()
    dao.clearSearchHistory()
    val compacted =
        rows.mapIndexed { index, row -> row.copy(position = rows.size.toLong() - index) }
    dao.insertSearch(compacted)
    return compacted.size.toLong() + 1
  }

  private fun readHistoryRows(rows: List<ReadingHistoryEntity>): List<Story> =
      rows.map { row -> decodeHistoryStory(row.snapshot) ?: error(LOCAL_HISTORY_CORRUPT) }

  private fun readSearchRows(rows: List<SearchHistoryEntity>): List<SearchHistoryEntry> =
      rows.map { row ->
        val type =
            runCatching { SearchScopeType.valueOf(row.scopeType) }.getOrNull()
                ?: error(LOCAL_HISTORY_CORRUPT)
        SearchHistoryEntry(row.query, SearchScope(type, row.scopeId, row.scopeName))
      }

  private fun decodeLegacyHistory(raw: String?): List<Story> {
    validateLegacyArray(raw)
    return decodeHistoryArray(raw)
  }

  private fun decodeLegacySearch(raw: String?): List<SearchHistoryEntry> {
    validateLegacyArray(raw)
    return decodeSearchHistory(raw)
  }

  private fun validateLegacyArray(raw: String?) {
    val parsed =
        try {
          legacyJson.parseToJsonElement(raw ?: "[]")
        } catch (_: Exception) {
          error(LEGACY_HISTORY_CORRUPT)
        }
    require(parsed is JsonArray) { LEGACY_HISTORY_CORRUPT }
  }

  private companion object {
    const val MIGRATION_KEY = "legacy_records_migrated_v1"
    const val MIGRATION_COMPLETE = "complete"
    const val MAX_LOCAL_HISTORY = 80
    const val LEGACY_HISTORY_CORRUPT = "本机历史迁移数据格式无效，请检查旧记录后重试。"
    const val LOCAL_HISTORY_CORRUPT = "本机记录数据格式无效，请重试或联系支持。"
    val legacyJson = Json { ignoreUnknownKeys = true }
  }
}
