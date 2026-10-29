package cn.heyread.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.model.SteamGame
import cn.heyread.app.ui.motion.pageInputGate
import cn.heyread.app.viewmodel.SteamGameSearchState
import coil.compose.AsyncImage

@Composable
fun SteamGameSearchSection(
  state: SteamGameSearchState,
  noImages: Boolean,
  active: Boolean,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onClear: () -> Unit,
  onSelect: (SteamGame) -> Unit,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester? = null,
  focusRequestId: Int = 0,
) {
  val localFocusRequester = remember { FocusRequester() }
  val actualFocusRequester = focusRequester ?: localFocusRequester
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val inputFocused = remember { mutableStateOf(false) }

  LaunchedEffect(active, focusRequestId, focusRequester) {
    if (active && focusRequester != null && focusRequestId > 0) {
      actualFocusRequester.requestFocus()
      keyboard?.show()
    }
  }
  LaunchedEffect(active, inputFocused.value) {
    if (!active && inputFocused.value) {
      keyboard?.hide()
      focusManager.clearFocus(force = true)
    }
  }
  fun submitSearch() {
    if (!active || state.loading || state.query.isBlank()) return
    onSubmit()
    keyboard?.hide()
    focusManager.clearFocus()
  }

  Card(
    modifier = modifier.fillMaxWidth().testTag("steam-game-search-section").pageInputGate(active),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(18.dp),
  ) {
    Column(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        "搜索 Steam 游戏",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 300.dp
        if (compact) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SteamGameSearchInput(
              query = state.query,
              enabled = active,
              isError = state.error != null,
              focusRequester = actualFocusRequester,
              onFocusChanged = { inputFocused.value = it },
              onQueryChange = onQueryChange,
              onClear = onClear,
              onSubmit = ::submitSearch,
              modifier = Modifier.fillMaxWidth(),
            )
            SteamGameSearchButton(
              enabled = active && state.query.isNotBlank() && !state.loading,
              onClick = ::submitSearch,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        } else {
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            SteamGameSearchInput(
              query = state.query,
              enabled = active,
              isError = state.error != null,
              focusRequester = actualFocusRequester,
              onFocusChanged = { inputFocused.value = it },
              onQueryChange = onQueryChange,
              onClear = onClear,
              onSubmit = ::submitSearch,
              modifier = Modifier.weight(1f),
            )
            SteamGameSearchButton(
              enabled = active && state.query.isNotBlank() && !state.loading,
              onClick = ::submitSearch,
            )
          }
        }
      }

      if (state.loading) {
        Row(
          Modifier.fillMaxWidth().testTag("steam-game-search-loading"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
          Text("正在搜索 Steam 商店…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      state.error?.let { error ->
        Row(
          Modifier.fillMaxWidth().testTag("steam-game-search-error"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            error,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
          TextButton(onClick = ::submitSearch, enabled = active && !state.loading) { Text("重试") }
        }
      }

      if (state.hasSearched && !state.loading && state.error == null && state.results.isEmpty()) {
        Text(
          "没有匹配的 Steam 游戏，可尝试英文名或AppID。",
          Modifier.fillMaxWidth().testTag("steam-game-search-empty"),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }

      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showResultImage = active && !noImages && maxWidth >= 300.dp
        Column(Modifier.fillMaxWidth()) {
          state.results.take(20).forEach { game ->
            Row(
              Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = active) { onSelect(game) }
                .testTag("steam-game-result-${game.appId}")
                .padding(horizontal = 8.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              val imageUrl = game.imageUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
              if (showResultImage && imageUrl != null) {
                AsyncImage(
                  model = imageUrl,
                  contentDescription = null,
                  modifier =
                    Modifier.size(width = 64.dp, height = 48.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .testTag("steam-game-result-image-${game.appId}"),
                )
              }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(game.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                  "AppID ${game.appId}",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SteamGameSearchInput(
  query: String,
  enabled: Boolean,
  isError: Boolean,
  focusRequester: FocusRequester,
  onFocusChanged: (Boolean) -> Unit,
  onQueryChange: (String) -> Unit,
  onClear: () -> Unit,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    enabled = enabled,
    modifier =
      modifier
        .focusRequester(focusRequester)
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .testTag("steam-game-search-query"),
    singleLine = true,
    placeholder = { Text("输入游戏名或AppID") },
    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
    trailingIcon = {
      if (query.isNotEmpty()) {
        IconButton(
          onClick = onClear,
          enabled = enabled,
          modifier = Modifier.testTag("steam-game-search-clear"),
        ) {
          Icon(Icons.Filled.Close, contentDescription = "清空搜索")
        }
      }
    },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    isError = isError,
  )
}

@Composable
private fun SteamGameSearchButton(
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.heightIn(min = 48.dp).testTag("steam-game-search-submit"),
  ) {
    Text("搜索")
  }
}
