package cn.heyread.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.data.account.FollowedActivityPage
import cn.heyread.app.data.account.FollowedUsersPage
import cn.heyread.app.data.account.SessionStore
import cn.heyread.app.data.local.ContentBlockingRuleFileAccess
import cn.heyread.app.data.local.ContentBlockingRuleFiles
import cn.heyread.app.data.local.LibraryStore
import cn.heyread.app.data.local.records.HeyReadRecordsDatabase
import cn.heyread.app.data.local.records.LegacyRecordsSnapshot
import cn.heyread.app.data.local.records.LocalRecordsStore
import cn.heyread.app.data.local.records.RoomRecordsRepository
import cn.heyread.app.data.local.records.openHeyReadRecordsDatabase
import cn.heyread.app.data.local.store
import cn.heyread.app.data.model.StoryPage
import cn.heyread.app.data.remote.ArticleGameRepository
import cn.heyread.app.data.remote.DestinationLoader
import cn.heyread.app.data.remote.DestinationRepository
import cn.heyread.app.data.remote.OnlineSearchLoader
import cn.heyread.app.data.remote.PublicSearchRepository
import cn.heyread.app.data.remote.SteamGameSearchRepository
import cn.heyread.app.data.remote.SteamGameSearchSource
import cn.heyread.app.data.remote.StoryRepositoryAssistedFactory
import cn.heyread.app.data.remote.StoryRepositoryCreator
import cn.heyread.app.data.translation.MlKitSteamTranslationServiceFactory
import cn.heyread.app.data.translation.SteamTranslationServiceFactory
import cn.heyread.app.viewmodel.AuthorFollowViewModel
import cn.heyread.app.viewmodel.CommentActionsViewModel
import cn.heyread.app.viewmodel.ContentBlockingTransferViewModelFactory
import cn.heyread.app.viewmodel.DynamicFeedViewModel
import cn.heyread.app.viewmodel.SocialActionsViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
  @Provides
  @AppCacheDirectory
  fun provideAppCacheDirectory(@ApplicationContext context: Context): File = context.cacheDir

  @Provides
  fun provideStoryRepositoryCreator(
      assistedFactory: StoryRepositoryAssistedFactory,
  ): StoryRepositoryCreator = StoryRepositoryCreator(assistedFactory::create)

  @Provides
  @Singleton
  fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
      context.store

  @Provides
  @Singleton
  fun provideRecordsDatabase(@ApplicationContext context: Context): HeyReadRecordsDatabase =
      openHeyReadRecordsDatabase(context)

  @Provides
  @Singleton
  fun provideLocalRecordsStore(
      database: HeyReadRecordsDatabase,
      preferences: DataStore<Preferences>,
  ): LocalRecordsStore =
      RoomRecordsRepository(
          db = database,
          legacySource = {
            val values = preferences.data.first()
            LegacyRecordsSnapshot(
                values[stringPreferencesKey("history")],
                values[stringPreferencesKey("search_history")],
            )
          },
      )

  @Provides
  @Singleton
  fun provideLibraryStore(
      @ApplicationContext context: Context,
      preferences: DataStore<Preferences>,
      recordsStore: dagger.Lazy<LocalRecordsStore>,
  ): LibraryStore = LibraryStore(context, preferences, recordsStore)

  @Provides
  @Singleton
  fun provideSearchHistoryStore(store: LibraryStore): cn.heyread.app.data.local.SearchHistoryStore =
      store

  @Provides
  @Singleton
  fun provideSessionStore(@ApplicationContext context: Context): SessionStore =
      SessionStore(context)

  @Provides fun provideDestinationLoader(): DestinationLoader = DestinationRepository()

  @Provides fun provideOnlineSearchLoader(): OnlineSearchLoader = PublicSearchRepository()

  @Provides fun provideSteamGameSearchSource(): SteamGameSearchSource = SteamGameSearchRepository()

  @Provides fun provideArticleGameRepository(): ArticleGameRepository = ArticleGameRepository()

  @Provides
  fun provideTranslationServiceFactory(): SteamTranslationServiceFactory =
      MlKitSteamTranslationServiceFactory

  @Provides
  fun provideContentBlockingFileAccess(
      @ApplicationContext context: Context,
  ): ContentBlockingRuleFileAccess = ContentBlockingRuleFiles(context.contentResolver)

  @Provides
  @Singleton
  fun provideTransferViewModelFactory(
      files: ContentBlockingRuleFileAccess,
  ): ContentBlockingTransferViewModelFactory = ContentBlockingTransferViewModelFactory(files)
}

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppCacheDirectory

@Module
@InstallIn(ActivityRetainedComponent::class)
object AccountModule {
  @Provides
  @ActivityRetainedScoped
  fun provideAccountRepository(): AccountRepository = AccountRepository()
}

/** Read-only access used by debug verification and the legacy LibraryStore adapter. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProductionGraphEntryPoint {
  fun libraryStore(): LibraryStore

  fun localRecordsStore(): LocalRecordsStore

  fun preferencesDataStore(): DataStore<Preferences>
}

/** Runtime callbacks are supplied by the Activity owner each time a factory is created. */
@ActivityRetainedScoped
class ActivityRuntimeViewModelFactories
@Inject
constructor(
    private val accountRepository: AccountRepository,
    private val destinationLoader: DestinationLoader,
) {
  fun dynamicFeed(
      recommendations: suspend (offset: Int, displayWidth: Int) -> StoryPage,
      following: suspend (userId: String, offset: Int, lastValue: String?) -> FollowedActivityPage,
      onSessionExpired: () -> Unit,
      followedUsers: suspend (userId: String) -> FollowedUsersPage,
  ): ViewModelProvider.Factory =
      DynamicFeedViewModel.Factory(
          recommendations,
          following,
          onSessionExpired,
          followedUsers,
          destinationLoader,
      )

  fun socialActions(onSessionExpired: () -> Unit): ViewModelProvider.Factory =
      SocialActionsViewModel.Factory(accountRepository, onSessionExpired)

  fun commentActions(onSessionExpired: () -> Unit): ViewModelProvider.Factory =
      CommentActionsViewModel.Factory(accountRepository, onSessionExpired)

  fun authorFollow(onSessionExpired: () -> Unit): ViewModelProvider.Factory =
      AuthorFollowViewModel.Factory(accountRepository, onSessionExpired)
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface ActivityFactoryEntryPoint {
  fun runtimeViewModelFactories(): ActivityRuntimeViewModelFactories
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ContentBlockingTransferEntryPoint {
  fun transferViewModelFactory(): ContentBlockingTransferViewModelFactory
}
