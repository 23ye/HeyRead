package cn.heyread.app.ui.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.ContentBlockingMatcher
import cn.heyread.app.data.model.ContentBlockingScope as ModelContentBlockingScope
import cn.heyread.app.data.model.Story
import kotlinx.coroutines.flow.collect

typealias ContentBlockingScope = ModelContentBlockingScope

data class ContentBlockingContext(
  val config: ContentBlockingConfig = ContentBlockingConfig(enabled = false),
  val ready: Boolean = true,
  val loading: Boolean = false,
  val error: String? = null,
  val sessionKey: String = "",
)

val LocalContentBlockingContext = staticCompositionLocalOf { ContentBlockingContext() }

class ContentBlockingListState
internal constructor(
  val items: List<Story>,
  val blockedCount: Int,
  val matchedCount: Int,
  val allBlocked: Boolean,
  val loading: Boolean,
  val error: String?,
  val isTemporarilyShowing: Boolean,
  val rawTailBlocked: Boolean,
  private val isAutoPaginationAllowed: () -> Boolean,
  private val onToggleTemporaryVisibility: () -> Unit,
  private val onBeforeManualLoad: () -> Unit,
) {
  /** Reads the live gate so a just-tapped manual load also stops an old pagination collector. */
  val canAutoPaginate: Boolean
    get() = isAutoPaginationAllowed()

  fun toggleTemporaryVisibility() = onToggleTemporaryVisibility()

  fun beforeManualLoad() = onBeforeManualLoad()
}

@Composable
fun rememberContentBlockingList(
  raw: List<Story>,
  scope: ContentBlockingScope,
  identity: String,
  isCurrentPage: Boolean,
  listState: LazyListState? = null,
): ContentBlockingListState {
  val context = LocalContentBlockingContext.current
  val projection =
    remember(raw, context.config, scope) {
      ContentBlockingMatcher.project(raw, context.config, scope)
    }
  var observedSignature by
    remember(scope, identity, context.sessionKey) { mutableStateOf(projection.signature) }
  var lastHadMatches by
    remember(scope, identity, context.sessionKey) { mutableStateOf(projection.matchedCount > 0) }
  var temporarilyShowing by remember(scope, identity, context.sessionKey) { mutableStateOf(false) }
  var autoPaginationSuppressed by
    remember(scope, identity, context.sessionKey) { mutableStateOf(projection.matchedCount > 0) }
  val signatureChanged = observedSignature != projection.signature
  val effectiveTemporaryShow = temporarilyShowing && !signatureChanged
  SideEffect {
    if (signatureChanged) {
      autoPaginationSuppressed = lastHadMatches || projection.matchedCount > 0
      temporarilyShowing = false
      observedSignature = projection.signature
      lastHadMatches = projection.matchedCount > 0
    } else {
      lastHadMatches = projection.matchedCount > 0
    }
  }
  LaunchedEffect(listState, scope, identity, context.sessionKey) {
    listState?.interactionSource?.interactions?.collect { interaction ->
      if (interaction is DragInteraction.Start) {
        autoPaginationSuppressed = false
      }
    }
  }
  LaunchedEffect(isCurrentPage) { if (!isCurrentPage) temporarilyShowing = false }

  val loading = context.loading || !context.ready && context.error == null
  val error = context.error.takeUnless { context.ready }
  val visibleItems =
    when {
      loading -> emptyList()
      !context.ready || effectiveTemporaryShow -> raw
      else -> projection.items
    }
  val blockedCount = if (effectiveTemporaryShow || !context.ready) 0 else projection.matchedCount
  val allBlocked = raw.isNotEmpty() && visibleItems.isEmpty() && blockedCount > 0
  val rawTailBlocked = projection.tailBlocked && !effectiveTemporaryShow
  return ContentBlockingListState(
    items = visibleItems,
    blockedCount = blockedCount,
    matchedCount = projection.matchedCount,
    allBlocked = allBlocked,
    loading = loading,
    error = error,
    isTemporarilyShowing = effectiveTemporaryShow,
    rawTailBlocked = projection.tailBlocked,
    isAutoPaginationAllowed = {
      val paginationSuppressed =
        autoPaginationSuppressed ||
          signatureChanged && (lastHadMatches || projection.matchedCount > 0)
      val allowed =
        !loading &&
          (!context.ready ||
            error != null ||
            (!effectiveTemporaryShow && !rawTailBlocked && !paginationSuppressed))
      allowed
    },
    onToggleTemporaryVisibility = {
      temporarilyShowing = !temporarilyShowing
      if (projection.matchedCount > 0) autoPaginationSuppressed = true
    },
    onBeforeManualLoad = { if (projection.matchedCount > 0) autoPaginationSuppressed = true },
  )
}

@Composable
fun ContentBlockingNotice(state: ContentBlockingListState) {
  when {
    state.loading ->
      Text(
        "正在读取屏蔽规则…",
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("content-blocking-loading"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    state.error != null ->
      Text(
        "规则暂不可用，显示原内容。",
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("content-blocking-unavailable"),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    state.matchedCount > 0 ->
      Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          if (state.isTemporarilyShowing) "临时显示已隐藏内容" else "已隐藏${state.blockedCount}条已加载内容",
          modifier =
            Modifier.weight(1f).padding(vertical = 12.dp).testTag("content-blocking-notice"),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
          onClick = state::toggleTemporaryVisibility,
          modifier = Modifier.heightIn(min = 48.dp).testTag("content-blocking-temporary-toggle"),
        ) {
          Text(if (state.isTemporarilyShowing) "恢复屏蔽" else "临时显示")
        }
      }
  }
}

@Composable
fun ProvideContentBlockingContext(
  context: ContentBlockingContext,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(LocalContentBlockingContext provides context, content = content)
}
