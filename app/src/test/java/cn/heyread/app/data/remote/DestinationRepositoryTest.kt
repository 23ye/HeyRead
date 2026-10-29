package cn.heyread.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRepositoryTest {
    @Test
    fun sectionUsesMenuAndFeedsSchemaAndCarriesRawOffset() = runBlocking {
        val requests = mutableListOf<Request>()
        val repository =
            DestinationRepository(
                execute = { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/bbs/app/topic/menu" ->
                            """{"status":"ok","result":{"topic":{"topic_id":"18745","name":"Test section","pic_url":"https://img.example/icon.png"}}}"""
                        "/bbs/app/topic/feeds" ->
                            """{"status":"ok","result":{"lastval":"","links":[{"linkid":"p1","title":"Post","description":"body","imgs":["https://img.example/cover.jpg"],"topics":[{"topic_id":"18745","name":"Test section","pic_url":"https://img.example/icon.png"}],"user":{"userid":123,"username":"Author"}}],"sort_filter":[],"filters":[],"banner":[]}}"""
                        else -> error("Unexpected endpoint")
                    }
                }
            )

        val page =
            repository.load(
                DestinationTarget(DestinationType.SECTION, "18745", "Fallback"),
                0,
                10,
                null,
                null,
                DestinationFeedType.ARTICLES,
                false,
            )

        assertEquals("Test section", page.data.title)
        assertEquals("https://img.example/icon.png", page.data.iconUrl)
        assertEquals("123", page.stories.single().authorProfile?.userId)
        assertEquals("https://img.example/cover.jpg", page.stories.single().image)
        assertEquals(1, page.nextOffset)
        assertTrue(page.hasMore)
        assertEquals(
            listOf("/bbs/app/topic/menu", "/bbs/app/topic/feeds"),
            requests.map { it.url.encodedPath },
        )
        requests.forEach { request ->
            assertEquals("api.xiaoheihe.cn", request.url.host)
            assertEquals("web", request.url.queryParameter("os_type"))
            assertEquals("heybox", request.url.queryParameter("app"))
            assertEquals("web", request.url.queryParameter("x_client_type"))
            assertEquals("Mac", request.url.queryParameter("x_os_type"))
            assertEquals("heybox", request.url.queryParameter("x_app"))
            assertEquals("999.0.4", request.url.queryParameter("version"))
            assertFalse(request.headers.names().any { it.equals("Cookie", true) })
            assertNull(request.url.queryParameter("hashtag_id"))
        }
        assertEquals("18745", requests.last().url.queryParameter("topic_id"))
    }

    @Test
    fun hashtagAndUserUseTheirExactIdsAndUserOffsetsArePageBased() = runBlocking {
        val requests = mutableListOf<Request>()
        val repository =
            DestinationRepository(
                execute = { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/bbs/app/hashtag/feeds" ->
                            """{"status":"ok","result":{"links":[{"linkid":"h1","title":"Hashtag post"}],"lastval":"","hashtag":{"id":"554","name":"攻略","description":"about","num":7}}}"""
                        "/bbs/app/profile/user/profile" ->
                            """{"status":"ok","result":{"account_detail":{"userid":"u7","username":"User","avatar":"https://img.example/user.png","level_info":{"level":5},"ip_location":"上海","signature":"Bio"}}}"""
                        "/bbs/app/profile/user/link/list_v2" ->
                            """{"status":"ok","result":{"post_links":[{"linkid":"u1","title":"User post"}],"user":{"userid":"u7","username":"User"},"total_page":5}}"""
                        else -> error("Unexpected endpoint")
                    }
                }
            )

        val hashtagPage =
            repository.load(
                DestinationTarget(DestinationType.HASHTAG, "554", "攻略"),
                0,
                30,
                null,
                null,
                DestinationFeedType.ARTICLES,
                false,
            )
        val userPage =
            repository.load(
                DestinationTarget(DestinationType.USER, "u7", "User"),
                0,
                30,
                null,
                null,
                DestinationFeedType.ARTICLES,
                true,
            )
        val userNextPage =
            repository.load(
                DestinationTarget(DestinationType.USER, "u7", "User"),
                30,
                30,
                null,
                userPage.data.profile,
                DestinationFeedType.ARTICLES,
                false,
            )

        assertEquals("554", requests[0].url.queryParameter("hashtag_id"))
        assertNull(requests[0].url.queryParameter("topic_id"))
        val userRequests =
            requests.filter {
                it.url.encodedPath.endsWith("/profile") || it.url.encodedPath.endsWith("/list_v2")
            }
        assertEquals("u7", userRequests[1].url.queryParameter("userid"))
        assertEquals("article", userRequests[1].url.queryParameter("list_type"))
        assertEquals("u7", userRequests[2].url.queryParameter("userid"))
        assertEquals("30", userRequests[2].url.queryParameter("offset"))
        assertEquals(30, userPage.nextOffset)
        assertTrue(userPage.hasMore)
        assertEquals(60, userNextPage.nextOffset)
        assertEquals("Bio", userPage.data.profile?.bio)
        assertEquals("上海", userPage.data.profile?.location)
        assertEquals("User", userNextPage.data.profile?.name)
        assertTrue(hashtagPage.hasMore)
    }

    @Test
    fun momentFeedPreservesFractionalCursorUnwrapsGameReviewAndUsesDescriptionFallback() =
        runBlocking {
            val requests = mutableListOf<Request>()
            val repository =
                DestinationRepository(
                    execute = { request ->
                        requests += request
                        when (request.url.encodedPath) {
                            "/bbs/app/profile/user/profile" ->
                                """{"status":"ok","result":{"account_detail":{"userid":"43436149","username":"User","bbs_info":{"follow_num":0,"fan_num":6,"awd_num":618,"be_favoured_num":6,"favour_num":999}}}}"""
                            "/bbs/app/profile/events" ->
                                if (request.url.queryParameter("lastval").isNullOrEmpty())
                                    """{"status":"ok","result":{"lastval":"1791693318.357","user":{"userid":"43436149","username":"User"},"moments":[{"linkid":"moment-1","userid":"43436149","title":"","description":"普通动态内容","create_str":"今天","user":{"userid":"43436149","username":"User"}},{"linkid":"180520879","content_type":42,"user":{"userid":"43436149","username":"User"},"link":{"linkid":180520879,"userid":"43436149","title":"","description":"垃圾游戏，一点都不平均","create_str":"2026-05-02","content_type":42,"topics":[{"topic_id":759761,"name":"地球Online","pic_url":"https://img.example/game.png"}],"imgs":[],"user":{"userid":"43436149","username":"User"}}}]}}"""
                                else
                                    """{"status":"ok","result":{"lastval":"1791079982.084","moments":[{"linkid":"moment-2","title":"Next","description":"next body"}]}}"""
                            else -> error("Unexpected endpoint")
                        }
                    }
                )
            val target = DestinationTarget(DestinationType.USER, "43436149", "User")
            val first =
                repository.load(target, 0, 30, null, null, DestinationFeedType.MOMENTS, true)
            val next =
                repository.load(
                    target,
                    0,
                    30,
                    first.lastValue,
                    first.data.profile,
                    DestinationFeedType.MOMENTS,
                    false,
                )

            assertEquals("43436149", requests.last().url.queryParameter("userid"))
            assertEquals("moment", requests.last().url.queryParameter("list_type"))
            assertEquals("1791693318.357", requests.last().url.queryParameter("lastval"))
            assertEquals("304", requests.last().url.queryParameter("dw"))
            assertEquals(listOf("moment-1", "180520879"), first.stories.map { it.id })
            assertEquals("普通动态内容", first.stories.first().title)
            assertEquals("垃圾游戏，一点都不平均", first.stories.last().title)
            assertEquals("地球Online", first.stories.last().sections.single().name)
            assertEquals(0L, first.data.profileStats?.following)
            assertEquals(6L, first.data.profileStats?.followers)
            assertEquals(624L, first.data.profileStats?.receivedLikesAndFavorites)
            assertEquals("1791693318.357", first.lastValue)
            assertEquals("1791079982.084", next.lastValue)
            assertTrue(first.hasMore)
            assertTrue(next.hasMore)
            assertEquals(
                1,
                requests.count { it.url.encodedPath == "/bbs/app/profile/user/profile" },
            )
        }

    @Test
    fun malformedOrOverflowingProfileCountsStayUnknown() = runBlocking {
        val repository =
            DestinationRepository(
                execute = { request ->
                    if (request.url.encodedPath.endsWith("/profile"))
                        """{"status":"ok","result":{"account_detail":{"userid":"u","username":"U","bbs_info":{"follow_num":"7","fan_num":-1,"awd_num":9223372036854775807,"be_favoured_num":1}}}}"""
                    else """{"status":"ok","result":{"moments":[],"lastval":""}}"""
                }
            )
        val page =
            repository.load(
                DestinationTarget(DestinationType.USER, "u", "U"),
                0,
                30,
                null,
                null,
                DestinationFeedType.MOMENTS,
                true,
            )
        assertEquals(null, page.data.profileStats?.following)
        assertEquals(null, page.data.profileStats?.followers)
        assertEquals(null, page.data.profileStats?.receivedLikesAndFavorites)
    }

    @Test
    fun malformedSuccessfulListSchemaIsReported() = runBlocking {
        val repository =
            DestinationRepository(
                execute = { """{"status":"ok","result":{"hashtag":{"id":"1","name":"tag"}}}""" }
            )
        val error =
            runCatching {
                    repository.load(
                        DestinationTarget(DestinationType.HASHTAG, "1", "tag"),
                        0,
                        30,
                        null,
                        null,
                        DestinationFeedType.ARTICLES,
                        false,
                    )
                }
                .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("links"))
    }

    @Test
    fun captchaDuringProfileStopsListAndListChallengeReusesSuccessfulProfile() = runBlocking {
        val requests = mutableListOf<Request>()
        val repository =
            DestinationRepository(
                execute = { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/bbs/app/profile/user/profile" ->
                            """{"status":"ok","result":{"account_detail":{"userid":"u1","username":"Reader","bbs_info":{"follow_num":2,"fan_num":3,"awd_num":4,"be_favoured_num":5}}}}"""
                        "/bbs/app/profile/events" ->
                            if (request.url.queryParameter("ticket") != null)
                                """{"status":"ok","result":{"moments":[{"linkid":"m1","title":"Moment"}],"lastval":""}}"""
                            else """{"status":"show_captcha","appid":199251710}"""
                        else -> error("Unexpected request path")
                    }
                }
            )
        val target = DestinationTarget(DestinationType.USER, "u1", "Reader")
        val firstError =
            runCatching {
                    repository.load(target, 0, 30, null, null, DestinationFeedType.MOMENTS, true)
                }
                .exceptionOrNull() as CaptchaRequiredException
        assertEquals("/bbs/app/profile/events", firstError.challenge.path)
        assertEquals("199251710", firstError.challenge.appId)
        assertEquals("Reader", firstError.partialData?.title)
        assertEquals(9L, firstError.partialData?.profileStats?.receivedLikesAndFavorites)
        assertEquals(
            listOf("/bbs/app/profile/user/profile", "/bbs/app/profile/events"),
            requests.map { it.url.encodedPath },
        )

        val proof =
            (parseCaptchaCallback("""{"ret":0,"ticket":"ticket-once","randstr":"rand-once"}""")
                    as CaptchaCallbackResult.Completed)
                .proof
        val page =
            repository.load(
                target,
                0,
                30,
                "",
                firstError.partialData?.profile,
                DestinationFeedType.MOMENTS,
                false,
                knownData = firstError.partialData,
                captchaChallenge = firstError.challenge,
                captchaProof = proof,
            )
        assertEquals(listOf("m1"), page.stories.map { it.id })
        assertEquals("ticket-once", requests.last().url.queryParameter("ticket"))
        assertEquals("rand-once", requests.last().url.queryParameter("randstr"))
        assertEquals("confirm", requests.last().url.queryParameter("captcha_type"))
        assertEquals(1, requests.count { it.url.encodedPath == "/bbs/app/profile/user/profile" })
        assertFalse(proof.toString().contains("ticket-once"))

        val duplicate =
            runCatching {
                    repository.load(
                        target,
                        0,
                        30,
                        "",
                        firstError.partialData?.profile,
                        DestinationFeedType.MOMENTS,
                        false,
                        knownData = firstError.partialData,
                        captchaChallenge = firstError.challenge,
                        captchaProof = proof,
                    )
                }
                .exceptionOrNull()
        assertTrue(duplicate is CaptchaRequiredException)
        assertNull(requests.last().url.queryParameter("ticket"))
        assertNull(requests.last().url.queryParameter("randstr"))
    }

    @Test
    fun captchaDuringProfileRetriesProfileBeforeLoadingMoments() = runBlocking {
        val requests = mutableListOf<Request>()
        val repository =
            DestinationRepository(
                execute = { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/bbs/app/profile/user/profile" ->
                            if (request.url.queryParameter("ticket") == null)
                                """{"status":"show_captcha","appid":2061805035}"""
                            else
                                """{"status":"ok","result":{"account_detail":{"userid":"u2","username":"Reader","bbs_info":{"follow_num":2,"fan_num":3,"awd_num":4,"be_favoured_num":5}}}}"""
                        "/bbs/app/profile/events" ->
                            """{"status":"ok","result":{"moments":[{"linkid":"m2","title":"Moment"}],"lastval":""}}"""
                        else -> error("Unexpected request path")
                    }
                }
            )
        val target = DestinationTarget(DestinationType.USER, "u2", "Reader")
        val profileChallenge =
            runCatching {
                    repository.load(target, 0, 30, null, null, DestinationFeedType.MOMENTS, true)
                }
                .exceptionOrNull() as CaptchaRequiredException
        assertEquals("/bbs/app/profile/user/profile", profileChallenge.challenge.path)
        assertNull(profileChallenge.partialData)
        assertEquals(listOf("/bbs/app/profile/user/profile"), requests.map { it.url.encodedPath })

        val proof =
            (parseCaptchaCallback(
                    """{"ret":0,"ticket":"profile-ticket","randstr":"profile-rand"}"""
                )
                    as CaptchaCallbackResult.Completed)
                .proof
        val page =
            repository.load(
                target,
                0,
                30,
                null,
                null,
                DestinationFeedType.MOMENTS,
                true,
                captchaChallenge = profileChallenge.challenge,
                captchaProof = proof,
            )

        assertEquals(listOf("m2"), page.stories.map { it.id })
        assertEquals(
            listOf(
                "/bbs/app/profile/user/profile",
                "/bbs/app/profile/user/profile",
                "/bbs/app/profile/events",
            ),
            requests.map { it.url.encodedPath },
        )
        assertEquals("profile-ticket", requests[1].url.queryParameter("ticket"))
        assertNull(requests[2].url.queryParameter("ticket"))
        assertEquals("Reader", page.data.title)
    }

    @Test
    fun wrongChallengePathOrParametersDoNotConsumeProof() = runBlocking {
        val requests = mutableListOf<Request>()
        val repository =
            DestinationRepository(
                execute = { request ->
                    requests += request
                    """{"status":"ok","result":{"moments":[],"lastval":""}}"""
                }
            )
        val target = DestinationTarget(DestinationType.USER, "u", "User")
        val parameters =
            mapOf("userid" to "u", "list_type" to "moment", "dw" to "304", "lastval" to "")
        val exact = CaptchaChallenge("/bbs/app/profile/events", parameters, "2061805035")
        val proofForWrongPath = CaptchaProof("path-ticket", "path-rand")
        repository.load(
            target,
            0,
            30,
            "",
            null,
            DestinationFeedType.MOMENTS,
            false,
            captchaChallenge = exact.copy(path = "/bbs/app/profile/events/other"),
            captchaProof = proofForWrongPath,
        )
        assertNull(requests.last().url.queryParameter("ticket"))
        repository.load(
            target,
            0,
            30,
            "",
            null,
            DestinationFeedType.MOMENTS,
            false,
            captchaChallenge = exact,
            captchaProof = proofForWrongPath,
        )
        assertEquals("path-ticket", requests.last().url.queryParameter("ticket"))

        val proofForWrongParams = CaptchaProof("params-ticket", "params-rand")
        repository.load(
            target,
            0,
            30,
            "",
            null,
            DestinationFeedType.MOMENTS,
            false,
            captchaChallenge = exact.copy(parameters = parameters + ("lastval" to "other")),
            captchaProof = proofForWrongParams,
        )
        assertNull(requests.last().url.queryParameter("ticket"))
        repository.load(
            target,
            0,
            30,
            "",
            null,
            DestinationFeedType.MOMENTS,
            false,
            captchaChallenge = exact,
            captchaProof = proofForWrongParams,
        )
        assertEquals("params-ticket", requests.last().url.queryParameter("ticket"))
    }
}
