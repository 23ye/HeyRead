package cn.heyread.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.heyread.app.data.local.records.HeyReadRecordsDatabase
import cn.heyread.app.data.local.records.LegacyRecordsSnapshot
import cn.heyread.app.data.local.records.RoomRecordsRepository
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.Channel
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRecordsRepositoryTest {
  @get:Rule val recordsRule = RecordsTestRule()
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun migrationPreservesOrderDeduplicatesBoundsAndStoresOnlyHistoryMetadata() = runBlocking {
    val rows = (0 until 85).map { story("row-$it") }.toMutableList().apply {
      this[3] =
          this[3].copy(
              image = "https://example.invalid/cover.jpg",
              url = "https://example.invalid/story",
              appId = 42,
              publishedAt = 1234L,
              channel = Channel.NEWS,
              authorProfile = AuthorProfile("Visible author", userId = "author-id"),
              sections = listOf(ArticleLabel("section-id", "Section")),
              hashtags = listOf(ArticleLabel("tag-id", "Tag")),
          )
    }
    rows[0] =
        rows[0].copy(
            feedVideo = ArticleBlock.Video("https://invalid.example/short"),
            detailBlocks = listOf(ArticleBlock.Paragraph("runtime-only detail")),
        )
    val duplicated = rows + rows[4].copy(title = "updated")
    val rawHistory = encodeHistoryArray(duplicated)
    val rawSearch =
        org.json.JSONArray().apply {
          repeat(32) { index ->
            put(
                org.json.JSONObject()
                    .put("query", " query-$index ")
                    .put("scopeType", "SECTION")
                    .put("scopeId", "section-$index")
                    .put("scopeName", "Section $index"),
            )
          }
        }.toString()
    putLegacy(
        rawHistory,
        rawSearch,
    )
    recordsRule.preferences.updateData { it.toMutablePreferences().apply {
      this[stringPreferencesKey("theme")] = "dark"
    } }

    val migrated = recordsRule.records.history()
    assertEquals(80, migrated.size)
    assertEquals("row-0", migrated.first().id)
    assertEquals(null, migrated.first().feedVideo)
    assertEquals(null, migrated.first().detailBlocks)
    assertEquals("Title row-4", migrated.first { it.id == "row-4" }.title)
    assertEquals(1, migrated.count { it.id == "row-4" })
    val metadata = migrated.first { it.id == "row-3" }
    assertEquals("https://example.invalid/cover.jpg", metadata.image)
    assertEquals(42, metadata.appId)
    assertEquals("author-id", metadata.authorProfile?.userId)
    assertEquals("section-id", metadata.sections.single().id)
    assertEquals("tag-id", metadata.hashtags.single().id)
    assertEquals(30, recordsRule.records.searchHistory().size)
    assertEquals("query-0", recordsRule.records.searchHistory().first().query)
    assertEquals("section-0", recordsRule.records.searchHistory().first().scope.id)
    val oldSnapshot = recordsRule.preferences.data.first()
    assertEquals(rawHistory, oldSnapshot[stringPreferencesKey("history")])
    assertEquals(rawSearch, oldSnapshot[stringPreferencesKey("search_history")])
    assertEquals("dark", oldSnapshot[stringPreferencesKey("theme")])

    recordsRule.records.addHistory(metadata.copy(title = "Reopened snapshot"))
    assertEquals("Reopened snapshot", recordsRule.records.history().first().title)
    recordsRule.records.addHistory(story("newest"))
    val afterOpen = recordsRule.records.history()
    assertEquals(80, afterOpen.size)
    assertEquals("newest", afterOpen.first().id)
    assertEquals("Reopened snapshot", afterOpen[1].title)
    assertEquals(1, afterOpen.count { it.id == "row-3" })
    assertEquals(1, afterOpen.count { it.id == "row-4" })
    assertEquals("row-78", afterOpen.last().id)

    val updatedSearch = SearchHistoryEntry("QUERY-0", SearchScope(SearchScopeType.SECTION, "section-0", "Section 0"))
    recordsRule.records.recordSearch(updatedSearch)
    val newestSearch = SearchHistoryEntry("new search", SearchScope(SearchScopeType.HASHTAG, "new-tag", "New tag"))
    recordsRule.records.recordSearch(newestSearch)
    val afterSearchWrite = recordsRule.records.searchHistory()
    assertEquals(30, afterSearchWrite.size)
    assertEquals("new search", afterSearchWrite.first().query)
    assertEquals("QUERY-0", afterSearchWrite[1].query)
    assertEquals(1, afterSearchWrite.count { it.key == updatedSearch.key })
    recordsRule.records.removeSearch(newestSearch)
    recordsRule.records.clearSearchHistory()
    assertTrue(recordsRule.records.searchHistory().isEmpty())
    assertEquals(80, recordsRule.records.history().size)
  }

  @Test
  fun failedMigrationRollsBackAndExplicitRetryCommitsWholeSnapshot() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        HeyReadRecordsDatabase::class.java,
    ).build()
    var shouldFail = true
    val repository =
        RoomRecordsRepository(
            db = db,
            legacySource = { LegacyRecordsSnapshot(encodeHistoryArray(listOf(story("retry"))), "[]") },
            onBeforeMigrationCommit = { if (shouldFail) error("synthetic migration failure") },
        )
    try {
      val failure = runCatching { repository.history() }.exceptionOrNull()
      assertTrue(failure is IllegalStateException)
      assertTrue(db.recordsDao().readHistory().isEmpty())
      assertTrue(db.recordsDao().readSearchHistory().isEmpty())
      assertEquals(null, db.recordsDao().metadataValue("legacy_records_migrated_v1"))
      shouldFail = false
      assertEquals(listOf("retry"), repository.history().map { it.id })
    } finally {
      db.close()
    }
  }

  @Test
  fun writesAndClearWaitForMigrationAndEmptyMarkerSurvivesReopenAndLegacyChanges() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(temporaryFolder.root, "records-${UUID.randomUUID()}.db")
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var snapshot = LegacyRecordsSnapshot(encodeHistoryArray(listOf(story("legacy"))), "[]")
    var db = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, file.absolutePath).build()
    var repository =
        RoomRecordsRepository(
            db = db,
            legacySource = { snapshot },
            onBeforeMigrationCommit = {
              entered.complete(Unit)
              release.await()
            },
        )
    val read = async { repository.history() }
    withTimeout(5_000) { entered.await() }
    val add = async(start = CoroutineStart.UNDISPATCHED) { repository.addHistory(story("new")) }
    val clear = async(start = CoroutineStart.UNDISPATCHED) { repository.clearHistory() }
    release.complete(Unit)
    assertEquals("legacy", read.await().first().id)
    add.await()
    clear.await()
    assertTrue(repository.history().isEmpty())
    db.close()

    snapshot = LegacyRecordsSnapshot(encodeHistoryArray(listOf(story("must-not-return"))), "[]")
    db = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, file.absolutePath).build()
    repository = RoomRecordsRepository(db, { snapshot })
    try {
      assertTrue(repository.history().isEmpty())
    } finally {
      db.close()
      file.delete()
    }

    val emptyFile = File(temporaryFolder.root, "empty-${UUID.randomUUID()}.db")
    var emptySnapshot = LegacyRecordsSnapshot(null, null)
    var emptyDb = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, emptyFile.absolutePath).build()
    try {
      repository = RoomRecordsRepository(emptyDb, legacySource = { emptySnapshot })
      assertTrue(repository.history().isEmpty())
      repository.clearHistory()
      emptyDb.close()
      emptySnapshot = LegacyRecordsSnapshot(encodeHistoryArray(listOf(story("late-old-key"))), "[]")
      emptyDb = Room.databaseBuilder(context, HeyReadRecordsDatabase::class.java, emptyFile.absolutePath).build()
      repository = RoomRecordsRepository(emptyDb, legacySource = { emptySnapshot })
      assertTrue(repository.history().isEmpty())
    } finally {
      emptyDb.close()
      emptyFile.delete()
    }
  }

  @Test
  fun actualFlowsObserveChangesAcrossRepositoryInstances() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        HeyReadRecordsDatabase::class.java,
    ).build()
    val repoA = RoomRecordsRepository(db, legacySource = { LegacyRecordsSnapshot(null, null) })
    val repoB = RoomRecordsRepository(db, legacySource = { LegacyRecordsSnapshot(null, null) })
    try {
      val history = async { withTimeout(5_000) { repoB.observeHistory().first { it.isNotEmpty() } } }
      repoA.addHistory(story("flow"))
      assertEquals("flow", history.await().single().id)
      val search = async { withTimeout(5_000) { repoB.observeSearchHistory().first { it.isNotEmpty() } } }
      repoA.recordSearch(
          SearchHistoryEntry("explicit", SearchScope(SearchScopeType.HASHTAG, "77", "topic")),
      )
      assertEquals("77", search.await().single().scope.id)
    } finally {
      db.close()
    }
  }

  private suspend fun putLegacy(history: String?, search: String?) {
    recordsRule.preferences.updateData { current ->
      current.toMutablePreferences().apply {
        if (history == null) remove(stringPreferencesKey("history"))
        else this[stringPreferencesKey("history")] = history
        if (search == null) remove(stringPreferencesKey("search_history"))
        else this[stringPreferencesKey("search_history")] = search
      }
    }
  }

  private fun story(id: String) =
      Story(id, Source.DEMO, "Title $id", "Excerpt", "Body", "Author", "date")
}
