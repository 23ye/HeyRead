package cn.heyread.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.records.HeyReadRecordsDatabase
import cn.heyread.app.data.local.records.LegacyRecordsSnapshot
import cn.heyread.app.data.local.records.LocalRecordsStore
import cn.heyread.app.data.local.records.RoomRecordsRepository
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Isolated DataStore snapshot source and in-memory Room store for instrumentation tests. */
class RecordsTestRule : TestWatcher() {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var scope: CoroutineScope
  private val databases = mutableListOf<HeyReadRecordsDatabase>()
  private val recordsByPreferences = IdentityHashMap<DataStore<Preferences>, LocalRecordsStore>()

  lateinit var preferences: DataStore<Preferences>
    private set
  lateinit var records: LocalRecordsStore
    private set

  override fun starting(description: Description) {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    root = File(context.cacheDir, "records-test-${UUID.randomUUID()}").apply { mkdirs() }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    preferences = createPreferencesStore(scope, File(root, "prefs.preferences_pb"))
    records = recordsFor(preferences)
  }

  override fun finished(description: Description) {
    runBlocking {
      scope.cancel()
      scope.coroutineContext[kotlinx.coroutines.Job]?.join()
      withContext(Dispatchers.IO) { databases.forEach(HeyReadRecordsDatabase::close) }
    }
    databases.clear()
    recordsByPreferences.clear()
    root.deleteRecursively()
  }

  fun recordsFor(preferencesStore: DataStore<Preferences>): LocalRecordsStore =
      recordsByPreferences.getOrPut(preferencesStore) {
        newRepository(preferencesStore)
      }

  fun newRepository(
      preferencesStore: DataStore<Preferences>,
      onBeforeMigrationCommit: suspend () -> Unit = {},
  ): LocalRecordsStore {
    val db = Room.inMemoryDatabaseBuilder(context, HeyReadRecordsDatabase::class.java).build()
    databases += db
    return RoomRecordsRepository(
        db = db,
        legacySource = {
          val snapshot = preferencesStore.data.first()
          LegacyRecordsSnapshot(
              historyJson = snapshot[stringPreferencesKey("history")],
              searchHistoryJson = snapshot[stringPreferencesKey("search_history")],
          )
        },
        onBeforeMigrationCommit = onBeforeMigrationCommit,
    )
  }
  private fun createPreferencesStore(
      scope: CoroutineScope,
      file: File,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
}
