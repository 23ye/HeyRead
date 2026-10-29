package cn.heyread.app.data.model

enum class Source {
    STEAM,
    HEYBOX,
    DEMO,
}

data class Story(
    val id: String,
    val source: Source,
    val title: String,
    val excerpt: String,
    val body: String,
    val author: String,
    val date: String,
    val image: String? = null,
    val url: String? = null,
    val appId: Int? = null,
    val publishedAt: Long? = null,
    val detailNotice: String? = null,
    val channel: Channel? = null,
    val hSrc: String? = null,
    val authorProfile: AuthorProfile? = null,
    val sections: List<ArticleLabel> = emptyList(),
    val hashtags: List<ArticleLabel> = emptyList(),
    /** Active-detail only. LibraryStore intentionally omits this field. */
    val detailBlocks: List<ArticleBlock>? = null,
    /** Short-lived video metadata from the public feed; never serialized to history. */
    val feedVideo: ArticleBlock.Video? = null,
)

data class AuthorProfile(
    val name: String,
    val avatarUrl: String? = null,
    val level: String? = null,
    val location: String? = null,
    val userId: String? = null,
    val bio: String? = null,
)

data class ArticleLabel(val id: String, val name: String, val imageUrl: String? = null)

enum class Channel(val label: String) {
    RECOMMEND("推荐"),
    NEWS("资讯"),
    COMMUNITY("社区"),
}

data class StoryPage(val items: List<Story>, val hasMore: Boolean)
