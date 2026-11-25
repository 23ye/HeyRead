package cn.heyread.app.data.local

import androidx.room.Room
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.records.HeyReadRecordsDatabase
import cn.heyread.app.data.local.records.LegacyRecordsSnapshot
import cn.heyread.app.data.local.records.RoomRecordsRepository
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Confirms Room migration samples the injected DataStore on a worker thread. */
@RunWith(AndroidJUnit4::class)
class LibraryStoreHistoryDispatcherTest {
  @get:Rule val recordsRule = RecordsTestRule()

  @Test
  fun migrationAndRoomReadsDoNotRunLegacySnapshotReadOnMain() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val stories = (0 until 80).map { index ->
      Story("history-$index", Source.DEMO, "Title $index", "Excerpt", "Body", "Author", "date")
    }
    val rawHistory = encodeHistoryArray(stories)
    val historyKey = stringPreferencesKey("history")
    runBlocking {
      recordsRule.preferences.updateData { it.toMutablePreferences().apply { this[historyKey] = rawHistory } }
    }

    var snapshotThread: Thread? = null
    val db = Room.inMemoryDatabaseBuilder(context, HeyReadRecordsDatabase::class.java).build()
    val records =
      RoomRecordsRepository(db, legacySource = {
        snapshotThread = Thread.currentThread()
        val snapshot = recordsRule.preferences.data.first()
        LegacyRecordsSnapshot(snapshot[historyKey], snapshot[stringPreferencesKey("search_history")])
      })
    val store = LibraryStore(context, recordsRule.preferences, records)
    val main = android.os.Looper.getMainLooper().thread
    try {
      instrumentation.runOnMainSync {
        runBlocking {
          assertEquals(80, store.history().size)
          store.addHistory(stories.first().copy(title = "Moved to front"))
          assertEquals("Moved to front", store.history().first().title)
        }
      }
      assertNotSame("legacy DataStore snapshot must be read off Main", main, snapshotThread)
    } finally {
      db.close()
    }
  }
}
