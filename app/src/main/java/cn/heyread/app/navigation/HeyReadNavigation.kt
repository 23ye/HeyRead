package cn.heyread.app.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import cn.heyread.app.data.account.SharedArticleContentParser
import cn.heyread.app.data.account.StoryDetailResolver
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.model.AuthorProfile
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.SearchScope
import cn.heyread.app.data.model.SearchScopeType
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.articleGameIds
import cn.heyread.app.data.remote.DestinationRepository
import cn.heyread.app.data.remote.DestinationTarget
import cn.heyread.app.data.remote.DestinationType
import cn.heyread.app.data.remote.PublicSearchRepository
import cn.heyread.app.data.remote.StoryRepository
import cn.heyread.app.ui.components.ContentBlockingContext
import cn.heyread.app.ui.components.ContentBlockingCardActions
import cn.heyread.app.ui.components.ContentBlockingFeedbackHost
import cn.heyread.app.ui.components.LocalContentBlockingContext
import cn.heyread.app.ui.components.LocalContentBlockingCardActions
import cn.heyread.app.ui.components.LocalCustomReadingTypeface
import cn.heyread.app.ui.components.LocalGlobalReadingFont
import cn.heyread.app.ui.components.toComposeFontFamily
import cn.heyread.app.ui.components.LocalPaneContentActive
import cn.heyread.app.ui.components.LocalPaneResizeEpoch
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.motion.LocalSharedCoverCoordinator
import cn.heyread.app.ui.motion.LocalSharedCoverEntryId
import cn.heyread.app.ui.motion.LocalSharedCoverIsDetail
import cn.heyread.app.ui.motion.LocalSharedCoverOriginId
import cn.heyread.app.ui.motion.MotionTokens
import cn.heyread.app.ui.motion.SharedCoverCoordinator
import cn.heyread.app.ui.motion.SharedCoverOverlayHost
import cn.heyread.app.ui.motion.pageInputGate
import cn.heyread.app.ui.motion.rememberMotionEnabled
import cn.heyread.app.ui.motion.rememberMotionFrameRateHintController
import cn.heyread.app.ui.screens.*
import cn.heyread.app.ui.theme.HeyReadTheme
import cn.heyread.app.viewmodel.AccountViewModel
import cn.heyread.app.viewmodel.AppViewModel
import cn.heyread.app.viewmodel.AuthorFollowViewModel
import cn.heyread.app.viewmodel.CommentActionsViewModel
import cn.heyread.app.viewmodel.DestinationViewModel
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.SearchViewModel
import cn.heyread.app.viewmodel.SocialActionsViewModel
import cn.heyread.app.viewmodel.SteamGameSearchViewModel
import cn.heyread.app.viewmodel.SteamTranslationViewModel
import kotlinx.coroutines.flow.collect

@Composable
fun HeyReadNavigation(vm: AppViewModel) {
  val ui by vm.state.collectAsStateWithLifecycle()
  val accountViewModel: AccountViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
  val account by accountViewModel.state.collectAsStateWithLifecycle()
  val contentBlockingSnackbar = remember { SnackbarHostState() }
  LaunchedEffect(ui.startupAppearanceNotice) {
    ui.startupAppearanceNotice?.let { message -> contentBlockingSnackbar.showSnackbar(message) }
  }
  LaunchedEffect(account.session?.userId, account.revision) {
    vm.dismissContentBlockingFeedback()
  }
  val dynamicFeedViewModel: DynamicFeedViewModel =
    androidx.lifecycle.viewmodel.compose.viewModel(
      factory =
        DynamicFeedViewModel.Factory(
          vm::loadRecommendedDynamicFeed,
          accountViewModel.repository::loadFollowedActivity,
          accountViewModel::handleSessionExpired,
          accountViewModel.repository::loadFollowedUsers,
          DestinationRepository(),
        )
    )
  val dynamicFeed by dynamicFeedViewModel.state.collectAsStateWithLifecycle()
  val socialViewModel: SocialActionsViewModel =
    androidx.lifecycle.viewmodel.compose.viewModel(
      factory =
        SocialActionsViewModel.Factory(
          accountViewModel.repository,
          accountViewModel::handleSessionExpired,
        )
    )
  val commentActionsViewModel: CommentActionsViewModel =
    androidx.lifecycle.viewmodel.compose.viewModel(
      factory =
        CommentActionsViewModel.Factory(
          accountViewModel.repository,
          accountViewModel::handleSessionExpired,
        )
    )
  val authorFollowViewModel: AuthorFollowViewModel =
    androidx.lifecycle.viewmodel.compose.viewModel(
      factory =
        AuthorFollowViewModel.Factory(
          accountViewModel.repository,
          accountViewModel::handleSessionExpired,
        )
    )
  LaunchedEffect(account.session?.userId, account.revision, account.loadingSession) {
    dynamicFeedViewModel.bindAccount(
      account.session?.userId,
      account.revision,
      account.loadingSession,
    )
    socialViewModel.bindSession(account.session?.userId, account.revision)
    commentActionsViewModel.bindSession(account.session?.userId, account.revision)
    authorFollowViewModel.bindSession(account.session?.userId, account.revision)
  }
  val nav = rememberNavController()
  val rootNav = nav
  val detailNav = rememberNavController()
  var settingsScrollPositions by
    rememberSaveable(stateSaver = settingsScrollPositionsSaver) {
      mutableStateOf<Map<String, Int>>(emptyMap())
    }
  var settingsGatewayDrafts by
    rememberSaveable(stateSaver = settingsGatewayDraftsSaver) {
      mutableStateOf<Map<String, String>>(emptyMap())
    }
  var settingsAdvancedValues by
    rememberSaveable(stateSaver = settingsAdvancedValuesSaver) {
      mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
  val settingsScrollStates = remember { mutableMapOf<String, ScrollState>() }
  var detailPaneOpen by remember { mutableStateOf(false) }
  var leftPaneFraction by rememberSaveable { mutableFloatStateOf(SPLIT_PANE_DEFAULT_LEFT_FRACTION) }
  var paneResizeEpoch by rememberSaveable { mutableIntStateOf(0) }
  var resizingPanes by remember { mutableStateOf(false) }
  var selectedStoryIdentity by rememberSaveable { mutableStateOf("") }
  var pendingFontPickerEntryId by rememberSaveable { mutableStateOf<String?>(null) }
  var fontImportOwnerEntryId by rememberSaveable { mutableStateOf<String?>(null) }
  var fontImportObservedBusy by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  val isDark = ui.theme == "dark" || (ui.theme == "system" && isSystemInDarkTheme())
  val motionEnabled = rememberMotionEnabled(ui.motionEnabled)
  val sharedCoverCoordinator = if (motionEnabled) remember { SharedCoverCoordinator() } else null
  val pageTransitionRecords =
    if (motionEnabled)
      remember { androidx.compose.runtime.mutableStateMapOf<String, PageTransitionRecord>() }
    else null
  val activeBackStackEntry by nav.currentBackStackEntryAsState()
  val activeDetailBackStackEntry by detailNav.currentBackStackEntryAsState()
  LaunchedEffect(
    activeDetailBackStackEntry?.id,
    pendingFontPickerEntryId,
    fontImportOwnerEntryId,
    ui.fontBusy,
  ) {
    val activeEntryId = activeDetailBackStackEntry?.id
    if (
      activeEntryId != null &&
        pendingFontPickerEntryId != null &&
        activeEntryId != pendingFontPickerEntryId
    ) {
      pendingFontPickerEntryId = null
      vm.cancelFontImport()
    }
    if (activeEntryId != null && fontImportOwnerEntryId != null) {
      if (activeEntryId != fontImportOwnerEntryId) {
        vm.cancelFontImport()
        fontImportOwnerEntryId = null
        fontImportObservedBusy = false
      } else if (ui.fontBusy) {
        fontImportObservedBusy = true
      } else if (fontImportObservedBusy) {
        fontImportOwnerEntryId = null
        fontImportObservedBusy = false
      }
    }
  }
  SideEffect {
    detailPaneOpen =
      activeDetailBackStackEntry?.destination?.route != null &&
        activeDetailBackStackEntry?.destination?.route != "detail-empty"
  }
  LaunchedEffect(sharedCoverCoordinator, activeBackStackEntry?.id, activeDetailBackStackEntry?.id) {
    sharedCoverCoordinator?.setCurrentEntries(
      setOfNotNull(
        activeBackStackEntry?.id,
        activeDetailBackStackEntry?.id?.takeUnless {
          activeDetailBackStackEntry?.destination?.route == "detail-empty"
        },
      ),
      focusedEntryId =
        if (
          activeDetailBackStackEntry?.destination?.route != null &&
            activeDetailBackStackEntry?.destination?.route != "detail-empty"
        )
          activeDetailBackStackEntry?.id
        else activeBackStackEntry?.id,
    )
  }
  LaunchedEffect(account.session?.userId, account.revision, sharedCoverCoordinator) {
    sharedCoverCoordinator?.cancel()
  }
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

  fun openStoryInDetailPane(
    story: cn.heyread.app.data.model.Story,
    sourceEntryId: String,
    sourceIsDetailPane: Boolean,
  ) {
    selectedStoryIdentity = "${story.source}:${story.id}"
    vm.opened(story)
    val existing =
      runCatching { detailNav.getBackStackEntry("detail/{source}/{id}?origin={origin}") }
        .getOrNull()
    val sameStory =
      existing?.arguments?.getString("source") == story.source.name &&
        existing.arguments?.getString("id") == story.id
    val current = activeDetailBackStackEntry
    val currentlyShowingSame =
      current?.destination?.route == "detail/{source}/{id}?origin={origin}" && sameStory
    if (currentlyShowingSame) return
    if (sameStory) {
      detailNav.popBackStack("detail/{source}/{id}?origin={origin}", inclusive = false)
      return
    }
    beginStoryCoverTransition(sharedCoverCoordinator, story, sourceEntryId, ui.noImages)
    detailNav.navigate(detailRoute(story, sourceEntryId)) {
      if (!sourceIsDetailPane) popUpTo("detail-empty") { inclusive = false }
    }
    sharedCoverCoordinator?.bindExpectedTarget(detailNav.currentBackStackEntry?.id)
  }
  fun navigateRootTab(target: String) {
    sharedCoverCoordinator?.cancel()
    selectedStoryIdentity = ""
    detailNav.popBackStack("detail-empty", inclusive = false)
    nav.navigate(target) {
      popUpTo("discover") { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }
  fun openAccountFavorites() {
    sharedCoverCoordinator?.cancel()
    selectedStoryIdentity = ""
    detailPaneOpen = false
    detailNav.popBackStack("detail-empty", inclusive = false)
    nav.navigate("saved") { launchSingleTop = true }
  }
  fun openAccountOwnProfile() {
    val currentAccount = accountViewModel.state.value
    val session = currentAccount.session ?: return
    if (currentAccount.loadingSession || session.userId.isBlank()) return
    sharedCoverCoordinator?.cancel()
    selectedStoryIdentity = ""
    detailPaneOpen = false
    detailNav.popBackStack("detail-empty", inclusive = false)
    openUser(
      nav,
      AuthorProfile(
        name = session.nickname.ifBlank { "小黑盒用户" },
        avatarUrl = session.avatarUrl,
        userId = session.userId,
      ),
    )
  }
  fun openSettingsCategory(category: SettingsCategory) {
    val current = activeDetailBackStackEntry
    if (
      current?.destination?.route == "settings/{category}" &&
        current.arguments?.getString("category") == category.name
    )
      return
    sharedCoverCoordinator?.cancel()
    selectedStoryIdentity = ""
    detailNav.navigate("settings/${category.name}") {
      popUpTo("detail-empty") { inclusive = false }
      launchSingleTop = true
    }
  }
  val view = LocalView.current
  SideEffect {
    (context as? Activity)?.window?.let { window ->
      WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !isDark
        isAppearanceLightNavigationBars = !isDark
      }
    }
  }
  fun NavGraphBuilder.registerContentRoutes(hostNav: NavHostController) {
    val nav = hostNav
    val hostIsRoot = hostNav === rootNav
    composable("login") { entry ->
      val isCurrentPage =
        nav.currentBackStackEntryAsState().value?.id == entry.id &&
          (!hostIsRoot || LocalPaneContentActive.current)
      SharedMotionPage(
        this@composable,
        entry.id,
        entry.id,
        route = entry.destination.route,
        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
        previousRoute = nav.previousBackStackEntry?.destination?.route,
        isCurrentPage = isCurrentPage,
        motionEnabled = motionEnabled,
        isRtl = isRtl,
      ) {
        LoginScreen(
          accountViewModel,
          onBack = { nav.popBackStack() },
          isCurrentPage = isCurrentPage,
        )
      }
    }
    composable("account-history") { entry ->
      val isCurrentPage =
        nav.currentBackStackEntryAsState().value?.id == entry.id &&
          (!hostIsRoot || LocalPaneContentActive.current)
      SharedMotionPage(
        this@composable,
        entry.id,
        entry.id,
        route = entry.destination.route,
        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
        previousRoute = nav.previousBackStackEntry?.destination?.route,
        isCurrentPage = isCurrentPage,
        motionEnabled = motionEnabled,
        isRtl = isRtl,
      ) {
        AccountLibraryScreen(
          state = account,
          account = accountViewModel,
          noImages = ui.noImages,
          homeLayout = ui.homeLayout,
          onLogin = { nav.navigate("login") },
          isCurrentPage = isCurrentPage,
          selectedStoryIdentity = selectedStoryIdentity,
          onOpen = { s -> openStoryInDetailPane(s, entry.id, hostNav !== rootNav) },
          onOpenSection = { label ->
            nav.openDestination(
              DestinationTarget(DestinationType.SECTION, label.id, label.name, label.imageUrl)
            )
          },
          history = true,
          onBack = { nav.popBackStack() },
        )
      }
    }
    composable(
      "search?scopeType={scopeType}&scopeId={scopeId}&scopeName={scopeName}&query={query}",
      arguments =
        listOf(
          navArgument("scopeType") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
          navArgument("scopeId") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
          navArgument("scopeName") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
          navArgument("query") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
        ),
    ) { entry ->
      val isCurrentPage =
        nav.currentBackStackEntryAsState().value?.id == entry.id &&
          (!hostIsRoot || LocalPaneContentActive.current)
      val scopeType =
        runCatching { SearchScopeType.valueOf(entry.arguments?.getString("scopeType").orEmpty()) }
          .getOrDefault(SearchScopeType.GLOBAL)
      val scope =
        if (scopeType == SearchScopeType.GLOBAL) SearchScope.GLOBAL
        else
          SearchScope(
            scopeType,
            entry.arguments?.getString("scopeId").orEmpty(),
            entry.arguments?.getString("scopeName").orEmpty(),
          )
      val initialQuery = entry.arguments?.getString("query").orEmpty()
      val appContext = context.applicationContext
      val searchViewModel: SearchViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
          viewModelStoreOwner = entry,
          factory =
            SearchViewModel.Factory(
              PublicSearchRepository(),
              LibraryStore(appContext),
              scope,
              initialQuery,
            ),
        )
      LaunchedEffect(account.revision) { searchViewModel.bindSession(account.revision) }
      SharedMotionPage(
        this@composable,
        entry.id,
        entry.id,
        route = entry.destination.route,
        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
        previousRoute = nav.previousBackStackEntry?.destination?.route,
        isCurrentPage = isCurrentPage,
        motionEnabled = motionEnabled,
        isRtl = isRtl,
      ) {
        SearchScreen(
          ui,
          searchViewModel,
          onBack = { nav.popBackStack() },
          onOpen = { s -> openStoryInDetailPane(s, entry.id, hostNav !== rootNav) },
          onOpenHistoryScope = { record -> nav.navigate(searchRoute(record.scope, record.query)) },
          isCurrentPage = isCurrentPage,
          selectedStoryIdentity = selectedStoryIdentity,
        )
      }
    }
    composable(
      "destination/{type}/{id}/{name}/{icon}",
      arguments =
        listOf(
          navArgument("type") { type = NavType.StringType },
          navArgument("id") { type = NavType.StringType },
          navArgument("name") { type = NavType.StringType },
          navArgument("icon") { type = NavType.StringType },
        ),
    ) { entry ->
      val isCurrentPage =
        nav.currentBackStackEntryAsState().value?.id == entry.id &&
          (!hostIsRoot || LocalPaneContentActive.current)
      val type =
        runCatching { DestinationType.valueOf(entry.arguments?.getString("type").orEmpty()) }
          .getOrDefault(DestinationType.SECTION)
      val target =
        DestinationTarget(
          type,
          entry.arguments?.getString("id").orEmpty(),
          entry.arguments?.getString("name").orEmpty(),
          entry.arguments?.getString("icon")?.takeIf { it.isNotBlank() && it != "-" },
        )
      val destinationVm: DestinationViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
          viewModelStoreOwner = entry,
          factory = DestinationViewModel.Factory(),
        )
      SharedMotionPage(
        this@composable,
        entry.id,
        entry.id,
        route = entry.destination.route,
        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
        previousRoute = nav.previousBackStackEntry?.destination?.route,
        isCurrentPage = isCurrentPage,
        motionEnabled = motionEnabled,
        isRtl = isRtl,
      ) {
        DestinationScreen(
          target = target,
          viewModel = destinationVm,
          ui = ui,
          onBack = { nav.popBackStack() },
          onOpenStory = { story -> openStoryInDetailPane(story, entry.id, hostNav !== rootNav) },
          onOpenSection = { label ->
            nav.openDestination(
              DestinationTarget(DestinationType.SECTION, label.id, label.name, label.imageUrl)
            )
          },
          onSearch = {
            nav.navigate(
              searchRoute(
                SearchScope(
                  if (type == DestinationType.SECTION) SearchScopeType.SECTION
                  else SearchScopeType.HASHTAG,
                  target.id,
                  target.name,
                )
              )
            )
          },
          onContentBlockingAction = vm::onContentBlockingAction,
          isCurrentPage = isCurrentPage,
          selectedStoryIdentity = selectedStoryIdentity,
        )
      }
    }
    composable(
      "detail/{source}/{id}?origin={origin}",
      arguments =
        listOf(
          navArgument("source") { type = NavType.StringType },
          navArgument("id") { type = NavType.StringType },
          navArgument("origin") {
            type = NavType.StringType
            defaultValue = ""
          },
        ),
    ) { entry ->
      val isCurrentPage =
        nav.currentBackStackEntryAsState().value?.id == entry.id &&
          (!hostIsRoot || LocalPaneContentActive.current)
      val source =
        runCatching { Source.valueOf(entry.arguments?.getString("source") ?: "DEMO") }
          .getOrDefault(Source.DEMO)
      val id = entry.arguments?.getString("id") ?: ""
      val key = "$source:$id"
      val translationViewModel: SteamTranslationViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
          viewModelStoreOwner = entry,
          factory = SteamTranslationViewModel.Factory(),
        )
      val articleGameViewModel: cn.heyread.app.viewmodel.ArticleGameViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
          viewModelStoreOwner = entry,
          factory = cn.heyread.app.viewmodel.ArticleGameViewModel.Factory(),
        )
      val openedStory = ui.knownStories[key]
      val found =
        if (openedStory?.source == source && openedStory.id == id) {
          openedStory
        } else {
          val candidates =
            remember(
              ui.discoverFeed.items,
              ui.gamesFeed.items,
              ui.history,
              account.cloudFavorites,
              account.cloudHistory,
            ) {
              ui.discoverFeed.items +
                ui.gamesFeed.items +
                ui.history +
                account.cloudFavorites +
                account.cloudHistory +
                cn.heyread.app.data.remote.StoryRepository.demos
            }
          remember(source, id, openedStory, candidates) {
            StoryDetailResolver.currentStory(
              source = source,
              id = id,
              openedStory = openedStory,
              candidates = candidates,
            )
          }
        }
      val cachedDetail = ui.details[key]
      val story =
        remember(found, cachedDetail) {
          found?.let { StoryDetailResolver.mergeCachedDetail(it, cachedDetail) }
        }
      val fullArticleStory = account.fullArticles[key]?.story
      val detailPresentationStory =
        remember(story, fullArticleStory) {
          story?.let { StoryDetailResolver.mergeCachedDetail(it, fullArticleStory) }
        }
      val detailStory = story
      val originEntryId = entry.arguments?.getString("origin").orEmpty()
      var publicAttemptedForEntry by rememberSaveable(entry.id, key) { mutableStateOf(false) }
      val detailCoverIdentity =
        story?.let {
          storyCoverSharedIdentity(
            it.source.name,
            it.id,
            it.image,
            originEntryId,
            enabled = !ui.noImages,
          )
        }
      val onDetailBack: () -> Unit =
        remember(nav, sharedCoverCoordinator, detailCoverIdentity, originEntryId, entry.id) {
          {
            sharedCoverCoordinator?.beginReturn(detailCoverIdentity, originEntryId, entry.id)
            nav.popBackStack()
            Unit
          }
        }
      BackHandler(enabled = isCurrentPage, onBack = onDetailBack)
      val fullArticleBlocks = account.fullArticles[key]?.blocks
      val articleFeedVideo = story?.feedVideo
      val cachedDetailBlocks = story?.detailBlocks
      val gameBlocks =
        remember(key, fullArticleBlocks, articleFeedVideo, cachedDetailBlocks) {
          fullArticleBlocks
            ?.takeIf { it.isNotEmpty() }
            ?.let { SharedArticleContentParser.withFeedVideo(it, articleFeedVideo) }
            ?: cachedDetailBlocks.orEmpty()
        }
      val gameIds = remember(key, gameBlocks) { articleGameIds(gameBlocks) }
      LaunchedEffect(key, gameBlocks) { articleGameViewModel.bindArticle(key, gameIds) }
      val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
      androidx.compose.runtime.DisposableEffect(
        articleGameViewModel,
        lifecycleOwner,
        isCurrentPage,
      ) {
        fun syncActive() {
          articleGameViewModel.setActive(
            isCurrentPage &&
              lifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
              )
          )
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> syncActive() }
        lifecycleOwner.lifecycle.addObserver(observer)
        syncActive()
        onDispose {
          lifecycleOwner.lifecycle.removeObserver(observer)
          articleGameViewModel.setActive(false)
        }
      }
      LaunchedEffect(
        key,
        found,
        account.session?.userId,
        account.revision,
        account.loadingSession,
        account.fullArticles[key],
        key in account.articleLoading,
        account.articleErrors[key],
        ui.detailRequests[key],
      ) {
        if (detailStory != null && detailStory.source == Source.HEYBOX && !account.loadingSession) {
          if (
            account.session != null &&
              account.fullArticles[key] == null &&
              key !in account.articleErrors
          ) {
            accountViewModel.loadFullArticle(detailStory)
          } else if (
            account.session == null ||
              account.articleErrors.containsKey(key) ||
              account.fullArticles[key]?.blocks.isNullOrEmpty()
          ) {
            val publicRequest = ui.detailRequests[key]
            val hasPublicBody = ui.details[key]?.detailBlocks?.isNotEmpty() == true
            val requestAlreadyLoading =
              publicRequest?.phase == cn.heyread.app.viewmodel.DetailRequestPhase.LOADING
            if (requestAlreadyLoading && !publicAttemptedForEntry) {
              publicAttemptedForEntry = true
            } else if (
              !hasPublicBody &&
                publicRequest?.phase != cn.heyread.app.viewmodel.DetailRequestPhase.READY &&
                (!publicAttemptedForEntry ||
                  publicRequest == null ||
                  publicRequest.phase == cn.heyread.app.viewmodel.DetailRequestPhase.IDLE)
            ) {
              publicAttemptedForEntry = true
              val retryFailedRequest =
                publicRequest?.phase == cn.heyread.app.viewmodel.DetailRequestPhase.FAILED ||
                  publicRequest?.phase == cn.heyread.app.viewmodel.DetailRequestPhase.LIMITED
              vm.detail(detailStory, force = retryFailedRequest)
            }
          }
        }
      }
      if (story != null)
        SharedMotionPage(
          this@composable,
          entry.id,
          entry.arguments?.getString("origin"),
          isDetail = true,
          route = entry.destination.route,
          currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
          previousRoute = nav.previousBackStackEntry?.destination?.route,
          isCurrentPage = isCurrentPage,
          motionEnabled = motionEnabled,
          isRtl = isRtl,
        ) {
          DetailScreen(
            detailPresentationStory ?: story,
            ui,
            vm,
            account,
            accountViewModel,
            socialViewModel,
            commentActionsViewModel,
            authorFollowViewModel,
            translationViewModel,
            articleGameViewModel,
            onBack = onDetailBack,
            onLogin = { nav.navigate("login") },
            onShare = {
              val share =
                Intent(Intent.ACTION_SEND)
                  .setType("text/plain")
                  .putExtra(Intent.EXTRA_TEXT, "${story.title}\n${story.url ?: ""}")
              context.startActivity(Intent.createChooser(share, "分享文章"))
            },
            onOpenUrl = { url ->
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            onOpenProfile = { profile -> openUser(nav, profile) },
            onOpenSection = { label ->
              nav.openDestination(
                DestinationTarget(DestinationType.SECTION, label.id, label.name, label.imageUrl)
              )
            },
            onOpenHashtag = { label ->
              nav.openDestination(DestinationTarget(DestinationType.HASHTAG, label.id, label.name))
            },
            isCurrentPage = isCurrentPage,
            publicAttemptedForEntry = publicAttemptedForEntry,
          )
        }
      else ErrorPage("文章暂不可用", { nav.popBackStack() }, isCurrentPage)
    }
  }
  HeyReadTheme(
    ui.theme,
    ui.accent,
    motionEnabled,
    vm::setThemeNotice,
    globalFontFamily = ui.readingPreferences.font.toComposeFontFamily(ui.customFontTypeface),
  ) {
    ContentBlockingFeedbackHost(
      ui = ui,
      hostState = contentBlockingSnackbar,
      onAction = vm::onContentBlockingAction,
      onDismiss = vm::dismissContentBlockingFeedback,
    )
    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = {
        val hasBottomBar = activeBackStackEntry?.destination?.route in listOf("discover", "games", "dynamic", "mine")
        SnackbarHost(
          contentBlockingSnackbar,
          modifier = Modifier.padding(bottom = if (hasBottomBar) 72.dp else 0.dp),
        )
      },
    ) { padding ->
      CompositionLocalProvider(
        LocalSharedCoverCoordinator provides sharedCoverCoordinator,
        LocalPageTransitionRecords provides (pageTransitionRecords ?: emptyMap()),
        LocalPaneResizeEpoch provides paneResizeEpoch,
        LocalCustomReadingTypeface provides ui.customFontTypeface,
        LocalGlobalReadingFont provides ui.readingPreferences.font,
        LocalContentBlockingContext provides
          ContentBlockingContext(
            config = ui.contentBlocking,
            ready = ui.contentBlockingLoaded,
            loading = !ui.contentBlockingLoaded && ui.contentBlockingLoading,
            error = if (ui.contentBlockingLoaded) null else ui.contentBlockingError,
            sessionKey = "${account.session?.userId.orEmpty()}:${account.revision}",
          ),
        LocalContentBlockingCardActions provides
          ContentBlockingCardActions(
            ui = ui,
            sessionKey = "${account.session?.userId.orEmpty()}:${account.revision}",
            onAction = vm::onContentBlockingAction,
          ),
      ) {
        Box(Modifier.fillMaxSize()) {
          val rootRoute = nav.currentBackStackEntryAsState().value?.destination?.route
          AdaptiveSplitPane(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            savedLeftFraction = leftPaneFraction,
            paneOpen = detailPaneOpen,
            bottomBarVisible = rootRoute in listOf("discover", "games", "dynamic", "mine"),
            motionEnabled = motionEnabled,
            onFractionChanged = { leftPaneFraction = it },
            onResizeStarted = {
              if (!resizingPanes) paneResizeEpoch++
              resizingPanes = true
              sharedCoverCoordinator?.cancel()
            },
            onResizeFinished = { resizingPanes = false },
            onWindowWidthChanged = {
              sharedCoverCoordinator?.cancel()
              if (!resizingPanes) paneResizeEpoch++
              resizingPanes = false
            },
            mainContent = { paneModifier, paneVisible ->
              CompositionLocalProvider(LocalPaneContentActive provides paneVisible) {
                Box(
                  paneModifier.then(if (paneVisible) Modifier else Modifier.clearAndSetSemantics {})
                ) {
                  NavHost(
                    nav,
                    "discover",
                    Modifier.fillMaxSize(),
                    enterTransition = { pageEnter(motionEnabled, isRtl, pageTransitionRecords) },
                    exitTransition = { pageExit(motionEnabled, isRtl, pageTransitionRecords) },
                    popEnterTransition = {
                      pagePopEnter(motionEnabled, isRtl, pageTransitionRecords)
                    },
                    popExitTransition = {
                      pagePopExit(motionEnabled, isRtl, pageTransitionRecords)
                    },
                    sizeTransform = { null },
                  ) {
                    composable("discover") { entry ->
                      val isCurrentPage =
                        nav.currentBackStackEntryAsState().value?.id == entry.id &&
                          LocalPaneContentActive.current
                      SharedMotionPage(
                        this@composable,
                        entry.id,
                        entry.id,
                        route = entry.destination.route,
                        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
                        previousRoute = nav.previousBackStackEntry?.destination?.route,
                        isCurrentPage = isCurrentPage,
                        motionEnabled = motionEnabled,
                        isRtl = isRtl,
                      ) {
                        FeedScreen(
                          "发现",
                          ui,
                          vm,
                          onOpen = { story -> openStoryInDetailPane(story, entry.id, false) },
                          onSearch = { nav.navigate("search") },
                          onOpenSection = { label ->
                            nav.openDestination(
                              DestinationTarget(
                                DestinationType.SECTION,
                                label.id,
                                label.name,
                                label.imageUrl,
                              )
                            )
                          },
                          isCurrentPage = isCurrentPage,
                          paneResizeEpoch = paneResizeEpoch,
                          selectedStoryIdentity = selectedStoryIdentity,
                        )
                      }
                    }
                    composable("games") { entry ->
                      val isCurrentPage =
                        nav.currentBackStackEntryAsState().value?.id == entry.id &&
                          LocalPaneContentActive.current
                      val steamGameSearchFactory =
                        remember(entry.id) { SteamGameSearchViewModel.Factory() }
                      val steamGameSearchViewModel: SteamGameSearchViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(
                          viewModelStoreOwner = entry,
                          factory = steamGameSearchFactory,
                        )
                      val steamGameSearch by
                        steamGameSearchViewModel.state.collectAsStateWithLifecycle()
                      LaunchedEffect(isCurrentPage) {
                        if (!isCurrentPage) steamGameSearchViewModel.pause()
                      }
                      DisposableEffect(steamGameSearchViewModel) {
                        onDispose { steamGameSearchViewModel.pause() }
                      }
                      SharedMotionPage(
                        this@composable,
                        entry.id,
                        entry.id,
                        route = entry.destination.route,
                        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
                        previousRoute = nav.previousBackStackEntry?.destination?.route,
                        isCurrentPage = isCurrentPage,
                        motionEnabled = motionEnabled,
                        isRtl = isRtl,
                      ) {
                        FeedScreen(
                          "游戏",
                          ui,
                          vm,
                          onOpen = { story -> openStoryInDetailPane(story, entry.id, false) },
                          onSearch = {},
                          games = true,
                          isCurrentPage = isCurrentPage,
                          paneResizeEpoch = paneResizeEpoch,
                          selectedStoryIdentity = selectedStoryIdentity,
                          steamGameSearchState = steamGameSearch,
                          onSteamGameQueryChange = steamGameSearchViewModel::updateQuery,
                          onSteamGameSearchSubmit = steamGameSearchViewModel::submit,
                          onSteamGameSearchClear = steamGameSearchViewModel::clear,
                          onSteamGameSelect = { game -> vm.selectGame(game.appId, game.name) },
                        )
                      }
                    }
                    composable("dynamic") { entry ->
                      val isCurrentPage =
                        nav.currentBackStackEntryAsState().value?.id == entry.id &&
                          LocalPaneContentActive.current
                      SharedMotionPage(
                        this@composable,
                        entry.id,
                        entry.id,
                        route = entry.destination.route,
                        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
                        previousRoute = nav.previousBackStackEntry?.destination?.route,
                        isCurrentPage = isCurrentPage,
                        motionEnabled = motionEnabled,
                        isRtl = isRtl,
                      ) {
                        DynamicFeedScreen(
                          state =
                            dynamicFeed.forAccountPresentation(
                              account.session?.userId,
                              account.revision,
                              account.loadingSession,
                            ),
                          ui = ui,
                          viewModel = dynamicFeedViewModel,
                          isCurrentPage = isCurrentPage,
                          onLogin = { nav.navigate("login") },
                          onOpenStory = { story -> openStoryInDetailPane(story, entry.id, false) },
                          selectedStoryIdentity = selectedStoryIdentity,
                        )
                      }
                    }
                    composable("saved") { entry ->
                      val isCurrentPage =
                        nav.currentBackStackEntryAsState().value?.id == entry.id &&
                          LocalPaneContentActive.current
                      SharedMotionPage(
                        this@composable,
                        entry.id,
                        entry.id,
                        route = entry.destination.route,
                        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
                        previousRoute = nav.previousBackStackEntry?.destination?.route,
                        isCurrentPage = isCurrentPage,
                        motionEnabled = motionEnabled,
                        isRtl = isRtl,
                      ) {
                        AccountLibraryScreen(
                          state = account,
                          account = accountViewModel,
                          noImages = ui.noImages,
                          homeLayout = ui.homeLayout,
                          isCurrentPage = isCurrentPage,
                          selectedStoryIdentity = selectedStoryIdentity,
                          isRoot = false,
                          onLogin = { nav.navigate("login") },
                          onBack = { nav.popBackStack() },
                          onOpen = { s -> openStoryInDetailPane(s, entry.id, false) },
                          onOpenSection = { label ->
                            nav.openDestination(
                              DestinationTarget(
                                DestinationType.SECTION,
                                label.id,
                                label.name,
                                label.imageUrl,
                              )
                            )
                          },
                        )
                      }
                    }
                    composable("mine") { entry ->
                      val isCurrentPage =
                        nav.currentBackStackEntryAsState().value?.id == entry.id &&
                          LocalPaneContentActive.current
                      SharedMotionPage(
                        this@composable,
                        entry.id,
                        entry.id,
                        route = entry.destination.route,
                        currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route,
                        previousRoute = nav.previousBackStackEntry?.destination?.route,
                        isCurrentPage = isCurrentPage,
                        motionEnabled = motionEnabled,
                        isRtl = isRtl,
                      ) {
                        MineScreen(
                          ui,
                          vm,
                          account,
                          accountViewModel,
                          onLogin = { nav.navigate("login") },
                          onOpenFavorites = ::openAccountFavorites,
                          onOpenOwnProfile = ::openAccountOwnProfile,
                          onAccountHistory = { nav.navigate("account-history") },
                          onOpenSettingsCategory = ::openSettingsCategory,
                          selectedSettingsCategory =
                            if (
                              activeDetailBackStackEntry?.destination?.route ==
                                "settings/{category}"
                            )
                              SettingsCategory.entries.firstOrNull {
                                it.name ==
                                  activeDetailBackStackEntry?.arguments?.getString("category")
                              }
                            else null,
                          isCurrentPage = isCurrentPage,
                        ) { s ->
                          openStoryInDetailPane(s, entry.id, false)
                        }
                      }
                    }
                    registerContentRoutes(nav)
                  }
                }
              }
            },
            detailContent = { paneModifier, paneVisible, isDualPane ->
              CompositionLocalProvider(LocalPaneContentActive provides paneVisible) {
                Box(
                  paneModifier.then(if (paneVisible) Modifier else Modifier.clearAndSetSemantics {})
                ) {
                  NavHost(
                    detailNav,
                    "detail-empty",
                    Modifier.fillMaxSize(),
                    enterTransition = { pageEnter(motionEnabled, isRtl, pageTransitionRecords) },
                    exitTransition = { pageExit(motionEnabled, isRtl, pageTransitionRecords) },
                    popEnterTransition = {
                      pagePopEnter(motionEnabled, isRtl, pageTransitionRecords)
                    },
                    popExitTransition = {
                      pagePopExit(motionEnabled, isRtl, pageTransitionRecords)
                    },
                    sizeTransform = { null },
                  ) {
                    composable("detail-empty") {
                      if (
                        isDualPane &&
                          paneVisible &&
                          activeDetailBackStackEntry?.destination?.route == "detail-empty"
                      ) {
                        Column(
                          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                          verticalArrangement = Arrangement.Center,
                          horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                          Text("选择左侧内容", style = MaterialTheme.typography.titleMedium)
                          Text("打开文章后会在这里显示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                    }
                    composable(
                      "settings/{category}",
                      arguments = listOf(navArgument("category") { type = NavType.StringType }),
                    ) { entry ->
                      val category =
                        SettingsCategory.entries.firstOrNull {
                          it.name == entry.arguments?.getString("category")
                        }
                      val currentEntry by detailNav.currentBackStackEntryAsState()
                      val isCurrentPage =
                        currentEntry?.id == entry.id && LocalPaneContentActive.current
                      val isEntryActive by rememberUpdatedState(isCurrentPage)
                      val latestDetailEntryId by
                        rememberUpdatedState(detailNav.currentBackStackEntry?.id)
                      val openDocument =
                        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                          uri ->
                          if (pendingFontPickerEntryId == entry.id) {
                            pendingFontPickerEntryId = null
                            if (uri != null && isEntryActive && latestDetailEntryId == entry.id) {
                              fontImportOwnerEntryId = entry.id
                              fontImportObservedBusy = false
                              vm.importReadingFont(uri)
                            }
                          }
                        }
                      val onBack = {
                        if (pendingFontPickerEntryId == entry.id) {
                          pendingFontPickerEntryId = null
                        }
                        detailNav.popBackStack()
                        Unit
                      }
                      BackHandler(enabled = isCurrentPage, onBack = onBack)
                      if (category == null) {
                        ErrorPage("设置分类不存在", onBack, isCurrentPage)
                      } else {
                        SharedMotionPage(
                          this@composable,
                          entry.id,
                          entry.id,
                          route = entry.destination.route,
                          currentRoute = currentEntry?.destination?.route,
                          previousRoute = detailNav.previousBackStackEntry?.destination?.route,
                          isCurrentPage = isCurrentPage,
                          motionEnabled = motionEnabled,
                          isRtl = isRtl,
                        ) {
                          Column(
                            Modifier.fillMaxSize()
                              .background(MaterialTheme.colorScheme.background)
                              .pageInputGate(isCurrentPage)
                              .testTag("settings-detail-${categoryTag(category)}")
                          ) {
                            PageTopBar(category.title, isRoot = false, onBack = onBack)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                              val gatewayDraft = settingsGatewayDrafts[category.name] ?: ui.gateway
                              val advanced = settingsAdvancedValues[category.name] ?: false
                              val scrollState =
                                remember(category.name) {
                                  settingsScrollStates.getOrPut(category.name) {
                                    ScrollState(settingsScrollPositions[category.name] ?: 0)
                                  }
                                }
                              LaunchedEffect(category.name, scrollState) {
                                snapshotFlow { scrollState.value }
                                  .collect { position ->
                                    if (settingsScrollPositions[category.name] != position) {
                                      settingsScrollPositions =
                                        settingsScrollPositions + (category.name to position)
                                    }
                                  }
                              }
                              SettingsCategoryScreen(
                                category = category,
                                ui = ui,
                                gatewayValue = gatewayDraft,
                                advanced = advanced,
                                onGatewayChange = {
                                  settingsGatewayDrafts =
                                    settingsGatewayDrafts + (category.name to it)
                                },
                                onAdvancedChange = {
                                  settingsAdvancedValues =
                                    settingsAdvancedValues + (category.name to it)
                                },
                                onThemeChange = { vm.settings(theme = it) },
                                onHomeLayoutChange = vm::setHomeLayout,
                                onAccentChange = vm::setAccent,
                                onMotionChange = vm::setMotionEnabled,
                                onNoImagesChange = { vm.settings(noImages = it) },
                                onGatewaySave = { vm.testGateway(gatewayDraft) },
                                onClearCache = vm::clearCache,
                                onOpenStory = { story ->
                                  openStoryInDetailPane(story, entry.id, true)
                                },
                                onClearHistory = vm::clearHistory,
                                onBack = onBack,
                                onReadingPreferencesChange = vm::setReadingPreferences,
                                onImportFont = {
                                  if (
                                    !ui.fontBusy &&
                                      pendingFontPickerEntryId == null &&
                                      isEntryActive
                                  ) {
                                    pendingFontPickerEntryId = entry.id
                                    openDocument.launch(arrayOf("*/*"))
                                  }
                                },
                                onRemoveFont = vm::removeReadingFont,
                                onContentBlockingAction = vm::onContentBlockingAction,
                                fontPickerPending = pendingFontPickerEntryId == entry.id,
                                isCurrentPage = isCurrentPage,
                                scrollState = scrollState,
                              )
                            }
                          }
                        }
                      }
                    }
                    registerContentRoutes(detailNav)
                  }
                }
              }
            },
            bottomBar = { _ ->
              val route = nav.currentBackStackEntryAsState().value?.destination?.route
              if (route in listOf("discover", "games", "dynamic", "mine")) {
                HeyReadBottomBar(route.orEmpty(), motionEnabled) { target ->
                  if (target != route) navigateRootTab(target)
                }
              }
            },
          )
          sharedCoverCoordinator?.let { SharedCoverOverlayHost(it) }
        }
      }
    }
  }
}

@Composable
internal fun HeyReadBottomBar(route: String, motionEnabled: Boolean, onNavigate: (String) -> Unit) {
  val tabs =
    listOf(
      Triple("discover", "发现", Icons.Filled.Home),
      Triple("games", "游戏", Icons.Filled.Star),
      Triple("dynamic", "动态", Icons.AutoMirrored.Filled.List),
      Triple("mine", "我的", Icons.Filled.Person),
    )
  Surface(
    modifier = Modifier.fillMaxWidth().testTag("heyread-bottom-tabs"),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(Modifier.fillMaxWidth()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(Modifier.fillMaxWidth().height(80.dp)) {
        tabs.forEach { (routeName, label, icon) ->
          val selected = route == routeName
          Box(
            Modifier.weight(1f)
              .fillMaxHeight()
              .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!selected) onNavigate(routeName) },
              )
              .testTag("root-tab-$routeName")
          ) {
            Column(
              Modifier.align(Alignment.Center),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Box(Modifier.width(64.dp).height(32.dp)) {
                RootTabCapsule(selected, motionEnabled, routeName)
                Icon(
                  icon,
                  contentDescription = null,
                  modifier = Modifier.align(Alignment.Center).testTag("root-tab-icon-$routeName"),
                  tint =
                    if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(Modifier.height(4.dp))
              Text(
                label,
                modifier = Modifier.testTag("root-tab-label-$routeName"),
                style = MaterialTheme.typography.labelSmall,
                color =
                  if (selected) MaterialTheme.colorScheme.onSurface
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun SharedMotionPage(
  visibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
  entryId: String,
  originId: String?,
  isDetail: Boolean = false,
  route: String? = null,
  currentRoute: String? = null,
  previousRoute: String? = null,
  isCurrentPage: Boolean = true,
  motionEnabled: Boolean = true,
  isRtl: Boolean = false,
  content: @Composable () -> Unit,
) {
  val transitionRecord = LocalPageTransitionRecords.current[entryId]
  CompositionLocalProvider(
    LocalSharedCoverEntryId provides entryId,
    LocalSharedCoverOriginId provides originId,
    LocalSharedCoverIsDetail provides isDetail,
    LocalPaneContentActive provides isCurrentPage,
    content = {
      PageMotionContainer(
        modifier = Modifier.fillMaxSize(),
        visibility = visibilityScope,
        route = route,
        currentRoute = currentRoute,
        previousRoute = previousRoute,
        isCurrentPage = isCurrentPage,
        motionEnabled = motionEnabled,
        isRtl = isRtl,
        transitionRecord = transitionRecord,
      ) {
        content()
      }
    },
  )
}

private fun detailRoute(story: cn.heyread.app.data.model.Story, originEntryId: String): String =
  "detail/${enc(story.source.name)}/${enc(story.id)}?origin=${enc(originEntryId)}"

private fun beginStoryCoverTransition(
  coordinator: SharedCoverCoordinator?,
  story: cn.heyread.app.data.model.Story,
  sourceEntryId: String,
  noImages: Boolean,
) {
  if (noImages) return
  val identity =
    storyCoverSharedIdentity(
      story.source.name,
      story.id,
      story.image,
      sourceEntryId,
      enabled = true,
    )
  coordinator?.begin(identity, sourceEntryId, sourceEntryId)
}

@Composable
private fun RootTabCapsule(selected: Boolean, motionEnabled: Boolean, route: String) {
  val color = MaterialTheme.colorScheme.secondaryContainer
  val shape = RoundedCornerShape(50)
  if (!motionEnabled) {
    if (selected)
      Box(Modifier.fillMaxSize().background(color, shape).testTag("root-tab-capsule-$route"))
    return
  }
  val progress = remember(route) { Animatable(if (selected) 1f else 0f) }
  var animating by remember(route) { mutableStateOf(false) }
  var previousSelected by remember(route) { mutableStateOf(selected) }
  val frameRateController =
    rememberMotionFrameRateHintController(
      motionEnabled && (selected != previousSelected || animating)
    )
  SideEffect { previousSelected = selected }
  LaunchedEffect(selected) {
    val target = if (selected) 1f else 0f
    if (progress.value == target) {
      animating = false
    } else {
      animating = true
      val frameRateLease = frameRateController?.acquire()
      try {
        progress.animateTo(target, tween(MotionTokens.ITEM, easing = MotionTokens.EMPHASIZED))
      } finally {
        frameRateLease?.close()
        animating = false
      }
    }
  }
  val transitionStarted = selected != previousSelected
  if (selected || previousSelected || animating) {
    val animatedModifier =
      Modifier.fillMaxSize()
        .then(
          if (animating || transitionStarted)
            Modifier.graphicsLayer {
              val fraction = progress.value
              alpha = fraction
              scaleX = fraction
              scaleY = fraction
            }
          else Modifier
        )
        .background(color, shape)
        .testTag("root-tab-capsule-$route")
    Box(animatedModifier)
  }
}

private fun NavHostController.openDestination(target: DestinationTarget) {
  destinationRoute(target)?.let(::navigate)
}

internal fun searchRoute(scope: SearchScope, query: String? = null): String {
  if (scope.type == SearchScopeType.GLOBAL)
    return query?.let { "search?query=${enc(it)}" } ?: "search"
  return "search?scopeType=${scope.type.name}&scopeId=${enc(scope.id)}&scopeName=${enc(scope.name)}&query=${enc(query.orEmpty())}"
}

internal fun destinationRoute(target: DestinationTarget): String? {
  if (target.id.isBlank()) return null
  val fallbackName =
    when (target.type) {
      DestinationType.SECTION -> "分区"
      DestinationType.HASHTAG -> "话题"
      DestinationType.USER -> "用户主页"
    }
  return "destination/${target.type.name}/${enc(target.id)}/${enc(target.name.ifBlank { fallbackName })}/${enc(target.iconUrl.orEmpty().ifBlank { "-" })}"
}

private fun openUser(nav: NavHostController, profile: AuthorProfile) {
  val id = profile.userId?.takeIf(String::isNotBlank) ?: return
  nav.openDestination(DestinationTarget(DestinationType.USER, id, profile.name, profile.avatarUrl))
}

fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private val settingsScrollPositionsSaver =
  listSaver<Map<String, Int>, Any>(
    save = { values -> values.flatMap { (category, position) -> listOf(category, position) } },
    restore = { values ->
      values
        .chunked(2)
        .mapNotNull { pair ->
          val category = pair.getOrNull(0) as? String
          val position = (pair.getOrNull(1) as? Number)?.toInt()
          if (category != null && position != null) category to position else null
        }
        .toMap()
    },
  )

private val settingsGatewayDraftsSaver =
  listSaver<Map<String, String>, Any>(
    save = { values -> values.flatMap { (category, draft) -> listOf(category, draft) } },
    restore = { values ->
      values
        .chunked(2)
        .mapNotNull { pair ->
          val category = pair.getOrNull(0) as? String
          val draft = pair.getOrNull(1) as? String
          if (category != null && draft != null) category to draft else null
        }
        .toMap()
    },
  )

private val settingsAdvancedValuesSaver =
  listSaver<Map<String, Boolean>, Any>(
    save = { values -> values.flatMap { (category, advanced) -> listOf(category, advanced) } },
    restore = { values ->
      values
        .chunked(2)
        .mapNotNull { pair ->
          val category = pair.getOrNull(0) as? String
          val advanced = pair.getOrNull(1) as? Boolean
          if (category != null && advanced != null) category to advanced else null
        }
        .toMap()
    },
  )
