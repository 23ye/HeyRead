package cn.heyread.app.data.local

import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import org.junit.Assert.*
import org.junit.Test

class SearchHistoryCodecTest {
    @Test
    fun codecKeepsScopeAndDropsBadTypesAndInvalidIds() {
        val decoded =
            decodeSearchHistory(
                """[{"query":"alpha","scopeType":"SECTION","scopeId":18745,"scopeName":"Hardware"},{"query":42,"scopeType":"GLOBAL"},{"query":"bad","scopeType":"SECTION","scopeId":""},{"query":"tag","scopeType":"HASHTAG","scopeId":"55","scopeName":"topic"}]"""
            )
        assertEquals(2, decoded.size)
        assertEquals("18745", decoded[0].scope.id)
        assertEquals(SearchScopeType.HASHTAG, decoded[1].scope.type)
        assertEquals(decoded, decodeSearchHistory(encodeSearchHistory(decoded)))
    }

    @Test
    fun duplicateScopesNormalizeQueryAndLimitThirty() {
        val entries =
            listOf(
                SearchHistoryEntry(" Alpha ", SearchScope(SearchScopeType.SECTION, "10", "old"))
            ) + (0..35).map { SearchHistoryEntry("q$it", SearchScope.GLOBAL) }
        val normalized = decodeSearchHistory(encodeSearchHistory(entries))
        assertEquals(MAX_SEARCH_HISTORY, normalized.size)
        assertEquals("Alpha", normalized.first().query)
        val repeat =
            decodeSearchHistory(
                encodeSearchHistory(
                    listOf(
                        SearchHistoryEntry(
                            "ALPHA",
                            SearchScope(SearchScopeType.SECTION, "10", "new"),
                        ),
                        normalized.first(),
                    )
                )
            )
        assertEquals(1, repeat.size)
        assertEquals("new", repeat.single().scope.name)
    }

    @Test
    fun literalNullQueryIsValidButJsonNullIsDropped() {
        val values =
            decodeSearchHistory(
                """[{"query":"null","scopeType":"GLOBAL"},{"query":null,"scopeType":"GLOBAL"}]"""
            )
        assertEquals(listOf("null"), values.map { it.query })
    }
}
