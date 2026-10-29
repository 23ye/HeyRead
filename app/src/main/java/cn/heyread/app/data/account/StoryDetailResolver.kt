package cn.heyread.app.data.account

import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story

/**
 * Resolves route data before persisted history and reapplies current list-only metadata to cache.
 */
internal object StoryDetailResolver {
    fun currentStory(
        source: Source,
        id: String,
        openedStory: Story?,
        candidates: List<Story>,
    ): Story? {
        fun matches(story: Story): Boolean = story.source == source && story.id == id

        return openedStory?.takeIf(::matches) ?: candidates.firstOrNull(::matches)
    }

    fun mergeCachedDetail(current: Story, cached: Story?): Story {
        if (cached == null || current.source != cached.source || current.id != cached.id)
            return current

        val video = current.feedVideo ?: cached.feedVideo
        val currentHasDetailBlocks = current.detailBlocks != null
        val blocks =
            (current.detailBlocks ?: cached.detailBlocks)?.let {
                SharedArticleContentParser.withFeedVideo(it, video)
            }
        val authorProfile = cached.authorProfile ?: current.authorProfile

        return current.copy(
            body =
                if (currentHasDetailBlocks) current.body else cached.body.ifBlank { current.body },
            author =
                authorProfile?.name?.takeIf(String::isNotBlank)
                    ?: cached.author.takeIf(String::isNotBlank)
                    ?: current.author,
            authorProfile = authorProfile,
            sections = cached.sections.ifEmpty { current.sections },
            hashtags = cached.hashtags.ifEmpty { current.hashtags },
            url = cached.url?.takeIf(String::isNotBlank) ?: current.url,
            detailNotice = cached.detailNotice,
            detailBlocks = blocks,
            feedVideo = video,
        )
    }
}
