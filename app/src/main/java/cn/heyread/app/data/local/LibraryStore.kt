package cn.heyread.app.data.local

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import cn.heyread.app.data.model.*
import cn.heyread.app.data.model.ReadingFont
import cn.heyread.app.data.model.ReadingPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.store by preferencesDataStore("heyread")

internal data class ReadingFontStoreSnapshot(
    val fonts: List<ImportedReadingFont>,
    val preferences: ReadingPreferences,
)

data class StartupAppearance(
  val theme: String,
  val accent: String,
  val motionEnabled: Boolean,
  val noImages: Boolean,
  val readingPreferences: ReadingPreferences,
  val importedFonts: List<ImportedReadingFont>,
)

class LibraryStore(private val context: Context) : SearchHistoryStore {
  private val readingFontStoreMutex = Mutex()
  private var readingFontsDirectoryOverride: java.io.File? = null
  private var preferencesStoreOverride: DataStore<Preferences>? = null

  private val preferencesStore: DataStore<Preferences>
    get() = preferencesStoreOverride ?: context.store

  internal constructor(
      context: Context,
      isolatedReadingFontsDirectory: java.io.File
  ) : this(context) {
    readingFontsDirectoryOverride = isolatedReadingFontsDirectory.canonicalFile
  }

  internal constructor(
      context: Context,
      injectedPreferencesStore: DataStore<Preferences>
  ) : this(context) {
    preferencesStoreOverride = injectedPreferencesStore
  }

  internal constructor(
      context: Context,
      isolatedReadingFontsDirectory: java.io.File,
      injectedPreferencesStore: DataStore<Preferences>,
  ) : this(context, injectedPreferencesStore) {
    readingFontsDirectoryOverride = isolatedReadingFontsDirectory.canonicalFile
  }

  val cacheDirectory
    get() = context.cacheDir

  private val hist = stringPreferencesKey("history")
  private val searchHist = stringPreferencesKey("search_history")
  private val theme = stringPreferencesKey("theme")
  private val themeAccent = stringPreferencesKey("theme_accent")
  private val motionEnabled = booleanPreferencesKey("motion_enabled")
  private val noImages = booleanPreferencesKey("no_images")
  private val gateway = stringPreferencesKey("gateway")
  private val homeLayout = stringPreferencesKey("home_layout")
  private val contentBlockingConfigKey = stringPreferencesKey("content_blocking_config")
  private val readingFontSize = floatPreferencesKey("reading_font_size")
  private val readingLineHeight = floatPreferencesKey("reading_line_height")
  private val readingParagraphSpacing = floatPreferencesKey("reading_paragraph_spacing")
  private val readingFontFamily = stringPreferencesKey("reading_font_family")
  private val importedReadingFontsKey = stringPreferencesKey("reading_imported_fonts")
  private val readingFontsDirectory: java.io.File
    get() = readingFontsDirectoryOverride ?: java.io.File(context.filesDir, "reading_fonts")

  private fun encode(s: Story) = encodeHistoryStory(s)

  private fun decode(raw: String) = decodeHistoryStory(raw)

  private fun read(raw: String?) =
      runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).mapNotNull { decode(a.optString(it)) }
          }
          .getOrDefault(emptyList())

  suspend fun history(): List<Story> =
      withContext(Dispatchers.IO) { read(preferencesStore.data.first()[hist]) }

  override suspend fun searchHistory(): List<SearchHistoryEntry> =
      decodeSearchHistory(preferencesStore.data.first()[searchHist])

  override suspend fun recordSearch(entry: SearchHistoryEntry) {
    val query = entry.query.trim().take(MAX_SEARCH_QUERY_LENGTH)
    require(query.isNotBlank() && entry.scope.isValid) { "无效的搜索记录" }
    val normalized = entry.copy(query = query)
    preferencesStore.updateData { preferences ->
      val entries = decodeSearchHistory(preferences[searchHist])
      preferences.toMutablePreferences().apply {
        this[searchHist] =
            encodeSearchHistory(listOf(normalized) + entries.filterNot { it.key == normalized.key })
      }
    }
  }

  override suspend fun removeSearch(entry: SearchHistoryEntry) {
    preferencesStore.updateData { preferences ->
      preferences.toMutablePreferences().apply {
        this[searchHist] =
            encodeSearchHistory(
                decodeSearchHistory(preferences[searchHist]).filterNot { it.key == entry.key })
      }
    }
  }

  override suspend fun clearSearchHistory() {
    preferencesStore.updateData { preferences ->
      preferences.toMutablePreferences().apply { this[searchHist] = "[]" }
    }
  }

  suspend fun removeLegacyFavorites() {
    preferencesStore.edit { it.remove(stringPreferencesKey("favorites")) }
  }

  suspend fun addHistory(s: Story) {
    preferencesStore.updateData { p ->
      withContext(Dispatchers.IO) {
        val all =
            (listOf(s) + read(p[hist]).filterNot { it.source == s.source && it.id == s.id }).take(
                80)
        p.toMutablePreferences().apply {
          this[hist] = JSONArray().apply { all.forEach { put(encode(it)) } }.toString()
        }
      }
    }
  }

  suspend fun setTheme(v: String) {
    preferencesStore.edit { it[theme] = v }
  }

  suspend fun getTheme() = preferencesStore.data.first()[theme] ?: "system"

  suspend fun setThemeAccent(value: String) {
    preferencesStore.edit { it[themeAccent] = value }
  }

  suspend fun getThemeAccent() = preferencesStore.data.first()[themeAccent] ?: "classic"

  suspend fun getStartupAppearance(): StartupAppearance {
    val snapshot = preferencesStore.data.first()
    return StartupAppearance(
      theme = snapshot[theme] ?: "system",
      accent = snapshot[themeAccent] ?: "classic",
      motionEnabled = snapshot[motionEnabled] ?: true,
      noImages = snapshot[noImages] ?: false,
      readingPreferences = decodeReadingPreferences(snapshot),
      importedFonts = readImportedFonts(snapshot[importedReadingFontsKey]),
    )
  }

  suspend fun setMotionEnabled(value: Boolean) {
    preferencesStore.edit { it[motionEnabled] = value }
  }

  suspend fun getMotionEnabled() = preferencesStore.data.first()[motionEnabled] ?: true

  suspend fun getReadingPreferences(): ReadingPreferences {
    return decodeReadingPreferences(preferencesStore.data.first())
  }

  private fun decodeReadingPreferences(preferences: Preferences): ReadingPreferences {
    val (font, customFontId) = ReadingFont.parseStoredValue(preferences[readingFontFamily])
    return ReadingPreferences(
            fontSizeSp = preferences[readingFontSize] ?: ReadingPreferences.DEFAULT_FONT_SIZE_SP,
            lineHeightMultiplier =
                preferences[readingLineHeight] ?: ReadingPreferences.DEFAULT_LINE_HEIGHT_MULTIPLIER,
            paragraphSpacingDp =
                preferences[readingParagraphSpacing]
                    ?: ReadingPreferences.DEFAULT_PARAGRAPH_SPACING_DP,
            font = font,
            customFontId = customFontId,
        )
        .normalized()
  }

  suspend fun setReadingPreferences(value: ReadingPreferences) {
    val normalized = value.normalized()
    preferencesStore.edit { preferences ->
      preferences[readingFontSize] = normalized.fontSizeSp
      preferences[readingLineHeight] = normalized.lineHeightMultiplier
      preferences[readingParagraphSpacing] = normalized.paragraphSpacingDp
      preferences[readingFontFamily] =
          ReadingFont.storedValue(normalized.font, normalized.customFontId)
    }
  }

  internal suspend fun getImportedReadingFonts(): List<ImportedReadingFont> =
      readImportedFonts(preferencesStore.data.first()[importedReadingFontsKey])

  internal suspend fun prepareReadingFont(uri: Uri): PreparedReadingFont =
      withContext(Dispatchers.IO) {
        require(uri.scheme.equals("content", ignoreCase = true)) { "请选择设备上的字体文件" }
        val displayName = queryDisplayName(uri)
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选字体文件")
        input.use { ReadingFontFiles.prepare(it, displayName, readingFontsDirectory) }
      }

  internal suspend fun commitReadingFont(
      prepared: PreparedReadingFont,
      preferences: ReadingPreferences,
      preferencesAtCommit: () -> ReadingPreferences = { preferences },
  ): ReadingFontStoreSnapshot =
      readingFontStoreMutex.withLock {
        try {
          withContext(Dispatchers.IO) {
            val font = prepared.font.normalized() ?: error("字体标识无效")
            val directory = readingFontsDirectory.canonicalFile
            require(prepared.temporaryFile.canonicalFile.parentFile == directory) { "字体临时文件无效" }
            val prior = preferencesStore.data.first()
            val currentFonts = readImportedFonts(prior[importedReadingFontsKey])
            val existing = currentFonts.firstOrNull { it.id == font.id }
            require(existing != null || currentFonts.size < ReadingFontFiles.MAX_IMPORTED_FONTS) {
              "最多导入8种字体，请先移除一种字体"
            }
            val finalFile = ReadingFontFiles.fileForId(directory, font.id)
            var installedByThisCall = false
            var committedPreferences = preferences.normalized()
            try {
              if (finalFile.exists()) {
                ReadingFontFiles.validatePlatformTypeface(finalFile)
                prepared.temporaryFile.delete()
              } else {
                check(prepared.temporaryFile.renameTo(finalFile)) { "无法保存字体副本" }
                installedByThisCall = true
              }
              val fonts = if (existing != null) currentFonts else currentFonts + font
              preferencesStore.edit { values ->
                committedPreferences = preferencesAtCommit().normalized()
                require(
                    committedPreferences.font != ReadingFont.CUSTOM ||
                        committedPreferences.customFontId == font.id ||
                        fonts.any { it.id == committedPreferences.customFontId }) {
                      "所选导入字体不存在"
                    }
                values[importedReadingFontsKey] = writeImportedFonts(fonts)
                writeReadingPreferences(values, committedPreferences)
              }
              ReadingFontStoreSnapshot(fonts, committedPreferences)
            } catch (error: Throwable) {
              prepared.temporaryFile.delete()
              if (installedByThisCall) {
                val isRegistered =
                    withContext(NonCancellable) {
                      runCatching {
                            parseImportedFontsStrict(
                                preferencesStore.data.first()[importedReadingFontsKey])
                          }
                          .getOrNull()
                          ?.any { it.id == font.id }
                    }
                // A failed catalog read means the registration state is unknown. Keep the file;
                // startup cleanup can reclaim it after a successful catalog read.
                if (isRegistered == false) finalFile.delete()
              }
              throw error
            }
          }
        } catch (error: Throwable) {
          withContext(NonCancellable + Dispatchers.IO) { prepared.temporaryFile.delete() }
          throw error
        }
      }

  internal suspend fun loadReadingTypeface(id: String): Typeface =
      loadReadingTypeface(id, getImportedReadingFonts())

  internal suspend fun loadReadingTypeface(
      id: String,
      importedFonts: List<ImportedReadingFont>,
  ): Typeface =
      readingFontStoreMutex.withLock {
        withContext(Dispatchers.IO) {
          require(ReadingFontFiles.isValidId(id)) { "字体标识无效" }
          val registered = importedFonts.any { it.id == id }
          require(registered) { "导入字体不存在" }
          ReadingFontFiles.validatePlatformTypeface(
              ReadingFontFiles.fileForId(readingFontsDirectory, id))
        }
      }

  internal suspend fun removeReadingFont(
      id: String,
      preferences: ReadingPreferences,
  ): ReadingFontStoreSnapshot =
      readingFontStoreMutex.withLock {
        withContext(Dispatchers.IO) {
          require(ReadingFontFiles.isValidId(id)) { "字体标识无效" }
          val current = preferencesStore.data.first()
          val fonts = readImportedFonts(current[importedReadingFontsKey])
          require(fonts.any { it.id == id }) { "导入字体不存在" }
          val normalizedPreferences = preferences.normalized()
          val nextPreferences =
              if (normalizedPreferences.font == ReadingFont.CUSTOM &&
                  normalizedPreferences.customFontId == id)
                  normalizedPreferences.copy(font = ReadingFont.SYSTEM, customFontId = null)
              else normalizedPreferences
          val remaining = fonts.filterNot { it.id == id }
          preferencesStore.edit { values ->
            values[importedReadingFontsKey] = writeImportedFonts(remaining)
            writeReadingPreferences(values, nextPreferences)
          }
          ReadingFontFiles.fileForId(readingFontsDirectory, id).delete()
          ReadingFontStoreSnapshot(remaining, nextPreferences)
        }
      }

  internal suspend fun cleanupUnreferencedReadingFonts() =
      readingFontStoreMutex.withLock {
        withContext(Dispatchers.IO) {
          val directory = readingFontsDirectory
          if (!directory.isDirectory) return@withContext
          val catalog =
              try {
                parseImportedFontsStrict(preferencesStore.data.first()[importedReadingFontsKey])
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                null
              } ?: return@withContext
          val registered = catalog.mapTo(mutableSetOf()) { it.id }
          val staleBefore = System.currentTimeMillis() - ORPHAN_PENDING_MAX_AGE_MS
          directory.listFiles()?.forEach { file ->
            when {
              file.name.startsWith("font-") &&
                  file.name.endsWith(".pending") &&
                  file.lastModified() in 1L..staleBefore -> file.delete()
              file.name.endsWith(".font") -> {
                val id = file.name.removeSuffix(".font")
                if (ReadingFontFiles.isValidId(id) && id !in registered) file.delete()
              }
            }
          }
        }
      }

  private fun queryDisplayName(uri: Uri): String? =
      runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                  if (cursor.moveToFirst())
                      cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                  else null
                }
          }
          .getOrNull()

  private fun readImportedFonts(raw: String?): List<ImportedReadingFont> =
      runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length())
                .mapNotNull { index ->
                  val item = array.optJSONObject(index) ?: return@mapNotNull null
                  ImportedReadingFont(item.optString("id"), item.optString("name")).normalized()
                }
                .distinctBy { it.id }
                .take(ReadingFontFiles.MAX_IMPORTED_FONTS)
          }
          .getOrDefault(emptyList())

  private fun parseImportedFontsStrict(raw: String?): List<ImportedReadingFont>? {
    if (raw == null) return emptyList()
    return try {
      val array = JSONArray(raw)
      if (array.length() > ReadingFontFiles.MAX_IMPORTED_FONTS) return null
      val fonts = mutableListOf<ImportedReadingFont>()
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: return null
        val font =
            ImportedReadingFont(item.optString("id"), item.optString("name")).normalized()
                ?: return null
        if (fonts.any { it.id == font.id }) return null
        fonts += font
      }
      fonts
    } catch (_: Exception) {
      null
    }
  }

  private fun writeImportedFonts(fonts: List<ImportedReadingFont>): String =
      JSONArray()
          .apply {
            fonts.forEach { put(JSONObject().put("id", it.id).put("name", it.displayName)) }
          }
          .toString()

  private fun writeReadingPreferences(values: MutablePreferences, value: ReadingPreferences) {
    val normalized = value.normalized()
    values[readingFontSize] = normalized.fontSizeSp
    values[readingLineHeight] = normalized.lineHeightMultiplier
    values[readingParagraphSpacing] = normalized.paragraphSpacingDp
    values[readingFontFamily] = ReadingFont.storedValue(normalized.font, normalized.customFontId)
  }

  suspend fun setHomeLayout(value: HomeLayout) {
    preferencesStore.edit { it[homeLayout] = value.storedValue }
  }

  suspend fun getContentBlocking(): ContentBlockingConfig =
    withContext(Dispatchers.IO) {
      decodeContentBlocking(preferencesStore.data.first()[contentBlockingConfigKey])
    }

  suspend fun updateContentBlocking(action: ContentBlockingAction): ContentBlockingMutation =
    withContext(Dispatchers.IO) {
      if (action == ContentBlockingAction.Reload)
        return@withContext ContentBlockingMutation(getContentBlocking(), changed = false)
      var result: ContentBlockingMutation? = null
      preferencesStore.edit { preferences ->
        val current = decodeContentBlocking(preferences[contentBlockingConfigKey])
        val mutation = reduceContentBlocking(current, action)
        if (mutation.changed) preferences[contentBlockingConfigKey] = encodeContentBlocking(mutation.config)
        result = mutation
      }
      checkNotNull(result)
    }

  suspend fun getHomeLayout(): HomeLayout =
      HomeLayout.fromStoredValue(preferencesStore.data.first()[homeLayout])

  suspend fun setGateway(v: String) {
    preferencesStore.edit { it[gateway] = v }
  }

  suspend fun getGateway() = preferencesStore.data.first()[gateway] ?: ""

  suspend fun saveSettings(themeValue: String, noImagesValue: Boolean, gatewayValue: String) {
    preferencesStore.edit {
      it[theme] = themeValue
      it[noImages] = noImagesValue
      it[gateway] = gatewayValue
    }
  }

  suspend fun setNoImages(v: Boolean) {
    preferencesStore.edit { it[noImages] = v }
  }

  suspend fun getNoImages() = preferencesStore.data.first()[noImages] ?: false

  suspend fun clearHistory() {
    preferencesStore.edit { it[hist] = "[]" }
  }
}

private const val ORPHAN_PENDING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

internal fun encodeHistoryStory(story: Story): String =
    JSONObject()
        .put("id", story.id)
        .put("source", story.source.name)
        .put("title", story.title)
        .put("excerpt", story.excerpt)
        .put("body", story.body)
        .put("author", story.author)
        .put("date", story.date)
        .put("image", story.image)
        .put("url", story.url)
        .put("appId", story.appId)
        .put("publishedAt", story.publishedAt)
        .put("detailNotice", story.detailNotice)
        .put("channel", story.channel?.name)
        .put(
            "authorProfile",
            story.authorProfile?.let { profile ->
              JSONObject()
                  .put("name", profile.name)
                  .put("avatarUrl", profile.avatarUrl)
                  .put("level", profile.level)
                  .put("location", profile.location)
                  .put("userId", profile.userId)
                  .put("bio", profile.bio)
            },
        )
        .put(
            "sections",
            JSONArray().apply { story.sections.forEach { put(encodeHistoryLabel(it)) } },
        )
        .put(
            "hashtags",
            JSONArray().apply { story.hashtags.forEach { put(encodeHistoryLabel(it)) } },
        )
        .toString()

internal fun decodeHistoryStory(raw: String): Story? =
    runCatching {
          JSONObject(raw).let { value ->
            val profile =
                value.optJSONObject("authorProfile")?.let { stored ->
                  val name = historyString(stored.opt("name"))
                  val id = historyString(stored.opt("userId"))
                  if (name == null && id == null) null
                  else
                      AuthorProfile(
                          name.orEmpty(),
                          historyString(stored.opt("avatarUrl")),
                          historyString(stored.opt("level")),
                          historyString(stored.opt("location")),
                          id,
                          historyString(stored.opt("bio")),
                      )
                }
            Story(
                value.getString("id"),
                Source.valueOf(value.getString("source")),
                value.getString("title"),
                historyString(value.opt("excerpt")).orEmpty(),
                historyString(value.opt("body")).orEmpty(),
                historyString(value.opt("author")).orEmpty(),
                historyString(value.opt("date")).orEmpty(),
                historyString(value.opt("image")),
                historyString(value.opt("url")),
                value.optInt("appId").takeIf { it > 0 },
                value.optLong("publishedAt").takeIf { it > 0 },
                historyString(value.opt("detailNotice")),
                historyString(value.opt("channel"))?.let(Channel::valueOf),
                authorProfile = profile,
                sections = decodeHistoryLabels(value.optJSONArray("sections")),
                hashtags = decodeHistoryLabels(value.optJSONArray("hashtags")),
            )
          }
        }
        .getOrNull()

private fun encodeHistoryLabel(label: ArticleLabel) =
    JSONObject().put("id", label.id).put("name", label.name).put("imageUrl", label.imageUrl)

private fun decodeHistoryLabels(array: JSONArray?): List<ArticleLabel> =
    (0 until (array?.length() ?: 0))
        .mapNotNull { index ->
          val value = array?.optJSONObject(index) ?: return@mapNotNull null
          val name = historyString(value.opt("name")) ?: return@mapNotNull null
          val id = historyString(value.opt("id")).orEmpty()
          ArticleLabel(id, name, historyString(value.opt("imageUrl")))
        }
        .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }

private fun historyString(value: Any?): String? =
    when (value) {
      null,
      JSONObject.NULL -> null
      is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
      is Number -> value.toString()
      else -> null
    }
