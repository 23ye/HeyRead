package cn.heyread.app.navigation

import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationRouteTest {
    @Test
    fun searchRouteRetainsScopeAndEncodesQueryForHistoryReplay() {
        assertEquals("search?query=hello%20world", searchRoute(SearchScope.GLOBAL, "hello world"))
        assertEquals(
            "search?scopeType=HASHTAG&scopeId=tag%2F2&scopeName=%E6%94%BB%E7%95%A5&query=hello%20world",
            searchRoute(SearchScope(SearchScopeType.HASHTAG, "tag/2", "攻略"), "hello world"),
        )
    }

    @Test
    fun encodesIdsAndUsesNonEmptyTitleSegments() {
        assertEquals(
            "destination/USER/user%2F42/%E7%94%A8%E6%88%B7%E4%B8%BB%E9%A1%B5/-",
            destinationRoute(DestinationTarget(DestinationType.USER, "user/42", "")),
        )
        assertEquals(
            "destination/HASHTAG/tag%201/%E6%94%BB%E7%95%A5/-",
            destinationRoute(DestinationTarget(DestinationType.HASHTAG, "tag 1", "攻略")),
        )
        assertNull(destinationRoute(DestinationTarget(DestinationType.USER, "", "Name")))
    }
}
