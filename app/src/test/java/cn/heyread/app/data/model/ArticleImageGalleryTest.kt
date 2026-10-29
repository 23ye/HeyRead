package cn.heyread.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleImageGalleryTest {
    @Test
    fun occurrencesFollowVisibleBodyAndQuoteOrderAndKeepDuplicates() {
        val repeated = ArticleBlock.Image("https://images.example/repeat.jpg", "重复图")
        val nested = ArticleBlock.Quote(listOf(ArticleBlock.Paragraph("引用前"), repeated))
        val blocks =
            listOf(
                ArticleBlock.Paragraph("正文前"),
                repeated,
                nested,
                ArticleBlock.Quote(
                    listOf(
                        ArticleBlock.Quote(
                            listOf(
                                ArticleBlock.Quote(
                                    listOf(
                                        ArticleBlock.Quote(
                                            listOf(
                                                ArticleBlock.Quote(
                                                    listOf(
                                                        ArticleBlock.Image(
                                                            "https://images.example/hidden-deep.jpg"
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                ArticleBlock.Image("https://images.example/last.jpg"),
            )

        val occurrences = articleImageOccurrences(blocks)

        assertEquals(listOf(0, 1, 2), occurrences.map { it.index })
        assertEquals(
            listOf(
                "https://images.example/repeat.jpg",
                "https://images.example/repeat.jpg",
                "https://images.example/last.jpg",
            ),
            occurrences.map { it.block.url },
        )
    }

    @Test
    fun imageCountMatchesQuoteRenderingDepthForOccurrenceOffsets() {
        val image = ArticleBlock.Image("https://images.example/a.jpg")
        val blocks =
            listOf(ArticleBlock.Paragraph("before"), ArticleBlock.Quote(listOf(image, image)))

        assertEquals(0, articleImageCount(blocks.take(1)))
        assertEquals(2, articleImageCount(blocks.drop(1)))
        assertEquals(2, articleImageCount(blocks.drop(1), quoteDepth = 3))
        assertEquals(0, articleImageCount(blocks.drop(1), quoteDepth = 4))
    }
}
