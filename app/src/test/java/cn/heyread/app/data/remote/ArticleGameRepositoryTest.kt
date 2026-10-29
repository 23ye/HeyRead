package cn.heyread.app.data.remote

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleGameRepositoryTest {
    @Test
    fun gameIdsAreFetchedInBoundedBatchesWithoutTruncation() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val repository =
            ArticleGameRepository(
                execute = { request ->
                    requests += request
                    val ids = request.url.queryParameter("appids").orEmpty().split(',')
                    JSONObject()
                        .put("status", "ok")
                        .put(
                            "result",
                            JSONObject()
                                .put(
                                    "base_infos",
                                    org.json.JSONArray(
                                        ids.map {
                                            JSONObject()
                                                .put("steam_appid", it.toInt())
                                                .put("name", "Game $it")
                                        }
                                    ),
                                ),
                        )
                        .toString()
                }
            )
        val ids = (1..25).toList()
        val result = repository.loadInfos(ids)
        assertEquals(ids.toSet(), result.keys)
        assertEquals(2, requests.size)
        assertEquals(24, requests[0].url.queryParameter("appids")!!.split(',').size)
        assertEquals("25", requests[1].url.queryParameter("appids"))
        assertTrue(
            requests.all { it.url.host == "api.xiaoheihe.cn" && it.header("Cookie") == null }
        )
    }

    @Test
    fun menuUsesAppIdAndParsesTheReturnedTopicId() = runBlocking {
        var request: Request? = null
        val repository =
            ArticleGameRepository(
                execute = {
                    request = it
                    """{"status":"ok","result":{"topic":{"topic_id":570904,"name":"链在一起","pic_url":"https://cdn.example/icon.png"}}}"""
                }
            )
        val topic = repository.resolveTopic(2567870)
        assertEquals("/bbs/app/topic/menu", request!!.url.encodedPath)
        assertEquals("2567870", request!!.url.queryParameter("appid"))
        assertEquals("570904", topic.label.id)
    }

    @Test
    fun moneyRejectsInvalidCouponAndAvoidsStaleFreeDiscount() {
        val repository = ArticleGameRepository()
        val malformed =
            repository.parseGameInfo(
                JSONObject()
                    .put("steam_appid", 42)
                    .put("name", "Game")
                    .put(
                        "heybox_price",
                        JSONObject()
                            .put("style", 110)
                            .put("coupon_info", JSONObject().put("final_price", "undefined"))
                            .put("cost_coin", 20000)
                            .put("original_coin", 30000),
                    )
            )!!
        assertEquals("¥20", malformed.currentPrice)
        assertEquals("¥30", malformed.originalPrice)
        val free =
            repository.parseGameInfo(
                JSONObject()
                    .put("steam_appid", 43)
                    .put("name", "Free")
                    .put("is_free", 1)
                    .put("heybox_price", JSONObject().put("discount", 80))
            )!!
        assertEquals("免费", free.currentPrice)
        assertEquals(null, free.discountPercent)
        assertNull(free.originalPrice)
    }

    @Test
    fun platformReferencePriceDoesNotPretendToBeYuanOrSteam() {
        val info =
            ArticleGameRepository()
                .parseGameInfo(
                    JSONObject()
                        .put("steam_appid", 9)
                        .put("name", "Game")
                        .put(
                            "platform_infos",
                            org.json
                                .JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "price",
                                            JSONObject()
                                                .put("current", "19.99")
                                                .put("initial", "29.99"),
                                        )
                                ),
                        )
                )!!
        assertEquals("19.99", info.currentPrice)
        assertEquals(PriceSource.PLATFORM, info.priceSource)
        assertEquals("29.99", info.originalPrice)
    }
}
