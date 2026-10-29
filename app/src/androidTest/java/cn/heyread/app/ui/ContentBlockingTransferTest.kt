package cn.heyread.app.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.local.ContentBlockingImportDocument
import cn.heyread.app.data.local.ContentBlockingRuleFileAccess
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.Source
import cn.heyread.app.ui.screens.ContentBlockingTransferControls
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.ContentBlockingTransferViewModel
import cn.heyread.app.viewmodel.UiState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBlockingTransferTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val viewModelStore = ViewModelStore()

  @After
  fun cleanUp() {
    compose.activityRule.scenario.onActivity { viewModelStore.clear() }
  }

  @Test
  fun importCancelMergeRepeatAndExportUseCurrentSnapshot() {
    val existing = keywordRule("same", RULE_1)
    val extraExisting = keywordRule("keep", RULE_4)
    val added =
      ContentBlockRule(
        id = RULE_2,
        kind = ContentBlockKind.TOPIC,
        value = "4321",
        label = "保留备注",
        enabled = false,
        source = Source.HEYBOX,
      )
    val document = ContentBlockingImportDocument(
      rules = listOf(keywordRule("same", RULE_3), added),
      totalCount = 2,
      duplicateCount = 0,
    )
    val files = FakeFiles(document)
    val vm = createViewModel(files)
    val ui = mutableStateOf(
      UiState(
        contentBlocking = ContentBlockingConfig(
          enabled = false,
          applyToFavorites = true,
          applyToHistory = false,
          rules = listOf(existing),
        ),
        contentBlockingLoading = false,
        contentBlockingLoaded = true,
      ),
    )
    val actions = mutableListOf<ContentBlockingAction>()
    val importRequest = AtomicLong(0L)
    val exportRequest = AtomicLong(0L)
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ContentBlockingTransferControls(
          ui = ui.value,
          isCurrentPage = true,
          transferViewModel = vm,
          onAction = { action ->
            actions += action
            if (action is ContentBlockingAction.MergeImportedRules) {
              ui.value =
                ui.value.copy(
                  contentBlocking = ui.value.contentBlocking.copy(rules = ui.value.contentBlocking.rules + added),
                )
            }
          },
          launchImportDocument = importRequest::set,
          launchExportDocument = exportRequest::set,
        )
      }
    }

    compose.onNodeWithTag("content-blocking-import").performClick()
    val cancelledPicker = importRequest.get()
    vm.onImportPickerResult(cancelledPicker, null)
    compose.waitForIdle()
    assertEquals(0, files.readCount)
    assertTrue(actions.isEmpty())

    openImport(vm, importRequest, "memory://rules")
    compose.onNodeWithTag("content-blocking-import-preview").assertIsDisplayed()
    compose.onNodeWithText("文件规则：2条").assertIsDisplayed()
    compose.onNodeWithText("将新增：1条").assertIsDisplayed()
    compose.onNodeWithText("重复：1条").assertIsDisplayed()
    compose.onNodeWithText("话题新增：1条").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-import-cancel").performClick()
    compose.waitForIdle()
    assertTrue(actions.isEmpty())
    assertEquals(listOf(existing), ui.value.contentBlocking.rules)

    openImport(vm, importRequest, "memory://rules")
    ui.value = ui.value.copy(contentBlocking = ui.value.contentBlocking.copy(rules = listOf(existing, extraExisting)))
    compose.waitUntil(5_000) {
      vm.state.value.importPreview?.baseRules == listOf(existing, extraExisting)
    }
    compose.onNodeWithText("将新增：1条").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-import-confirm").performClick()
    compose.waitForIdle()
    assertEquals(1, actions.size)
    val merge = actions.single() as ContentBlockingAction.MergeImportedRules
    assertEquals(document.rules, merge.rules)
    assertEquals(listOf(existing, extraExisting), merge.expectedRules)
    assertFalse(ui.value.contentBlocking.enabled)
    assertTrue(ui.value.contentBlocking.applyToFavorites)
    assertFalse(ui.value.contentBlocking.applyToHistory)

    openImport(vm, importRequest, "memory://rules")
    compose.onNodeWithText("没有新规则需要导入").assertIsDisplayed()
    compose.onNodeWithText("将新增：0条").assertIsDisplayed()
    compose.onNodeWithTag("content-blocking-import-confirm").performClick()
    compose.waitForIdle()
    assertEquals("重复预览不能再次提交合并", 1, actions.size)

    compose.onNodeWithTag("content-blocking-export").performClick()
    val exportTicket = exportRequest.get()
    assertTrue(exportTicket > 0L)
    vm.onExportPickerResult(exportTicket, Uri.parse("memory://export"))
    compose.waitUntil(5_000) { vm.state.value.exportMessage != null }
    compose.onNodeWithText("已导出3条规则。").assertIsDisplayed()
    assertEquals(listOf(existing, extraExisting, added), files.exportedRules)
  }

  @Test
  fun invalidFileAndLatePickerAfterHidingNeverDispatchAnAction() {
    val files = FakeFiles(ContentBlockingImportDocument(emptyList(), 0, 0), failRead = true)
    val vm = createViewModel(files)
    val visible = mutableStateOf(true)
    val ui =
      UiState(contentBlockingLoading = false, contentBlockingLoaded = true)
    val actions = mutableListOf<ContentBlockingAction>()
    val importRequest = AtomicLong(0L)
    compose.setContent {
      HeyReadTheme("light", motionEnabled = false) {
        ContentBlockingTransferControls(
          ui = ui,
          isCurrentPage = visible.value,
          transferViewModel = vm,
          onAction = { actions += it },
          launchImportDocument = importRequest::set,
          launchExportDocument = {},
        )
      }
    }

    openImport(vm, importRequest, "memory://bad")
    compose.onNodeWithTag("content-blocking-transfer-error").assertIsDisplayed()
    assertTrue(actions.isEmpty())

    files.failRead = false
    files.delayReadMillis = 2_000
    compose.onNodeWithTag("content-blocking-import").performClick()
    val readingRequest = importRequest.get()
    vm.onImportPickerResult(readingRequest, Uri.parse("memory://slow"))
    compose.waitUntil(5_000) { vm.state.value.readingImport }
    visible.value = false
    compose.waitForIdle()
    assertFalse(vm.state.value.readingImport)
    visible.value = true
    compose.waitForIdle()

    compose.onNodeWithTag("content-blocking-import").performClick()
    val staleRequest = importRequest.get()
    assertTrue(staleRequest > 0L)
    visible.value = false
    compose.waitForIdle()
    visible.value = true
    compose.waitForIdle()
    vm.onImportPickerResult(staleRequest, Uri.parse("memory://rules"))
    compose.waitForIdle()

    assertEquals("隐藏后迟到的选择不应再读取文件", 2, files.readCount)
    assertNull(files.exportedRules)
    assertNull(vm.state.value.importPreview)
    assertFalse(vm.state.value.readingImport)
    assertTrue(actions.isEmpty())
  }

  private fun openImport(
    vm: ContentBlockingTransferViewModel,
    request: AtomicLong,
    uri: String,
  ) {
    compose.onNodeWithTag("content-blocking-import").performClick()
    val ticket = request.get()
    assertTrue("The import button should start a picker request", ticket > 0L)
    vm.onImportPickerResult(ticket, Uri.parse(uri))
    compose.waitUntil(5_000) {
      vm.state.value.importPreview != null || vm.state.value.error != null
    }
  }

  private fun createViewModel(files: ContentBlockingRuleFileAccess): ContentBlockingTransferViewModel =
    ContentBlockingTransferViewModel(files).also {
      viewModelStore.put("blocking-transfer-test-${System.identityHashCode(it)}", it)
    }

  private fun keywordRule(value: String, id: String) =
    ContentBlockRule(id, ContentBlockKind.KEYWORD, value)

  private class FakeFiles(
    private val document: ContentBlockingImportDocument,
    failRead: Boolean = false,
  ) : ContentBlockingRuleFileAccess {
    var failRead = failRead
    var delayReadMillis = 10L
    var readCount = 0
      private set
    var exportedRules: List<ContentBlockRule>? = null
      private set

    override suspend fun read(uri: Uri): ContentBlockingImportDocument {
      readCount++
      if (failRead) throw IllegalArgumentException("导入文件格式无效。")
      delay(delayReadMillis)
      return document
    }

    override suspend fun write(uri: Uri, rules: List<ContentBlockRule>): Int {
      exportedRules = rules.toList()
      return rules.size
    }
  }

  private companion object {
    const val RULE_1 = "00000000-0000-0000-0000-000000000001"
    const val RULE_2 = "00000000-0000-0000-0000-000000000002"
    const val RULE_3 = "00000000-0000-0000-0000-000000000003"
    const val RULE_4 = "00000000-0000-0000-0000-000000000004"
  }
}
