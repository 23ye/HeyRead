package cn.heyread.app.data.model

/** Rich article content held only in active detail state, never in local history. */
sealed interface ArticleBlock {
    data class Paragraph(val text: String) : ArticleBlock

    data class ListItem(val text: String, val depth: Int, val marker: String) : ArticleBlock

    data class Quote(val blocks: List<ArticleBlock>) : ArticleBlock

    data class UnavailableImage(val description: String = "", val caption: String = "") :
        ArticleBlock

    data class Image(
        val url: String,
        val description: String = "",
        val originalUrl: String? = null,
        val caption: String = "",
        val mimeType: String? = null,
        val animated: Boolean = false,
    ) : ArticleBlock

    data class GameCard(val appId: Int) : ArticleBlock

    data class Video(
        val url: String?,
        val thumbnailUrl: String? = null,
        val title: String? = null,
        val durationSeconds: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val sourcePlaceholder: Boolean = false,
        /** Marks metadata associated with the post, so refreshed list data can replace it. */
        val postLevelVideo: Boolean = false,
    ) : ArticleBlock

    data class ExternalVideo(val url: String, val title: String? = null) : ArticleBlock

    data class UnavailableVideo(val title: String? = null) : ArticleBlock
}
