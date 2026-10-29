package cn.heyread.app

import cn.heyread.app.data.remote.HeyboxAdapter
import cn.heyread.app.data.remote.WebRequestSigner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeyboxAdapterTest {
    @Test
    fun acceptsOnlySuccessAndFeedsSchema() {
        val rows =
            HeyboxAdapter.validate(
                JSONObject("""{"status":"success","result":{"feeds":[{"link_id":"a"}]}}""")
            )
        assertEquals(1, rows.size)
        assertEquals("a", rows.first().getString("link_id"))
    }

    @Test
    fun acceptsVerifiedLiveLinksShape() {
        val rows =
            HeyboxAdapter.validate(
                JSONObject(
                    """{"status":"ok","result":{"links":[{"linkid":194068274,"description":"列表摘要","imgs":["https://img.example/cover.jpg"],"create_str":"3小时前","user":{"username":"玩家"}}]}}"""
                )
            )
        val row = rows.single()
        assertEquals(194068274L, row.getLong("linkid"))
        assertEquals("玩家", row.getJSONObject("user").getString("username"))
        assertEquals("https://img.example/cover.jpg", row.getJSONArray("imgs").getString(0))
    }

    @Test
    fun refusesUpstreamFailure() {
        val error =
            runCatching {
                    HeyboxAdapter.validate(JSONObject("""{"status":"failed","result":{}}"""))
                }
                .exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("公开内容接口返回失败"))
    }

    @Test
    fun rejectsMissingFeedArray() {
        val error =
            runCatching {
                    HeyboxAdapter.validate(JSONObject("""{"status":"success","result":{}}"""))
                }
                .exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun publicRequestSignatureMatchesVerifiedVector() {
        val signer = WebRequestSigner()
        assertEquals(
            "V2V1Z67",
            signer.sign("/bbs/app/feeds", 1700000000, "ABCDEF0123456789ABCDEF0123456789"),
        )
        assertEquals(
            signer.sign("bbs/app/feeds/", 1700000000, "ABCDEF0123456789ABCDEF0123456789"),
            signer.sign("/bbs/app/feeds", 1700000000, "ABCDEF0123456789ABCDEF0123456789"),
        )
    }
}
