package cn.heyread.app.data.model

import java.util.Locale

/** Immutable result of matching one already-loaded Story list against local block rules. */
data class ContentBlockingProjection(
  val items: List<Story>,
  val matchedCount: Int,
  val tailBlocked: Boolean,
  val signature: String,
)

/** Pure local matcher. It deliberately reads only list metadata, never body or detail content. */
object ContentBlockingMatcher {
  fun project(
    raw: List<Story>,
    config: ContentBlockingConfig,
    scope: ContentBlockingScope,
  ): ContentBlockingProjection {
    val rules = compiledRules(config, scope)
    val signature = effectiveSignature(config, scope, rules.map { it.signaturePart })
    if (rules.isEmpty()) return ContentBlockingProjection(raw, 0, false, signature)
    val blocked =
      raw.map { story ->
        val title = story.title.lowercase(Locale.ROOT)
        val excerpt = story.excerpt.lowercase(Locale.ROOT)
        rules.any { matches(it, story, title, excerpt) }
      }
    return ContentBlockingProjection(
      items = raw.filterIndexed { index, _ -> !blocked[index] },
      matchedCount = blocked.count { it },
      tailBlocked = blocked.lastOrNull() == true,
      signature = signature,
    )
  }

  fun effectiveSignature(config: ContentBlockingConfig, scope: ContentBlockingScope): String {
    val rules = compiledRules(config, scope).map { it.signaturePart }
    return effectiveSignature(config, scope, rules)
  }

  private data class CompiledRule(
    val kind: ContentBlockKind,
    val source: Source?,
    val value: String,
  ) {
    val signaturePart: String = "${kind.name}:${source?.name.orEmpty()}:$value"
  }

  private fun compiledRules(
    config: ContentBlockingConfig,
    scope: ContentBlockingScope,
  ): List<CompiledRule> =
    effectiveRules(config, scope)
      .mapNotNull { rule ->
        val value =
          when (rule.kind) {
            ContentBlockKind.KEYWORD -> rule.value.lowercase(Locale.ROOT)
            else -> canonicalId(rule.value)
          } ?: return@mapNotNull null
        CompiledRule(
          kind = rule.kind,
          source = rule.source.takeUnless { rule.kind == ContentBlockKind.KEYWORD },
          value = value,
        )
      }
      .distinctBy { it.signaturePart }

  private fun effectiveRules(
    config: ContentBlockingConfig,
    scope: ContentBlockingScope,
  ): List<ContentBlockRule> {
    if (!config.enabled) return emptyList()
    if (scope == ContentBlockingScope.FAVORITES && !config.applyToFavorites) return emptyList()
    if (scope == ContentBlockingScope.HISTORY && !config.applyToHistory) return emptyList()
    return config.rules.filter { rule ->
      if (!rule.enabled) return@filter false
      when (rule.kind) {
        ContentBlockKind.KEYWORD -> rule.value.isNotEmpty()
        ContentBlockKind.AUTHOR,
        ContentBlockKind.SECTION,
        ContentBlockKind.TOPIC -> rule.source == Source.HEYBOX && canonicalId(rule.value) != null
      }
    }
  }

  private fun effectiveSignature(
    config: ContentBlockingConfig,
    scope: ContentBlockingScope,
    rules: List<String>,
  ): String = buildString {
    val scopeEnabled =
      config.enabled &&
        (scope != ContentBlockingScope.FAVORITES || config.applyToFavorites) &&
        (scope != ContentBlockingScope.HISTORY || config.applyToHistory)
    appendLengthPrefixed(scopeEnabled.toString())
    appendLengthPrefixed(scope.name)
    rules.sorted().forEach { appendLengthPrefixed(it) }
  }

  private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length).append(':').append(value)
  }

  private fun matches(rule: CompiledRule, story: Story, title: String, excerpt: String): Boolean =
    when (rule.kind) {
      ContentBlockKind.KEYWORD -> title.contains(rule.value) || excerpt.contains(rule.value)
      ContentBlockKind.AUTHOR ->
        story.source == Source.HEYBOX &&
          rule.source == story.source &&
          canonicalId(story.authorProfile?.userId) == canonicalId(rule.value)
      ContentBlockKind.SECTION ->
        story.source == Source.HEYBOX &&
          rule.source == story.source &&
          story.sections.any { canonicalId(it.id) == canonicalId(rule.value) }
      ContentBlockKind.TOPIC ->
        story.source == Source.HEYBOX &&
          rule.source == story.source &&
          story.hashtags.any { canonicalId(it.id) == canonicalId(rule.value) }
    }

  private fun canonicalId(value: String?): String? {
    val exact = value ?: return null
    if (exact.isEmpty() || exact.any { it !in '0'..'9' }) return null
    val canonical = exact.dropWhile { it == '0' }.ifEmpty { "0" }
    return canonical.takeUnless { it == "0" }
  }
}

enum class ContentBlockingScope {
  FEED,
  FAVORITES,
  HISTORY,
}
