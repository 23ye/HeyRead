package cn.heyread.app.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.local.SearchHistoryEntry
import cn.heyread.app.data.local.SearchHistoryStore
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.OnlineSearchBatch
import cn.heyread.app.data.remote.OnlineSearchLoader
import cn.heyread.app.ui.screens.SearchScreen
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.SearchViewModel
import cn.heyread.app.viewmodel.UiState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun hashtagSearchSubmitsExplicitlyAndContinuesOnlyOneBatchPerTap() {
        val calls = CopyOnWriteArrayList<Int>()
        val history = MemorySearchHistory()
        val loader = OnlineSearchLoader { _, _, offset, _, _, _ ->
            calls += offset
            OnlineSearchBatch(
                listOf(
                    Story(
                        "post-$offset",
                        Source.HEYBOX,
                        "Post $offset",
                        "summary",
                        "summary",
                        "Author",
                        "Today",
                    )
                ),
                1,
                listOf("post-$offset"),
                offset + 1,
                true,
                "cursor-$offset",
            )
        }
        val vm =
            SearchViewModel(loader, history, SearchScope(SearchScopeType.HASHTAG, "55", "topic"))
        compose.setContent {
            HeyReadTheme("light") {
                Surface { SearchScreen(UiState(noImages = true), vm, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("search-query").performTextInput("needle")
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), calls.toList())
        compose.onNodeWithTag("search-submit").performClick()
        compose.waitForIdle()
        assertEquals(listOf(0), calls.toList())
        compose.onNodeWithText("继续查找一批").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf(0, 1), calls.toList())
        assertEquals("needle", history.entries.first().query)
    }

    @Test
    fun clearingSearchHistoryUsesCancelableMaterialConfirmationAndConfirmsOnce() {
        val history =
            MemorySearchHistory().apply {
                entries += SearchHistoryEntry("keep-record", SearchScope.GLOBAL)
            }
        val vm =
            SearchViewModel(
                OnlineSearchLoader { _, _, _, _, _, _ -> error("unexpected search") },
                history,
                SearchScope.GLOBAL,
            )
        compose.setContent {
            HeyReadTheme("light") {
                Surface { SearchScreen(UiState(noImages = true), vm, {}, {}, {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("keep-record").assertIsDisplayed()
        compose.onNodeWithText("清空搜索记录").performClick()
        compose.onNodeWithText("清空本机搜索记录？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals(listOf("keep-record"), history.entries.map { it.query })

        compose.onNodeWithText("清空搜索记录").performClick()
        compose.onNodeWithText("清空", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<SearchHistoryEntry>(), history.entries.toList())
        compose.onNodeWithText("清空本机搜索记录？").assertDoesNotExist()
    }

    private class MemorySearchHistory : SearchHistoryStore {
        val entries = CopyOnWriteArrayList<SearchHistoryEntry>()

        override suspend fun searchHistory() = entries.toList()

        override suspend fun recordSearch(entry: SearchHistoryEntry) {
            entries.removeAll { it.key == entry.key }
            entries.add(0, entry)
        }

        override suspend fun removeSearch(entry: SearchHistoryEntry) {
            entries.removeAll { it.key == entry.key }
        }

        override suspend fun clearSearchHistory() {
            entries.clear()
        }
    }
}
