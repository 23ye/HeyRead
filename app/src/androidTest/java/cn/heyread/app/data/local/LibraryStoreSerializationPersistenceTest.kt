package cn.heyread.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.records.HeyReadRecordsDatabase
import cn.heyread.app.data.local.records.LegacyRecordsSnapshot
import cn.heyread.app.data.local.records.RoomRecordsRepository
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStoreSerializationPersistenceTest {
  @get:Rule val recordsRule = RecordsTestRule()
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun roomHistoryAndSearchSurviveCloseReopenAndClear() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(temporaryFolder.root, "room-${UUID.randomUUID()}.db")
    var database = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, file.absolutePath).build()
    try {
      var repository = RoomRecordsRepository(database, legacySource = { LegacyRecordsSnapshot(null, null) })
      val story = Story("persisted", Source.DEMO, "Synthetic", "excerpt", "body", "author", "date")
      repository.addHistory(story)
      repository.recordSearch(
          SearchHistoryEntry(
              "explicit query",
              SearchScope(SearchScopeType.SECTION, "18745", "Hardware"),
          ),
      )
      assertEquals(listOf(story), repository.history())
      database.close()

      database = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, file.absolutePath).build()
      repository = RoomRecordsRepository(database, legacySource = { LegacyRecordsSnapshot(null, null) })
      assertEquals("persisted", repository.history().single().id)
      assertEquals("18745", repository.searchHistory().single().scope.id)
      repository.clearHistory()
      repository.clearSearchHistory()
      database.close()

      database = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, file.absolutePath).build()
      repository = RoomRecordsRepository(database, legacySource = { LegacyRecordsSnapshot(null, null) })
      assertEquals(emptyList<Story>(), repository.history())
      assertEquals(emptyList<SearchHistoryEntry>(), repository.searchHistory())
    } finally {
      database.close()
      file.delete()
    }
  }
}
