package cn.heyread.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.local.ContentBlockingImportDocument
import cn.heyread.app.data.local.ContentBlockingImportPreview
import cn.heyread.app.data.local.ContentBlockingRuleFileAccess
import cn.heyread.app.data.local.ContentBlockingRuleFileException
import cn.heyread.app.data.local.previewContentBlockingImport
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContentBlockingTransferUiState(
  val selectingImport: Boolean = false,
  val selectingExport: Boolean = false,
  val readingImport: Boolean = false,
  val writingExport: Boolean = false,
  val importDocument: ContentBlockingImportDocument? = null,
  val importPreview: ContentBlockingImportPreview? = null,
  val error: String? = null,
  val previewError: String? = null,
  val previewNotice: String? = null,
  val exportMessage: String? = null,
)

/** Owns picker requests and file work for the visible content-blocking settings page. */
class ContentBlockingTransferViewModel(
  private val files: ContentBlockingRuleFileAccess,
) : ViewModel() {
  private val mutableState = MutableStateFlow(ContentBlockingTransferUiState())
  val state = mutableState.asStateFlow()

  private var activePageOwner: String? = null
  private var pageOwner = 0L
  private var nextRequestId = 0L
  private var pendingImportRequest: PickerRequest? = null
  private var pendingExportRequest: PickerRequest? = null
  private var fileJob: Job? = null
  private var latestConfig = ContentBlockingConfig()

  fun setPageVisible(ownerId: String, visible: Boolean) {
    if (visible) {
      if (activePageOwner == ownerId) return
      invalidateOwnerWork()
      activePageOwner = ownerId
      pageOwner++
    } else if (activePageOwner == ownerId) {
      activePageOwner = null
      invalidateOwnerWork()
    }
  }

  private fun invalidateOwnerWork() {
    pageOwner++
    pendingImportRequest = null
    pendingExportRequest = null
    fileJob?.cancel()
    fileJob = null
    mutableState.value = ContentBlockingTransferUiState()
  }

  fun updateConfig(config: ContentBlockingConfig) {
    latestConfig = config
    val current = mutableState.value
    val document = current.importDocument ?: return
    try {
      val nextPreview = previewContentBlockingImport(config, document)
      val tooManyRules = config.rules.size + nextPreview.addedCount > 500
      val previewChanged = current.importPreview != nextPreview
      if (previewChanged || tooManyRules) {
        mutableState.value =
          current.copy(
            importPreview = nextPreview,
            previewError = if (tooManyRules) "最多保存500条屏蔽规则。" else null,
            previewNotice = null,
          )
      }
    } catch (error: Exception) {
      mutableState.value =
        current.copy(
          importPreview = null,
          previewError = safePreviewError(error),
          previewNotice = null,
        )
    }
  }

  fun beginImportPicker(): Long? {
    val current = mutableState.value
    if (
      activePageOwner == null ||
        current.selectingImport ||
        current.selectingExport ||
        current.readingImport ||
        current.writingExport
    ) return null
    fileJob?.cancel()
    val request = PickerRequest(++nextRequestId, pageOwner, requireNotNull(activePageOwner))
    pendingExportRequest = null
    pendingImportRequest = request
    mutableState.value =
      mutableState.value.copy(
        selectingImport = true,
        selectingExport = false,
        error = null,
        previewError = null,
        previewNotice = null,
        exportMessage = null,
      )
    return request.id
  }

  fun onImportPickerResult(requestId: Long, uri: Uri?) {
    val request = pendingImportRequest
    if (!isCurrent(request, requestId)) return
    pendingImportRequest = null
    if (uri == null) {
      mutableState.value = mutableState.value.copy(selectingImport = false)
      return
    }
    mutableState.value =
      mutableState.value.copy(selectingImport = false, readingImport = true, error = null)
    fileJob =
      viewModelScope.launch {
        try {
          val document = files.read(uri)
          if (!isCurrentOwner(request)) return@launch
          try {
            val preview = previewContentBlockingImport(latestConfig, document)
            val tooManyRules = latestConfig.rules.size + preview.addedCount > 500
            mutableState.value =
              mutableState.value.copy(
                readingImport = false,
                importDocument = document,
                importPreview = preview,
                error = null,
                previewError = if (tooManyRules) "最多保存500条屏蔽规则。" else null,
                previewNotice = null,
                exportMessage = null,
              )
          } catch (error: Exception) {
            mutableState.value =
              mutableState.value.copy(
                readingImport = false,
                importDocument = document,
                importPreview = null,
                previewError = safePreviewError(error),
                previewNotice = null,
                error = null,
              )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (isCurrentOwner(request)) {
            mutableState.value =
              mutableState.value.copy(readingImport = false, error = readableFileError(error))
          }
        }
      }
  }

  fun beginExportPicker(rules: List<ContentBlockRule>): Long? {
    val current = mutableState.value
    if (
      activePageOwner == null ||
        current.selectingImport ||
        current.selectingExport ||
        current.readingImport ||
        current.writingExport
    ) return null
    fileJob?.cancel()
    val request = PickerRequest(++nextRequestId, pageOwner, requireNotNull(activePageOwner))
    pendingImportRequest = null
    pendingExportRequest = request.copy(exportRules = rules.toList())
    mutableState.value =
      mutableState.value.copy(
        selectingImport = false,
        selectingExport = true,
        error = null,
        exportMessage = null,
      )
    return request.id
  }

  fun onExportPickerResult(requestId: Long, uri: Uri?) {
    val request = pendingExportRequest
    if (!isCurrent(request, requestId)) return
    pendingExportRequest = null
    if (uri == null) {
      mutableState.value = mutableState.value.copy(selectingExport = false)
      return
    }
    val snapshot = request?.exportRules.orEmpty()
    mutableState.value =
      mutableState.value.copy(selectingExport = false, writingExport = true, error = null)
    fileJob =
      viewModelScope.launch {
        try {
          val count = files.write(uri, snapshot)
          if (!isCurrentOwner(request)) return@launch
          mutableState.value =
            mutableState.value.copy(
              writingExport = false,
              exportMessage = "已导出${count}条规则。",
              error = null,
            )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (isCurrentOwner(request)) {
            mutableState.value =
              mutableState.value.copy(writingExport = false, error = readableFileError(error))
          }
        }
      }
  }

  fun confirmImport(
    ownerId: String,
    document: ContentBlockingImportDocument,
    shownPreview: ContentBlockingImportPreview,
    currentConfig: ContentBlockingConfig,
    onAction: (ContentBlockingAction) -> Unit,
  ): Boolean {
    if (!isActiveOwner(ownerId)) return false
    latestConfig = currentConfig
    val preview = try {
      previewContentBlockingImport(currentConfig, document)
    } catch (error: Exception) {
      mutableState.value =
        mutableState.value.copy(previewError = safePreviewError(error), previewNotice = null)
      return false
    }
    if (preview != shownPreview) {
      mutableState.value = mutableState.value.copy(
        importPreview = preview,
        previewError = null,
        previewNotice = "规则状态已变化，预览已更新，请再次确认。",
      )
      return false
    }
    if (currentConfig.rules.size + preview.addedCount > 500) {
      mutableState.value =
        mutableState.value.copy(
          previewError = "最多保存500条屏蔽规则。",
          previewNotice = null,
        )
      return false
    }
    if (preview.addedCount <= 0) {
      dismissImportPreview(ownerId)
      return true
    }
    onAction(ContentBlockingAction.MergeImportedRules(document.rules, shownPreview.baseRules))
    mutableState.value =
      mutableState.value.copy(
        importDocument = null,
        importPreview = null,
        error = null,
        previewError = null,
        previewNotice = null,
      )
    return true
  }

  fun dismissImportPreview(ownerId: String) {
    if (!isActiveOwner(ownerId)) return
    mutableState.value =
      mutableState.value.copy(
        importDocument = null,
        importPreview = null,
        previewError = null,
        previewNotice = null,
      )
  }

  private fun isCurrent(request: PickerRequest?, requestId: Long): Boolean =
    request != null &&
      request.id == requestId &&
      request.id == nextRequestId &&
      request.owner == pageOwner &&
      isActiveOwner(request.ownerId)

  private fun isCurrentOwner(request: PickerRequest?): Boolean =
    request != null &&
      request.id == nextRequestId &&
      request.owner == pageOwner &&
      isActiveOwner(request.ownerId)

  private fun isActiveOwner(ownerId: String): Boolean = activePageOwner == ownerId

  private fun safePreviewError(error: Exception): String =
    (error as? IllegalArgumentException)?.message ?: "无法生成导入预览，请检查规则。"

  private fun readableFileError(error: Exception): String =
    when (error) {
      is ContentBlockingRuleFileException -> error.safeMessage
      is IllegalArgumentException -> error.message ?: "规则文件格式无效。"
      is IOException -> "无法读取或写入所选文件。"
      else -> "规则文件操作失败，请检查文件后重试。"
    }

  private data class PickerRequest(
    val id: Long,
    val owner: Long,
    val ownerId: String,
    val exportRules: List<ContentBlockRule> = emptyList(),
  )
}

class ContentBlockingTransferViewModelFactory(
  private val files: ContentBlockingRuleFileAccess,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    require(modelClass.isAssignableFrom(ContentBlockingTransferViewModel::class.java))
    return ContentBlockingTransferViewModel(files) as T
  }
}
