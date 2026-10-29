package cn.heyread.app.data.local

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.data.model.ReadingPreferences
import cn.heyread.app.data.model.Source
import cn.heyread.app.ui.components.LocalCustomReadingTypeface
import cn.heyread.app.ui.screens.ArticleBlockContent
import cn.heyread.app.ui.theme.HeyReadTheme
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingFontImportTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val temporaryFolder = TemporaryFolder()

    private var viewModelStore: ViewModelStore? = null
    private var preferencesScope: CoroutineScope? = null

    @After
    fun clearViewModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStore?.clear() }
        viewModelStore = null
        preferencesScope?.cancel()
        preferencesScope = null
    }

    @Test
    fun contentImportLoadsAndPersistsSyntheticTypefaceDeduplicatesRejectsBadBytesAndRemovesSafely() =
        runBlocking {
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val originalTargetPreferences = LibraryStore(targetContext).getReadingPreferences()
            val fixtureContext = targetContext
            val fontsDirectory = temporaryFolder.newFolder("private-fonts")
            val preferencesStore = createPreferencesStore("font-import-preferences")
            val store = LibraryStore(fixtureContext, fontsDirectory, preferencesStore)
            val owner = ViewModelStore()
            viewModelStore = owner
            lateinit var viewModel: AppViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel = AppViewModel(store)
                owner.put("reading-font-import-test", viewModel)
                viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
            }

            val fixtureUri = fontUri("synthetic-a.ttf")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.importReadingFont(fixtureUri)
            }
            awaitState(viewModel, store, "first synthetic font import and load") {
                !it.fontBusy &&
                    it.importedFonts.size == 1 &&
                    it.readingPreferences.font == ReadingFont.CUSTOM &&
                    it.customFontTypeface != null
            }
            val imported = viewModel.state.value.importedFonts.single()
            assertEquals("synthetic-a", imported.displayName)
            val selectedFirst = viewModel.state.value.readingPreferences
            assertEquals(ReadingFont.CUSTOM, selectedFirst.font)
            assertEquals(imported.id, selectedFirst.customFontId)
            assertNotNull(viewModel.state.value.customFontTypeface)
            val renderedTypeface =
                mutableStateOf(requireNotNull(viewModel.state.value.customFontTypeface))
            val renderedPreferences = mutableStateOf(selectedFirst)
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalCustomReadingTypeface provides renderedTypeface.value
                ) {
                    HeyReadTheme("light") {
                        Column(Modifier.fillMaxWidth()) {
                            ArticleBlockContent(
                                ArticleBlock.Paragraph("AAAA AAAA AAAA"),
                                noImages = true,
                                onOpenImage = {},
                                readingPreferences =
                                    renderedPreferences.value.copy(
                                        font = ReadingFont.SYSTEM,
                                        customFontId = null,
                                    ),
                            )
                            ArticleBlockContent(
                                ArticleBlock.Paragraph("AAAA AAAA AAAA"),
                                noImages = true,
                                onOpenImage = {},
                                readingPreferences = renderedPreferences.value,
                            )
                        }
                    }
                }
            }
            val installed = ReadingFontFiles.fileForId(fontsDirectory, imported.id)
            assertTrue("a private hash-named copy is installed", installed.isFile)
            assertEquals(
                imported,
                LibraryStore(fixtureContext, fontsDirectory, preferencesStore)
                    .getImportedReadingFonts()
                    .single(),
            )
            assertEquals(
                selectedFirst,
                LibraryStore(fixtureContext, fontsDirectory, preferencesStore)
                    .getReadingPreferences(),
            )
            val reopenedOwner = ViewModelStore()
            lateinit var reopenedViewModel: AppViewModel
            val reopenedStore = LibraryStore(fixtureContext, fontsDirectory, preferencesStore)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                reopenedViewModel = AppViewModel(reopenedStore)
                reopenedOwner.put("reading-font-reopen-test", reopenedViewModel)
                reopenedViewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
            }
            try {
                awaitState(
                    reopenedViewModel,
                    reopenedStore,
                    "reopened ViewModel restores selected font",
                ) {
                    !it.fontBusy &&
                        it.readingPreferences == selectedFirst &&
                        it.importedFonts == listOf(imported) &&
                        it.customFontTypeface != null
                }
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { reopenedOwner.clear() }
            }
            val firstFontPixels = customRenderedPixels()

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.importReadingFont(fontUri("synthetic-b.ttf"))
            }
            awaitState(viewModel, store, "second synthetic font import and selection") {
                !it.fontBusy &&
                    it.customFontTypeface != null &&
                    it.importedFonts.size == 2 &&
                    it.readingPreferences.font == ReadingFont.CUSTOM
            }
            val secondFont = viewModel.state.value.importedFonts.single { it.id != imported.id }
            compose.runOnIdle {
                renderedTypeface.value = requireNotNull(viewModel.state.value.customFontTypeface)
                renderedPreferences.value = viewModel.state.value.readingPreferences
            }
            compose.waitForIdle()
            val secondFontPixels = customRenderedPixels()
            assertNotEquals(
                "selecting another imported font changes real article glyphs",
                firstFontPixels,
                secondFontPixels,
            )

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.importReadingFont(fixtureUri)
            }
            awaitState(viewModel, store, "same-byte font reimport selects existing entry") {
                !it.fontBusy &&
                    it.customFontTypeface != null &&
                    it.readingPreferences.customFontId == imported.id &&
                    it.importedFonts == listOf(imported, secondFont)
            }
            assertEquals(listOf(imported, secondFont), viewModel.state.value.importedFonts)
            val selected = viewModel.state.value.readingPreferences
            assertEquals(imported.id, selected.customFontId)
            assertTrue("same bytes do not create a second library entry", installed.isFile)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.importReadingFont(fontUri("corrupt.ttf"))
            }
            awaitState(viewModel, store, "corrupt font import completes with a notice") {
                !it.fontBusy && it.fontNotice != null
            }
            assertEquals(selected, viewModel.state.value.readingPreferences)
            assertEquals(listOf(imported, secondFont), viewModel.state.value.importedFonts)
            assertTrue("a failed import preserves the prior registered file", installed.isFile)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.removeReadingFont(imported.id)
            }
            awaitState(viewModel, store, "selected font removal falls back to system font") {
                !it.fontBusy &&
                    it.importedFonts == listOf(secondFont) &&
                    it.readingPreferences.font == ReadingFont.SYSTEM
            }
            val afterRemoval = viewModel.state.value.readingPreferences
            assertEquals(selected.fontSizeSp, afterRemoval.fontSizeSp)
            assertEquals(selected.lineHeightMultiplier, afterRemoval.lineHeightMultiplier)
            assertEquals(selected.paragraphSpacingDp, afterRemoval.paragraphSpacingDp)
            assertFalse("only the selected private font is removed", installed.exists())
            val secondInstalled = ReadingFontFiles.fileForId(fontsDirectory, secondFont.id)
            assertTrue(
                "removing one font retains the other registered file",
                secondInstalled.isFile,
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.removeReadingFont(secondFont.id)
            }
            awaitState(viewModel, store, "last font removal completes") {
                !it.fontBusy && it.importedFonts.isEmpty()
            }
            assertFalse(secondInstalled.exists())
            assertTrue(
                LibraryStore(fixtureContext, fontsDirectory, preferencesStore)
                    .getImportedReadingFonts()
                    .isEmpty()
            )
            assertEquals(
                afterRemoval,
                LibraryStore(fixtureContext, fontsDirectory, preferencesStore)
                    .getReadingPreferences(),
            )
            assertEquals(
                "the production app's reading settings were untouched",
                originalTargetPreferences,
                LibraryStore(targetContext).getReadingPreferences(),
            )
        }

    @Test
    fun cancellingLargeContentUriClosesThePipeAndLeavesNoPendingFont() {
        val fixtureContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fontsDirectory = temporaryFolder.newFolder("cancelled-fonts")
        val preferencesStore = createPreferencesStore("cancelled-font-preferences")
        val owner = ViewModelStore()
        viewModelStore = owner
        lateinit var viewModel: AppViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = AppViewModel(LibraryStore(fixtureContext, fontsDirectory, preferencesStore))
            owner.put("reading-font-cancel-test", viewModel)
            viewModel.selectSource(FeedTab.DISCOVER, Source.DEMO)
            viewModel.importReadingFont(fontUri("slow.ttf"))
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val started = System.nanoTime()
            var slowState = slowWriterState()
            while (
                slowState.getLong(TestFontContentProvider.KEY_SLOW_BYTES_WRITTEN) < 32 * 1024 &&
                    (System.nanoTime() - started) < 5_000_000_000L
            ) {
                Thread.sleep(20)
                slowState = slowWriterState()
            }
            assertTrue(
                "the content resolver consumed the first controlled chunk",
                slowState.getLong(TestFontContentProvider.KEY_SLOW_BYTES_WRITTEN) >= 32 * 1024,
            )
            Thread.sleep(100)
            instrumentation.runOnMainSync { viewModel.cancelFontImport() }
        } finally {
            releaseSlowWriter()
        }
        assertTrue("the provider writer exits after cancellation", awaitSlowWriterStopped(5_000))
        assertTrue(
            "cancellation closes the blocked content reader",
            slowWriterState().getBoolean(TestFontContentProvider.KEY_SLOW_WRITER_CLOSED),
        )
        runBlocking {
            awaitState(
                viewModel,
                LibraryStore(fixtureContext, fontsDirectory, preferencesStore),
                "cancelled import settles",
            ) {
                !it.fontBusy
            }
        }
        assertTrue(viewModel.state.value.importedFonts.isEmpty())
        assertEquals(ReadingPreferences(), viewModel.state.value.readingPreferences)
        runBlocking {
            withTimeout(5_000) {
                while (fontsDirectory.listFiles().orEmpty().isNotEmpty()) delay(20)
            }
        }
        assertTrue(
            "cancel removes the pending private copy",
            fontsDirectory.listFiles().orEmpty().isEmpty(),
        )
    }

    private suspend fun awaitState(
        viewModel: AppViewModel,
        store: LibraryStore,
        step: String,
        predicate: (cn.heyread.app.viewmodel.UiState) -> Boolean,
    ) {
        try {
            withTimeout(15_000) { while (!predicate(viewModel.state.value)) delay(20) }
        } catch (timeout: TimeoutCancellationException) {
            val current = viewModel.state.value
            val stored =
                withContext(NonCancellable) {
                    runCatching { store.getReadingPreferences().toString() }
                        .getOrElse { "<read failed: ${it.javaClass.simpleName}>" }
                }
            throw AssertionError(
                "Timed out at '$step'; readingPrefs=${current.readingPreferences}, " +
                    "storedReadingPrefs=$stored, fontBusy=${current.fontBusy}, " +
                    "fontNotice=${current.fontNotice}, settingsNotice=${current.settingsNotice}, " +
                    "importedFontCount=${current.importedFonts.size}, " +
                    "discoverSource=${current.discoverFeed.source}, " +
                    "discoverLoading=${current.discoverFeed.loading}",
                timeout,
            )
        }
    }

    private fun createPreferencesStore(directoryName: String): DataStore<Preferences> {
        val directory = temporaryFolder.newFolder(directoryName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferencesScope = scope
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "reading.preferences_pb")
        }
    }

    private fun fontUri(name: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(TestFontContentProvider.AUTHORITY)
            .appendPath(name)
            .build()

    private fun slowWriterState(): Bundle =
        requireNotNull(
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .contentResolver
                .call(
                    TestFontContentProvider.AUTHORITY,
                    TestFontContentProvider.METHOD_GET_SLOW_STATE,
                    null,
                    null,
                )
        )

    private fun releaseSlowWriter() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
            .call(
                TestFontContentProvider.AUTHORITY,
                TestFontContentProvider.METHOD_RELEASE_SLOW_WRITER,
                null,
                null,
            )
    }

    private fun awaitSlowWriterStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (slowWriterState().getBoolean(TestFontContentProvider.KEY_SLOW_WRITER_DONE)) {
                return true
            }
            Thread.sleep(20)
        }
        return slowWriterState().getBoolean(TestFontContentProvider.KEY_SLOW_WRITER_DONE)
    }

    private fun customRenderedPixels(): List<Int> {
        val paragraphs = compose.onAllNodesWithTag("article-paragraph-text", useUnmergedTree = true)
        val systemLayout = textLayout(paragraphs[0])
        val customLayout = textLayout(paragraphs[1])
        assertNotEquals(
            systemLayout.layoutInput.style.fontFamily,
            customLayout.layoutInput.style.fontFamily,
        )
        val systemPixels = paragraphs[0].captureToImage().toPixelMap()
        val customPixels = paragraphs[1].captureToImage().toPixelMap()
        var differs =
            systemPixels.width != customPixels.width || systemPixels.height != customPixels.height
        val rendered = ArrayList<Int>(customPixels.width * customPixels.height)
        for (y in 0 until customPixels.height) {
            for (x in 0 until customPixels.width) rendered += customPixels[x, y].hashCode()
        }
        if (!differs) {
            for (y in 0 until systemPixels.height) {
                for (x in 0 until systemPixels.width) {
                    if (systemPixels[x, y] != customPixels[x, y]) {
                        differs = true
                        break
                    }
                }
                if (differs) break
            }
        }
        assertTrue("production article text renders the loaded synthetic glyphs", differs)
        return rendered
    }

    private fun textLayout(
        node: androidx.compose.ui.test.SemanticsNodeInteraction
    ): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(results)
        }
        return results.single()
    }
}
