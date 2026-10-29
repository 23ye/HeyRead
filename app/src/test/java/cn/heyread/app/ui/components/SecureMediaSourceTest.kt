package cn.heyread.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMediaSourceTest {
    @Test
    fun everyDatasourceResourceMustBeHttpsAndSignedQueryIsPreserved() {
        assertTrue(isSafeMediaResourceUrl("https://cdn.example/segment.ts?sig=private&expires=42"))
        assertFalse(isSafeMediaResourceUrl("http://cdn.example/video.mp4"))
        assertFalse(isSafeMediaResourceUrl("file:///private/video.mp4"))
        assertFalse(isSafeMediaResourceUrl("https://user:pass@cdn.example/video.mp4"))
    }
}
