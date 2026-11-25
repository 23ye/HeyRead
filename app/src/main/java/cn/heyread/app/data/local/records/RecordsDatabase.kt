package cn.heyread.app.data.local.records

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_reading_history", primaryKeys = ["source", "storyId"])
data class ReadingHistoryEntity(
    val source: String,
    val storyId: String,
    val snapshot: String,
    val position: Long,
)

@Entity(tableName = "local_search_history", primaryKeys = ["entryKey"])
data class SearchHistoryEntity(
    val entryKey: String,
    val query: String,
    val scopeType: String,
    val scopeId: String,
    val scopeName: String,
    val position: Long,
)

@Entity(tableName = "record_metadata")
data class RecordMetadataEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface RecordsDao {
  @Query("SELECT * FROM local_reading_history ORDER BY position DESC")
  fun observeHistory(): Flow<List<ReadingHistoryEntity>>

  @Query("SELECT * FROM local_reading_history ORDER BY position DESC")
  suspend fun readHistory(): List<ReadingHistoryEntity>

  @Query("SELECT MAX(position) FROM local_reading_history") suspend fun maxHistoryPosition(): Long?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHistory(row: ReadingHistoryEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHistory(rows: List<ReadingHistoryEntity>)

  @Query("DELETE FROM local_reading_history WHERE source = :source AND storyId = :storyId")
  suspend fun deleteHistory(source: String, storyId: String)

  @Query(
      "DELETE FROM local_reading_history WHERE position NOT IN (SELECT position FROM local_reading_history ORDER BY position DESC LIMIT :limit)")
  suspend fun trimHistory(limit: Int)

  @Query("DELETE FROM local_reading_history") suspend fun clearHistory()

  @Query("SELECT * FROM local_search_history ORDER BY position DESC")
  fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

  @Query("SELECT * FROM local_search_history ORDER BY position DESC")
  suspend fun readSearchHistory(): List<SearchHistoryEntity>

  @Query("SELECT MAX(position) FROM local_search_history") suspend fun maxSearchPosition(): Long?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSearch(row: SearchHistoryEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSearch(rows: List<SearchHistoryEntity>)

  @Query("DELETE FROM local_search_history WHERE entryKey = :key")
  suspend fun deleteSearch(key: String)

  @Query(
      "DELETE FROM local_search_history WHERE position NOT IN (SELECT position FROM local_search_history ORDER BY position DESC LIMIT :limit)")
  suspend fun trimSearch(limit: Int)

  @Query("DELETE FROM local_search_history") suspend fun clearSearchHistory()

  @Query("SELECT value FROM record_metadata WHERE `key` = :key LIMIT 1")
  suspend fun metadataValue(key: String): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMetadata(row: RecordMetadataEntity)
}

@Database(
    entities =
        [ReadingHistoryEntity::class, SearchHistoryEntity::class, RecordMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HeyReadRecordsDatabase : RoomDatabase() {
  abstract fun recordsDao(): RecordsDao
}

fun openHeyReadRecordsDatabase(context: Context): HeyReadRecordsDatabase =
    Room.databaseBuilder(
            context.applicationContext,
            HeyReadRecordsDatabase::class.java,
            "heyread_records.db",
        )
        .build()
