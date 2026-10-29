package cn.heyread.app.data.local

import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.canonicalContentBlockTargetId
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal const val CONTENT_BLOCKING_MAX_RULES = 500
internal const val CONTENT_BLOCKING_MAX_KEYWORD_LENGTH = 100
internal const val CONTENT_BLOCKING_MAX_LABEL_LENGTH = 60
internal const val CONTENT_BLOCKING_MAX_JSON_BYTES = 256 * 1024

data class ContentBlockingMutation(
    val config: ContentBlockingConfig,
    val changed: Boolean,
    val addedCount: Int = 0,
    val addedRules: List<ContentBlockRule> = emptyList(),
)

internal fun decodeContentBlocking(raw: String?): ContentBlockingConfig {
  if (raw == null) return ContentBlockingConfig()
  require(raw.toByteArray(Charsets.UTF_8).size <= CONTENT_BLOCKING_MAX_JSON_BYTES) {
    "内容屏蔽配置超过256KiB。"
  }
  val root = JSONObject(raw)
  requireKnownKeys(root, setOf("version", "enabled", "applyToFavorites", "applyToHistory", "rules"))
  require(root.opt("version") == 1) { "内容屏蔽配置版本不受支持。" }
  val enabled = requiredBoolean(root, "enabled")
  val applyToFavorites = requiredBoolean(root, "applyToFavorites")
  val applyToHistory = requiredBoolean(root, "applyToHistory")
  val rows = root.opt("rules") as? JSONArray ?: error("内容屏蔽配置缺少规则列表。")
  require(rows.length() <= CONTENT_BLOCKING_MAX_RULES) { "内容屏蔽规则超过500条。" }
  val rules =
      (0 until rows.length()).map { index ->
        val row = rows.opt(index) as? JSONObject ?: error("内容屏蔽规则格式无效。")
        requireKnownKeys(row, setOf("id", "kind", "value", "label", "enabled", "source"))
        val id = requiredString(row, "id")
        require(id.matches(UUID_PATTERN)) { "内容屏蔽规则标识无效。" }
        val kind =
            requiredString(row, "kind").let { value ->
              ContentBlockKind.entries.firstOrNull { it.name == value } ?: error("内容屏蔽规则类型无效。")
            }
        val value = requiredString(row, "value")
        val label = requiredString(row, "label")
        val ruleEnabled = requiredBoolean(row, "enabled")
        val sourceValue = row.opt("source")
        val source =
            when (sourceValue) {
              JSONObject.NULL -> null
              is String ->
                  Source.entries.firstOrNull { it.name == sourceValue } ?: error("内容屏蔽规则来源无效。")
              else -> error("内容屏蔽规则来源格式无效。")
            }
        val rule = ContentBlockRule(id, kind, value, label, ruleEnabled, source)
        require(validateStoredRule(rule) == rule) { "内容屏蔽规则未规范化。" }
        rule
      }
  require(rules.map(::ruleIdentity).distinct().size == rules.size) { "内容屏蔽配置含重复规则。" }
  require(rules.map(ContentBlockRule::id).distinct().size == rules.size) { "内容屏蔽配置含重复标识。" }
  return ContentBlockingConfig(enabled, applyToFavorites, applyToHistory, rules)
}

internal fun encodeContentBlocking(config: ContentBlockingConfig): String {
  validateConfig(config)
  val encoded =
      JSONObject()
          .put("version", 1)
          .put("enabled", config.enabled)
          .put("applyToFavorites", config.applyToFavorites)
          .put("applyToHistory", config.applyToHistory)
          .put(
              "rules",
              JSONArray().apply {
                config.rules.forEach { rule ->
                  put(
                      JSONObject()
                          .put("id", rule.id)
                          .put("kind", rule.kind.name)
                          .put("value", rule.value)
                          .put("label", rule.label)
                          .put("enabled", rule.enabled)
                          .put("source", rule.source?.name ?: JSONObject.NULL),
                  )
                }
              },
          )
          .toString()
  require(encoded.toByteArray(Charsets.UTF_8).size <= CONTENT_BLOCKING_MAX_JSON_BYTES) {
    "内容屏蔽配置超过256KiB。"
  }
  return encoded
}

internal fun reduceContentBlocking(
    config: ContentBlockingConfig,
    action: ContentBlockingAction,
): ContentBlockingMutation {
  val updated =
      when (action) {
        is ContentBlockingAction.SetEnabled -> config.copy(enabled = action.enabled)
        is ContentBlockingAction.SetApplyToFavorites ->
            config.copy(applyToFavorites = action.enabled)
        is ContentBlockingAction.SetApplyToHistory -> config.copy(applyToHistory = action.enabled)
        is ContentBlockingAction.AddKeywords -> {
          val keywords =
              action.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
          require(keywords.isNotEmpty()) { "请输入至少一个关键词。" }
          require(keywords.all { it.length <= CONTENT_BLOCKING_MAX_KEYWORD_LENGTH }) {
            "每个关键词最多100个字符。"
          }
          val existing = config.rules.mapTo(mutableSetOf(), ::ruleIdentity)
          val newRules =
              keywords
                  .distinctBy { it.lowercase(Locale.ROOT) }
                  .filterNot { identity(ContentBlockKind.KEYWORD, null, it) in existing }
                  .map { keyword ->
                    ContentBlockRule(
                        id = UUID.randomUUID().toString(),
                        kind = ContentBlockKind.KEYWORD,
                        value = keyword,
                    )
                  }
          require(config.rules.size + newRules.size <= CONTENT_BLOCKING_MAX_RULES) {
            "最多保存500条屏蔽规则。"
          }
          return ContentBlockingMutation(
              config.copy(rules = config.rules + newRules),
              newRules.isNotEmpty(),
              newRules.size,
              newRules,
          )
        }
        is ContentBlockingAction.AddIdentity -> {
          require(action.kind != ContentBlockKind.KEYWORD) { "身份规则类型无效。" }
          val id = canonicalIdentityId(action.targetId.trim())
          val label = action.label.trim()
          require(label.length <= CONTENT_BLOCKING_MAX_LABEL_LENGTH) { "备注最多60个字符。" }
          val key = identity(action.kind, Source.HEYBOX, id)
          if (config.rules.any { ruleIdentity(it) == key })
              return ContentBlockingMutation(config, changed = false)
          require(config.rules.size < CONTENT_BLOCKING_MAX_RULES) { "最多保存500条屏蔽规则。" }
          val addedRule =
              ContentBlockRule(
                  id = UUID.randomUUID().toString(),
                  kind = action.kind,
                  value = id,
                  label = label,
                  source = Source.HEYBOX,
              )
          val updatedConfig = config.copy(rules = config.rules + addedRule)
          validateConfig(updatedConfig)
          return ContentBlockingMutation(
              updatedConfig,
              changed = true,
              addedCount = 1,
              addedRules = listOf(addedRule),
          )
        }
        is ContentBlockingAction.EditKeyword -> {
          val keyword = action.text.trim()
          require(keyword.isNotEmpty()) { "关键词不能为空。" }
          require('\n' !in keyword && '\r' !in keyword) { "请一次编辑一个关键词。" }
          require(keyword.length <= CONTENT_BLOCKING_MAX_KEYWORD_LENGTH) { "每个关键词最多100个字符。" }
          val index =
              config.rules.indexOfFirst {
                it.id == action.ruleId && it.kind == ContentBlockKind.KEYWORD
              }
          require(index >= 0) { "找不到要编辑的关键词。" }
          val current = config.rules[index]
          val duplicate =
              config.rules.withIndex().any {
                it.index != index &&
                    ruleIdentity(it.value) == identity(ContentBlockKind.KEYWORD, null, keyword)
              }
          if (duplicate) return ContentBlockingMutation(config, changed = false)
          config.copy(
              rules =
                  config.rules.toMutableList().also { it[index] = current.copy(value = keyword) })
        }
        is ContentBlockingAction.SetRuleEnabled -> {
          val index = config.rules.indexOfFirst { it.id == action.ruleId }
          require(index >= 0) { "找不到要修改的规则。" }
          config.copy(
              rules =
                  config.rules.toMutableList().also {
                    it[index] = it[index].copy(enabled = action.enabled)
                  })
        }
        is ContentBlockingAction.RemoveRule -> {
          require(config.rules.any { it.id == action.ruleId }) { "找不到要删除的规则。" }
          config.copy(rules = config.rules.filterNot { it.id == action.ruleId })
        }
        is ContentBlockingAction.UndoAddedRules -> {
          require(action.expected.isNotEmpty()) { "撤销已失效。" }
          require(action.expected.map(ContentBlockRule::id).distinct().size == action.expected.size) {
            "撤销已失效。"
          }
          val currentById = config.rules.associateBy(ContentBlockRule::id)
          require(action.expected.all { currentById[it.id] == it }) { "撤销已失效。" }
          val expectedIds = action.expected.mapTo(mutableSetOf(), ContentBlockRule::id)
          config.copy(rules = config.rules.filterNot { it.id in expectedIds })
        }
        is ContentBlockingAction.MergeImportedRules -> {
          require(config.rules == action.expectedRules) {
            "规则已变化，请重新预览导入。"
          }
          val imported = action.rules.map { validateStoredRule(it) }
          require(imported == action.rules) { "内容屏蔽导入规则格式无效。" }
          require(imported.map(::contentBlockRuleIdentity).distinct().size == imported.size) {
            "内容屏蔽导入文件含重复规则。"
          }
          val existing = config.rules.mapTo(mutableSetOf(), ::contentBlockRuleIdentity)
          val added = imported.filterNot { contentBlockRuleIdentity(it) in existing }
          require(config.rules.size + added.size <= CONTENT_BLOCKING_MAX_RULES) {
            "最多保存500条屏蔽规则。"
          }
          if (added.isEmpty()) return ContentBlockingMutation(config, changed = false)
          val merged = config.copy(rules = config.rules + added)
          validateConfig(merged)
          return ContentBlockingMutation(merged, changed = true, addedCount = added.size, addedRules = added)
        }
        ContentBlockingAction.ClearRules -> config.copy(rules = emptyList())
        ContentBlockingAction.Reload -> return ContentBlockingMutation(config, changed = false)
      }
  validateConfig(updated)
  return ContentBlockingMutation(updated, changed = updated != config)
}

private fun validateConfig(config: ContentBlockingConfig) {
  require(config.rules.size <= CONTENT_BLOCKING_MAX_RULES) { "最多保存500条屏蔽规则。" }
  require(config.rules.map(ContentBlockRule::id).distinct().size == config.rules.size) {
    "内容屏蔽规则标识重复。"
  }
  require(config.rules.map(::ruleIdentity).distinct().size == config.rules.size) { "相同内容的规则已存在。" }
  config.rules.forEach { require(validateStoredRule(it) == it) { "内容屏蔽规则格式无效。" } }
}

internal fun validateStoredRule(rule: ContentBlockRule): ContentBlockRule {
  require(rule.id.matches(UUID_PATTERN)) { "内容屏蔽规则标识无效。" }
  return when (rule.kind) {
    ContentBlockKind.KEYWORD -> {
      val value = rule.value.trim()
      require(value.isNotEmpty() && value.length <= CONTENT_BLOCKING_MAX_KEYWORD_LENGTH) {
        "关键词须为1至100个字符。"
      }
      require('\n' !in value && '\r' !in value) { "关键词不能包含换行。" }
      require(rule.label.isEmpty() && rule.source == null) { "关键词规则字段无效。" }
      rule.copy(value = value)
    }
    ContentBlockKind.AUTHOR,
    ContentBlockKind.SECTION,
    ContentBlockKind.TOPIC -> {
      val value = canonicalIdentityId(rule.value)
      require(rule.source == Source.HEYBOX) { "身份规则必须来自小黑盒。" }
      require(rule.label.length <= CONTENT_BLOCKING_MAX_LABEL_LENGTH) { "备注最多60个字符。" }
      rule.copy(value = value, label = rule.label.trim())
    }
  }
}

private fun canonicalIdentityId(raw: String): String = canonicalContentBlockTargetId(raw)

private fun ruleIdentity(rule: ContentBlockRule) = contentBlockRuleIdentity(rule)

internal fun contentBlockRuleIdentity(rule: ContentBlockRule) = identity(rule.kind, rule.source, rule.value)

private fun identity(kind: ContentBlockKind, source: Source?, value: String): String =
    "${kind.name}:${source?.name.orEmpty()}:${if (kind == ContentBlockKind.KEYWORD) value.lowercase(Locale.ROOT) else value}"

private fun requireKnownKeys(json: JSONObject, allowed: Set<String>) {
  val keys = json.keys()
  while (keys.hasNext()) require(keys.next() in allowed) { "内容屏蔽配置包含未知字段。" }
}

private fun requiredString(json: JSONObject, key: String): String =
    json.opt(key) as? String ?: error("内容屏蔽配置字段无效：$key。")

private fun requiredBoolean(json: JSONObject, key: String): Boolean =
    json.opt(key) as? Boolean ?: error("内容屏蔽配置字段无效：$key。")

private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
