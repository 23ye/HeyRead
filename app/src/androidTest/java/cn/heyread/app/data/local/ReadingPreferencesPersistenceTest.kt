package cn.heyread.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.data.model.ReadingPreferences
import cn.heyread.app.data.model.Source
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.FeedTab
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingPreferencesPersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private var viewModelStore: ViewModelStore? = null
    private var preferencesScope: CoroutineScope? = null

    @After
    fun clearTestResources() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStore?.clear() }
        viewModelStore = null
        preferencesScope?.cancel()
        preferencesScope = null
    }

    @Test
    fun preferencesAreReadTogetherAfterReopenWithoutChangingExistingSettingsOrHistories() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val preferencesStore = createPreferencesStore()
            val fontsDirectory = temporaryFolder.newFolder("reading-fonts")
            val store = LibraryStore(context, fontsDirectory, preferencesStore)
            val owner = ViewModelStore()
            viewModelStore = owner
            lateinit var viewModel: AppViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel = AppViewModel(store)
                owner.put("reading-preferences-test", viewModel)
                viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
            }
            val beforeReading = store.getReadingPreferences()
            val beforeTheme = store.getTheme()
            val beforeAccent = store.getThemeAccent()
            val beforeMotion = store.getMotionEnabled()
            val beforeNoImages = store.getNoImages()
            val beforeGateway = store.getGateway()
            val beforeLayout = store.getHomeLayout()
            val beforeSearch = store.searchHistory()
            val beforeHistory = store.history()
            val intermediate = ReadingPreferences(fontSizeSp = 19f)
            val penultimate = ReadingPreferences(lineHeightMultiplier = 2.1f)
            val selected = ReadingPreferences(23f, 1.8f, 32f, ReadingFont.SERIF)

            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    viewModel.setReadingPreferences(intermediate)
                    viewModel.setReadingPreferences(penultimate)
                    viewModel.setReadingPreferences(selected)
                }
                assertEquals(selected, viewModel.state.value.readingPreferences)
                awaitStored(store, selected, viewModel, "selected reading preferences persist")
                val reopened = LibraryStore(context, fontsDirectory, preferencesStore)

                assertEquals(selected, reopened.getReadingPreferences())
                assertEquals(beforeTheme, reopened.getTheme())
                assertEquals(beforeAccent, reopened.getThemeAccent())
                assertEquals(beforeMotion, reopened.getMotionEnabled())
                assertEquals(beforeNoImages, reopened.getNoImages())
                assertEquals(beforeGateway, reopened.getGateway())
                assertEquals(beforeLayout, reopened.getHomeLayout())
                assertEquals(beforeSearch, reopened.searchHistory())
                assertEquals(beforeHistory, reopened.history())
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    viewModel.setReadingPreferences(beforeReading)
                }
                awaitStored(store, beforeReading, viewModel, "original reading preferences restore")
            }
            assertEquals(
                beforeReading,
                LibraryStore(context, fontsDirectory, preferencesStore).getReadingPreferences(),
            )
        }

    private suspend fun awaitStored(
        store: LibraryStore,
        expected: ReadingPreferences,
        viewModel: AppViewModel,
        step: String,
    ) {
        try {
            withTimeout(10_000) { while (store.getReadingPreferences() != expected) delay(25) }
        } catch (timeout: TimeoutCancellationException) {
            val current = viewModel.state.value
            val stored =
                withContext(NonCancellable) {
                    runCatching { store.getReadingPreferences().toString() }
                        .getOrElse { "<read failed: ${it.javaClass.simpleName}>" }
                }
            throw AssertionError(
                "Timed out at '$step'; expectedReadingPrefs=$expected, " +
                    "readingPrefs=${current.readingPreferences}, storedReadingPrefs=$stored, " +
                    "fontBusy=${current.fontBusy}, fontNotice=${current.fontNotice}, " +
                    "settingsNotice=${current.settingsNotice}, " +
                    "importedFontCount=${current.importedFonts.size}, " +
                    "discoverSource=${current.discoverFeed.source}, " +
                    "discoverLoading=${current.discoverFeed.loading}",
                timeout,
            )
        }
    }

    private fun createPreferencesStore(): DataStore<Preferences> {
        val root = temporaryFolder.newFolder("reading-preferences-context")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferencesScope = scope
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(root, "reading.preferences_pb")
        }
    }
}
