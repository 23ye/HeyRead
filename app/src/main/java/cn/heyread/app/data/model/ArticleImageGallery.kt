package cn.heyread.app.data.model

data class ArticleImageOccurrence(val index: Int, val block: ArticleBlock.Image)

/** Images in the order the current article renderer presents them. */
fun articleImageOccurrences(blocks: List<ArticleBlock>): List<ArticleImageOccurrence> = buildList {
    fun collect(current: List<ArticleBlock>, quoteDepth: Int) {
        current.forEach { block ->
            when (block) {
                is ArticleBlock.Image -> add(ArticleImageOccurrence(size, block))
                is ArticleBlock.Quote ->
                    if (quoteDepth < MAX_RENDERED_ARTICLE_QUOTE_DEPTH)
                        collect(block.blocks, quoteDepth + 1)
                else -> Unit
            }
        }
    }
    collect(blocks, 0)
}

/** Count images before a rendered block without collapsing repeated occurrences. */
fun articleImageCount(blocks: List<ArticleBlock>, quoteDepth: Int = 0): Int =
    blocks.sumOf { block ->
        when (block) {
            is ArticleBlock.Image -> 1
            is ArticleBlock.Quote ->
                if (quoteDepth < MAX_RENDERED_ARTICLE_QUOTE_DEPTH)
                    articleImageCount(block.blocks, quoteDepth + 1)
                else 0
            else -> 0
        }
    }

fun articleImageOffsets(blocks: List<ArticleBlock>): List<Int> {
    var nextIndex = 0
    return blocks.map { block ->
        val offset = nextIndex
        nextIndex += articleImageCount(listOf(block))
        offset
    }
}

const val MAX_RENDERED_ARTICLE_QUOTE_DEPTH = 4
