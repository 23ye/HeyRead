package cn.heyread.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockingStoreTest {
  @Test
  fun persistsRulesAtomicallyAndRejectsCorruptConfigWithoutTouchingOtherPreferences() = runTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "content-blocking-${UUID.randomUUID()}").apply { mkdirs() }
    val isolatedFonts = File(root, "fonts")
    val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val injectedStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = dataStoreScope) {
          File(root, "isolated.preferences_pb")
        }
    val historyKey = stringPreferencesKey("history")
    val searchKey = stringPreferencesKey("search_history")
    val themeKey = stringPreferencesKey("theme")
    val layoutKey = stringPreferencesKey("home_layout")
    val fontKey = floatPreferencesKey("reading_font_size")
    val fontsKey = stringPreferencesKey("reading_imported_fonts")
    val blockingKey = stringPreferencesKey("content_blocking_config")

    try {
      injectedStore.edit { preferences ->
        preferences[historyKey] = "history-sentinel"
        preferences[searchKey] = "search-sentinel"
        preferences[themeKey] = "dark"
        preferences[layoutKey] = "C"
        preferences[fontKey] = 21f
        preferences[fontsKey] = "fonts-sentinel"
      }
      val store = LibraryStore(context, isolatedFonts, injectedStore)
      assertEquals(ContentBlockingConfig(), store.getContentBlocking())

      listOf(
              async { store.updateContentBlocking(ContentBlockingAction.AddKeywords("alpha")) },
              async { store.updateContentBlocking(ContentBlockingAction.AddKeywords("beta")) },
              async {
                store.updateContentBlocking(
                    ContentBlockingAction.AddIdentity(ContentBlockKind.AUTHOR, "000123", "手工ID备注"))
              },
          )
          .awaitAll()

      val reopened = LibraryStore(context, isolatedFonts, injectedStore)
      val saved = reopened.getContentBlocking()
      assertEquals(setOf("alpha", "beta", "123"), saved.rules.map { it.value }.toSet())
      assertTrue(saved.rules.any { it.kind == ContentBlockKind.AUTHOR && it.label == "手工ID备注" })

      val beforeCorruptWrite = injectedStore.data.first()
      assertEquals("history-sentinel", beforeCorruptWrite[historyKey])
      assertEquals("search-sentinel", beforeCorruptWrite[searchKey])
      assertEquals("dark", beforeCorruptWrite[themeKey])
      assertEquals("C", beforeCorruptWrite[layoutKey])
      assertEquals(21f, beforeCorruptWrite[fontKey] ?: Float.NaN, 0f)
      assertEquals("fonts-sentinel", beforeCorruptWrite[fontsKey])
      val validConfigBytes = beforeCorruptWrite[blockingKey]
      assertTrue(!validConfigBytes.isNullOrBlank())

      val corrupt = "{\"version\":99,\"opaque\":\"keep me\"}"
      injectedStore.edit { it[blockingKey] = corrupt }
      assertTrue(runCatching { reopened.getContentBlocking() }.isFailure)
      assertTrue(
          runCatching { reopened.updateContentBlocking(ContentBlockingAction.SetEnabled(false)) }
              .isFailure)
      assertEquals(corrupt, injectedStore.data.first()[blockingKey])
      val afterCorruptWrite = injectedStore.data.first()
      assertEquals("history-sentinel", afterCorruptWrite[historyKey])
      assertEquals("search-sentinel", afterCorruptWrite[searchKey])
      assertEquals("dark", afterCorruptWrite[themeKey])
      assertEquals("C", afterCorruptWrite[layoutKey])
      assertEquals(21f, afterCorruptWrite[fontKey] ?: Float.NaN, 0f)
      assertEquals("fonts-sentinel", afterCorruptWrite[fontsKey])
    } finally {
      dataStoreScope.cancel()
      root.deleteRecursively()
    }
  }
}
