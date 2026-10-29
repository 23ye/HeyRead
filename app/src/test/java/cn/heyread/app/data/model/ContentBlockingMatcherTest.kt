package cn.heyread.app.data.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockingMatcherTest {
  @Test
  fun matchesLiteralKeywordSeparatelyAgainstTitleAndExcerpt() {
    val titleMatch = story("1", title = "BLUE fox")
    val excerptMatch = story("2", excerpt = "A blue fox arrives")
    val crossFieldOnly = story("3", title = "Blu", excerpt = "e fox")
    val config = config(ContentBlockKind.KEYWORD, "blue fox")

    val result =
      ContentBlockingMatcher.project(
        listOf(titleMatch, excerptMatch, crossFieldOnly),
        config,
        ContentBlockingScope.FEED,
      )

    assertEquals(listOf(crossFieldOnly), result.items)
    assertEquals(2, result.matchedCount)
    assertFalse(result.tailBlocked)

    val onlyBodyAndAuthor =
      story("4", title = "ordinary", excerpt = "text")
        .copy(body = "spoiler in body", author = "spoiler author")
    assertEquals(
      listOf(onlyBodyAndAuthor),
      ContentBlockingMatcher.project(
          listOf(onlyBodyAndAuthor),
          config(ContentBlockKind.KEYWORD, "spoiler"),
          ContentBlockingScope.FEED,
        )
        .items,
    )
  }

  @Test
  fun keywordCaseFoldUsesLocaleRoot() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      val result =
        ContentBlockingMatcher.project(
          listOf(story("1", title = "ISTANBUL")),
          config(ContentBlockKind.KEYWORD, "istanbul"),
          ContentBlockingScope.FEED,
        )
      assertEquals(1, result.matchedCount)
    } finally {
      Locale.setDefault(original)
    }
  }

  @Test
  fun identityRulesRequireExactHeyboxIdsAndDoNotUseNamesOrTargetContext() {
    val byAuthor = story("1", authorId = "00042", author = "Same name")
    val sameNameDifferentId = story("2", authorId = "43", author = "Same name")
    val missingId = story("3", author = null, authorId = null)
    val steamSameId = story("4", source = Source.STEAM, authorId = "42", author = "Same name")
    val authorResult =
      ContentBlockingMatcher.project(
        listOf(byAuthor, sameNameDifferentId, missingId, steamSameId),
        config(ContentBlockKind.AUTHOR, "42", source = Source.HEYBOX),
        ContentBlockingScope.FEED,
      )
    assertEquals(listOf(sameNameDifferentId, missingId, steamSameId), authorResult.items)

    val longAuthorId = "12345678901234567890123456789012"
    val largeIdStories =
      listOf(
        story("9", authorId = longAuthorId),
        story("10", authorId = null),
        story("11", authorId = "12x"),
        story("12", authorId = "0"),
        story("13", authorId = " $longAuthorId "),
      )
    val largeIdResult =
      ContentBlockingMatcher.project(
        largeIdStories,
        config(ContentBlockKind.AUTHOR, longAuthorId, source = Source.HEYBOX),
        ContentBlockingScope.FEED,
      )
    assertEquals(1, largeIdResult.matchedCount)
    assertFalse(largeIdStories[0] in largeIdResult.items)
    assertEquals(largeIdStories.drop(1), largeIdResult.items)

    val section = ArticleLabel("17", "Same label")
    val sectionResult =
      ContentBlockingMatcher.project(
        listOf(
          story("5", sections = listOf(section)),
          story("6", sections = listOf(ArticleLabel("18", "Same label"))),
        ),
        config(ContentBlockKind.SECTION, "17", source = Source.HEYBOX),
        ContentBlockingScope.FEED,
      )
    assertEquals(1, sectionResult.matchedCount)

    val topic = ArticleLabel("25", "Same topic")
    val topicResult =
      ContentBlockingMatcher.project(
        listOf(
          story("7", hashtags = listOf(topic)),
          story("8", hashtags = listOf(ArticleLabel("26", "Same topic"))),
        ),
        config(ContentBlockKind.TOPIC, "25", source = Source.HEYBOX),
        ContentBlockingScope.FEED,
      )
    assertEquals(1, topicResult.matchedCount)

    val wrongSourceRule =
      ContentBlockingMatcher.project(
        listOf(story("14", sections = listOf(section))),
        config(ContentBlockKind.SECTION, "17", source = Source.STEAM),
        ContentBlockingScope.FEED,
      )
    assertEquals(0, wrongSourceRule.matchedCount)
  }

  @Test
  fun scopeAndDisabledRulesLeaveRawReferencesUntouched() {
    val blocked = story("1", title = "spoiler")
    val visible = story("2", title = "other")
    val raw = listOf(blocked, visible)
    val config = config(ContentBlockKind.KEYWORD, "spoiler")

    val favoriteDefault =
      ContentBlockingMatcher.project(raw, config, ContentBlockingScope.FAVORITES)
    assertEquals(raw, favoriteDefault.items)
    assertSame(blocked, favoriteDefault.items[0])
    assertSame(visible, favoriteDefault.items[1])

    val historyDefault = ContentBlockingMatcher.project(raw, config, ContentBlockingScope.HISTORY)
    assertEquals(raw, historyDefault.items)

    val historyEnabled =
      ContentBlockingMatcher.project(
        raw,
        config.copy(applyToHistory = true),
        ContentBlockingScope.HISTORY,
      )
    assertEquals(listOf(visible), historyEnabled.items)

    val favoritesEnabled =
      ContentBlockingMatcher.project(
        raw,
        config.copy(applyToFavorites = true),
        ContentBlockingScope.FAVORITES,
      )
    assertEquals(listOf(visible), favoritesEnabled.items)

    val disabledRule =
      ContentBlockingMatcher.project(
        raw,
        config.copy(rules = config.rules.map { it.copy(enabled = false) }, applyToFavorites = true),
        ContentBlockingScope.FAVORITES,
      )
    assertEquals(raw, disabledRule.items)

    val disabled =
      ContentBlockingMatcher.project(
        raw,
        config.copy(enabled = false, applyToFavorites = true),
        ContentBlockingScope.FAVORITES,
      )
    assertEquals(raw, disabled.items)
  }

  @Test
  fun duplicateRulesHideOnceAndTailStatusTracksRawTail() {
    val matching = story("1", title = "bad")
    val visible = story("2", title = "good")
    val cfg =
      ContentBlockingConfig(
        rules =
          listOf(
            ContentBlockRule("a", ContentBlockKind.KEYWORD, "bad"),
            ContentBlockRule("b", ContentBlockKind.KEYWORD, "bad"),
          )
      )
    val middle =
      ContentBlockingMatcher.project(listOf(matching, visible), cfg, ContentBlockingScope.FEED)
    assertEquals(1, middle.matchedCount)
    assertFalse(middle.tailBlocked)
    assertEquals(listOf(visible), middle.items)

    val tail =
      ContentBlockingMatcher.project(listOf(visible, matching), cfg, ContentBlockingScope.FEED)
    assertTrue(tail.tailBlocked)

    val fullyBlocked =
      ContentBlockingMatcher.project(
        listOf(matching, matching.copy(id = "3")),
        cfg,
        ContentBlockingScope.FEED,
      )
    assertTrue(fullyBlocked.items.isEmpty())
    assertEquals(2, fullyBlocked.matchedCount)
    assertTrue(fullyBlocked.tailBlocked)
  }

  @Test
  fun signatureIgnoresRuleIdentityAndLabelButTracksEffectiveValueAndScope() {
    val base = config(ContentBlockKind.AUTHOR, "0042", source = Source.HEYBOX)
    val renamed = base.copy(rules = base.rules.map { it.copy(id = "other", label = "new name") })
    assertEquals(
      ContentBlockingMatcher.effectiveSignature(base, ContentBlockingScope.FEED),
      ContentBlockingMatcher.effectiveSignature(renamed, ContentBlockingScope.FEED),
    )
    assertTrue(
      ContentBlockingMatcher.effectiveSignature(base, ContentBlockingScope.FEED) !=
        ContentBlockingMatcher.effectiveSignature(
          base.copy(rules = base.rules.map { it.copy(value = "43") }),
          ContentBlockingScope.FEED,
        )
    )
    assertTrue(
      ContentBlockingMatcher.effectiveSignature(base, ContentBlockingScope.FEED) !=
        ContentBlockingMatcher.effectiveSignature(base, ContentBlockingScope.HISTORY)
    )
    val separatorValue =
      ContentBlockingConfig(
        rules = listOf(ContentBlockRule("x", ContentBlockKind.KEYWORD, "a|KEYWORD::b"))
      )
    val splitValues =
      ContentBlockingConfig(
        rules =
          listOf(
            ContentBlockRule("a", ContentBlockKind.KEYWORD, "a"),
            ContentBlockRule("b", ContentBlockKind.KEYWORD, "b"),
          )
      )
    assertTrue(
      ContentBlockingMatcher.effectiveSignature(separatorValue, ContentBlockingScope.FEED) !=
        ContentBlockingMatcher.effectiveSignature(splitValues, ContentBlockingScope.FEED)
    )
  }

  private fun config(kind: ContentBlockKind, value: String, source: Source? = null) =
    ContentBlockingConfig(rules = listOf(ContentBlockRule("rule-id", kind, value, source = source)))

  private fun story(
    id: String,
    title: String = "",
    excerpt: String = "",
    source: Source = Source.HEYBOX,
    authorId: String? = "1",
    author: String? = "author",
    sections: List<ArticleLabel> = emptyList(),
    hashtags: List<ArticleLabel> = emptyList(),
  ) =
    Story(
      id = id,
      source = source,
      title = title,
      excerpt = excerpt,
      body = "do not inspect this hidden body",
      author = author.orEmpty(),
      date = "",
      authorProfile = author?.let { AuthorProfile(it, userId = authorId) },
      sections = sections,
      hashtags = hashtags,
    )
}
