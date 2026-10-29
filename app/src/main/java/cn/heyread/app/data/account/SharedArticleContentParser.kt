package cn.heyread.app.data.account

import cn.heyread.app.data.model.ArticleBlock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared pure parsing entry point for guest and account content. The wrapped repository is created
 * with an empty, in-memory cookie jar and these calls never execute a request.
 */
internal object SharedArticleContentParser {
    private val parser by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AccountRepository() }

    fun parse(content: JSONArray?): List<ArticleBlock> = parser.parseArticleBlocks(content)

    fun html(html: String): List<ArticleBlock> = parser.htmlBlocks(html)

    fun fallback(link: JSONObject, body: JSONObject): List<ArticleBlock> =
        parser.fallbackArticleBlocks(link, body)

    fun withPublicVideo(
        blocks: List<ArticleBlock>,
        vararg sources: JSONObject?,
    ): List<ArticleBlock> = parser.withPublicVideo(blocks, *sources)

    fun withFeedVideo(blocks: List<ArticleBlock>, video: ArticleBlock.Video?): List<ArticleBlock> =
        parser.withFeedVideo(blocks, video)

    fun publicVideo(source: JSONObject): ArticleBlock.Video? = parser.parsePublicVideo(source)
}
