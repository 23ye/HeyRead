package cn.heyread.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.ui.components.toComposeFontFamily
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.StartupAppearanceContent
import cn.heyread.app.viewmodel.AppViewModel
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalFontStartupTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var storeScope: CoroutineScope? = null
  private var viewModelStore: ViewModelStore? = null

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStore?.clear() }
    viewModelStore = null
    storeScope?.cancel()
    storeScope = null
  }

  @Test
  fun firstComposeDrawUsesStoredCustomFontAfterDelayedSnapshot() = runBlocking {
    val fontsDirectory = temporaryFolder.newFolder("startup-fonts")
    val base = createStore()
    val fontId = seedCustomFont(base, fontsDirectory)
    val gate = ReadGate()
    val viewModel = createViewModel(FirstReadGatedDataStore(base, gate), fontsDirectory)
    withTimeout(5_000) { gate.captured.await() }
    assertFalse(viewModel.state.value.startupAppearanceReady)

    val mounted = AtomicBoolean(false)
    val drawnFonts =
      Collections.synchronizedList(
        mutableListOf<
          Pair<androidx.compose.ui.text.font.FontFamily, androidx.compose.ui.text.font.FontFamily>
        >()
      )
    val decor = compose.activity.window.decorView
    lateinit var drawGate: StartupPreDrawGate
    compose.runOnUiThread {
      drawGate = StartupPreDrawGate(decor)
      compose.activity.setContent {
        val ui by viewModel.state.collectAsState()
        val loadedFamily =
          ui.readingPreferences.font.toComposeFontFamily(ui.customFontTypeface)
        HeyReadTheme(
          theme = ui.theme,
          accent = ui.accent,
          motionEnabled = ui.motionEnabled,
          globalFontFamily = loadedFamily,
        ) {
          val appliedFont = requireNotNull(MaterialTheme.typography.bodyLarge.fontFamily)
          val background = MaterialTheme.colorScheme.background
          StartupAppearanceContent(ui.startupAppearanceReady, drawGate) {
            SideEffect { mounted.set(true) }
            Box(
              Modifier.fillMaxSize()
                .drawBehind {
                  synchronized(drawnFonts) {
                    if (drawnFonts.isEmpty()) drawnFonts.add(appliedFont to loadedFamily)
                  }
                  drawRect(background)
                }
                .testTag("global-font-startup-content")
            )
          }
        }
      }
    }
    delay(150)
    assertFalse("Navigation content stays unmounted while saved font is loading", mounted.get())
    assertTrue("No app content draw may precede startup readiness", drawnFonts.isEmpty())

    gate.release.complete(Unit)
    awaitState(viewModel) { it.startupAppearanceReady }
    assertEquals(ReadingFont.CUSTOM, viewModel.state.value.readingPreferences.font)
    assertEquals(fontId, viewModel.state.value.readingPreferences.customFontId)
    assertNotNull(viewModel.state.value.customFontTypeface)
    compose.waitUntil(2_000) { drawnFonts.isNotEmpty() }
    compose.runOnIdle { drawGate.dispose() }
    assertSame(
      "The first rendered Material typography uses the persisted Typeface",
      drawnFonts.first().second,
      drawnFonts.first().first,
    )
    assertTrue(
      "The loaded first-frame family is not the platform default",
      drawnFonts.first().second !== androidx.compose.ui.text.font.FontFamily.Default,
    )
  }

  @Test
  fun startupTimeoutUsesSystemFontWithoutChangingSavedCustomSelectionOrApplyingLateRead() = runBlocking {
    val fontsDirectory = temporaryFolder.newFolder("timeout-fonts")
    val base = createStore()
    val fontId = seedCustomFont(base, fontsDirectory)
    val gate = ReadGate()
    val viewModel = createViewModel(FirstReadGatedDataStore(base, gate), fontsDirectory)
    withTimeout(5_000) { gate.captured.await() }

    awaitState(viewModel) { it.startupAppearanceReady }
    val timedOutState = viewModel.state.value
    assertEquals("system", timedOutState.theme)
    assertEquals(ReadingFont.SYSTEM, timedOutState.readingPreferences.font)
    assertNotNull(timedOutState.startupAppearanceNotice)
    assertTrue(timedOutState.startupAppearanceNotice!!.contains("超时"))

    gate.release.complete(Unit)
    delay(150)
    assertEquals("system", viewModel.state.value.theme)
    assertEquals(ReadingFont.SYSTEM, viewModel.state.value.readingPreferences.font)
    val persisted = LibraryStore(InstrumentationRegistry.getInstrumentation().targetContext, fontsDirectory, base)
      .getStartupAppearance()
    assertEquals("dark", persisted.theme)
    assertEquals(ReadingFont.CUSTOM, persisted.readingPreferences.font)
    assertEquals(fontId, persisted.readingPreferences.customFontId)
  }

  private fun createStore(): DataStore<Preferences> {
    val directory = temporaryFolder.newFolder("preferences-${System.nanoTime()}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    storeScope = scope
    return PreferenceDataStoreFactory.create(scope = scope) { File(directory, "isolated.preferences_pb") }
  }

  private suspend fun seedCustomFont(dataStore: DataStore<Preferences>, fontsDirectory: File): String {
    val context = InstrumentationRegistry.getInstrumentation().context
    val bytes = context.assets.open("fonts/synthetic-a.ttf").use { it.readBytes() }
    val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    File(fontsDirectory, "$id.font").writeBytes(bytes)
    dataStore.updateData { current ->
      current.toMutablePreferences().apply {
        this[stringPreferencesKey("theme")] = "dark"
        this[stringPreferencesKey("reading_font_family")] = "custom:$id"
        this[stringPreferencesKey("reading_imported_fonts")] = JSONArray()
          .put(JSONObject().put("id", id).put("name", "Synthetic A"))
          .toString()
      }
    }
    return id
  }

  private fun createViewModel(dataStore: DataStore<Preferences>, fontsDirectory: File): AppViewModel {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val owner = ViewModelStore()
    viewModelStore = owner
    lateinit var viewModel: AppViewModel
    instrumentation.runOnMainSync {
      viewModel = AppViewModel(LibraryStore(instrumentation.targetContext, fontsDirectory, dataStore))
      owner.put("global-font-startup", viewModel)
    }
    return viewModel
  }

  private suspend fun awaitState(viewModel: AppViewModel, predicate: (cn.heyread.app.viewmodel.UiState) -> Boolean) {
    withTimeout(7_000) { while (!predicate(viewModel.state.value)) delay(20) }
  }

  private class ReadGate {
    val captured = CompletableDeferred<Preferences>()
    val release = CompletableDeferred<Unit>()
  }

  private class FirstReadGatedDataStore(
    private val delegate: DataStore<Preferences>,
    private val gate: ReadGate,
  ) : DataStore<Preferences> {
    private val claimed = AtomicBoolean(false)
    override val data: Flow<Preferences>
      get() = if (claimed.compareAndSet(false, true)) {
        flow {
          val snapshot = delegate.data.first()
          gate.captured.complete(snapshot)
          gate.release.await()
          emit(snapshot)
        }
      } else delegate.data

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
      delegate.updateData(transform)
  }
}
