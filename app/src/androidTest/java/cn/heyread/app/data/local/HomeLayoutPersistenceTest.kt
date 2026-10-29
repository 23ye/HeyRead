package cn.heyread.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.HomeLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLayoutPersistenceTest {
    @Test
    fun homeLayoutPersistsIndependentlyOfOtherPreferencesAndHistories() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context)
        val beforeLayout = store.getHomeLayout()
        val beforeTheme = store.getTheme()
        val beforeAccent = store.getThemeAccent()
        val beforeMotion = store.getMotionEnabled()
        val beforeNoImages = store.getNoImages()
        val beforeGateway = store.getGateway()
        val beforeSearch = store.searchHistory()
        val beforeReading = store.history()
        try {
            store.setHomeLayout(HomeLayout.C)
            val reopened = LibraryStore(context)

            assertEquals(HomeLayout.C, reopened.getHomeLayout())
            assertEquals(beforeTheme, reopened.getTheme())
            assertEquals(beforeAccent, reopened.getThemeAccent())
            assertEquals(beforeMotion, reopened.getMotionEnabled())
            assertEquals(beforeNoImages, reopened.getNoImages())
            assertEquals(beforeGateway, reopened.getGateway())
            assertEquals(beforeSearch, reopened.searchHistory())
            assertEquals(beforeReading, reopened.history())
        } finally {
            store.setHomeLayout(beforeLayout)
        }
    }

    @Test
    fun storedLayoutAReopensAndDoesNotOverwriteHistories() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context)
        val beforeLayout = store.getHomeLayout()
        val beforeSearch = store.searchHistory()
        val beforeReading = store.history()
        try {
            store.setHomeLayout(HomeLayout.A)
            assertEquals(HomeLayout.A, LibraryStore(context).getHomeLayout())
            assertEquals(beforeSearch, store.searchHistory())
            assertEquals(beforeReading, store.history())
        } finally {
            store.setHomeLayout(beforeLayout)
        }
    }
}
