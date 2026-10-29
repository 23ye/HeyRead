package cn.heyread.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.ui.components.MotionAlertDialog
import cn.heyread.app.viewmodel.ContentBlockingTransferViewModel
import cn.heyread.app.viewmodel.UiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun ContentBlockingTransferControls(
  ui: UiState,
  isCurrentPage: Boolean,
  transferViewModel: ContentBlockingTransferViewModel,
  onAction: (ContentBlockingAction) -> Unit,
  launchImportDocument: ((Long) -> Unit)? = null,
  launchExportDocument: ((Long) -> Unit)? = null,
) {
  val transfer by transferViewModel.state.collectAsState()
  val context = androidx.compose.ui.platform.LocalContext.current
  val activity = context.findActivity()
  var pageOwnerId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
  var importPickerRequest by rememberSaveable { mutableLongStateOf(0L) }
  var exportPickerRequest by rememberSaveable { mutableLongStateOf(0L) }
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      val request = importPickerRequest
      importPickerRequest = 0L
      if (request != 0L) transferViewModel.onImportPickerResult(request, uri)
    }
  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
      uri ->
      val request = exportPickerRequest
      exportPickerRequest = 0L
      if (request != 0L) transferViewModel.onExportPickerResult(request, uri)
    }

  SideEffect {
    transferViewModel.setPageVisible(pageOwnerId, isCurrentPage)
    transferViewModel.updateConfig(ui.contentBlocking)
  }
  DisposableEffect(transferViewModel, pageOwnerId, activity) {
    onDispose {
      if (activity?.isChangingConfigurations != true) {
        transferViewModel.setPageVisible(pageOwnerId, false)
      }
    }
  }
  val appBusy =
    !isCurrentPage ||
      !ui.contentBlockingLoaded ||
      ui.contentBlockingLoading ||
      ui.contentBlockingBusy
  val transferBusy =
    transfer.selectingImport ||
      transfer.selectingExport ||
      transfer.readingImport ||
      transfer.writingExport

  Column(
    Modifier.fillMaxWidth().testTag("content-blocking-transfer-controls"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("可将规则保存为JSON文件，或从文件合并规则；重复项会保留本机现有设置。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = {
          val request = transferViewModel.beginImportPicker() ?: return@Button
          if (launchImportDocument != null) launchImportDocument(request)
          else {
            importPickerRequest = request
            importLauncher.launch(arrayOf("application/json"))
          }
        },
        enabled = !appBusy && !transferBusy,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("content-blocking-import"),
      ) {
        Text("导入规则")
      }
      Button(
        onClick = {
          val request = transferViewModel.beginExportPicker(ui.contentBlocking.rules) ?: return@Button
          if (launchExportDocument != null) launchExportDocument(request)
          else {
            exportPickerRequest = request
            exportLauncher.launch(
              "HeyRead-blocking-rules-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json",
            )
          }
        },
        enabled = !appBusy && !transferBusy,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("content-blocking-export"),
      ) {
        Text("导出规则")
      }
    }
    if (transfer.readingImport) Text("正在读取规则文件…", Modifier.testTag("content-blocking-transfer-reading"))
    if (transfer.writingExport) Text("正在导出规则…", Modifier.testTag("content-blocking-transfer-writing"))
    transfer.error?.let { error ->
      Text(
        error,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("content-blocking-transfer-error"),
      )
    }
    transfer.exportMessage?.let { message ->
      Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
          message,
          Modifier.fillMaxWidth().padding(10.dp).testTag("content-blocking-transfer-message"),
        )
      }
    }
  }

  val document = transfer.importDocument
  val preview = transfer.importPreview
  val canMerge =
    preview != null && preview.addedCount > 0 && !appBusy && transfer.previewError == null
  MotionAlertDialog(
    visible = document != null && (preview != null || transfer.previewError != null) && isCurrentPage,
    onDismiss = { transferViewModel.dismissImportPreview(pageOwnerId) },
    title =
      when {
        transfer.previewError != null -> "无法生成导入预览"
        transfer.previewNotice != null -> "预览已更新，请再次确认"
        preview?.addedCount == 0 -> "没有新规则需要导入"
        else -> "确认合并导入规则？"
      },
    message = "",
    confirmLabel = if (canMerge) "合并导入" else "知道了",
    onConfirm = {},
    dismissLabel = if (canMerge) "取消" else "关闭",
    modifier = Modifier.testTag("content-blocking-import-preview"),
    onConfirmAttempt = {
      if (document == null) {
        transferViewModel.dismissImportPreview(pageOwnerId)
        true
      } else if (transfer.previewError != null || preview?.addedCount == 0) {
        transferViewModel.dismissImportPreview(pageOwnerId)
        true
      } else if (!canMerge || preview == null) {
        false
      } else {
        transferViewModel.confirmImport(
          pageOwnerId,
          document,
          preview,
          ui.contentBlocking,
          onAction,
        )
      }
    },
    confirmTestTag = "content-blocking-import-confirm",
    dismissTestTag = "content-blocking-import-cancel",
    confirmEnabled = transfer.previewError != null || preview?.addedCount == 0 || canMerge,
    content = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (transfer.previewError != null) {
          Text(
            transfer.previewError.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("content-blocking-import-preview-error"),
          )
        } else {
          Text("文件规则：${document?.totalCount ?: 0}条", Modifier.testTag("content-blocking-import-total"))
          Text("将新增：${preview?.addedCount ?: 0}条", Modifier.testTag("content-blocking-import-added"))
          Text("重复：${preview?.duplicateCount ?: 0}条", Modifier.testTag("content-blocking-import-duplicates"))
          transfer.previewNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("content-blocking-import-preview-notice"))
          }
          ContentBlockKind.entries.forEach { kind ->
            Text(
              "${kind.label}新增：${preview?.addedByKind?.get(kind) ?: 0}条",
              modifier = Modifier.testTag("content-blocking-import-kind-${kind.name.lowercase()}"),
            )
          }
          Text("合并导入；已有规则保持原设置。总开关和收藏、历史范围不会改变。")
        }
      }
    },
  )
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
