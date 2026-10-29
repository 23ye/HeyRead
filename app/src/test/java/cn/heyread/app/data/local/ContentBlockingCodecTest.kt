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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockingCodecTest {
  @Test
  fun keywordBatchTrimsDeduplicatesCaseInsensitivelyAndPreservesDisplayText() {
    val result =
        reduceContentBlocking(
            ContentBlockingConfig(),
            ContentBlockingAction.AddKeywords("  Boss  \nboss\n  Raid Alert \n  raid,alert  \n"))

    assertEquals(3, result.addedCount)
    assertEquals(listOf("Boss", "Raid Alert", "raid,alert"), result.config.rules.map { it.value })
    assertTrue(result.config.rules.all { it.kind == ContentBlockKind.KEYWORD && it.source == null })
    val repeated =
        reduceContentBlocking(result.config, ContentBlockingAction.AddKeywords("boss\nRAID ALERT"))
    assertFalse(repeated.changed)
    assertEquals(0, repeated.addedCount)
  }

  @Test
  fun identityKindsAreDistinctAndDecimalIdsAreCanonicalWithoutLongConversion() {
    val author =
        reduceContentBlocking(
                ContentBlockingConfig(),
                ContentBlockingAction.AddIdentity(
                    ContentBlockKind.AUTHOR, " 00012345678901234567890123456789 ", "备注"),
            )
            .config
    val section =
        reduceContentBlocking(
                author,
                ContentBlockingAction.AddIdentity(
                    ContentBlockKind.SECTION, "12345678901234567890123456789"),
            )
            .config
    val withKeyword =
        reduceContentBlocking(
                section,
                ContentBlockingAction.AddKeywords("12345678901234567890123456789"),
            )
            .config

    assertEquals(3, withKeyword.rules.size)
    assertEquals("12345678901234567890123456789", section.rules.first().value)
    assertTrue(section.rules.all { it.source == Source.HEYBOX })
    assertNotEquals(section.rules[0].id, section.rules[1].id)
    val duplicate =
        reduceContentBlocking(
            section,
            ContentBlockingAction.AddIdentity(
                ContentBlockKind.AUTHOR, "00012345678901234567890123456789"),
        )
    assertFalse(duplicate.changed)
  }

  @Test
  fun editsKeepRuleIdentityAndClearKeepsGlobalAndScopeOptions() {
    val first =
        reduceContentBlocking(ContentBlockingConfig(), ContentBlockingAction.AddKeywords("old"))
            .config
    val rule = first.rules.single()
    val edited =
        reduceContentBlocking(first, ContentBlockingAction.EditKeyword(rule.id, " new ")).config
    assertEquals(rule.id, edited.rules.single().id)
    assertEquals("new", edited.rules.single().value)
    val disabled =
        reduceContentBlocking(edited, ContentBlockingAction.SetRuleEnabled(rule.id, false)).config
    assertFalse(disabled.rules.single().enabled)
    val scoped = disabled.copy(enabled = false, applyToFavorites = true, applyToHistory = true)
    val cleared = reduceContentBlocking(scoped, ContentBlockingAction.ClearRules).config
    assertTrue(cleared.rules.isEmpty())
    assertFalse(cleared.enabled)
    assertTrue(cleared.applyToFavorites)
    assertTrue(cleared.applyToHistory)
  }

  @Test
  fun invalidOrOverLimitBatchesRejectWithoutPartialMutation() {
    val config = ContentBlockingConfig()
    listOf("0", "000", "-1", "１２", "1".repeat(33)).forEach { invalid ->
      assertTrue(
          runCatching {
                reduceContentBlocking(
                    config, ContentBlockingAction.AddIdentity(ContentBlockKind.AUTHOR, invalid))
              }
              .isFailure)
    }
    assertTrue(
        runCatching {
              reduceContentBlocking(
                  config,
                  ContentBlockingAction.AddIdentity(ContentBlockKind.AUTHOR, "1", "备注".repeat(31)),
              )
            }
            .isFailure)
    val maxLengthLabel =
        reduceContentBlocking(
                config,
                ContentBlockingAction.AddIdentity(ContentBlockKind.AUTHOR, "2", "备注".repeat(30)),
            )
            .config
            .rules
            .single()
    assertEquals(60, maxLengthLabel.label.length)
    assertTrue(
        runCatching {
              reduceContentBlocking(config, ContentBlockingAction.AddKeywords("x".repeat(101)))
            }
            .isFailure)
    val full =
        ContentBlockingConfig(
            rules =
                (0 until CONTENT_BLOCKING_MAX_RULES).map { index ->
                  ContentBlockRule(
                      UUID.randomUUID().toString(), ContentBlockKind.KEYWORD, "词$index")
                })
    assertTrue(
        runCatching { reduceContentBlocking(full, ContentBlockingAction.AddKeywords("最后一条")) }
            .isFailure)
    assertEquals(CONTENT_BLOCKING_MAX_RULES, full.rules.size)
  }

  @Test
  fun versionedCodecRoundTripsAndRejectsUnknownOrMalformedConfig() {
    val config =
        ContentBlockingConfig(
            enabled = false,
            applyToFavorites = true,
            rules =
                listOf(
                    ContentBlockRule(
                        UUID.randomUUID().toString(), ContentBlockKind.KEYWORD, "Boss"),
                    ContentBlockRule(
                        UUID.randomUUID().toString(),
                        ContentBlockKind.TOPIC,
                        "98765432101234567890",
                        "topic note",
                        source = Source.HEYBOX,
                    ),
                ),
        )
    assertEquals(config, decodeContentBlocking(encodeContentBlocking(config)))
    assertEquals(ContentBlockingConfig(), decodeContentBlocking(null))

    listOf(
            "{\"version\":2,\"enabled\":true,\"applyToFavorites\":false,\"applyToHistory\":false,\"rules\":[]}",
            "{\"version\":1,\"enabled\":\"true\",\"applyToFavorites\":false,\"applyToHistory\":false,\"rules\":[]}",
            "not-json",
            " ".repeat(CONTENT_BLOCKING_MAX_JSON_BYTES + 1),
        )
        .forEach { raw -> assertTrue(runCatching { decodeContentBlocking(raw) }.isFailure) }
  }

  @Test
  fun keywordRuleRejectsNewlinesFromBothActionsAndStoredJson() {
    val existing =
        reduceContentBlocking(ContentBlockingConfig(), ContentBlockingAction.AddKeywords("one"))
            .config
    val id = existing.rules.single().id
    assertTrue(
        runCatching {
              reduceContentBlocking(existing, ContentBlockingAction.EditKeyword(id, "one\ntwo"))
            }
            .isFailure)
    val raw =
        JSONObject()
            .put("version", 1)
            .put("enabled", true)
            .put("applyToFavorites", false)
            .put("applyToHistory", false)
            .put(
                "rules",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", id)
                            .put("kind", ContentBlockKind.KEYWORD.name)
                            .put("value", "one\ntwo")
                            .put("label", "")
                            .put("enabled", true)
                            .put("source", JSONObject.NULL)))
            .toString()
    assertTrue(runCatching { decodeContentBlocking(raw) }.isFailure)
  }
}
