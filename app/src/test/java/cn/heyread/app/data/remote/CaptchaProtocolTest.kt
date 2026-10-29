package cn.heyread.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaProtocolTest {
    @Test
    fun acceptsOnlyValidSuccessfulTicketAndRedactsItFromToString() {
        val result =
            parseCaptchaCallback("""{"ret":0,"ticket":"secret-ticket","randstr":"secret-rand"}""")
                as CaptchaCallbackResult.Completed

        assertTrue(result.proof.toString().contains("redacted"))
        assertFalse(result.proof.toString().contains("secret-ticket"))
        assertFalse(result.toString().contains("secret-rand"))
    }

    @Test
    fun rejectsCanceledFallbackMalformedAndOversizedCallbacks() {
        assertTrue(parseCaptchaCallback("""{"ret":2}""") is CaptchaCallbackResult.Cancelled)
        assertTrue(
            parseCaptchaCallback("""{"ret":0,"ticket":"trerror_1","randstr":"r"}""")
                is CaptchaCallbackResult.Failed
        )
        assertTrue(
            parseCaptchaCallback("""{"ret":0,"errorCode":0.5,"ticket":"t","randstr":"r"}""")
                is CaptchaCallbackResult.Failed
        )
        assertTrue(
            parseCaptchaCallback("{" + " ".repeat(16 * 1024)) is CaptchaCallbackResult.Failed
        )
    }

    @Test
    fun allowsOnlyExactHttpsCaptchaHostsWithoutNonstandardPortsOrUserInfo() {
        assertTrue(isAllowedCaptchaUrl("https://turing.captcha.qcloud.com/TCaptcha.js"))
        assertTrue(isAllowedCaptchaUrl("https://cloudcache.tencentcs.com/resource"))
        assertFalse(isAllowedCaptchaUrl("http://turing.captcha.qcloud.com/TCaptcha.js"))
        assertFalse(isAllowedCaptchaUrl("https://evil-turing.captcha.qcloud.com/path"))
        assertFalse(isAllowedCaptchaUrl("https://turing.captcha.qcloud.com.evil.test/path"))
        assertFalse(isAllowedCaptchaUrl("https://user@turing.captcha.qcloud.com/path"))
        assertFalse(isAllowedCaptchaUrl("https://turing.captcha.qcloud.com:444/path"))
    }

    @Test
    fun usesOnlyEvidenceBasedPathFallbacksForCaptchaAppId() {
        assertEquals("199251710", resolveCaptchaAppId(null, "/bbs/app/profile/events"))
        assertEquals("199251710", resolveCaptchaAppId(null, "/bbs/app/api/general/search/v1"))
        assertEquals("2061805035", resolveCaptchaAppId(null, "/bbs/app/profile/user/link/list_v2"))
        assertEquals("199251710", resolveCaptchaAppId(null, "/bbs/app/profile/user/link/list/v2"))
        assertEquals("123456", resolveCaptchaAppId("123456", "/bbs/app/profile/events"))
        assertEquals("199251710", resolveCaptchaAppId("12x", "/bbs/app/profile/events"))
    }
}
