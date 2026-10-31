package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「入口探测」三类真实失败场景的回归测试（都注入假 transport，不联网）：
 *
 *  1. 站点 301 跳 www：首页 HTML 必须跟着重定向拿到，候选规则要按落地站拼；
 *  2. 站点没有关键词搜索，搜索入口 302 到详情页：含关键词才收成单条结果；
 *  3. HTTP 403（Cloudflare）/ 404：失败文案要能说清到底发生了什么。
 */
class SourceProberFailureModesTest {

    private class FakeTransport(
        private val handler: (SearchHttpRequest) -> SearchHttpResponse
    ) : SearchTransport {
        val requests = mutableListOf<SearchHttpRequest>()
        override fun execute(request: SearchHttpRequest): SearchHttpResponse {
            requests += request
            return handler(request)
        }
    }

    /** 首页表单（MacCMS: GET /search.html + wd）。 */
    private val homepageWithForm = """
        <html><body>
        <form action="/search.html" method="get">
            <input name="wd" type="text">
            <input type="submit" value="搜索">
        </form>
        </body></html>
    """.trimIndent()

    /** 搜索结果列表页：两条详情链接。 */
    private val listHtml = """
        <html><body>
        <a href="/detail/123.html" title="测试影片一">测试影片一</a>
        <a href="/detail/456.html" title="测试影片二">测试影片二</a>
        </body></html>
    """.trimIndent()

    @Test
    fun homepage301ToWwwIsFollowedAndRuleUsesWwwHost() {
        // 用户填 witzsb.com 这类不带 www 的域名：首页 301 到 www，HTML 不能丢，
        // 候选规则也必须拼在 www 上，否则搜索入口永远拼在错的 host。
        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "https://x.com/" ->
                    SearchHttpResponse(req.url, 301, location = "https://www.x.com/")
                req.method == "GET" && req.url == "https://www.x.com/" ->
                    SearchHttpResponse(req.url, 200, body = homepageWithForm)
                req.method == "GET" && req.url.startsWith("https://www.x.com/search.html?wd=") ->
                    SearchHttpResponse(req.url, 200, body = listHtml)
                else -> SearchHttpResponse(req.url, 404)
            }
        }

        val outcome = SourceProber.probe(
            OnlineVideoSource(id = "x", name = "X站", base = "https://x.com"), transport, keyword = "测试"
        )

        assertTrue(outcome.error, outcome.success)
        assertNotNull(outcome.rule)
        assertEquals("https://www.x.com/search.html", outcome.rule!!.urlTemplate)
        assertEquals(2, outcome.itemCount)
        assertTrue(transport.requests.any { it.url.startsWith("https://www.x.com/search.html?wd=") })
    }

    @Test
    fun searchRedirectingToDetailPageWithKeywordBecomesSingleItem() {
        // 这个站没有关键词搜索：搜索入口 302 到某部片的详情页，详情页含关键词 → 收成单条。
        val detailHtml = """
            <html><head>
            <meta property="og:title" content="测试作品-在线观看-免费高清-大象影视">
            <title>测试作品第01集_大象影视</title>
            </head><body><h1>测试作品</h1><p>测试作品 剧情简介…</p></body></html>
        """.trimIndent()
        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "https://www.x.com/" ->
                    SearchHttpResponse(req.url, 200, body = homepageWithForm)
                req.method == "GET" && req.url.startsWith("https://www.x.com/search.html?wd=") ->
                    SearchHttpResponse(req.url, 302, location = "/detail/123.html")
                req.method == "GET" && req.url == "https://www.x.com/detail/123.html" ->
                    SearchHttpResponse(req.url, 200, body = detailHtml)
                else -> SearchHttpResponse(req.url, 404)
            }
        }

        val outcome = SourceProber.probe(
            OnlineVideoSource(id = "x", name = "X站", base = "https://www.x.com"), transport, keyword = "测试"
        )

        assertTrue(outcome.error, outcome.success)
        assertEquals(1, outcome.items.size)
        val item = outcome.items.first()
        assertEquals("测试作品", item.title)
        assertEquals("https://www.x.com/detail/123.html", item.url)
    }

    @Test
    fun redirectToDetailPageWithoutKeywordReportsNoSearchEntry() {
        // 搜索入口 302 到随机热片详情页（站上真事：同一关键词两次跳不同 id）——不能算命中。
        val hotDetail = """
            <html><head><title>御赐小仵作2_第01集-大象影视</title></head>
            <body><h1>御赐小仵作2</h1><p>古装悬疑剧</p></body></html>
        """.trimIndent()
        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "https://www.x.com/" ->
                    SearchHttpResponse(req.url, 200, body = homepageWithForm)
                req.method == "GET" && req.url.startsWith("https://www.x.com/search.html?wd=") ->
                    SearchHttpResponse(req.url, 302, location = "/detail/176020.html")
                req.method == "GET" && req.url == "https://www.x.com/detail/176020.html" ->
                    SearchHttpResponse(req.url, 200, body = hotDetail)
                else -> SearchHttpResponse(req.url, 404)
            }
        }

        val outcome = SourceProber.probe(
            OnlineVideoSource(id = "x", name = "X站", base = "https://www.x.com"), transport, keyword = "深海测试词"
        )

        assertFalse(outcome.success)
        assertEquals(0, outcome.itemCount)
        assertTrue(outcome.error.orEmpty(), outcome.error.orEmpty().contains("没有可搜索入口"))
    }

    @Test
    fun cloudflare403IsReportedAsBlockedAndCountsAsSourceFailure() {
        val cfHtml = """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body><h1>You have been blocked</h1><div class="cf-error-details">Cloudflare Ray ID</div></body></html>
        """.trimIndent()
        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "https://www.x.com/" ->
                    SearchHttpResponse(req.url, 200, body = homepageWithForm)
                req.method == "GET" && req.url.startsWith("https://www.x.com/search.html?wd=") ->
                    SearchHttpResponse(req.url, 403, body = cfHtml)
                else -> SearchHttpResponse(req.url, 404)
            }
        }

        val attempt = SourceProber.attemptRule(
            SearchRule(
                kind = SearchRuleKind.CUSTOM, method = SearchMethod.GET,
                urlTemplate = "https://www.x.com/search.html", keywordParam = "wd"
            ),
            "https://www.x.com", "测试", transport
        )

        assertFalse(attempt.success)
        assertEquals(403, attempt.httpStatus)
        assertTrue(attempt.error.orEmpty(), attempt.error.orEmpty().contains("Cloudflare"))
        assertTrue(attempt.networkFailure)
    }

    @Test
    fun detailPageWithKeywordOnlyInBodyIsNotCountedAsHit() {
        // 关键词只出现在页面正文/导航里（「测试」这种常见词），标题却是另一部片：
        // 不能收成结果，否则跳热片的站会一直搜出不相干的作品。
        val hotDetail = """
            <html><head><title>御赐小仵作2-在线观看-大象影视</title></head>
            <body>
            <nav>电视剧 电影 综艺 测试频道 排行榜</nav>
            <h1>御赐小仵作2</h1><p>古装悬疑剧，测试数据仅供参考</p>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "https://www.x.com/" ->
                    SearchHttpResponse(req.url, 200, body = homepageWithForm)
                req.method == "GET" && req.url.startsWith("https://www.x.com/search.html?wd=") ->
                    SearchHttpResponse(req.url, 302, location = "/detail/176020.html")
                req.method == "GET" && req.url == "https://www.x.com/detail/176020.html" ->
                    SearchHttpResponse(req.url, 200, body = hotDetail)
                else -> SearchHttpResponse(req.url, 404)
            }
        }

        val attempt = SourceProber.attemptRule(
            SearchRule(
                kind = SearchRuleKind.CUSTOM, method = SearchMethod.GET,
                urlTemplate = "https://www.x.com/search.html", keywordParam = "wd"
            ),
            "https://www.x.com", "测试", transport
        )

        assertFalse(attempt.success)
        assertTrue(attempt.error.orEmpty(), attempt.error.orEmpty().contains("没有可搜索入口"))
    }

    @Test
    fun notFoundSearchEntryReports404() {
        val transport = FakeTransport { req -> SearchHttpResponse(req.url, 404, body = "<html>404 Not Found</html>") }

        val attempt = SourceProber.attemptRule(
            SearchRule(
                kind = SearchRuleKind.MACCMS, method = SearchMethod.GET,
                urlTemplate = "https://www.x.com/index.php/vod/search.html?wd={kw}", keywordParam = "wd"
            ),
            "https://www.x.com", "测试", transport
        )

        assertFalse(attempt.success)
        assertEquals(404, attempt.httpStatus)
        assertTrue(attempt.error.orEmpty(), attempt.error.orEmpty().contains("404"))
        // 入口不存在不是源的故障，不该扣健康度。
        assertFalse(attempt.networkFailure)
    }
}
