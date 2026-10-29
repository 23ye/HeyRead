package cn.heyread.app.ui.screens

import cn.heyread.app.data.model.ArticleBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailPresentationMetadataTest {
  @Test
  fun derivesRevealKeysAndNestedGameIdsInArticleOrder() {
    val blocks =
        listOf(
            ArticleBlock.Paragraph("opening"),
            ArticleBlock.Quote(
                listOf(
                    ArticleBlock.Paragraph("quoted paragraph"),
                    ArticleBlock.ListItem("quoted item", depth = 1, marker = "•"),
                ),
            ),
            ArticleBlock.GameCard(appId = 42),
            ArticleBlock.Quote(
                listOf(
                    ArticleBlock.Paragraph("not a text-only reveal"),
                    ArticleBlock.GameCard(appId = 73),
                ),
            ),
            ArticleBlock.ListItem("ending", depth = 0, marker = "1."),
            ArticleBlock.GameCard(appId = 42),
        )

    val metadata = deriveDetailPresentationMetadata("demo:story", blocks, hasFallbackBody = true)

    assertEquals(
        setOf(
            "detail-block:demo:story:0:Paragraph",
            "detail-block:demo:story:1:Quote",
            "detail-block:demo:story:4:ListItem",
        ),
        metadata.bodyRevealKeys,
    )
    assertEquals(listOf(42, 73), metadata.gameIds)
    assertEquals(
        setOf(
            "detail-heading:demo:story",
            "detail-block:demo:story:0:Paragraph",
            "detail-block:demo:story:1:Quote",
            "detail-block:demo:story:4:ListItem",
        ),
        metadata.initialRevealKeys,
    )
  }

  @Test
  fun addsFallbackRevealKeyOnlyWhenThereAreNoBlocksAndBodyExists() {
    assertEquals(
        setOf("detail-heading:steam:article", "detail-fallback:steam:article"),
        deriveDetailPresentationMetadata("steam:article", emptyList(), hasFallbackBody = true)
            .initialRevealKeys,
    )
    assertEquals(
        setOf("detail-heading:steam:article"),
        deriveDetailPresentationMetadata("steam:article", emptyList(), hasFallbackBody = false)
            .initialRevealKeys,
    )
  }
}
