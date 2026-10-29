package cn.heyread.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cn.heyread.app.data.model.ArticleBlock
import cn.heyread.app.data.model.articleGameIds

internal data class DetailPresentationMetadata(
    val bodyRevealKeys: Set<String>,
    val gameIds: List<Int>,
    val initialRevealKeys: Set<String>,
)

/** Keeps large article scans tied to article identity and the immutable block-list instance. */
@Composable
internal fun rememberDetailPresentationMetadata(
    articleKey: String,
    displayBlocks: List<ArticleBlock>,
    hasFallbackBody: Boolean,
): DetailPresentationMetadata =
    remember(articleKey, IdentityKey(displayBlocks), hasFallbackBody) {
      deriveDetailPresentationMetadata(articleKey, displayBlocks, hasFallbackBody)
    }

internal fun deriveDetailPresentationMetadata(
    articleKey: String,
    displayBlocks: List<ArticleBlock>,
    hasFallbackBody: Boolean,
): DetailPresentationMetadata {
  val bodyRevealKeys = buildSet {
    displayBlocks.forEachIndexed { index, block ->
      if (isTextRevealBlock(block))
          add("detail-block:$articleKey:$index:${block::class.simpleName}")
    }
  }
  val fallbackBodyRevealKey = "detail-fallback:$articleKey"
  val initialRevealKeys = buildSet {
    add("detail-heading:$articleKey")
    addAll(bodyRevealKeys)
    if (displayBlocks.isEmpty() && hasFallbackBody) add(fallbackBodyRevealKey)
  }
  return DetailPresentationMetadata(
      bodyRevealKeys = bodyRevealKeys,
      gameIds = articleGameIds(displayBlocks),
      initialRevealKeys = initialRevealKeys,
  )
}

internal fun isTextRevealBlock(block: ArticleBlock): Boolean =
    when (block) {
      is ArticleBlock.Paragraph,
      is ArticleBlock.ListItem -> true
      is ArticleBlock.Quote -> block.blocks.all(::isTextRevealBlock)
      else -> false
    }

private class IdentityKey(private val value: Any) {
  override fun equals(other: Any?): Boolean = other is IdentityKey && value === other.value

  override fun hashCode(): Int = System.identityHashCode(value)
}
