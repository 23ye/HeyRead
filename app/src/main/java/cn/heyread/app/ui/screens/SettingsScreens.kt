package cn.heyread.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.HomeLayout
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.data.model.ReadingPreferences
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.ui.components.AnimatedImage
import cn.heyread.app.ui.components.ContentBlockingNotice
import cn.heyread.app.ui.components.ContentBlockingScope
import cn.heyread.app.ui.components.MotionAlertDialog
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.components.rememberContentBlockingList
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.cancelSharedCoverOnScroll
import cn.heyread.app.viewmodel.AccountUiState
import cn.heyread.app.viewmodel.UiState
import coil.compose.AsyncImagePainter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

enum class SettingsCategory(val title: String, val summary: String) {
  Appearance("外观与阅读体验", "主题、卡片布局和阅读偏好"),
  Data("数据来源与缓存", "公开来源、连接状态和缓存"),
  ContentBlocking("内容屏蔽", "管理关键词、作者、分区和话题规则"),
  LocalHistory("本机阅读历史", "仅保存在这台设备上的阅读记录"),
  About("关于盒阅", "版本、内容来源与第三方说明"),
}

@Composable
fun MineSettingsContent(
  ui: UiState,
  account: AccountUiState,
  isCurrentPage: Boolean,
  onLogin: () -> Unit,
  onLogout: () -> Unit,
  onOpenFavorites: () -> Unit,
  onAccountHistory: () -> Unit,
  onThemeChange: (String) -> Unit,
  onHomeLayoutChange: (HomeLayout) -> Unit,
  onAccentChange: (String) -> Unit,
  onMotionChange: (Boolean) -> Unit,
  onNoImagesChange: (Boolean) -> Unit,
  onGatewaySave: (String) -> Unit,
  onClearCache: () -> Unit,
  onOpenStory: (Story) -> Unit,
  onClearHistory: () -> Unit,
  onReadingPreferencesChange: (ReadingPreferences) -> Unit = {},
  onOpenCategory: ((SettingsCategory) -> Unit)? = null,
  selectedCategory: SettingsCategory? = null,
  onImportFont: () -> Unit = {},
  onRemoveFont: (String) -> Unit = {},
  onOpenOwnProfile: () -> Unit = {},
  onContentBlockingAction: (ContentBlockingAction) -> Unit = {},
) {
  var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
  var gatewayDraft by rememberSaveable(ui.gateway) { mutableStateOf(ui.gateway) }
  var advanced by rememberSaveable { mutableStateOf(false) }
  val stateHolder = rememberSaveableStateHolder()
  val localCategory =
    if (onOpenCategory == null)
      selectedCategoryName?.let { selected ->
        SettingsCategory.entries.firstOrNull { it.name == selected }
      }
    else null
  val category = localCategory
  BackHandler(enabled = category != null && isCurrentPage) { selectedCategoryName = null }
  Column(Modifier.fillMaxSize().testTag("mine-settings-root")) {
    PageTopBar(
      category?.title ?: "我的",
      isRoot = category == null,
      onBack = { selectedCategoryName = null },
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      stateHolder.SaveableStateProvider(category?.name ?: "overview") {
        if (category == null) {
          SettingsOverviewScreen(
            account = account,
            ui = ui,
            onLogin = onLogin,
            onLogout = onLogout,
            onOpenFavorites = onOpenFavorites,
            onAccountHistory = onAccountHistory,
            onOpenOwnProfile = onOpenOwnProfile,
            selectedCategory = selectedCategory,
            onCategory = { selected ->
              if (onOpenCategory != null) onOpenCategory(selected)
              else selectedCategoryName = selected.name
            },
          )
        } else {
          SettingsCategoryScreen(
            category = category,
            ui = ui,
            gatewayValue = gatewayDraft,
            advanced = advanced,
            onGatewayChange = { gatewayDraft = it },
            onAdvancedChange = { advanced = it },
            onThemeChange = onThemeChange,
            onHomeLayoutChange = onHomeLayoutChange,
            onAccentChange = onAccentChange,
            onMotionChange = onMotionChange,
            onNoImagesChange = onNoImagesChange,
            onGatewaySave = { onGatewaySave(gatewayDraft) },
            onClearCache = onClearCache,
            onOpenStory = onOpenStory,
            onClearHistory = onClearHistory,
            onBack = { selectedCategoryName = null },
            onReadingPreferencesChange = onReadingPreferencesChange,
            onImportFont = onImportFont,
            onRemoveFont = onRemoveFont,
            onContentBlockingAction = onContentBlockingAction,
            isCurrentPage = isCurrentPage,
          )
        }
      }
    }
  }
}

@Composable
fun SettingsOverviewScreen(
  account: AccountUiState,
  ui: UiState,
  onLogin: () -> Unit,
  onLogout: () -> Unit,
  onOpenFavorites: () -> Unit,
  onAccountHistory: () -> Unit,
  onCategory: (SettingsCategory) -> Unit,
  onOpenOwnProfile: () -> Unit = {},
  selectedCategory: SettingsCategory? = null,
) {
  Column(
    Modifier.fillMaxWidth()
      .testTag("settings-overview")
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    AccountHeaderCard(account, ui.noImages, onLogin, onLogout, onOpenOwnProfile)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      GroupHeading("小黑盒账号")
      SettingsGroup {
        SettingsRow(
          icon = { Icon(Icons.Filled.Favorite, null) },
          title = "账号收藏",
          subtitle = if (account.session != null) "查看小黑盒云端收藏" else "登录后查看云端收藏",
          tag = "settings-account-favorites",
          onClick = onOpenFavorites,
        )
        GroupDivider()
        SettingsRow(
          icon = { Icon(Icons.Filled.DateRange, null) },
          title = "账号浏览历史",
          subtitle = if (account.session != null) "小黑盒账号记录" else "登录后查看账号历史",
          tag = "settings-account-history",
          onClick = onAccountHistory,
        )
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      GroupHeading("设置")
      SettingsGroup {
        SettingsCategory.entries.forEachIndexed { index, category ->
          if (index > 0) GroupDivider()
          val icon =
            when (category) {
              SettingsCategory.Appearance -> Icons.Filled.Settings
              SettingsCategory.Data -> Icons.Filled.Settings
              SettingsCategory.ContentBlocking -> Icons.Filled.Settings
              SettingsCategory.LocalHistory -> Icons.Filled.DateRange
              SettingsCategory.About -> Icons.Filled.Info
            }
          SettingsRow(
            icon = { Icon(icon, null) },
            title = category.title,
            subtitle = categorySummary(category, ui),
            tag = "settings-category-${categoryTag(category)}",
            selected = selectedCategory == category,
            onClick = { onCategory(category) },
          )
        }
      }
    }
  }
}

@Composable
private fun AccountHeaderCard(
  account: AccountUiState,
  noImages: Boolean,
  onLogin: () -> Unit,
  onLogout: () -> Unit,
  onOpenOwnProfile: () -> Unit,
) {
  val session = account.session
  val canOpenProfile = session?.userId?.isNotBlank() == true
  val onHeaderClick = {
    when {
      account.loadingSession -> Unit
      session != null -> if (canOpenProfile) onOpenOwnProfile() else Unit
      else -> onLogin()
    }
  }
  Surface(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(enabled = !account.loadingSession && (session == null || canOpenProfile)) {
          onHeaderClick()
        }
        .testTag("settings-account-header"),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
      val compact = maxWidth < 300.dp
      val avatarSize = if (compact) 42.dp else 52.dp
      if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            AccountAvatar(account, noImages, avatarSize)
            AccountText(account, Modifier.weight(1f))
          }
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            AccountAction(account, onLogin, onLogout)
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          AccountAvatar(account, noImages, avatarSize)
          AccountText(account, Modifier.weight(1f))
          AccountAction(account, onLogin, onLogout)
        }
      }
    }
  }
}

@Composable
private fun AccountAvatar(
  account: AccountUiState,
  noImages: Boolean,
  size: androidx.compose.ui.unit.Dp,
) {
  val session = account.session
  val avatarUrl = normalizedAvatarUrl(session?.avatarUrl).takeUnless { noImages }
  var imageLoaded by remember(session?.userId, avatarUrl) { mutableStateOf(false) }
  Box(
    Modifier.size(size)
      .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
      .testTag("settings-account-avatar"),
    contentAlignment = Alignment.Center,
  ) {
    if (session != null && session.nickname.isNotBlank()) {
      Text(
        session.nickname.take(1),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("settings-account-avatar-fallback"),
      )
    } else {
      Icon(
        if (session == null) Icons.Filled.Person else Icons.Filled.AccountCircle,
        null,
        modifier = Modifier.testTag("settings-account-avatar-fallback"),
      )
    }
    if (avatarUrl != null) {
      key(session?.userId, avatarUrl) {
        AnimatedImage(
          model = avatarUrl,
          contentDescription = "账号头像",
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
          modifier =
            Modifier.size(size)
              .clip(CircleShape)
              .alpha(if (imageLoaded) 1f else 0f)
              .testTag("settings-account-avatar-image"),
          onState = { state -> imageLoaded = state is AsyncImagePainter.State.Success },
        )
      }
    }
  }
}

private fun normalizedAvatarUrl(raw: String?): String? {
  val value =
    raw?.trim()?.takeIf {
      it.isNotEmpty() &&
        it.length <= 2048 &&
        it.none { char -> char.isWhitespace() || char.code < 0x20 || char.code in 0x7f..0x9f }
    } ?: return null
  val candidate = if (value.startsWith("//")) "https:$value" else value
  val uri = runCatching { android.net.Uri.parse(candidate) }.getOrNull() ?: return null
  if (!uri.scheme.equals("https", ignoreCase = true)) return null
  val authority = uri.encodedAuthority ?: return null
  if (uri.host.isNullOrBlank() || authority.contains('@')) return null
  return candidate
}

@Composable
private fun AccountText(account: AccountUiState, modifier: Modifier) {
  val session = account.session
  val title =
    session?.nickname?.ifBlank { "小黑盒用户" } ?: if (account.loadingSession) "正在读取账号状态…" else "访客模式"
  Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
      when {
        session != null -> "账号 ID：${session.userId}"
        account.loadingSession -> "正在恢复已保存的会话"
        else -> "登录后读取账号收藏、历史和文章内容"
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun AccountAction(account: AccountUiState, onLogin: () -> Unit, onLogout: () -> Unit) {
  if (account.session != null) {
    TextButton(
      onClick = onLogout,
      modifier = Modifier.heightIn(min = 48.dp).testTag("settings-account-logout"),
    ) {
      Text("退出登录")
    }
  } else if (!account.loadingSession) {
    Button(
      onClick = onLogin,
      modifier = Modifier.heightIn(min = 48.dp).testTag("settings-account-login"),
    ) {
      Text("登录")
    }
  }
}

@Composable
fun SettingsCategoryScreen(
  category: SettingsCategory,
  ui: UiState,
  gatewayValue: String,
  advanced: Boolean,
  onGatewayChange: (String) -> Unit,
  onAdvancedChange: (Boolean) -> Unit,
  onThemeChange: (String) -> Unit,
  onHomeLayoutChange: (HomeLayout) -> Unit,
  onAccentChange: (String) -> Unit,
  onMotionChange: (Boolean) -> Unit,
  onNoImagesChange: (Boolean) -> Unit,
  onGatewaySave: () -> Unit,
  onClearCache: () -> Unit,
  onOpenStory: (Story) -> Unit,
  onClearHistory: () -> Unit,
  onBack: () -> Unit,
  onReadingPreferencesChange: (ReadingPreferences) -> Unit = {},
  onImportFont: () -> Unit = {},
  onRemoveFont: (String) -> Unit = {},
  onContentBlockingAction: (ContentBlockingAction) -> Unit = {},
  fontPickerPending: Boolean = false,
  isCurrentPage: Boolean = true,
  scrollState: ScrollState = rememberScrollState(),
) {
  var pendingRemovalId by remember { mutableStateOf<String?>(null) }
  val pendingFont = ui.importedFonts.firstOrNull { it.id == pendingRemovalId }
  LaunchedEffect(isCurrentPage, pendingRemovalId, ui.importedFonts, ui.fontBusy) {
    if (!isCurrentPage || ui.fontBusy || (pendingRemovalId != null && pendingFont == null)) {
      pendingRemovalId = null
    }
  }
  Column(
    Modifier.fillMaxWidth()
      .testTag("settings-category-${categoryTag(category)}")
      .verticalScroll(scrollState)
      .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    when (category) {
      SettingsCategory.Appearance ->
        AppearanceSettings(
          ui,
          onThemeChange,
          onHomeLayoutChange,
          onAccentChange,
          onMotionChange,
          onNoImagesChange,
          onReadingPreferencesChange,
          onImportFont,
          { pendingRemovalId = it },
          !ui.fontBusy && !fontPickerPending,
        )
      SettingsCategory.Data ->
        DataSettings(
          ui,
          gatewayValue,
          advanced,
          onGatewayChange,
          onAdvancedChange,
          onGatewaySave,
          onClearCache,
        )
      SettingsCategory.ContentBlocking ->
        ContentBlockingSettings(ui, onContentBlockingAction, isCurrentPage)
      SettingsCategory.LocalHistory -> {
        cancelSharedCoverOnScroll(
          scrollState,
          LocalSharedCoverEntryId.current,
          LocalSharedCoverCoordinator.current,
        )
        LocalHistorySettings(ui, onOpenStory, onClearHistory, isCurrentPage)
      }
      SettingsCategory.About -> AboutSettings()
    }
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回我的") }
  }
  MotionAlertDialog(
    visible = pendingFont != null && isCurrentPage && !ui.fontBusy,
    onDismiss = { pendingRemovalId = null },
    title = "移除导入字体？",
    message = "",
    confirmLabel = "移除",
    onConfirm = {
      val fontId = pendingFont?.id
      if (
        fontId != null && isCurrentPage && !ui.fontBusy && ui.importedFonts.any { it.id == fontId }
      ) {
        pendingRemovalId = null
        onRemoveFont(fontId)
      }
    },
    dismissLabel = "取消",
    content = {
      Text(
        "确定移除「${pendingFont?.displayName.orEmpty()}」？如果当前正在使用，整个应用将切回系统字体。",
        Modifier.testTag("settings-font-remove-dialog-content"),
      )
    },
  )
}

@Composable
private fun ReadingTypographySettings(
  ui: UiState,
  onChange: (ReadingPreferences) -> Unit,
  onImportFont: () -> Unit,
  onRemoveFont: (String) -> Unit,
  importEnabled: Boolean,
) {
  var draft by remember { mutableStateOf(ui.readingPreferences.normalized()) }
  var lastObserved by remember { mutableStateOf(ui.readingPreferences.normalized()) }
  var draggingSlider by remember { mutableStateOf<String?>(null) }
  var invalidatedDrag by remember { mutableStateOf(false) }
  val latestPreferences = ui.readingPreferences.normalized()
  androidx.compose.runtime.SideEffect {
    if (latestPreferences != lastObserved) {
      lastObserved = latestPreferences
      if (draggingSlider != null && latestPreferences != draft.normalized()) {
        invalidatedDrag = true
      }
      draft = latestPreferences
    }
  }
  val current = draft.normalized()
  SettingsGroup {
    SectionLabel("正文排版")
    PreferenceSlider(
      title = "字号",
      value = current.fontSizeSp,
      valueLabel = "${current.fontSizeSp.toInt()} sp",
      valueRange = ReadingPreferences.MIN_FONT_SIZE_SP..ReadingPreferences.MAX_FONT_SIZE_SP,
      steps = 11,
      rangeLabel = "14–26 sp，每格 1",
      testTag = "settings-reading-font-size-slider",
      onValueChange = { draft = draft.copy(fontSizeSp = it.roundToInt().toFloat()).normalized() },
      onDragStarted = {
        draggingSlider = "font-size"
        invalidatedDrag = false
      },
      onValueChangeFinished = { cancelled ->
        finishPreferenceSlider(
          cancelled,
          latestPreferences = ui.readingPreferences.normalized(),
          draft = draft,
          onDraft = { draft = it },
          onCommit = onChange,
          isInvalidated = invalidatedDrag,
          onDraggingChanged = { draggingSlider = it },
        )
      },
    )
    GroupDivider()
    PreferenceSlider(
      title = "行高",
      value = current.lineHeightMultiplier,
      valueLabel = "${"%.2f".format(java.util.Locale.ROOT, current.lineHeightMultiplier)} ×",
      valueRange =
        ReadingPreferences.MIN_LINE_HEIGHT_MULTIPLIER..ReadingPreferences
            .MAX_LINE_HEIGHT_MULTIPLIER,
      steps = 11,
      rangeLabel = "1.20–2.40 ×，每格 0.10",
      testTag = "settings-reading-line-height-slider",
      onValueChange = {
        draft = draft.copy(lineHeightMultiplier = (it * 10f).roundToInt() / 10f).normalized()
      },
      onDragStarted = {
        draggingSlider = "line-height"
        invalidatedDrag = false
      },
      onValueChangeFinished = { cancelled ->
        finishPreferenceSlider(
          cancelled,
          latestPreferences = ui.readingPreferences.normalized(),
          draft = draft,
          onDraft = { draft = it },
          onCommit = onChange,
          isInvalidated = invalidatedDrag,
          onDraggingChanged = { draggingSlider = it },
        )
      },
    )
    GroupDivider()
    PreferenceSlider(
      title = "段间距",
      value = current.paragraphSpacingDp,
      valueLabel = "${current.paragraphSpacingDp.toInt()} dp",
      valueRange =
        ReadingPreferences.MIN_PARAGRAPH_SPACING_DP..ReadingPreferences.MAX_PARAGRAPH_SPACING_DP,
      steps = 15,
      rangeLabel = "8–40 dp，每格 2",
      testTag = "settings-reading-paragraph-spacing-slider",
      onValueChange = {
        val quantized = 8f + ((it - 8f) / 2f).roundToInt() * 2f
        draft = draft.copy(paragraphSpacingDp = quantized).normalized()
      },
      onDragStarted = {
        draggingSlider = "paragraph-spacing"
        invalidatedDrag = false
      },
      onValueChangeFinished = { cancelled ->
        finishPreferenceSlider(
          cancelled,
          latestPreferences = ui.readingPreferences.normalized(),
          draft = draft,
          onDraft = { draft = it },
          onCommit = onChange,
          isInvalidated = invalidatedDrag,
          onDraggingChanged = { draggingSlider = it },
        )
      },
    )
    Text(
      "滑动实时预览，松手后保存。",
      Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GroupDivider()
    SectionLabel("全局字体")
    Text(
      "所选字体用于应用界面和正文；字号、行高与段间距只影响正文。",
      Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ReadingFont.entries
      .filter { it != ReadingFont.CUSTOM }
      .forEach { font ->
        OptionRow(
          tag = "settings-reading-font-${font.storedValue}",
          title = fontLabel(font),
          selected = current.font == font,
          onClick = { onChange(current.copy(font = font, customFontId = null).normalized()) },
        )
      }
    ui.importedFonts.forEach { font ->
      Column {
        OptionRow(
          tag = "settings-reading-font-custom-${font.id}",
          title = font.displayName,
          subtitle = "导入字体",
          selected = current.font == ReadingFont.CUSTOM && current.customFontId == font.id,
          onClick = {
            onChange(current.copy(font = ReadingFont.CUSTOM, customFontId = font.id).normalized())
          },
        )
        TextButton(
          onClick = { onRemoveFont(font.id) },
          enabled = !ui.fontBusy,
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("settings-reading-font-remove-${font.id}"),
        ) {
          Text("移除 ${font.displayName}")
        }
      }
    }
    TextButton(
      onClick = onImportFont,
      enabled = importEnabled,
      modifier =
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-reading-font-import"),
    ) {
      Text(if (ui.fontBusy) "正在处理字体…" else "导入字体（TTF / OTF / TTC）")
    }
    ui.fontNotice?.let {
      Text(
        it,
        Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .testTag("settings-reading-font-notice"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      "部分中文字形由系统或导入字体提供。",
      Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
      onClick = { onChange(ReadingPreferences()) },
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-reading-reset"),
    ) {
      Text("恢复正文默认排版与全局字体")
    }
  }
  SettingsGroup {
    SectionLabel("正文预览")
    Column(
      Modifier.fillMaxWidth()
        .heightIn(max = 360.dp)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("settings-typography-preview"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(Modifier.testTag("settings-typography-paragraph-1")) {
        ArticleBlockContent(
          block = ArticleBlock.Paragraph("正文预览覆盖中文与 English，字号、行高与字体会直接使用当前正文设置。"),
          noImages = ui.noImages,
          onOpenImage = {},
          readingPreferences = current,
        )
      }
      Box(Modifier.testTag("settings-typography-paragraph-2")) {
        ArticleBlockContent(
          block = ArticleBlock.Paragraph("每一段都保留清楚的间距与舒适的阅读节奏。调整字号后，文本也会按可用宽度重新换行。"),
          noImages = ui.noImages,
          onOpenImage = {},
          readingPreferences = current,
        )
      }
      Box(Modifier.testTag("settings-typography-list-item")) {
        ArticleBlockContent(
          block =
            ArticleBlock.ListItem(text = "列表正文使用同一字号、行高和字体，继续保留缩进与标记。", depth = 0, marker = "•"),
          noImages = ui.noImages,
          onOpenImage = {},
          readingPreferences = current,
        )
      }
    }
  }
}

@Composable
private fun PreferenceSlider(
  title: String,
  value: Float,
  valueLabel: String,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  rangeLabel: String,
  testTag: String,
  onValueChange: (Float) -> Unit,
  onDragStarted: () -> Unit,
  onValueChangeFinished: (cancelled: Boolean) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val scope = rememberCoroutineScope()
  val latestOnDragStarted by rememberUpdatedState(onDragStarted)
  val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
  var activeDrag by remember { mutableStateOf<DragInteraction.Start?>(null) }
  var cancelledDrag by remember { mutableStateOf(false) }
  LaunchedEffect(interactionSource) {
    interactionSource.interactions.collect { interaction ->
      when (interaction) {
        is DragInteraction.Start -> {
          activeDrag = interaction
          cancelledDrag = false
          latestOnDragStarted()
        }
        is DragInteraction.Stop -> if (activeDrag == interaction.start) activeDrag = null
        is DragInteraction.Cancel -> {
          if (activeDrag == interaction.start) {
            activeDrag = null
            cancelledDrag = true
            latestOnFinished(true)
          }
        }
      }
    }
  }
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
      Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
      value = value,
      onValueChange = {
        if (cancelledDrag && activeDrag == null) cancelledDrag = false
        onValueChange(it)
      },
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag),
      interactionSource = interactionSource,
      enabled = true,
      valueRange = valueRange,
      steps = steps,
      onValueChangeFinished = {
        scope.launch {
          yield()
          withFrameNanos {}
          if (!cancelledDrag) latestOnFinished(false)
          cancelledDrag = false
          activeDrag = null
        }
      },
    )
    Text(
      rangeLabel,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun finishPreferenceSlider(
  cancelled: Boolean,
  latestPreferences: ReadingPreferences,
  draft: ReadingPreferences,
  onDraft: (ReadingPreferences) -> Unit,
  onCommit: (ReadingPreferences) -> Unit,
  isInvalidated: Boolean,
  onDraggingChanged: (String?) -> Unit,
) {
  onDraggingChanged(null)
  if (cancelled || isInvalidated) {
    onDraft(latestPreferences)
  } else {
    onCommit(draft.normalized())
  }
}

private fun fontLabel(font: ReadingFont): String =
  when (font) {
    ReadingFont.SYSTEM -> "系统默认"
    ReadingFont.SANS -> "无衬线"
    ReadingFont.SERIF -> "衬线"
    ReadingFont.MONO -> "等宽"
    ReadingFont.CUSTOM -> "导入字体"
  }

@Composable
private fun AppearanceSettings(
  ui: UiState,
  onThemeChange: (String) -> Unit,
  onHomeLayoutChange: (HomeLayout) -> Unit,
  onAccentChange: (String) -> Unit,
  onMotionChange: (Boolean) -> Unit,
  onNoImagesChange: (Boolean) -> Unit,
  onReadingPreferencesChange: (ReadingPreferences) -> Unit,
  onImportFont: () -> Unit,
  onRemoveFont: (String) -> Unit,
  importEnabled: Boolean,
) {
  ReadingTypographySettings(
    ui,
    onReadingPreferencesChange,
    onImportFont,
    onRemoveFont,
    importEnabled,
  )
  ui.settingsNotice?.let { NoticeCard(it, error = true) }
  SettingsGroup {
    SectionLabel("界面主题")
    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
      OptionRow("settings-theme-$value", label, ui.theme == value) { onThemeChange(value) }
    }
  }
  SettingsGroup {
    SectionLabel("卡片布局")
    Text(
      "适用于发现、游戏、动态、收藏及本机/账号历史。",
      Modifier.padding(horizontal = 16.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HomeLayout.entries.forEach { layout ->
      OptionRow(
        "settings-layout-${layout.name}",
        layout.title,
        ui.homeLayout == layout,
        subtitle = layout.description,
      ) {
        onHomeLayoutChange(layout)
      }
    }
  }
  SettingsGroup {
    SectionLabel("主题色")
    val accents =
      listOf(
        "classic" to "经典黄",
        "blue" to "雾蓝",
        "green" to "松绿",
        "purple" to "藤紫",
        "wallpaper" to "壁纸取色",
      )
    accents.forEach { (value, label) ->
      val available = value != "wallpaper" || android.os.Build.VERSION.SDK_INT >= 31
      OptionRow(
        "settings-accent-$value",
        if (available) label else "$label（需Android12+）",
        ui.accent == value,
        enabled = available,
      ) {
        onAccentChange(value)
      }
    }
  }
  ui.themeNotice?.let { NoticeCard(it, error = true) }
  SettingsGroup {
    ToggleRow("settings-motion", "界面动效", "页面转场、列表入场和按压反馈", ui.motionEnabled, onMotionChange)
    GroupDivider()
    ToggleRow("settings-no-images", "无图模式", "不加载资讯封面，节省流量", ui.noImages, onNoImagesChange)
  }
}

@Composable
private fun DataSettings(
  ui: UiState,
  gatewayValue: String,
  advanced: Boolean,
  onGatewayChange: (String) -> Unit,
  onAdvancedChange: (Boolean) -> Unit,
  onGatewaySave: () -> Unit,
  onClearCache: () -> Unit,
) {
  SettingsGroup {
    SectionLabel("公开内容来源")
    Text(
      "当前：${sourceLabel(ui.discoverFeed.source)}",
      Modifier.padding(horizontal = 16.dp),
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      ui.discoverFeed.error?.let { "连接状态：$it" }
        ?: if (ui.discoverFeed.loading) "连接状态：正在读取"
        else if (ui.discoverFeed.items.isNotEmpty()) "连接状态：已连接" else "连接状态：尚未连接",
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      style = MaterialTheme.typography.bodySmall,
      color =
        if (ui.discoverFeed.error != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  SettingsGroup {
    TextButton(
      onClick = { onAdvancedChange(!advanced) },
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
      Text(if (advanced) "收起高级数据源设置" else "高级数据源设置")
    }
    if (advanced) {
      Text(
        "公开内容通过 HTTPS 读取；可选配置自建兼容网关。账号凭据不会发送到自建网关。",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = gatewayValue,
        onValueChange = onGatewayChange,
        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("settings-gateway"),
        label = { Text("兼容网关 HTTPS 地址") },
        placeholder = { Text("https://your-gateway.example") },
        isError = ui.error != null,
        supportingText = { ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
        singleLine = true,
      )
      Button(
        onClick = onGatewaySave,
        modifier =
          Modifier.padding(horizontal = 12.dp)
            .heightIn(min = 48.dp)
            .testTag("settings-gateway-save"),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
          ),
      ) {
        Text("保存并测试")
      }
      ui.notice?.let {
        Text(
          it,
          Modifier.padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onClearCache, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("清理网络缓存")
      }
    }
  }
}

@Composable
private fun LocalHistorySettings(
  ui: UiState,
  onOpenStory: (Story) -> Unit,
  onClearHistory: () -> Unit,
  isCurrentPage: Boolean,
) {
  val blocking =
    rememberContentBlockingList(
      raw = ui.history,
      scope = ContentBlockingScope.HISTORY,
      identity = "local-history",
      isCurrentPage = isCurrentPage,
    )
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionLabel("本机阅读历史 · ${ui.history.size}", Modifier.testTag("settings-history"))
    if (blocking.loading || blocking.error != null || blocking.matchedCount > 0)
      ContentBlockingNotice(blocking)
    if (ui.history.isEmpty()) {
      Text("还没有本机阅读记录", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      blocking.items.take(5).forEachIndexed { index, story ->
        HomeStoryCard(
          story = story,
          layout = ui.homeLayout,
          featured = index == 0,
          noImages = ui.noImages,
          onOpen = onOpenStory,
        )
      }
    }
    TextButton(
      onClick = onClearHistory,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-history-clear"),
    ) {
      Icon(Icons.Filled.Delete, null)
      Spacer(Modifier.width(8.dp))
      Text("清理阅读历史")
    }
  }
}

@Composable
private fun AboutSettings() {
  SettingsGroup {
    SectionLabel("关于盒阅")
    Text("盒阅是独立第三方阅读客户端，与小黑盒无隶属关系。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    HorizontalDivider(
      Modifier.padding(horizontal = 16.dp),
      color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
      "小黑盒内容来自小黑盒；Steam 内容来自 Steam Community。演示数据会明确标注。",
      Modifier.padding(16.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun GroupHeading(text: String) {
  Text(
    text,
    Modifier.padding(start = 4.dp),
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
  )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column { content() }
  }
}

@Composable
private fun GroupDivider() {
  HorizontalDivider(
    Modifier.padding(start = 68.dp, end = 14.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
  )
}

@Composable
private fun SettingsRow(
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  tag: String,
  selected: Boolean = false,
  onClick: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth()
      .heightIn(min = 68.dp)
      .selectable(selected = selected, role = Role.Button, onClick = onClick)
      .testTag(tag)
      .background(
        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceContainerLow
      )
      .padding(horizontal = 14.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      Modifier.size(42.dp)
        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)),
      contentAlignment = Alignment.Center,
    ) {
      icon()
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(Icons.Filled.KeyboardArrowRight, "进入", tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun OptionRow(
  tag: String,
  title: String,
  selected: Boolean,
  subtitle: String? = null,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth()
      .heightIn(min = 52.dp)
      .selectable(
        selected = selected,
        role = Role.RadioButton,
        enabled = enabled,
        onClick = onClick,
      )
      .testTag(tag)
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null, enabled = enabled)
    Column(Modifier.weight(1f).padding(start = 8.dp)) {
      Text(title, fontWeight = FontWeight.Medium)
      subtitle?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ToggleRow(
  tag: String,
  title: String,
  subtitle: String,
  checked: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked,
      onChange,
      modifier = Modifier.semantics { contentDescription = title }.testTag(tag),
    )
  }
}

@Composable
private fun NoticeCard(message: String, error: Boolean) {
  Surface(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color =
      if (error) MaterialTheme.colorScheme.errorContainer
      else MaterialTheme.colorScheme.secondaryContainer,
  ) {
    Text(
      message,
      Modifier.padding(14.dp),
      color =
        if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
    )
  }
}

private fun categorySummary(category: SettingsCategory, ui: UiState): String =
  when (category) {
    SettingsCategory.Appearance ->
      "${ui.homeLayout.title} · ${if (ui.noImages) "无图模式开启" else "阅读偏好"}"
    SettingsCategory.Data -> "当前：${sourceLabel(ui.discoverFeed.source)}"
    SettingsCategory.ContentBlocking ->
      if (ui.contentBlockingLoading) "正在读取规则…"
      else if (ui.contentBlockingError != null) "规则读取失败，可重试"
      else
        "${ui.contentBlocking.rules.size} 条规则 · 当前列表筛选${if (ui.contentBlocking.enabled) "已开启" else "已关闭"}"
    SettingsCategory.LocalHistory -> "${ui.history.size} 条本机阅读记录"
    SettingsCategory.About -> "独立第三方客户端与内容来源说明"
  }

internal fun categoryTag(category: SettingsCategory): String =
  when (category) {
    SettingsCategory.Appearance -> "appearance"
    SettingsCategory.Data -> "data"
    SettingsCategory.ContentBlocking -> "content-blocking"
    SettingsCategory.LocalHistory -> "local-history"
    SettingsCategory.About -> "about"
  }

private fun sourceLabel(source: Source): String =
  when (source) {
    Source.STEAM -> "Steam"
    Source.HEYBOX -> "小黑盒"
    Source.DEMO -> "演示"
  }
