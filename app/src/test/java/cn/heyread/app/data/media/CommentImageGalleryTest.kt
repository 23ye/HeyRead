package cn.heyread.app.data.media

import cn.heyread.app.data.account.CommentImage
import cn.heyread.app.data.account.RemoteComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class CommentImageGalleryTest {
  @Test
  fun collectsRootsThenVisibleRepliesAndAccountRepliesOverrideEvenWhenEmpty() {
    val first =
      comment(
        "root-a",
        images = listOf(image("a1"), image("a2")),
        replies = listOf(comment("embedded", "root-a", listOf(image("embedded")))),
      )
    val second =
      comment(
        "root-b",
        images = listOf(image("b")),
        replies = listOf(comment("embedded-b", "root-b", listOf(image("embedded-b")))),
      )
    val result =
      collectCommentImageOccurrences(
        listOf(first, second),
        mapOf(
          "root-a" to listOf(comment("loaded", "root-a", listOf(image("loaded")))),
          "root-b" to emptyList(),
        ),
      )
    assertEquals(listOf("root-a", "root-a", "loaded", "root-b"), result.map { it.commentId })
    assertEquals(listOf(0, 1, 0, 0), result.map { it.imageIndex })
    assertEquals(listOf("a1", "a2", "loaded", "b"), result.map { it.image.url })
  }

  @Test
  fun repeatedUrlsRemainDistinctAndResolveByRootCommentAndImagePosition() {
    val repeated = image("same")
    val root = comment("root", images = listOf(repeated, image("middle"), repeated))
    val reply = comment("reply", "root", listOf(repeated))
    val occurrences = collectCommentImageOccurrences(listOf(root), mapOf("root" to listOf(reply)))
    assertEquals(4, occurrences.size)
    assertEquals(0, commentImageOccurrenceIndex(occurrences, "root", "root", 0))
    assertEquals(2, commentImageOccurrenceIndex(occurrences, "root", "root", 2))
    assertEquals(3, commentImageOccurrenceIndex(occurrences, "root", "reply", 0))
    assertEquals(-1, commentImageOccurrenceIndex(occurrences, "other", "reply", 0))
    assertEquals(-1, commentImageOccurrenceIndex(occurrences, "root", "root", 9))
  }

  @Test
  fun aPreviouslyOpenedOccurrenceListIsStableWhenNewCommentsArrive() {
    val loaded = mutableListOf(comment("first", images = listOf(image("one"))))
    val openedSnapshot = collectCommentImageOccurrences(loaded, emptyMap())
    loaded += comment("later", images = listOf(image("two")))
    val latest = collectCommentImageOccurrences(loaded, emptyMap())
    assertNotSame(openedSnapshot, latest)
    assertEquals(listOf("one"), openedSnapshot.map { it.image.url })
    assertEquals(listOf("one", "two"), latest.map { it.image.url })
  }

  private fun comment(
    id: String,
    rootId: String? = null,
    images: List<CommentImage> = emptyList(),
    replies: List<RemoteComment> = emptyList(),
  ) =
    RemoteComment(
      id,
      "author-$id",
      "text-$id",
      0,
      0,
      replies = replies,
      rootCommentId = rootId,
      images = images,
    )

  private fun image(url: String) = CommentImage(url)
}
