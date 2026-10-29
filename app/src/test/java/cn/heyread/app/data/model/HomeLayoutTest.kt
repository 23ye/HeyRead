package cn.heyread.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun missingAndInvalidStoredValuesDefaultToLayoutA() {
        assertEquals(HomeLayout.A, HomeLayout.fromStoredValue(null))
        assertEquals(HomeLayout.A, HomeLayout.fromStoredValue(""))
        assertEquals(HomeLayout.A, HomeLayout.fromStoredValue("unknown"))
        assertEquals(HomeLayout.A, HomeLayout.fromStoredValue(" a "))
    }

    @Test
    fun eachLayoutHasAnIndependentStableStoredValue() {
        assertEquals(HomeLayout.A, HomeLayout.fromStoredValue("a"))
        assertEquals(HomeLayout.B, HomeLayout.fromStoredValue("b"))
        assertEquals(HomeLayout.C, HomeLayout.fromStoredValue("c"))
        assertEquals(3, HomeLayout.entries.map { it.storedValue }.distinct().size)
    }
}
