package cn.heyread.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.heyread.app.data.local.ContentBlockingRuleFiles
import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockTarget
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.parseContentBlockIdentityInput
import cn.heyread.app.ui.components.ContentBlockingLinkPreview
import cn.heyread.app.ui.components.MotionAlertDialog
import cn.heyread.app.viewmodel.ContentBlockingTransferViewModel
import cn.heyread.app.viewmodel.ContentBlockingTransferViewModelFactory
import cn.heyread.app.viewmodel.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentBlockingSettings(
  ui: UiState,
  onAction: (ContentBlockingAction) -> Unit,
  isCurrentPage: Boolean,
) {
  val config = ui.contentBlocking
  val busy = ui.contentBlockingLoading || ui.contentBlockingBusy || !isCurrentPage
  val context = LocalContext.current
  val ruleFiles = remember(context) { ContentBlockingRuleFiles(context.contentResolver) }
  val transferFactory = remember(ruleFiles) { ContentBlockingTransferViewModelFactory(ruleFiles) }
  val transferViewModel: ContentBlockingTransferViewModel =
    viewModel(key = "content-blocking-transfer", factory = transferFactory)
  var selectedKindName by rememberSaveable { mutableStateOf(ContentBlockKind.KEYWORD.name) }
  val selectedKind = ContentBlockKind.entries.first { it.name == selectedKindName }
  val filteredRules =
      remember(config.rules, selectedKind) { config.rules.filter { it.kind == selectedKind } }
  var keywords by rememberSaveable { mutableStateOf("") }
  var targetId by rememberSaveable { mutableStateOf("") }
  var targetLabel by rememberSaveable { mutableStateOf("") }
  var pendingEditId by rememberSaveable { mutableStateOf<String?>(null) }
  var editDraft by rememberSaveable { mutableStateOf("") }
  var editSubmitted by rememberSaveable { mutableStateOf(false) }
  var pendingRemoveId by rememberSaveable { mutableStateOf<String?>(null) }
  var confirmClear by rememberSaveable { mutableStateOf(false) }
  var pendingIdentityTarget by remember { mutableStateOf<ContentBlockTarget?>(null) }
  var identityParseError by rememberSaveable { mutableStateOf<String?>(null) }
  val editingRule = config.rules.firstOrNull { it.id == pendingEditId }
  val removingRule = config.rules.firstOrNull { it.id == pendingRemoveId }

  LaunchedEffect(
    isCurrentPage,
    pendingEditId,
    pendingRemoveId,
    confirmClear,
    editSubmitted,
    ui.contentBlockingBusy,
    ui.contentBlockingError,
    config.rules,
    editDraft,
    pendingIdentityTarget,
  ) {
    if (!isCurrentPage) {
      pendingEditId = null
      editSubmitted = false
      pendingRemoveId = null
      confirmClear = false
      pendingIdentityTarget = null
      identityParseError = null
    } else if (!ui.contentBlockingBusy && ui.contentBlockingError == null) {
      if (editSubmitted && pendingEditId != null && editingRule?.value == editDraft) {
        pendingEditId = null
        editSubmitted = false
      }
      if (pendingRemoveId != null && removingRule == null) pendingRemoveId = null
      if (confirmClear && config.rules.isEmpty()) confirmClear = false
    }
  }

  Column(
    Modifier.fillMaxWidth().testTag("content-blocking-settings"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      shape = MaterialTheme.shapes.medium,
    ) {
      Text(
        "规则会筛选当前列表；收藏与历史按下方范围控制。屏蔽只影响列表显示，不删除本机记录或改变服务器分页。",
        Modifier.fillMaxWidth().padding(14.dp).testTag("content-blocking-scope-note"),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }

    ContentBlockingTransferControls(
      ui = ui,
      isCurrentPage = isCurrentPage,
      transferViewModel = transferViewModel,
      onAction = onAction,
    )

    SwitchRow(
      tag = "content-blocking-enabled",
      title = "启用屏蔽规则",
      checked = config.enabled,
      enabled = !busy,
      onCheckedChange = { onAction(ContentBlockingAction.SetEnabled(it)) },
    )
    SwitchRow(
      tag = "content-blocking-favorites",
      title = "应用到账号收藏",
      checked = config.applyToFavorites,
      enabled = !busy,
      onCheckedChange = { onAction(ContentBlockingAction.SetApplyToFavorites(it)) },
    )
    SwitchRow(
      tag = "content-blocking-history",
      title = "应用到历史记录（本机与账号）",
      checked = config.applyToHistory,
      enabled = !busy,
      onCheckedChange = { onAction(ContentBlockingAction.SetApplyToHistory(it)) },
    )

    Text("规则类型", style = MaterialTheme.typography.titleSmall)
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      ContentBlockKind.entries.forEach { kind ->
        FilterChip(
          selected = selectedKind == kind,
          onClick = {
            selectedKindName = kind.name
            pendingIdentityTarget = null
            identityParseError = null
          },
          label = { Text(kind.label) },
          modifier =
            Modifier.heightIn(min = 48.dp)
              .testTag("content-blocking-kind-${kind.name.lowercase()}"),
          enabled = !busy,
        )
      }
    }

    if (selectedKind == ContentBlockKind.KEYWORD) {
      OutlinedTextField(
        value = keywords,
        onValueChange = { keywords = it },
        label = { Text("关键词（每行一条）") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("content-blocking-keyword-input"),
        enabled = !busy,
      )
      Text("按标题或摘要字面匹配；不支持正则。", style = MaterialTheme.typography.bodySmall)
    } else {
      OutlinedTextField(
        value = targetId,
        onValueChange = {
          targetId = it
          pendingIdentityTarget = null
          identityParseError = null
        },
        label = { Text("小黑盒 ID 或支持的主页链接") },
        modifier = Modifier.fillMaxWidth().testTag("content-blocking-id-input"),
        enabled = !busy,
      )
      if (pendingIdentityTarget == null) {
        OutlinedTextField(
          value = targetLabel,
          onValueChange = {
            targetLabel = it
            pendingIdentityTarget = null
            identityParseError = null
          },
          label = { Text("本机备注（可选）") },
          modifier = Modifier.fillMaxWidth().testTag("content-blocking-label-input"),
          enabled = !busy,
        )
      }
      Text(
        if (selectedKind == ContentBlockKind.TOPIC)
          "话题链接格式未确认，请填写ID或从话题页快捷添加；备注仅供本机识别。"
        else
          "支持小黑盒作者/分区主页链接；只解析ID，不查询姓名。备注仅供本机识别。",
        style = MaterialTheme.typography.bodySmall,
      )
    }

    Button(
      onClick = {
        if (selectedKind == ContentBlockKind.KEYWORD) {
          onAction(ContentBlockingAction.AddKeywords(keywords))
        } else {
          try {
            pendingIdentityTarget =
              parseContentBlockIdentityInput(selectedKind, targetId, targetLabel)
            identityParseError = null
          } catch (error: IllegalArgumentException) {
            pendingIdentityTarget = null
            identityParseError = error.message ?: "ID或链接格式无效。"
          }
        }
      },
      enabled =
        !busy &&
          if (selectedKind == ContentBlockKind.KEYWORD) keywords.isNotBlank()
          else targetId.isNotBlank(),
      modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-add"),
    ) {
      Text(if (selectedKind == ContentBlockKind.KEYWORD) "添加关键词规则" else "预览${selectedKind.label}规则")
    }

    if (identityParseError != null) {
      Text(
        identityParseError.orEmpty(),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("content-blocking-link-error"),
      )
    }
    pendingIdentityTarget?.let { target ->
      ContentBlockingLinkPreview(
        target = target,
        enabled = !busy,
        onLabelChange = { label -> pendingIdentityTarget = target.copy(label = label) },
        onConfirm = {
          onAction(ContentBlockingAction.AddIdentity(target.kind, target.targetId, target.label))
          pendingIdentityTarget = null
        },
        onCancel = { pendingIdentityTarget = null },
      )
    }

    if (ui.contentBlockingLoading) Text("正在读取规则…", Modifier.testTag("content-blocking-loading"))
    if (ui.contentBlockingBusy) Text("正在保存…", Modifier.testTag("content-blocking-busy"))
    ui.contentBlockingError?.let { error ->
      Text(
        error,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("content-blocking-error"),
      )
      TextButton(
        onClick = { onAction(ContentBlockingAction.Reload) },
        enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-retry"),
      ) {
        Text("重试读取")
      }
    }
    ui.contentBlockingMessage?.let { Text(it, Modifier.testTag("content-blocking-message")) }

    HorizontalDivider()
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        "${selectedKind.label}规则（${filteredRules.size}）",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("content-blocking-rule-count"),
      )
      TextButton(
        onClick = { confirmClear = true },
        enabled = !busy && config.rules.isNotEmpty(),
        modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-clear"),
      ) {
        Text("清空全部（${config.rules.size}）")
      }
    }
    filteredRules.forEach { rule ->
      RuleRow(
        rule = rule,
        busy = busy,
        onToggle = { onAction(ContentBlockingAction.SetRuleEnabled(rule.id, it)) },
        onEdit = {
          pendingEditId = rule.id
          editDraft = rule.value
          editSubmitted = false
        },
        onRemove = { pendingRemoveId = rule.id },
      )
    }
    if (filteredRules.isEmpty() && !ui.contentBlockingLoading)
      Text(
        "当前没有${selectedKind.label}规则（${filteredRules.size}）。切换类型不会修改已保存规则。",
        Modifier.testTag("content-blocking-empty"),
      )
  }

  MotionAlertDialog(
    visible = editingRule != null && isCurrentPage && !ui.contentBlockingBusy,
    onDismiss = {
      pendingEditId = null
      editSubmitted = false
    },
    title = "编辑关键词",
    message = "",
    confirmLabel = "保存",
    onConfirm = {
      editingRule?.let {
        pendingEditId = it.id
        editSubmitted = true
        onAction(ContentBlockingAction.EditKeyword(it.id, editDraft))
      }
    },
    dismissLabel = "取消",
    content = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = editDraft,
          onValueChange = { editDraft = it },
          label = { Text("关键词") },
          modifier = Modifier.fillMaxWidth().testTag("content-blocking-edit-input"),
          enabled = !busy,
        )
        if (ui.contentBlockingError != null)
          Text(ui.contentBlockingError, color = MaterialTheme.colorScheme.error)
        ui.contentBlockingMessage?.let {
          Text(it, Modifier.testTag("content-blocking-edit-message"))
        }
      }
    },
  )
  MotionAlertDialog(
    visible = removingRule != null && isCurrentPage && !ui.contentBlockingBusy,
    onDismiss = { pendingRemoveId = null },
    title = "删除规则？",
    message = ui.contentBlockingError ?: "${removingRule?.displayValue.orEmpty()}",
    confirmLabel = "删除",
    onConfirm = {
      removingRule?.let {
        pendingRemoveId = it.id
        onAction(ContentBlockingAction.RemoveRule(it.id))
      }
    },
    dismissLabel = "取消",
    modifier = Modifier.testTag("content-blocking-remove-dialog"),
  )
  MotionAlertDialog(
    visible = confirmClear && isCurrentPage && !ui.contentBlockingBusy,
    onDismiss = { confirmClear = false },
    title = "清空所有规则？",
    message =
      ui.contentBlockingError
        ?: "将删除全部${config.rules.size}条规则（所有类型），保留总开关和收藏、历史范围设置。",
    confirmLabel = "清空",
    onConfirm = {
      onAction(ContentBlockingAction.ClearRules)
      confirmClear = true
    },
    dismissLabel = "取消",
    modifier = Modifier.testTag("content-blocking-clear-dialog"),
  )
}

@Composable
private fun SwitchRow(
  tag: String,
  title: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.width(8.dp))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      modifier = Modifier.semantics { contentDescription = title }.testTag(tag),
    )
  }
}

@Composable
private fun RuleRow(
  rule: ContentBlockRule,
  busy: Boolean,
  onToggle: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onRemove: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().testTag("content-blocking-rule-${rule.id}"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(rule.displayValue, style = MaterialTheme.typography.bodyLarge)
        Text(
          if (rule.kind == ContentBlockKind.KEYWORD) "关键词"
          else
            "${rule.kind.label} · 小黑盒 ID ${rule.value}${if (rule.label.isNotBlank()) " · ${rule.label}" else ""}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = rule.enabled,
        onCheckedChange = onToggle,
        enabled = !busy,
        modifier =
          Modifier.semantics { contentDescription = "启用${rule.displayValue}" }
            .testTag("content-blocking-rule-enabled-${rule.id}"),
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (rule.kind == ContentBlockKind.KEYWORD)
        TextButton(
          onClick = onEdit,
          enabled = !busy,
          modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-edit-${rule.id}"),
        ) {
          Text("编辑")
        }
      TextButton(
        onClick = onRemove,
        enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-remove-${rule.id}"),
      ) {
        Text("删除")
      }
    }
    HorizontalDivider()
  }
}

private val ContentBlockRule.displayValue: String
  get() = if (kind == ContentBlockKind.KEYWORD) value else label.ifBlank { "$value（未备注）" }
