package cn.heyread.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AppViewModel
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupAppearanceTest {
  @get:Rule val temporaryFolder = TemporaryFolder()
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
  fun firstDrawWaitsForSavedAppearanceAndThenUsesOnePersistedSnapshot() = runBlocking {
    val base = createStore()
    seedAppearance(base)
    val gate = ReadGate()
    val gated = FirstReadGatedDataStore(base, gate)
    val viewModel = createViewModel(gated)
    val captured = withTimeout(5_000) { gate.captured.await() }
    assertFalse(viewModel.state.value.startupAppearanceReady)
    assertEquals("system", viewModel.state.value.theme)

    lateinit var drawGate: StartupPreDrawGate
    val rootDraws = AtomicInteger()
    val contentMounted = AtomicBoolean(false)
    val decor = compose.activity.window.decorView
    lateinit var drawListener: android.view.ViewTreeObserver.OnDrawListener
    var initialDrawCount = 0
    compose.runOnUiThread {
      drawGate = StartupPreDrawGate(decor)
      drawListener = android.view.ViewTreeObserver.OnDrawListener { rootDraws.incrementAndGet() }
      decor.viewTreeObserver.addOnDrawListener(drawListener)
      initialDrawCount = rootDraws.get()
    }
    val drawnFrames = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
    compose.runOnUiThread {
      compose.activity.setContent {
        val ui by viewModel.state.collectAsState()
        HeyReadTheme(ui.theme, ui.accent, ui.motionEnabled) {
          val backgroundColor = MaterialTheme.colorScheme.background
          StartupAppearanceContent(ui.startupAppearanceReady, drawGate) {
            SideEffect { contentMounted.set(true) }
            Box(
              Modifier.fillMaxSize()
                .drawBehind {
                  synchronized(drawnFrames) {
                    if (drawnFrames.isEmpty()) drawnFrames.add(ui.theme to backgroundColor.toArgb())
                  }
                  drawRect(backgroundColor)
                }
                .testTag("startup-theme-content")
            )
          }
        }
      }
    }
    delay(150)
    assertFalse("Pending appearance must not mount navigation content", contentMounted.get())
    assertTrue("No application content may draw while appearance is pending", drawnFrames.isEmpty())
    assertEquals("The real decor predraw remains blocked", initialDrawCount, rootDraws.get())

    gate.release.complete(Unit)
    awaitState(viewModel) { it.startupAppearanceReady }
    assertEquals("dark", viewModel.state.value.theme)
    assertEquals("purple", viewModel.state.value.accent)
    assertFalse(viewModel.state.value.motionEnabled)
    assertTrue(viewModel.state.value.noImages)

    compose.waitUntil(2_000) { drawnFrames.isNotEmpty() }
    compose.onNodeWithTag("startup-theme-content").assertIsDisplayed()
    assertTrue(contentMounted.get())
    assertEquals("dark", drawnFrames.first().first)
    assertEquals("First content frame must use the saved dark purple scheme", 0xFF151316.toInt(), drawnFrames.first().second)
    compose.runOnUiThread {
      drawGate.dispose()
      decor.viewTreeObserver.removeOnDrawListener(drawListener)
    }
    assertEquals("dark", captured[stringPreferencesKey("theme")])
  }

  @Test
  fun appearanceChangesDuringDelayedReadWinIndependentlyByField() = runBlocking {
    val base = createStore()
    seedAppearance(base)
    val gate = ReadGate()
    val viewModel = createViewModel(FirstReadGatedDataStore(base, gate))
    withTimeout(5_000) { gate.captured.await() }

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      viewModel.setAccent("blue")
    }
    gate.release.complete(Unit)
    awaitState(viewModel) { it.startupAppearanceReady }

    val state = viewModel.state.value
    assertEquals("dark", state.theme)
    assertEquals("blue", state.accent)
    assertFalse(state.motionEnabled)
    assertTrue(state.noImages)
  }

  @Test
  fun failedAppearanceReadFallsBackWithVisibleNoticeWithoutOverwritingStoredValues() = runBlocking {
    val base = createStore()
    seedAppearance(base)
    val viewModel = createViewModel(FailingReadsDataStore(base))
    awaitState(viewModel) { it.startupAppearanceReady }

    val state = viewModel.state.value
    assertEquals("system", state.theme)
    assertEquals("classic", state.accent)
    assertTrue(state.motionEnabled)
    assertFalse(state.noImages)
    assertNotNull(state.startupAppearanceNotice)
    assertTrue(state.startupAppearanceNotice!!.contains("原设置未更改"))
    val saved =
      LibraryStore(InstrumentationRegistry.getInstrumentation().targetContext, base)
        .getStartupAppearance()
    assertEquals("dark", saved.theme)
    assertEquals("purple", saved.accent)
    assertFalse(saved.motionEnabled)
    assertTrue(saved.noImages)
  }

  @Test
  fun appearanceReadTimeoutReleasesStartupWithoutApplyingLateSnapshot() = runBlocking {
    val base = createStore()
    seedAppearance(base)
    val gate = ReadGate()
    val viewModel = createViewModel(FirstReadGatedDataStore(base, gate))
    withTimeout(5_000) { gate.captured.await() }
    awaitState(viewModel) { it.startupAppearanceReady }

    val state = viewModel.state.value
    assertEquals("system", state.theme)
    assertEquals("classic", state.accent)
    assertNotNull(state.startupAppearanceNotice)
    assertTrue(state.startupAppearanceNotice!!.contains("超时"))
    gate.release.complete(Unit)
    delay(100)
    assertEquals("system", viewModel.state.value.theme)
    assertEquals("classic", viewModel.state.value.accent)
    assertTrue(viewModel.state.value.motionEnabled)
    assertFalse(viewModel.state.value.noImages)
    assertEquals(
      "dark",
      LibraryStore(InstrumentationRegistry.getInstrumentation().targetContext, base)
        .getStartupAppearance()
        .theme,
    )
  }

  private fun createStore(): DataStore<Preferences> {
    val root = temporaryFolder.newFolder("startup-appearance-${System.nanoTime()}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    storeScope = scope
    return PreferenceDataStoreFactory.create(scope = scope) {
      File(root, "isolated.preferences_pb")
    }
  }

  private suspend fun seedAppearance(dataStore: DataStore<Preferences>) {
    dataStore.updateData { current ->
      current.toMutablePreferences().apply {
        this[stringPreferencesKey("theme")] = "dark"
        this[stringPreferencesKey("theme_accent")] = "purple"
        this[booleanPreferencesKey("motion_enabled")] = false
        this[booleanPreferencesKey("no_images")] = true
      }
    }
  }

  private fun createViewModel(dataStore: DataStore<Preferences>): AppViewModel {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val owner = ViewModelStore()
    viewModelStore = owner
    lateinit var viewModel: AppViewModel
    instrumentation.runOnMainSync {
      viewModel =
        AppViewModel(
          LibraryStore(
            instrumentation.targetContext,
            File(temporaryFolder.root, "fonts-${System.nanoTime()}").apply { mkdirs() },
            dataStore,
          )
        )
      owner.put("startup-appearance", viewModel)
    }
    return viewModel
  }

  private suspend fun awaitState(
    viewModel: AppViewModel,
    predicate: (cn.heyread.app.viewmodel.UiState) -> Boolean,
  ) {
    withTimeout(5_000) { while (!predicate(viewModel.state.value)) delay(20) }
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
      get() =
        if (claimed.compareAndSet(false, true)) {
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

  private class FailingReadsDataStore(private val delegate: DataStore<Preferences>) :
    DataStore<Preferences> {
    override val data: Flow<Preferences>
      get() = flow { throw IOException("synthetic startup read failure") }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
      delegate.updateData(transform)
  }
}
