package cn.heyread.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockTarget
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.viewmodel.UiState

data class ContentBlockingCardActions(
  val ui: UiState,
  val sessionKey: String,
  val onAction: (ContentBlockingAction) -> Unit,
)

val LocalContentBlockingCardActions = staticCompositionLocalOf<ContentBlockingCardActions?> { null }

@Composable
fun ContentBlockingFeedbackHost(
  ui: UiState,
  hostState: SnackbarHostState,
  onAction: (ContentBlockingAction) -> Unit,
  onDismiss: () -> Unit,
) {
  val currentFeedback by
    rememberUpdatedState(
      Triple(ui.contentBlockingMessage, ui.contentBlockingError, ui.contentBlockingUndo)
    )
  LaunchedEffect(ui.contentBlockingMessage, ui.contentBlockingError, ui.contentBlockingUndo) {
    val message = ui.contentBlockingError ?: ui.contentBlockingMessage ?: return@LaunchedEffect
    val undo = ui.contentBlockingUndo.toList()
    val hasUndo = undo.isNotEmpty() && ui.contentBlockingError == null
    val result =
      hostState.showSnackbar(
        message = message,
        actionLabel = if (hasUndo) "撤销" else null,
        withDismissAction = hasUndo,
        duration = SnackbarDuration.Short,
      )
    if (hasUndo && result == SnackbarResult.ActionPerformed) {
      onAction(ContentBlockingAction.UndoAddedRules(undo))
    } else if (
      currentFeedback == Triple(ui.contentBlockingMessage, ui.contentBlockingError, undo)
    ) {
      onDismiss()
    }
  }
}

/** A single confirmation flow shared by article and destination screens. */
@Composable
fun ContentBlockingQuickAdd(
  targets: List<ContentBlockTarget>,
  ui: UiState,
  isCurrentPage: Boolean,
  identityKey: String,
  preferTarget: Boolean = false,
  label: String = "屏蔽内容",
  keywordPrefill: String = "",
  keywordTooLong: Boolean = false,
  disabled: Boolean = false,
  showTrigger: Boolean = true,
  externalOpenSignal: Int? = null,
  persistDialogState: Boolean = true,
  onAction: (ContentBlockingAction) -> Unit,
) {
  val dialogState =
    if (persistDialogState) rememberSaveable(identityKey) { mutableStateOf(false) }
    else remember(identityKey) { mutableStateOf(false) }
  var dialogOpen by dialogState
  var selectedKindName by rememberSaveable(identityKey) { mutableStateOf(ContentBlockKind.KEYWORD.name) }
  var selectedTargetId by rememberSaveable(identityKey) { mutableStateOf("") }
  var keyword by rememberSaveable(identityKey) { mutableStateOf("") }
  var targetLabel by rememberSaveable(identityKey) { mutableStateOf("") }
  val selectedKind = ContentBlockKind.entries.first { it.name == selectedKindName }
  val target = targets.firstOrNull { it.targetId == selectedTargetId && it.kind == selectedKind }

  LaunchedEffect(isCurrentPage) {
    if (!isCurrentPage) dialogOpen = false
  }
  var lastExternalOpenSignal by remember(identityKey) { mutableIntStateOf(0) }
  LaunchedEffect(externalOpenSignal, identityKey) {
    if (
      externalOpenSignal != null &&
        externalOpenSignal > lastExternalOpenSignal
    ) {
      lastExternalOpenSignal = externalOpenSignal
      if (
        isCurrentPage &&
          !disabled &&
          ui.contentBlockingLoaded &&
          !ui.contentBlockingLoading &&
          !ui.contentBlockingBusy
      ) {
        selectedKindName =
          if (preferTarget && targets.size == 1) targets.single().kind.name
          else ContentBlockKind.KEYWORD.name
        selectedTargetId = if (preferTarget && targets.size == 1) targets.single().targetId else ""
        keyword = keywordPrefill.takeUnless { keywordTooLong } ?: ""
        targetLabel = targets.firstOrNull { it.targetId == selectedTargetId }?.label.orEmpty()
        dialogOpen = true
      }
    }
  }

  val validationMessage =
    if (selectedKind == ContentBlockKind.KEYWORD) {
      when {
        keyword.trim().isEmpty() -> "请输入关键词。"
        keyword.trim().length > 100 -> "关键词最多100字。"
        '\n' in keyword || '\r' in keyword -> "一次只能添加一个关键词。"
        else -> null
      }
    } else {
      when {
        target == null -> "请选择仍有效的屏蔽对象。"
        targetLabel.trim().length > 60 -> "本机备注最多60字。"
        else -> null
      }
    }

  if (showTrigger) {
    IconButton(
      onClick = {
        selectedKindName =
          if (preferTarget && targets.size == 1) targets.single().kind.name
          else ContentBlockKind.KEYWORD.name
        selectedTargetId =
          if (preferTarget && targets.size == 1) targets.single().targetId else ""
        keyword = keywordPrefill.takeUnless { keywordTooLong } ?: ""
        targetLabel = targets.firstOrNull { it.targetId == selectedTargetId }?.label.orEmpty()
        dialogOpen = true
      },
      enabled =
        isCurrentPage &&
          !disabled &&
          ui.contentBlockingLoaded &&
          !ui.contentBlockingLoading &&
          !ui.contentBlockingBusy,
      modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-quick-add-open"),
    ) {
      Icon(Icons.Filled.MoreVert, contentDescription = label)
    }
  }

  if (dialogOpen && isCurrentPage) {
    AlertDialog(
      onDismissRequest = { dialogOpen = false },
      modifier = Modifier.testTag("content-blocking-quick-add-dialog"),
      title = { Text(label) },
      text = {
      Column(
        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (targets.isNotEmpty()) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
              selected = selectedKind == ContentBlockKind.KEYWORD,
              onClick = {
                selectedKindName = ContentBlockKind.KEYWORD.name
                selectedTargetId = ""
              },
              label = { Text("关键词") },
              modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-quick-kind-keyword"),
            )
          }
          targets.forEach { candidate ->
            FilterChip(
              selected = target == candidate,
              onClick = {
                selectedKindName = candidate.kind.name
                selectedTargetId = candidate.targetId
                targetLabel = candidate.label
              },
              label = { Text("${candidate.kind.label} · ${candidate.label.ifBlank { candidate.targetId }}") },
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("content-blocking-quick-target-${candidate.kind.name.lowercase()}-${candidate.targetId}"),
            )
          }
        }
        if (selectedKind == ContentBlockKind.KEYWORD) {
          OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = { Text("关键词") },
            supportingText = {
              if (keywordTooLong && keyword.isEmpty()) Text("标题超过100字，请编辑后再确认；不会自动截断。")
              else Text("按标题或摘要字面匹配，最多100字。")
            },
            modifier = Modifier.fillMaxWidth().testTag("content-blocking-quick-keyword"),
            enabled = !ui.contentBlockingBusy,
          )
        } else {
          OutlinedTextField(
            value = targetLabel,
            onValueChange = { targetLabel = it },
            label = { Text("本机备注（可选）") },
            supportingText = { Text("将按小黑盒ID ${target?.targetId.orEmpty()} 保存。") },
            modifier = Modifier.fillMaxWidth().testTag("content-blocking-quick-label"),
            enabled = !ui.contentBlockingBusy,
          )
        }
        if (!ui.contentBlocking.enabled) {
          Text("总开关已关闭，保存后需在设置开启。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        validationMessage?.let { error ->
          Text(
            error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("content-blocking-quick-validation"),
          )
        }
      }
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (selectedKind == ContentBlockKind.KEYWORD) {
              onAction(ContentBlockingAction.AddKeywords(keyword.trim()))
            } else {
              target?.let {
                onAction(ContentBlockingAction.AddIdentity(it.kind, it.targetId, targetLabel.trim()))
              }
            }
            dialogOpen = false
          },
          enabled = validationMessage == null && !ui.contentBlockingBusy,
          modifier = Modifier.testTag("content-blocking-quick-confirm"),
        ) {
          Text("确认屏蔽")
        }
      },
      dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text("取消") } },
    )
  }
}

@Composable
fun ContentBlockingLinkPreview(
  target: ContentBlockTarget,
  enabled: Boolean = true,
  onLabelChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  val labelValid = target.label.trim().length <= 60
  Column(
    Modifier.fillMaxWidth().testTag("content-blocking-link-preview").padding(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text("识别为${target.kind.label}，小黑盒ID ${target.targetId}")
    OutlinedTextField(
      value = target.label,
      onValueChange = onLabelChange,
      label = { Text("本机备注（可选）") },
      modifier = Modifier.fillMaxWidth().testTag("content-blocking-link-label"),
      enabled = enabled,
      singleLine = true,
    )
    if (!labelValid) Text("本机备注最多60字。", color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(
        onClick = onConfirm,
        enabled = enabled && labelValid,
        modifier = Modifier.testTag("content-blocking-link-confirm"),
      ) {
        Text("确认添加")
      }
      TextButton(
        onClick = onCancel,
        enabled = enabled,
        modifier = Modifier.testTag("content-blocking-link-cancel"),
      ) {
        Text("取消")
      }
    }
  }
}
