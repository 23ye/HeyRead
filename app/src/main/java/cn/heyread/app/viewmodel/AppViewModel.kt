package cn.heyread.app.viewmodel

import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.local.PreparedReadingFont
import cn.heyread.app.data.local.ContentBlockingMutation
import cn.heyread.app.data.model.*
import cn.heyread.app.data.model.Channel
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import cn.heyread.app.data.remote.StoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_APPEARANCE_TIMEOUT_MILLIS = 3_000L
private val VALID_THEMES = setOf("system", "light", "dark")
private val VALID_ACCENTS = setOf("classic", "blue", "green", "purple", "wallpaper")

private fun fallbackAppearanceNotice(reason: String, userSelectionPreserved: Boolean): String =
  if (userSelectionPreserved) {
    "$reason，启动期间的外观选择已保留；已保存值未改写。"
  } else {
    "$reason，已使用系统主题和经典黄主题色；原设置未更改。"
  }

private data class StartupPreferencesResult(
  val appearance: cn.heyread.app.data.local.StartupAppearance?,
  val readingPreferences: ReadingPreferences,
  val customTypeface: Typeface?,
  val importedFonts: List<ImportedReadingFont>,
  val fontNotice: String?,
  val notice: String?,
)

data class UiState(
  val discoverFeed: FeedPageState = FeedPageState(Source.HEYBOX),
  val gamesFeed: FeedPageState = FeedPageState(Source.STEAM),
  val history: List<Story> = emptyList(),
  val error: String? = null,
  val theme: String = "system",
  val accent: String = "classic",
  val startupAppearanceReady: Boolean = false,
  val startupAppearanceNotice: String? = null,
  val motionEnabled: Boolean = true,
  val homeLayout: HomeLayout = HomeLayout.A,
  val readingPreferences: ReadingPreferences = ReadingPreferences(),
  val importedFonts: List<ImportedReadingFont> = emptyList(),
  val customFontTypeface: Typeface? = null,
  val fontBusy: Boolean = false,
  val fontNotice: String? = null,
  val themeNotice: String? = null,
  val settingsNotice: String? = null,
  val noImages: Boolean = false,
  val gateway: String = "",
  val contentBlocking: ContentBlockingConfig = ContentBlockingConfig(),
  val contentBlockingLoading: Boolean = true,
  val contentBlockingLoaded: Boolean = false,
  val contentBlockingBusy: Boolean = false,
  val contentBlockingError: String? = null,
  val contentBlockingMessage: String? = null,
  val contentBlockingUndo: List<ContentBlockRule> = emptyList(),
  val details: Map<String, Story> = emptyMap(),
  val detailRequests: Map<String, DetailRequestState> = emptyMap(),
  val knownStories: Map<String, Story> = emptyMap(),
  val notice: String? = null,
)

class AppViewModel(private val store: LibraryStore) : ViewModel() {
  private val repository = StoryRepository({ _gateway }, store.cacheDirectory)
  private var _gateway = ""
  private val feedConfigurationReady = CompletableDeferred<Unit>()
  private val _state = MutableStateFlow(UiState())
  private val homeLayoutReady = CompletableDeferred<Unit>()
  private val readingPreferencesReady = CompletableDeferred<Unit>()
  private val contentBlockingReady = CompletableDeferred<Unit>()
  private val contentBlockingMutex = Mutex()
  private val feeds =
    FeedCoordinator(
      viewModelScope,
      FeedLoader { source, channel, page, appId ->
        // FeedScreen can compose before DataStore finishes loading the saved gateway.
        // Keep UI requests queued until the correct gateway has been installed.
        feedConfigurationReady.await()
        repository.load(source, channel, page, appId)
      },
    )
  val state = _state.asStateFlow()
  private var accentWriteGeneration = 0L
  private var themeWriteGeneration = 0L
  private var noImagesWriteGeneration = 0L
  private var motionWriteGeneration = 0L
  private var homeLayoutWriteGeneration = 0L
  private var historyGeneration = 0L
  private var contentBlockingWriteGeneration = 0L
  private var persistedHomeLayout = HomeLayout.A
  private var homeLayoutWriteJob: Job? = null
  private var readingPreferencesWriteGeneration = 0L
  private var persistedReadingPreferences = ReadingPreferences()
  private val readingPreferencesWriteMutex = Mutex()
  private var fontImportJob: Job? = null
  private var fontImportJobGeneration = 0L
  @Volatile private var fontImportCommitting = false
  @Volatile private var fontImportCommitDecisionMade = false
  private var fontLoadJob: Job? = null
  @Volatile private var fontImportGeneration = 0L
  @Volatile private var fontSelectionGeneration = 0L

  init {
    // Capture before the first suspending store read. A preference edit made while initialization
    // is in flight must always win over the older persisted snapshot.
    val initialReadingPreferencesGeneration = readingPreferencesWriteGeneration
    val initialHistoryGeneration = historyGeneration
    val initialContentBlockingGeneration = contentBlockingWriteGeneration
    val initialThemeWriteGeneration = themeWriteGeneration
    val initialNoImagesWriteGeneration = noImagesWriteGeneration
    val initialAccentWriteGeneration = accentWriteGeneration
    val initialMotionWriteGeneration = motionWriteGeneration
    val initialFontSelectionGeneration = fontSelectionGeneration
    viewModelScope.launch {
      val deadline = SystemClock.elapsedRealtime() + STARTUP_APPEARANCE_TIMEOUT_MILLIS
      var result =
        StartupPreferencesResult(
          appearance = null,
          readingPreferences = ReadingPreferences(),
          customTypeface = null,
          importedFonts = emptyList(),
          fontNotice = null,
          notice = null,
        )
      try {
        val appearance =
          withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
            store.getStartupAppearance()
          }
        if (appearance == null) {
          result = result.copy(notice = "读取已保存外观与字体设置超时，已暂用系统外观；保存值未改写。")
        } else {
          var readingPreferences = appearance.readingPreferences
          var customTypeface: Typeface? = null
          var fontNotice: String? = null
          var startupNotice: String? = null
          var persistFallback = false
          if (readingPreferences.font == ReadingFont.CUSTOM) {
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
              try {
                customTypeface =
                  withTimeoutOrNull(remainingMillis) {
                    store.loadReadingTypeface(
                      readingPreferences.customFontId.orEmpty(),
                      appearance.importedFonts,
                    )
                  }
                if (customTypeface == null) {
                  fontNotice = "读取自定义字体超时，当前暂用系统字体；保存值未改写。"
                  startupNotice = fontNotice
                }
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                readingPreferences = readingPreferences.copy(font = ReadingFont.SYSTEM, customFontId = null)
                fontNotice = "所选字体无法加载，已使用系统字体。"
                startupNotice = fontNotice
                persistFallback = true
              }
            } else {
              fontNotice = "读取自定义字体超时，当前暂用系统字体；保存值未改写。"
              startupNotice = fontNotice
            }
          }
          persistedReadingPreferences = appearance.readingPreferences
          if (
            persistFallback &&
              readingPreferencesWriteGeneration == initialReadingPreferencesGeneration &&
              fontSelectionGeneration == initialFontSelectionGeneration
          ) {
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            val saved =
              try {
                if (remainingMillis > 0L) {
                  withTimeoutOrNull(remainingMillis) {
                    store.setReadingPreferences(readingPreferences)
                    true
                  } == true
                } else false
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                false
              }
            if (saved) {
              persistedReadingPreferences = readingPreferences
            }
            if (!saved) {
              fontNotice = "所选字体无法加载，已暂用系统字体；设置保存失败。"
              startupNotice = fontNotice
            }
          }
          result =
            StartupPreferencesResult(
              appearance = appearance,
              readingPreferences = readingPreferences,
              customTypeface = customTypeface,
              importedFonts = appearance.importedFonts,
              fontNotice =
                fontNotice.takeIf { fontSelectionGeneration == initialFontSelectionGeneration },
              notice =
                startupNotice.takeIf { fontSelectionGeneration == initialFontSelectionGeneration },
            )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        result = result.copy(notice = "无法读取已保存外观与字体设置，已暂用系统外观；保存值未改写。")
      }
      if (result.appearance == null) {
        persistedReadingPreferences = result.readingPreferences
      }
      _state.update { current ->
        val appearance = result.appearance
        val loadedPreferences = result.readingPreferences
        current.copy(
          theme =
            if (themeWriteGeneration == initialThemeWriteGeneration)
              appearance?.theme?.takeIf { it in VALID_THEMES } ?: "system"
            else current.theme,
          accent =
            if (accentWriteGeneration == initialAccentWriteGeneration)
              appearance?.accent?.takeIf { it in VALID_ACCENTS } ?: "classic"
            else current.accent,
          motionEnabled =
            if (motionWriteGeneration == initialMotionWriteGeneration)
              appearance?.motionEnabled ?: true
            else current.motionEnabled,
          noImages =
            if (noImagesWriteGeneration == initialNoImagesWriteGeneration)
              appearance?.noImages ?: false
            else current.noImages,
          readingPreferences =
            if (readingPreferencesWriteGeneration == initialReadingPreferencesGeneration)
              loadedPreferences
            else current.readingPreferences,
          customFontTypeface =
            if (readingPreferencesWriteGeneration == initialReadingPreferencesGeneration)
              result.customTypeface
            else current.customFontTypeface,
          importedFonts =
            if (readingPreferencesWriteGeneration == initialReadingPreferencesGeneration)
              result.importedFonts
            else current.importedFonts,
          fontNotice = result.fontNotice ?: current.fontNotice,
          fontBusy = false,
          startupAppearanceReady = true,
          startupAppearanceNotice =
            listOfNotNull(
                current.startupAppearanceNotice,
                result.notice,
                if (appearance == null) "读取失败或超时未覆盖已保存设置。" else null,
              )
              .distinct()
              .joinToString(" ")
              .ifBlank { null },
        )
      }
      readingPreferencesReady.complete(Unit)
    }
    viewModelScope.launch {
      feeds.states.collect { feedStates ->
        _state.update { it.copy(discoverFeed = feedStates.discover, gamesFeed = feedStates.games) }
      }
    }
    viewModelScope.launch {
      try {
        store.removeLegacyFavorites()
        _gateway = store.getGateway()
        val storedHomeLayout = store.getHomeLayout()
        store.cleanupUnreferencedReadingFonts()
        val storedHistory = store.history()
        persistedHomeLayout = storedHomeLayout
        _state.update { current ->
          current.copy(
            homeLayout =
              if (homeLayoutWriteGeneration == 0L) storedHomeLayout else current.homeLayout,
            gateway = _gateway,
            history =
              if (historyGeneration == initialHistoryGeneration) storedHistory else current.history,
            knownStories =
              if (historyGeneration == initialHistoryGeneration)
                storedHistory.associateBy { "${it.source}:${it.id}" }
              else current.knownStories,
          )
        }
        homeLayoutReady.complete(Unit)
        feedConfigurationReady.complete(Unit)
        feeds.ensureLoaded(FeedTab.DISCOVER)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        val message = "部分本机设置读取失败；历史和其他已保存数据未写回默认值。请重试。"
        _state.update { current ->
          current.copy(
            startupAppearanceNotice =
              listOfNotNull(current.startupAppearanceNotice, message).joinToString(" "),
          )
        }
        if (!homeLayoutReady.isCompleted) homeLayoutReady.completeExceptionally(error)
        if (!feedConfigurationReady.isCompleted) feedConfigurationReady.completeExceptionally(error)
      }
    }
    viewModelScope.launch {
      try {
        val config = store.getContentBlocking()
        if (initialContentBlockingGeneration == contentBlockingWriteGeneration)
          _state.update {
            it.copy(
              contentBlocking = config,
              contentBlockingLoading = false,
              contentBlockingLoaded = true,
              contentBlockingError = null,
            )
          }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (initialContentBlockingGeneration == contentBlockingWriteGeneration)
          _state.update {
            it.copy(
              contentBlockingLoading = false,
              contentBlockingError = contentBlockingErrorMessage(error),
            )
          }
      } finally {
        contentBlockingReady.complete(Unit)
      }
    }
  }

  fun ensureFeedLoaded(tab: FeedTab) = feeds.ensureLoaded(tab)

  suspend fun loadRecommendedDynamicFeed(offset: Int, displayWidth: Int): StoryPage {
    feedConfigurationReady.await()
    return repository.loadRecommendedFeed(offset, displayWidth)
  }

  fun selectSource(tab: FeedTab, source: Source) = feeds.selectSource(tab, source)

  fun selectChannel(channel: Channel) = feeds.selectChannel(channel)

  fun selectGame(appId: Int, name: String? = null) = feeds.selectGame(appId, name)

  fun refresh(tab: FeedTab = FeedTab.DISCOVER) = feeds.refresh(tab)

  fun loadMore(tab: FeedTab = FeedTab.DISCOVER) = feeds.loadMore(tab)

  fun opened(s: Story) {
    val generation = ++historyGeneration
    val key = "${s.source}:${s.id}"
    val knownStories =
      (_state.value.knownStories + (key to s)).entries.toList().takeLast(80).associate {
        it.toPair()
      }
    _state.value = _state.value.copy(knownStories = knownStories)
    viewModelScope.launch {
      store.addHistory(s)
      val storedHistory = store.history()
      _state.update { current ->
        if (historyGeneration == generation) current.copy(history = storedHistory) else current
      }
    }
  }

  fun onContentBlockingAction(action: ContentBlockingAction) {
    if (_state.value.contentBlockingBusy) return
    if (action is ContentBlockingAction.UndoAddedRules) {
      val currentUndo = _state.value.contentBlockingUndo
      if (action.expected.isEmpty() || action.expected != currentUndo) {
        _state.update {
          if (it.contentBlockingUndo.isEmpty() && it.contentBlockingError == null)
            it.copy(contentBlockingMessage = "这次撤销已失效。")
          else it
        }
        return
      }
    }
    val generation = ++contentBlockingWriteGeneration
    _state.update {
      it.copy(
        contentBlockingBusy = true,
        contentBlockingLoading = action == ContentBlockingAction.Reload,
        contentBlockingError = null,
        contentBlockingMessage = null,
        contentBlockingUndo = emptyList(),
      )
    }
    viewModelScope.launch {
      contentBlockingMutex.withLock {
        contentBlockingReady.await()
        if (generation != contentBlockingWriteGeneration) return@withLock
        try {
          if (action == ContentBlockingAction.Reload) {
            val config = store.getContentBlocking()
            if (generation == contentBlockingWriteGeneration)
              _state.update {
                it.copy(
                  contentBlocking = config,
                  contentBlockingBusy = false,
                  contentBlockingLoading = false,
                  contentBlockingLoaded = true,
                  contentBlockingError = null,
                  contentBlockingMessage = "规则已重新读取。",
                  contentBlockingUndo = emptyList(),
                )
              }
          } else {
            val mutation = store.updateContentBlocking(action)
            if (generation == contentBlockingWriteGeneration)
              _state.update {
                it.copy(
                  contentBlocking = mutation.config,
                  contentBlockingBusy = false,
                  contentBlockingLoading = false,
                  contentBlockingLoaded = true,
                  contentBlockingError = null,
                  contentBlockingMessage = contentBlockingMutationMessage(action, mutation),
                  contentBlockingUndo = mutation.addedRules,
                )
              }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (generation == contentBlockingWriteGeneration)
            _state.update {
              it.copy(
                contentBlockingBusy = false,
                contentBlockingLoading = false,
                contentBlockingError = contentBlockingErrorMessage(error),
                contentBlockingMessage = null,
                contentBlockingUndo = emptyList(),
              )
            }
        }
      }
    }
  }

  fun dismissContentBlockingFeedback() {
    _state.update {
      it.copy(
        contentBlockingMessage = null,
        contentBlockingUndo = emptyList(),
      )
    }
  }

  private fun contentBlockingMutationMessage(
    action: ContentBlockingAction,
    mutation: ContentBlockingMutation,
  ): String =
    when {
      action is ContentBlockingAction.UndoAddedRules -> "规则已撤销。"
      mutation.changed && action is ContentBlockingAction.AddKeywords && mutation.addedCount <
        action.text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().count() ->
        "规则已保存，重复关键词已忽略。"
      mutation.changed && action is ContentBlockingAction.MergeImportedRules ->
        "已导入${mutation.addedCount}条规则。"
      mutation.changed -> "规则已保存。"
      action is ContentBlockingAction.ClearRules -> "没有可清除的规则。"
      action is ContentBlockingAction.MergeImportedRules -> "没有新规则需要导入。"
      action is ContentBlockingAction.AddKeywords || action is ContentBlockingAction.AddIdentity ||
        action is ContentBlockingAction.EditKeyword -> "重复规则未添加。"
      else -> "规则设置未变化。"
    }

  private fun contentBlockingErrorMessage(error: Exception): String {
    val message = error.message.orEmpty()
    if (
      message.startsWith("内容屏蔽配置") || message.startsWith("内容屏蔽规则") ||
        message.startsWith("内容屏蔽导入") ||
        message in
          setOf(
            "请输入至少一个关键词。",
            "每个关键词最多100个字符。",
            "最多保存500条屏蔽规则。",
            "身份规则类型无效。",
            "ID须为最多32位的正十进制数字。",
            "ID不能为0。",
            "备注最多60个字符。",
            "关键词不能为空。",
            "请一次编辑一个关键词。",
            "找不到要编辑的关键词。",
            "找不到要修改的规则。",
            "找不到要删除的规则。",
            "关键词须为1至100个字符。",
            "关键词不能包含换行。",
            "关键词规则字段无效。",
            "身份规则必须来自小黑盒。",
            "相同内容的规则已存在。",
            "撤销已失效。",
            "规则已变化，请重新预览导入。",
          )
    )
      return message
    return "内容屏蔽规则读取或保存失败，请重试。"
  }

  private val detailJobs = mutableMapOf<String, Job>()
  private val detailGenerations = DetailRequestGenerations()

  fun detail(s: Story, force: Boolean = false) {
    if (s.source != Source.HEYBOX) return
    val key = "${s.source}:${s.id}"
    val current = _state.value.details[key]
    if (!force && detailJobs[key]?.isActive == true) return
    if (!force && current?.detailBlocks != null) {
      val prior = _state.value.detailRequests[key]
      if (prior?.isTerminal != true) {
        val generation = detailGenerations.begin(key)
        _state.update {
          it.copy(
            detailRequests =
              it.detailRequests +
                (key to
                  DetailRequestState(
                    generation,
                    if (current.detailBlocks.isNullOrEmpty() && current.detailNotice != null)
                      DetailRequestPhase.LIMITED
                    else DetailRequestPhase.READY,
                    current.detailNotice,
                  ))
          )
        }
      }
      return
    }
    val prior = _state.value.detailRequests[key]
    if (!force && prior?.isTerminal == true) return
    detailJobs[key]?.cancel()
    val token = detailGenerations.begin(key)
    _state.update {
      it.copy(
        detailRequests =
          it.detailRequests + (key to DetailRequestState(token, DetailRequestPhase.LOADING))
      )
    }
    detailJobs[key] =
      viewModelScope.launch {
        try {
          val loaded = repository.loadDetail(s)
          if (!detailGenerations.isCurrent(key, token)) return@launch
          _state.update {
            it.copy(
              details = it.details + (key to loaded),
              detailRequests =
                it.detailRequests +
                  (key to
                    DetailRequestState(
                      token,
                      if (loaded.detailNotice != null && loaded.detailBlocks.isNullOrEmpty())
                        DetailRequestPhase.LIMITED
                      else DetailRequestPhase.READY,
                      loaded.detailNotice,
                    )),
            )
          }
        } catch (error: Exception) {
          if (error is kotlinx.coroutines.CancellationException) throw error
          if (!detailGenerations.isCurrent(key, token)) return@launch
          val message = "暂时无法读取全文，请检查网络并打开原文。"
          val existing = _state.value.details[key]
          val failedDetail = (existing ?: s).copy(detailNotice = message)
          _state.update {
            it.copy(
              details = it.details + (key to failedDetail),
              detailRequests =
                it.detailRequests +
                  (key to DetailRequestState(token, DetailRequestPhase.FAILED, message)),
            )
          }
        } finally {
          if (detailGenerations.isCurrent(key, token)) detailJobs.remove(key)
        }
      }
  }

  fun settings(
    theme: String = _state.value.theme,
    noImages: Boolean = _state.value.noImages,
    gateway: String = _state.value.gateway,
  ) {
    if (
      gateway.isNotBlank() &&
        (!gateway.startsWith("https://", true) ||
          android.net.Uri.parse(gateway).host.isNullOrBlank())
    ) {
      _state.value = _state.value.copy(error = "网关地址必须是有效的 HTTPS 地址")
      return
    }
    _gateway = gateway
    if (theme != _state.value.theme) themeWriteGeneration++
    if (noImages != _state.value.noImages) noImagesWriteGeneration++
    _state.value =
      _state.value.copy(theme = theme, noImages = noImages, gateway = gateway, error = null)
    viewModelScope.launch { store.saveSettings(theme, noImages, gateway) }
  }

  fun setAccent(value: String) {
    if (value !in setOf("classic", "blue", "green", "purple", "wallpaper")) return
    val generation = ++accentWriteGeneration
    _state.value = _state.value.copy(accent = value, settingsNotice = null)
    viewModelScope.launch {
      try {
        store.setThemeAccent(value)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == accentWriteGeneration && _state.value.accent == value)
          _state.value = _state.value.copy(settingsNotice = "主题色保存失败，请重试。")
      }
    }
  }

  fun setThemeNotice(message: String?) {
    if (_state.value.themeNotice != message) _state.value = _state.value.copy(themeNotice = message)
  }

  fun setMotionEnabled(value: Boolean) {
    val generation = ++motionWriteGeneration
    _state.value = _state.value.copy(motionEnabled = value, settingsNotice = null)
    viewModelScope.launch {
      try {
        store.setMotionEnabled(value)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == motionWriteGeneration && _state.value.motionEnabled == value)
          _state.value = _state.value.copy(settingsNotice = "界面动效设置保存失败，请重试。")
      }
    }
  }

  fun setReadingPreferences(value: ReadingPreferences) {
    val normalized = value.normalized()
    if (
      normalized.font == ReadingFont.CUSTOM &&
        _state.value.importedFonts.none { it.id == normalized.customFontId }
    ) {
      _state.update { it.copy(fontNotice = "所选字体已不存在，请重新导入。") }
      return
    }
    val previous = _state.value.readingPreferences
    val fontChanged =
      previous.font != normalized.font || previous.customFontId != normalized.customFontId
    if (fontChanged) fontSelectionGeneration++
    val selectionGeneration = fontSelectionGeneration
    val generation = ++readingPreferencesWriteGeneration
    _state.value =
      _state.value.copy(
        readingPreferences = normalized,
        customFontTypeface = if (fontChanged) null else _state.value.customFontTypeface,
        fontNotice = if (fontChanged) null else _state.value.fontNotice,
        fontBusy =
          if (fontChanged && normalized.font == ReadingFont.CUSTOM) true else _state.value.fontBusy,
        settingsNotice = null,
      )
    if (fontChanged && normalized.font == ReadingFont.CUSTOM) {
      startCustomFontLoad(normalized.customFontId.orEmpty(), selectionGeneration)
    } else if (fontChanged) {
      fontLoadJob?.cancel()
      _state.update {
        it.copy(customFontTypeface = null, fontBusy = fontImportJob?.isActive == true)
      }
    }
    viewModelScope.launch {
      readingPreferencesWriteMutex.withLock {
        readingPreferencesReady.await()
        if (generation != readingPreferencesWriteGeneration) return@withLock
        try {
          val latestPreferences = _state.value.readingPreferences.normalized()
          store.setReadingPreferences(latestPreferences)
          // Earlier writes can finish after a newer choice is made. Record every durable value so
          // a later current-generation failure rolls back to what DataStore actually contains.
          persistedReadingPreferences = latestPreferences
          if (generation == readingPreferencesWriteGeneration) {
            _state.value = _state.value.copy(settingsNotice = null)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (generation == readingPreferencesWriteGeneration) {
            val rollback = persistedReadingPreferences.normalized()
            val current = _state.value
            val rollbackFontChanged =
              current.readingPreferences.font != rollback.font ||
                current.readingPreferences.customFontId != rollback.customFontId
            if (rollbackFontChanged) {
              fontSelectionGeneration++
              fontLoadJob?.cancel()
            }
            _state.update {
              it.copy(
                readingPreferences = rollback,
                customFontTypeface = if (rollbackFontChanged) null else it.customFontTypeface,
                fontBusy =
                  if (rollbackFontChanged) rollback.font == ReadingFont.CUSTOM else it.fontBusy,
                settingsNotice = "正文排版设置保存失败，请重试。",
              )
            }
            if (rollbackFontChanged && rollback.font == ReadingFont.CUSTOM) {
              startCustomFontLoad(rollback.customFontId.orEmpty(), fontSelectionGeneration)
            }
          }
        }
      }
    }
  }

  fun importReadingFont(uri: Uri) {
    if (fontImportJob?.isActive == true) return
    val importGeneration = ++fontImportGeneration
    fontImportJobGeneration = importGeneration
    val selectionGenerationAtStart = fontSelectionGeneration
    _state.update { it.copy(fontBusy = true, fontNotice = null) }
    fontImportJob =
      viewModelScope.launch {
        var prepared: PreparedReadingFont? = null
        var commitSucceeded = false
        try {
          readingPreferencesReady.await()
          prepared = store.prepareReadingFont(uri)
          val item = checkNotNull(prepared)
          var loadSelection: Pair<String, Long>? = null
          var selectedNewFont = false
          readingPreferencesWriteMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (importGeneration != fontImportGeneration) {
              deletePreparedFont(item)
              prepared = null
              return@withLock
            }
            val current = _state.value.readingPreferences.normalized()
            val maySelectImported = selectionGenerationAtStart == fontSelectionGeneration
            fontImportCommitDecisionMade = false
            fontImportCommitting = true
            val snapshot =
              try {
                withContext(NonCancellable) {
                  store.commitReadingFont(item, current) {
                    fontImportCommitDecisionMade = true
                    if (importGeneration != fontImportGeneration) {
                      throw CancellationException("字体导入已取消")
                    }
                    val latest = _state.value.readingPreferences.normalized()
                    if (
                      importGeneration == fontImportGeneration &&
                        maySelectImported &&
                        selectionGenerationAtStart == fontSelectionGeneration
                    )
                      latest.copy(font = ReadingFont.CUSTOM, customFontId = item.font.id)
                    else latest
                  }
                }
              } finally {
                fontImportCommitting = false
                fontImportCommitDecisionMade = false
              }
            persistedReadingPreferences = snapshot.preferences
            commitSucceeded = true
            _state.update { currentUi ->
              val stillMaySelect =
                snapshot.preferences.font == ReadingFont.CUSTOM &&
                  snapshot.preferences.customFontId == item.font.id &&
                  selectionGenerationAtStart == fontSelectionGeneration
              selectedNewFont = stillMaySelect
              val latestPreferences = currentUi.readingPreferences.normalized()
              currentUi.copy(
                importedFonts = snapshot.fonts,
                readingPreferences =
                  if (stillMaySelect)
                    latestPreferences.copy(font = ReadingFont.CUSTOM, customFontId = item.font.id)
                  else latestPreferences,
                customFontTypeface = if (stillMaySelect) null else currentUi.customFontTypeface,
                fontNotice = null,
                fontBusy = true,
              )
            }
            if (selectedNewFont) {
              fontSelectionGeneration++
              val selectedGeneration = fontSelectionGeneration
              loadSelection = item.font.id to selectedGeneration
            }
            prepared = null
          }
          loadSelection?.let { (id, generation) -> startCustomFontLoad(id, generation) }
          if (commitSucceeded && importGeneration == fontImportGeneration) {
            _state.update { it.copy(fontNotice = it.fontNotice ?: "字体已导入。") }
          }
        } catch (cancelled: CancellationException) {
          prepared?.let { deletePreparedFont(it) }
          throw cancelled
        } catch (error: Exception) {
          prepared?.let { deletePreparedFont(it) }
          if (importGeneration == fontImportGeneration) {
            _state.update { it.copy(fontNotice = readableFontError(error)) }
          }
        } finally {
          if (fontImportJobGeneration == importGeneration) {
            fontImportJob = null
            _state.update { it.copy(fontBusy = fontLoadJob?.isActive == true) }
          }
        }
      }
  }

  fun cancelFontImport() {
    if (fontImportCommitting) {
      if (!fontImportCommitDecisionMade) fontImportGeneration++
      return
    }
    val job = fontImportJob ?: return
    if (!job.isActive) return
    fontImportGeneration++
    fontImportJob = null
    job.cancel()
    _state.update { it.copy(fontBusy = fontLoadJob?.isActive == true) }
  }

  private suspend fun deletePreparedFont(prepared: PreparedReadingFont) {
    withContext(NonCancellable + Dispatchers.IO) { prepared.temporaryFile.delete() }
  }

  fun removeReadingFont(id: String) {
    if (fontImportJob?.isActive == true) return
    _state.update { it.copy(fontBusy = true, fontNotice = null) }
    viewModelScope.launch {
      readingPreferencesWriteMutex.withLock {
        readingPreferencesReady.await()
        try {
          val snapshot = store.removeReadingFont(id, _state.value.readingPreferences)
          persistedReadingPreferences = snapshot.preferences
          val selectedIdBeforeUpdate = _state.value.readingPreferences.customFontId
          val removedCurrentSelection = selectedIdBeforeUpdate == id
          if (removedCurrentSelection) {
            fontSelectionGeneration++
            fontLoadJob?.cancel()
            fontLoadJob = null
          }
          _state.update { current ->
            val currentPreferences = current.readingPreferences
            val nextCurrentPreferences =
              if (
                currentPreferences.font == ReadingFont.CUSTOM &&
                  currentPreferences.customFontId == id
              )
                currentPreferences.copy(font = ReadingFont.SYSTEM, customFontId = null)
              else currentPreferences
            current.copy(
              importedFonts = snapshot.fonts,
              readingPreferences = nextCurrentPreferences,
              customFontTypeface =
                if (current.readingPreferences.customFontId == id) null
                else current.customFontTypeface,
              fontNotice = null,
              fontBusy = fontImportJob?.isActive == true || fontLoadJob?.isActive == true,
            )
          }
          if (_state.value.readingPreferences.font == ReadingFont.CUSTOM) {
            startCustomFontLoad(
              _state.value.readingPreferences.customFontId.orEmpty(),
              fontSelectionGeneration,
            )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          _state.update {
            it.copy(
              fontNotice = "移除字体失败，请重试。",
              fontBusy = fontImportJob?.isActive == true || fontLoadJob?.isActive == true,
            )
          }
        }
      }
    }
  }

  private fun startCustomFontLoad(id: String, selectionGeneration: Long) {
    fontLoadJob?.cancel()
    _state.update { it.copy(customFontTypeface = null, fontBusy = true) }
    fontLoadJob =
      viewModelScope.launch {
        try {
          readingPreferencesReady.await()
          val typeface = store.loadReadingTypeface(id)
          if (
            selectionGeneration == fontSelectionGeneration &&
              _state.value.readingPreferences.customFontId == id
          ) {
            _state.update {
              it.copy(
                customFontTypeface = typeface,
                fontBusy = fontImportJob?.isActive == true,
                fontNotice = null,
              )
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (
            selectionGeneration == fontSelectionGeneration &&
              _state.value.readingPreferences.customFontId == id
          ) {
            readingPreferencesWriteMutex.withLock {
              if (
                selectionGeneration == fontSelectionGeneration &&
                  _state.value.readingPreferences.customFontId == id
              ) {
                val fallbackSystem =
                  _state.value.readingPreferences
                    .normalized()
                    .copy(font = ReadingFont.SYSTEM, customFontId = null)
                readingPreferencesWriteGeneration++
                try {
                  store.setReadingPreferences(fallbackSystem)
                  persistedReadingPreferences = fallbackSystem
                  if (
                    selectionGeneration == fontSelectionGeneration &&
                      _state.value.readingPreferences.customFontId == id
                  ) {
                    fontSelectionGeneration++
                    _state.update { current ->
                      val latest = current.readingPreferences
                      current.copy(
                        readingPreferences =
                          if (latest.customFontId == id)
                            latest.copy(font = ReadingFont.SYSTEM, customFontId = null)
                          else latest,
                        customFontTypeface = null,
                        fontBusy = fontImportJob?.isActive == true,
                        fontNotice = "所选字体无法加载，已使用系统字体。",
                      )
                    }
                  }
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (_: Exception) {
                  if (
                    selectionGeneration == fontSelectionGeneration &&
                      _state.value.readingPreferences.customFontId == id
                  ) {
                    fontSelectionGeneration++
                    _state.update { current ->
                      current.copy(
                        readingPreferences =
                          current.readingPreferences.copy(
                            font = ReadingFont.SYSTEM,
                            customFontId = null,
                          ),
                        customFontTypeface = null,
                        fontBusy = fontImportJob?.isActive == true,
                        fontNotice = "字体加载失败，已暂用系统字体；设置保存失败。",
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
  }

  private fun readableFontError(error: Exception): String =
    when (error.message) {
      "字体文件超过32 MiB限制" -> "字体文件超过32 MiB限制。"
      "文件不是受支持的TTF、OTF或TTC字体" -> "所选文件不是有效的TTF、OTF或TTC字体。"
      "字体文件大小无效" -> "字体文件大小无效。"
      "最多导入8种字体，请先移除一种字体" -> "最多导入8种字体，请先移除一种字体。"
      "请选择设备上的字体文件" -> "请选择设备上的字体文件。"
      "无法读取所选字体文件" -> "无法读取所选字体文件，请重新选择。"
      "无法保存字体副本" -> "无法保存字体，请检查设备空间后重试。"
      "所选导入字体不存在" -> "所选字体已不存在，请重新选择。"
      else -> "字体导入失败，请检查所选文件后重试。"
    }

  fun setHomeLayout(value: HomeLayout) {
    if (
      homeLayoutReady.isCompleted &&
        _state.value.homeLayout == value &&
        _state.value.settingsNotice == null
    )
      return
    val generation = ++homeLayoutWriteGeneration
    homeLayoutWriteJob?.cancel()
    _state.value = _state.value.copy(homeLayout = value, settingsNotice = null)
    homeLayoutWriteJob =
      viewModelScope.launch {
        try {
          homeLayoutReady.await()
          if (generation != homeLayoutWriteGeneration) return@launch
          store.setHomeLayout(value)
          if (generation == homeLayoutWriteGeneration) persistedHomeLayout = value
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (generation == homeLayoutWriteGeneration) {
            _state.value =
              _state.value.copy(homeLayout = persistedHomeLayout, settingsNotice = "首页布局保存失败，请重试。")
          }
        }
      }
  }

  fun testGateway(gateway: String) {
    settings(gateway = gateway)
    if (_state.value.gateway != gateway) return
    feeds.selectSource(FeedTab.DISCOVER, Source.HEYBOX, force = true)
  }

  fun clearHistory() {
    val generation = ++historyGeneration
    viewModelScope.launch {
      store.clearHistory()
      _state.update { current ->
        if (historyGeneration == generation) current.copy(history = emptyList()) else current
      }
    }
  }

  fun clearCache() {
    viewModelScope.launch {
      repository.clearCache()
      _state.value = _state.value.copy(notice = "网络缓存已清理")
    }
  }

  class Factory(private val app: android.app.Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      AppViewModel(LibraryStore(app)) as T
  }
}
