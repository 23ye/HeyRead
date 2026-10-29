package cn.heyread.app.ui.motion

import cn.heyread.app.data.model.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutPaginationGateTest {
    @Test
    fun layoutReflowSuppressesAutoloadUntilExplicitScrollOrLoad() {
        val initial = HomeLayoutPaginationGate(HomeLayout.A)
        assertFalse(initial.observeLayout(HomeLayout.A).suppressAutoPagination)

        val afterLayoutChange = initial.observeLayout(HomeLayout.B)
        assertEquals(HomeLayout.B, afterLayoutChange.observedLayout)
        assertTrue(afterLayoutChange.suppressAutoPagination)
        assertTrue(afterLayoutChange.userScrolled().observedLayout == HomeLayout.B)
        assertFalse(afterLayoutChange.userScrolled().suppressAutoPagination)
    }
}
