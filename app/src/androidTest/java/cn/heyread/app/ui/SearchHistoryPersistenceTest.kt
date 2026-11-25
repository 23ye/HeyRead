package cn.heyread.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.local.RecordsTestRule
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchHistoryPersistenceTest {
    @get:Rule val recordsRule = RecordsTestRule()
    @Test
    fun themeAccentAndMotionPersistIndependentlyOfSearchAndReadingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context, recordsRule.preferences, recordsRule.records)
        val beforeAccent = store.getThemeAccent()
        val beforeMotion = store.getMotionEnabled()
        val beforeSearch = store.searchHistory()
        val beforeReading = store.history()
        try {
            store.setThemeAccent("purple")
            store.setMotionEnabled(!beforeMotion)

            val reopened = LibraryStore(context, recordsRule.preferences, recordsRule.records)
            assertEquals("purple", reopened.getThemeAccent())
            assertEquals(!beforeMotion, reopened.getMotionEnabled())
            assertEquals(beforeSearch, reopened.searchHistory())
            assertEquals(beforeReading, reopened.history())
        } finally {
            store.setThemeAccent(beforeAccent)
            store.setMotionEnabled(beforeMotion)
        }
    }

    @Test
    fun recordsSurviveStoreRecreationAndClearingLeavesReadingHistoryUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context, recordsRule.preferences, recordsRule.records)
        val beforeSearch = store.searchHistory()
        val beforeReading = store.history()
        try {
            store.clearSearchHistory()
            val global = SearchHistoryEntry("synthetic global", SearchScope.GLOBAL)
            val topic =
                SearchHistoryEntry(
                    "synthetic topic",
                    SearchScope(SearchScopeType.HASHTAG, "test-55", "Synthetic topic"),
                )
            store.recordSearch(global)
            store.recordSearch(topic)

            val reopened = LibraryStore(context, recordsRule.preferences, recordsRule.records)
            assertEquals(listOf(topic, global), reopened.searchHistory())
            reopened.removeSearch(topic)
            assertEquals(listOf(global), store.searchHistory())
            reopened.clearSearchHistory()
            assertTrue(store.searchHistory().isEmpty())
            assertEquals(beforeReading, store.history())
        } finally {
            store.clearSearchHistory()
            for (entry in beforeSearch.asReversed()) store.recordSearch(entry)
        }
    }
}
