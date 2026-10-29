package cn.heyread.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlPolicyTest {
    @Test
    fun explicitOriginalWinsOverTransformDerivedCandidate() {
        assertEquals(
            "https://cdn.example/full.jpg",
            MediaUrlPolicy.originalOrPreview(
                "https://img.max-c.com/a/thumb.jpg?imageMogr2/thumbnail/!50p",
                "https://cdn.example/full.jpg",
            ),
        )
    }

    @Test
    fun onlyKnownPureMaxCTransformQueryIsRemovedWithoutChangingPath() {
        assertEquals(
            "https://img.max-c.com/a/thumb.jpg",
            MediaUrlPolicy.originalOrPreview(
                "https://img.max-c.com/a/thumb.jpg?imageMogr2/thumbnail/!50p&imageView2/2/w/300"
            ),
        )
        assertEquals(
            "https://img.max-c.com/a/thumb.jpg?imageMogr2/thumbnail/!50p&token=abc",
            MediaUrlPolicy.originalOrPreview(
                "https://img.max-c.com/a/thumb.jpg?imageMogr2/thumbnail/!50p&token=abc"
            ),
        )
        assertEquals(
            "https://cdn.example/a/thumb.jpg?imageMogr2/thumbnail/!50p",
            MediaUrlPolicy.originalOrPreview(
                "https://cdn.example/a/thumb.jpg?imageMogr2/thumbnail/!50p"
            ),
        )
    }

    @Test
    fun malformedOrNonHttpsUrlsAreRejectedSafely() {
        assertEquals(null, MediaUrlPolicy.originalOrPreview("http://cdn.example/image.jpg"))
        assertEquals(
            "https://img.max-c.com/a.jpg?%ZZ=imageMogr2",
            MediaUrlPolicy.originalOrPreview("https://img.max-c.com/a.jpg?%ZZ=imageMogr2"),
        )
    }
}
