package cn.heyread.app.data.model

fun articleGameIds(blocks: List<ArticleBlock>): List<Int> =
    buildList {
          fun collect(current: List<ArticleBlock>) {
            current.forEach { block ->
              when (block) {
                is ArticleBlock.GameCard -> add(block.appId)
                is ArticleBlock.Quote -> collect(block.blocks)
                else -> Unit
              }
            }
          }
          collect(blocks)
        }
        .distinct()
