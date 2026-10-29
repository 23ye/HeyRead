package cn.heyread.app.data.local

import android.os.Bundle
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Captures the pre-fix execution location of history JSON parsing without treating DataStore I/O as
 * JSON work. The test is intentionally bounded and uses only synthetic history rows.
 */
@RunWith(AndroidJUnit4::class)
class LibraryStoreHistoryDispatcherTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private var dataStoreScope: CoroutineScope? = null

  @After
  fun closeDataStoreScope() {
    dataStoreScope?.cancel()
    dataStoreScope = null
  }

  @Test
  fun historyJsonReadAndWriteStayOffMainAndPreserveBoundedHistorySemantics() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val dataStore = createPreferencesStore()
    val body = "synthetic-body-" + "x".repeat(16 * 1024)
    val records =
        (0 until 80).map { index ->
          Story(
              id = "history-$index",
              source = Source.DEMO,
              title = "Synthetic history $index",
              excerpt = "Synthetic excerpt",
              body = body,
              author = "Test author",
              date = "2026-10-23",
          )
        }
    val rawHistory =
        JSONArray().apply { records.forEach { put(encodeHistoryStory(it)) } }.toString()
    val historyKey = stringPreferencesKey("history")
    runBlocking {
      dataStore.updateData { it.toMutablePreferences().apply { this[historyKey] = rawHistory } }
    }
    val store = LibraryStore(instrumentation.targetContext, dataStore)

    val sampling = AtomicBoolean(true)
    val phase = AtomicInteger(0)
    val readMainJsonSamples = AtomicInteger(0)
    val readBackgroundJsonSamples = AtomicInteger(0)
    val writeMainJsonSamples = AtomicInteger(0)
    val writeBackgroundJsonSamples = AtomicInteger(0)
    val samplerReady = CountDownLatch(1)
    val mainThread = Looper.getMainLooper().thread
    val sampler =
        Thread(
            {
              samplerReady.countDown()
              while (sampling.get()) {
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                  if (stack.any(::isJsonFrame)) {
                    when (phase.get()) {
                      1 ->
                          if (thread === mainThread) readMainJsonSamples.incrementAndGet()
                          else readBackgroundJsonSamples.incrementAndGet()
                      2 ->
                          if (thread === mainThread) writeMainJsonSamples.incrementAndGet()
                          else writeBackgroundJsonSamples.incrementAndGet()
                    }
                  }
                }
                Thread.sleep(1)
              }
            },
            "history-json-main-sampler",
        )
    sampler.start()
    assertTrue("JSON sampler did not start", samplerReady.await(2, TimeUnit.SECONDS))

    var observedRows = 0
    val insertedStory =
        Story(
            id = "history-newest",
            source = Source.DEMO,
            title = "Newest synthetic history",
            excerpt = "Synthetic excerpt",
            body = body,
            author = "Test author",
            date = "2026-10-23",
        )
    try {
      phase.set(1)
      instrumentation.runOnMainSync {
        repeat(8) {
          val decoded = runBlocking { store.history() }
          assertEquals(80, decoded.size)
          assertEquals("history-0", decoded.first().id)
          assertEquals(body, decoded.first().body)
          observedRows = decoded.size
        }
        phase.set(2)
        runBlocking { store.addHistory(insertedStory) }
        runBlocking { store.addHistory(records[40].copy(title = "Updated synthetic history 40")) }
      }
    } finally {
      sampling.set(false)
      sampler.join(2_000)
    }

    assertEquals(80, observedRows)
    val afterWrite = runBlocking { store.history() }
    assertEquals(80, afterWrite.size)
    assertEquals("history-40", afterWrite[0].id)
    assertEquals("Updated synthetic history 40", afterWrite[0].title)
    assertEquals("history-newest", afterWrite[1].id)
    assertEquals("history-0", afterWrite[2].id)
    assertEquals("history-78", afterWrite.last().id)
    assertEquals(1, afterWrite.count { it.id == "history-40" })
    assertTrue(
        "History reads should parse JSON on a background thread",
        readBackgroundJsonSamples.get() > 0)
    assertEquals("History reads must not parse JSON on Main", 0, readMainJsonSamples.get())
    assertTrue(
        "History writes should serialize JSON on a background thread",
        writeBackgroundJsonSamples.get() > 0)
    assertEquals("History writes must not serialize JSON on Main", 0, writeMainJsonSamples.get())
    instrumentation.sendStatus(
        2,
        Bundle().apply {
          putInt("history_rows_per_read", 80)
          putInt("history_body_bytes", 16 * 1024)
          putInt("history_repeated_reads", 8)
          putInt("history_read_main_json_stack_samples", readMainJsonSamples.get())
          putInt("history_read_background_json_stack_samples", readBackgroundJsonSamples.get())
          putInt("history_write_main_json_stack_samples", writeMainJsonSamples.get())
          putInt("history_write_background_json_stack_samples", writeBackgroundJsonSamples.get())
          putInt("history_rows_after_write", afterWrite.size)
        },
    )
  }

  private fun isJsonFrame(frame: StackTraceElement): Boolean =
      frame.className == JSONObject::class.java.name ||
          frame.className == JSONArray::class.java.name ||
          frame.className == "org.json.JSONTokener"

  private fun createPreferencesStore(): DataStore<Preferences> {
    val root = temporaryFolder.newFolder("history-dispatcher-store")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dataStoreScope = scope
    return PreferenceDataStoreFactory.create(scope = scope) { File(root, "history.preferences_pb") }
  }
}
