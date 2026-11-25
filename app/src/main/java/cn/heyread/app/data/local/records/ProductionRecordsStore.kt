package cn.heyread.app.data.local.records

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import cn.heyread.app.di.ProductionGraphEntryPoint
import dagger.hilt.android.EntryPointAccessors

/** Compatibility adapter that resolves the one lazy production repository from the Hilt graph. */
object ProductionRecordsStore {
  fun get(
      context: Context,
      @Suppress("UNUSED_PARAMETER") legacyPreferences: DataStore<Preferences>,
  ): LocalRecordsStore {
    // The Hilt graph is the sole owner of the production database and repository. The arguments
    // remain for source compatibility with existing explicit LibraryStore construction.
    return EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProductionGraphEntryPoint::class.java,
        )
        .localRecordsStore()
  }
}
