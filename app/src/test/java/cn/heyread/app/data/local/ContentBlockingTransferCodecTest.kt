package cn.heyread.app.data.local

import cn.heyread.app.data.model.ContentBlockKind
import cn.heyread.app.data.model.ContentBlockRule
import cn.heyread.app.data.model.ContentBlockingAction
import cn.heyread.app.data.model.ContentBlockingConfig
import cn.heyread.app.data.model.Source
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockingTransferCodecTest {
  @Test
  fun exportDecodePreviewAndMergePreserveCurrentOptionsAndImportedRuleState() {
    val currentRule = rule(ContentBlockKind.KEYWORD, "Boss", enabled = false)
    val current =
        ContentBlockingConfig(
            enabled = false,
            applyToFavorites = true,
            applyToHistory = true,
            rules = listOf(currentRule),
        )
    val canonicalExport =
        encodeContentBlockingRules(
            listOf(
                rule(ContentBlockKind.KEYWORD, "boss", enabled = true),
                rule(
                    ContentBlockKind.AUTHOR,
                    "123",
                    "Author note",
                    enabled = false,
                    source = Source.HEYBOX,
                ),
            ),
        )
    val root = JSONObject(canonicalExport.toString(Charsets.UTF_8))
    root.getJSONArray("rules").put(
        JSONObject()
            .put("kind", ContentBlockKind.AUTHOR.name)
            .put("value", "000123")
            .put("label", "ignored duplicate")
            .put("enabled", true)
            .put("source", Source.HEYBOX.name),
    )
    val encoded = root.toString().toByteArray(Charsets.UTF_8)
    assertEquals(setOf("format", "version", "rules"), root.keys().asSequence().toSet())
    assertEquals(
        setOf("kind", "value", "label", "enabled", "source"),
        (root.getJSONArray("rules").get(1) as JSONObject).keys().asSequence().toSet(),
    )

    val document = decodeContentBlockingRules(encoded)
    assertEquals(3, document.totalCount)
    assertEquals(1, document.duplicateCount)
    assertEquals(2, document.rules.size)
    val preview = previewContentBlockingImport(current, document)
    assertEquals(1, preview.addedCount)
    assertEquals(2, preview.duplicateCount)
    assertEquals(1, preview.addedByKind[ContentBlockKind.AUTHOR])
    assertEquals(current.rules, preview.baseRules)

    val mutation =
        reduceContentBlocking(
            current,
            ContentBlockingAction.MergeImportedRules(document.rules, preview.baseRules),
        )
    assertTrue(mutation.changed)
    assertEquals(1, mutation.addedCount)
    assertEquals(mutation.addedRules, listOf(mutation.config.rules.last()))
    assertEquals(listOf(false, false), mutation.config.rules.map { it.enabled })
    assertFalse(mutation.config.enabled)
    assertTrue(mutation.config.applyToFavorites)
    assertTrue(mutation.config.applyToHistory)

    val repeated =
        reduceContentBlocking(
            mutation.config,
            ContentBlockingAction.MergeImportedRules(document.rules, mutation.config.rules),
        )
    assertFalse(repeated.changed)
    assertTrue(repeated.addedRules.isEmpty())

    val emoji =
        decodeContentBlockingRules(
            encodeContentBlockingRules(listOf(rule(ContentBlockKind.KEYWORD, "🚀"))),
        )
    assertEquals("🚀", emoji.rules.single().value)
  }

  @Test
  fun invalidOversizedAndStaleImportsFailWithoutChangingCurrentRules() {
    val original =
        ContentBlockingConfig(
            applyToFavorites = true,
            rules = listOf(rule(ContentBlockKind.KEYWORD, "keep")),
        )
    val invalid = """{"format":"heyread-content-blocking","version":1,"rules":[],"id":"forbidden"}"""
    assertTrue(runCatching { decodeContentBlockingRules(invalid.toByteArray()) }.isFailure)
    val nested =
        """{"format":"heyread-content-blocking","version":1,"rules":[${"[".repeat(20)}0${"]".repeat(20)}]}"""
    assertTrue(runCatching { decodeContentBlockingRules(nested.toByteArray()) }.isFailure)
    val loneSurrogate =
        "{\"format\":\"heyread-content-blocking\",\"version\":1,\"rules\":[{" +
            "\"kind\":\"KEYWORD\",\"value\":\"\\uD800\",\"label\":\"\",\"enabled\":true,\"source\":null}]}"
    assertTrue(runCatching { decodeContentBlockingRules(loneSurrogate.toByteArray()) }.isFailure)

    val oversized =
        JSONObject()
            .put("format", "heyread-content-blocking")
            .put("version", 1)
            .put(
                "rules",
                JSONArray().apply {
                  repeat(CONTENT_BLOCKING_MAX_RULES + 1) { put(validKeywordRow("x$it")) }
                },
            )
    assertTrue(
        runCatching { decodeContentBlockingRules(oversized.toString().toByteArray()) }.isFailure,
    )

    val imported =
        decodeContentBlockingRules(
            encodeContentBlockingRules(listOf(rule(ContentBlockKind.KEYWORD, "new"))),
        )
    val changedSincePreview =
        original.copy(rules = original.rules + rule(ContentBlockKind.KEYWORD, "later"))
    val stale =
        runCatching {
          reduceContentBlocking(
              changedSincePreview,
              ContentBlockingAction.MergeImportedRules(imported.rules, original.rules),
          )
        }
    assertTrue(stale.isFailure)
    assertEquals(listOf("keep", "later"), changedSincePreview.rules.map { it.value })
    assertTrue(changedSincePreview.applyToFavorites)

    val tooManyImported =
        ContentBlockingImportDocument(
            rules =
                (0..CONTENT_BLOCKING_MAX_RULES).map {
                  rule(ContentBlockKind.KEYWORD, "import$it")
                },
            totalCount = CONTENT_BLOCKING_MAX_RULES + 1,
            duplicateCount = 0,
        )
    assertTrue(
        runCatching { previewContentBlockingImport(ContentBlockingConfig(), tooManyImported) }
            .isFailure,
    )
    val fullConfig =
        ContentBlockingConfig(
            rules =
                (0 until CONTENT_BLOCKING_MAX_RULES).map {
                  rule(ContentBlockKind.KEYWORD, "saved$it")
                },
        )
    val oneMore =
        ContentBlockingImportDocument(
            rules = listOf(rule(ContentBlockKind.KEYWORD, "overflow")),
            totalCount = 1,
            duplicateCount = 0,
        )
    assertTrue(runCatching { previewContentBlockingImport(fullConfig, oneMore) }.isFailure)
  }

  private fun rule(
      kind: ContentBlockKind,
      value: String,
      label: String = "",
      enabled: Boolean = true,
      source: Source? = if (kind == ContentBlockKind.KEYWORD) null else Source.HEYBOX,
  ) = ContentBlockRule(UUID.randomUUID().toString(), kind, value, label, enabled, source)

  private fun validKeywordRow(value: String) =
      JSONObject()
          .put("kind", ContentBlockKind.KEYWORD.name)
          .put("value", value)
          .put("label", "")
          .put("enabled", true)
          .put("source", JSONObject.NULL)
}
