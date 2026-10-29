package cn.heyread.app.data.local

import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.Source
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class ContentBlockingImportDocument(
    val rules: List<ContentBlockRule>,
    val totalCount: Int,
    val duplicateCount: Int,
)

data class ContentBlockingImportPreview(
    val baseRules: List<ContentBlockRule>,
    val addedCount: Int,
    val duplicateCount: Int,
    val addedByKind: Map<ContentBlockKind, Int>,
)

private const val TRANSFER_FORMAT = "heyread-content-blocking"
private const val TRANSFER_VERSION = 1
private const val MAX_JSON_NESTING_DEPTH = 16

fun encodeContentBlockingRules(rules: List<ContentBlockRule>): ByteArray {
  require(rules.size <= CONTENT_BLOCKING_MAX_RULES) { "内容屏蔽导出规则超过500条。" }
  val normalized = rules.map(::validateStoredRule)
  require(normalized == rules) { "内容屏蔽导出规则格式无效。" }
  require(rules.map(ContentBlockRule::id).distinct().size == rules.size) {
    "内容屏蔽导出规则标识重复。"
  }
  require(rules.map(::contentBlockRuleIdentity).distinct().size == rules.size) {
    "内容屏蔽导出规则含重复内容。"
  }
  val root =
      JSONObject()
          .put("format", TRANSFER_FORMAT)
          .put("version", TRANSFER_VERSION)
          .put(
              "rules",
              JSONArray().apply {
                rules.forEach { rule ->
                  put(
                      JSONObject()
                          .put("kind", rule.kind.name)
                          .put("value", rule.value)
                          .put("label", rule.label)
                          .put("enabled", rule.enabled)
                          .put("source", rule.source?.name ?: JSONObject.NULL))
                }
              },
          )
  val bytes = root.toString().toByteArray(Charsets.UTF_8)
  require(bytes.size <= CONTENT_BLOCKING_MAX_JSON_BYTES) { "内容屏蔽导出文件超过256KiB。" }
  return bytes
}

fun decodeContentBlockingRules(bytes: ByteArray): ContentBlockingImportDocument {
  require(bytes.size <= CONTENT_BLOCKING_MAX_JSON_BYTES) { "内容屏蔽导入文件超过256KiB。" }
  val decoded =
      try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
      } catch (_: CharacterCodingException) {
        error("内容屏蔽导入文件不是有效UTF-8。")
      }
  val tokener = JSONTokener(decoded)
  StrictJsonSyntax(decoded).validate()
  val root =
      try {
        tokener.nextValue() as? JSONObject ?: error("内容屏蔽导入文件格式无效。")
      } catch (_: JSONException) {
        error("内容屏蔽导入文件JSON无效。")
      }
  requireTransferKeys(root, setOf("format", "version", "rules"))
  require(root.opt("format") == TRANSFER_FORMAT) { "内容屏蔽导入文件格式不受支持。" }
  val version = root.opt("version")
  require(
      (version is Int || version is Long) &&
          (version as Number).toLong() == TRANSFER_VERSION.toLong(),
  ) { "内容屏蔽导入文件版本不受支持。" }
  val rows = root.opt("rules") as? JSONArray ?: error("内容屏蔽导入文件缺少规则列表。")
  require(rows.length() <= CONTENT_BLOCKING_MAX_RULES) { "内容屏蔽导入文件超过500条规则。" }

  val seen = mutableSetOf<String>()
  val rules = mutableListOf<ContentBlockRule>()
  var duplicateCount = 0
  repeat(rows.length()) { index ->
    val row = rows.opt(index) as? JSONObject ?: error("内容屏蔽导入规则格式无效。")
    requireTransferKeys(row, setOf("kind", "value", "label", "enabled", "source"))
    val kindName = row.opt("kind") as? String ?: error("内容屏蔽导入规则类型无效。")
    val kind =
        ContentBlockKind.entries.firstOrNull { it.name == kindName }
            ?: error("内容屏蔽导入规则类型无效。")
    val value = row.opt("value") as? String ?: error("内容屏蔽导入规则内容无效。")
    val label = row.opt("label") as? String ?: error("内容屏蔽导入规则备注无效。")
    val enabled =
        row.opt("enabled") as? Boolean ?: error("内容屏蔽导入规则启停状态无效。")
    val source =
        when (val rawSource = row.opt("source")) {
          JSONObject.NULL -> null
          is String -> Source.entries.firstOrNull { it.name == rawSource }
              ?: error("内容屏蔽导入规则来源无效。")
          else -> error("内容屏蔽导入规则来源无效。")
        }
    val candidate =
        validateStoredRule(
            ContentBlockRule(
                id = UUID.randomUUID().toString(),
                kind = kind,
                value = value,
                label = label,
                enabled = enabled,
                source = source,
            ),
        )
    val identity = contentBlockRuleIdentity(candidate)
    if (!seen.add(identity)) duplicateCount++ else rules += candidate
  }
  return ContentBlockingImportDocument(rules, rows.length(), duplicateCount)
}

fun previewContentBlockingImport(
    config: ContentBlockingConfig,
    document: ContentBlockingImportDocument,
): ContentBlockingImportPreview {
  require(document.rules.size <= CONTENT_BLOCKING_MAX_RULES) {
    "内容屏蔽导入文件超过500条规则。"
  }
  val existing = config.rules.mapTo(mutableSetOf(), ::contentBlockRuleIdentity)
  val added = document.rules.filterNot { contentBlockRuleIdentity(it) in existing }
  require(config.rules.size + added.size <= CONTENT_BLOCKING_MAX_RULES) {
    "最多保存500条屏蔽规则。"
  }
  val alreadyPresent = document.rules.size - added.size
  return ContentBlockingImportPreview(
      baseRules = config.rules.toList(),
      addedCount = added.size,
      duplicateCount = document.duplicateCount + alreadyPresent,
      addedByKind =
          ContentBlockKind.entries.associateWith { kind -> added.count { it.kind == kind } },
  )
}

private fun requireTransferKeys(json: JSONObject, allowed: Set<String>) {
  val keys = json.keys()
  while (keys.hasNext()) require(keys.next() in allowed) { "内容屏蔽导入文件包含未知字段。" }
  require(allowed.all { json.has(it) }) { "内容屏蔽导入文件缺少必要字段。" }
}

/** Reject JSONTokener's permissive extensions and duplicate object keys before reading values. */
private class StrictJsonSyntax(private val input: String) {
  private var index = 0

  fun validate() {
    skipWhitespace()
    parseValue(nestingDepth = 0)
    skipWhitespace()
    require(index == input.length) { "内容屏蔽导入文件包含尾随内容。" }
  }

  private fun parseValue(nestingDepth: Int) {
    skipWhitespace()
    when (peek()) {
      '{' -> {
        require(nestingDepth < MAX_JSON_NESTING_DEPTH) { "内容屏蔽导入文件嵌套过深。" }
        parseObject(nestingDepth + 1)
      }
      '[' -> {
        require(nestingDepth < MAX_JSON_NESTING_DEPTH) { "内容屏蔽导入文件嵌套过深。" }
        parseArray(nestingDepth + 1)
      }
      '"' -> parseString()
      't' -> parseLiteral("true")
      'f' -> parseLiteral("false")
      'n' -> parseLiteral("null")
      '-', in '0'..'9' -> parseNumber()
      else -> error("内容屏蔽导入文件JSON无效。")
    }
  }

  private fun parseObject(nestingDepth: Int) {
    consume('{')
    skipWhitespace()
    if (peek() == '}') {
      index++
      return
    }
    val keys = mutableSetOf<String>()
    while (true) {
      skipWhitespace()
      require(peek() == '"') { "内容屏蔽导入文件JSON无效。" }
      val key = parseString()
      require(keys.add(key)) { "内容屏蔽导入文件包含重复字段。" }
      skipWhitespace()
      consume(':')
      parseValue(nestingDepth)
      skipWhitespace()
      when (peek()) {
        ',' -> index++
        '}' -> {
          index++
          return
        }
        else -> error("内容屏蔽导入文件JSON无效。")
      }
    }
  }

  private fun parseArray(nestingDepth: Int) {
    consume('[')
    skipWhitespace()
    if (peek() == ']') {
      index++
      return
    }
    while (true) {
      parseValue(nestingDepth)
      skipWhitespace()
      when (peek()) {
        ',' -> index++
        ']' -> {
          index++
          return
        }
        else -> error("内容屏蔽导入文件JSON无效。")
      }
    }
  }

  private fun parseString(): String {
    consume('"')
    val result = StringBuilder()
    while (index < input.length) {
      val char = input[index++]
      when {
        char == '"' -> {
          val value = result.toString()
          requireValidSurrogates(value)
          return value
        }
        char == '\\' -> {
          require(index < input.length) { "内容屏蔽导入文件JSON无效。" }
          when (val escaped = input[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
              require(index + 4 <= input.length) { "内容屏蔽导入文件JSON无效。" }
              val hex = input.substring(index, index + 4)
              require(hex.all { it.digitToIntOrNull(16) != null }) {
                "内容屏蔽导入文件JSON无效。"
              }
              result.append(hex.toInt(16).toChar())
              index += 4
            }
            else -> error("内容屏蔽导入文件JSON无效。")
          }
        }
        char.code < 0x20 -> error("内容屏蔽导入文件JSON无效。")
        else -> result.append(char)
      }
    }
    error("内容屏蔽导入文件JSON无效。")
  }

  private fun parseLiteral(value: String) {
    require(input.startsWith(value, index)) { "内容屏蔽导入文件JSON无效。" }
    index += value.length
  }

  private fun parseNumber() {
    if (peek() == '-') index++
    when {
      peek() == '0' -> {
        index++
        require(peek() !in '0'..'9') { "内容屏蔽导入文件JSON无效。" }
      }
      peek() in '1'..'9' -> while (peek() in '0'..'9') index++
      else -> error("内容屏蔽导入文件JSON无效。")
    }
    if (peek() == '.') {
      index++
      require(peek() in '0'..'9') { "内容屏蔽导入文件JSON无效。" }
      while (peek() in '0'..'9') index++
    }
    if (peek() == 'e' || peek() == 'E') {
      index++
      if (peek() == '+' || peek() == '-') index++
      require(peek() in '0'..'9') { "内容屏蔽导入文件JSON无效。" }
      while (peek() in '0'..'9') index++
    }
  }

  private fun consume(expected: Char) {
    require(peek() == expected) { "内容屏蔽导入文件JSON无效。" }
    index++
  }

  private fun skipWhitespace() {
    while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') index++
  }

  private fun peek(): Char = input.getOrElse(index) { '\u0000' }

  private fun requireValidSurrogates(value: String) {
    var charIndex = 0
    while (charIndex < value.length) {
      when {
        value[charIndex].isHighSurrogate() -> {
          require(charIndex + 1 < value.length && value[charIndex + 1].isLowSurrogate()) {
            "内容屏蔽导入文件包含无效Unicode字符。"
          }
          charIndex += 2
        }
        value[charIndex].isLowSurrogate() -> error("内容屏蔽导入文件包含无效Unicode字符。")
        else -> charIndex++
      }
    }
  }
}
