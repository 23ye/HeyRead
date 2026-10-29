package cn.heyread.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.model.ArticleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailPresentationRememberTest {
  @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  @Test
  fun unrelatedRecompositionReusesScansAndArticleOrBlockChangesInvalidateThem() {
    val initialBlocks = CountingBlockList(longArticleBlocks())
    val blocks = mutableStateOf<List<ArticleBlock>>(initialBlocks)
    val articleKey = mutableStateOf("demo:long-article")
    val unrelatedRevision = mutableIntStateOf(0)

    compose.setContent {
      val tick = unrelatedRevision.intValue
      val currentBlocks = blocks.value
      val currentKey = articleKey.value
      val metadata =
          rememberDetailPresentationMetadata(
              currentKey,
              currentBlocks,
              hasFallbackBody = false,
          )
      Text("$tick:${metadata.bodyRevealKeys.size}:${metadata.gameIds.size}")
    }
    compose.waitForIdle()

    val initialScans = initialBlocks.readCount
    assertTrue("The production helper should scan the supplied article", initialScans > 0)
    repeat(20) { revision ->
      compose.runOnIdle { unrelatedRevision.intValue = revision + 1 }
      compose.waitForIdle()
    }
    assertEquals(
        "Unrelated state must reuse the metadata calculation",
        initialScans,
        initialBlocks.readCount)

    val replacementBlocks = CountingBlockList(longArticleBlocks() + ArticleBlock.GameCard(999))
    compose.runOnIdle { blocks.value = replacementBlocks }
    compose.waitForIdle()
    assertTrue("New content must trigger a fresh metadata scan", replacementBlocks.readCount > 0)
    val afterContentChange = replacementBlocks.readCount

    compose.runOnIdle { articleKey.value = "demo:another-article" }
    compose.waitForIdle()
    assertTrue(
        "A new article identity must invalidate derived metadata",
        replacementBlocks.readCount > afterContentChange)
  }

  private fun longArticleBlocks(): List<ArticleBlock> =
      List(160) { index ->
        when (index % 12) {
          0 -> ArticleBlock.GameCard(100 + index)
          4 -> ArticleBlock.Quote(listOf(ArticleBlock.Paragraph("quoted $index")))
          else -> ArticleBlock.Paragraph("article paragraph $index")
        }
      }

  private class CountingBlockList(private val values: List<ArticleBlock>) :
      AbstractList<ArticleBlock>() {
    var readCount: Int = 0
      private set

    override val size: Int
      get() = values.size

    override fun get(index: Int): ArticleBlock {
      readCount += 1
      return values[index]
    }
  }
}
