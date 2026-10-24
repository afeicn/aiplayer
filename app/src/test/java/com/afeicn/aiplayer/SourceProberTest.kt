package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「入口探测状态机」单测：候选规则逐个试探、POST→302→GET 搜索变体、全失败提示。
 * 用假 [SearchTransport] 注入响应，不联网。
 */
class SourceProberTest {

    private val base = "https://cms.example.com"

    private val homepage = """
        <html><body>
        <form method="post" action="/e/search/so.php">
            <input type="hidden" name="show" value="title">
            <input type="hidden" name="tempid" value="1">
            <input type="hidden" name="tbname" value="article">
            <input type="hidden" name="mid" value="1">
            <input type="hidden" name="dopost" value="search">
            <input name="keyboard" type="text">
            <input type="submit" value="搜索">
        </form>
        </body></html>
    """.trimIndent()

    private val resultsHtml = """
        <li class="post box row fixed-hight"><div class="article">
          <h2><a href="/kehuanpian/20830.html">测试影片</a></h2>
          <div class="entry_post"><p>◎年　　代　2024</p></div>
        </div></li>
    """.trimIndent()

    private class FakeTransport(
        private val handler: (SearchHttpRequest) -> SearchHttpResponse
    ) : SearchTransport {
        val requests = mutableListOf<SearchHttpRequest>()
        override fun execute(request: SearchHttpRequest): SearchHttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun source() = OnlineVideoSource(id = "empirecms", name = "6v电影", base = base)

    @Test
    fun probeWalksCandidatesUntilFirstHitAndCachesRule() {
        // 候选1：首页表单 POST → 302 → GET result 返回「无详情链接」页 → 失败；
        // 候选2：标准 GET /e/search/?keyboard=测试 → 命中。
        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "$base/" -> SearchHttpResponse(req.url, 200, body = homepage)
                req.method == "POST" && req.url.endsWith("/e/search/so.php") ->
                    SearchHttpResponse(req.url, 302, location = "result/?searchid=1")
                req.method == "GET" && req.url.contains("/result/?searchid=1") ->
                    SearchHttpResponse(req.url, 200, body = "<html><p>没有找到相关影片</p></html>")
                req.method == "GET" && req.url.contains("/e/search/?keyboard=") ->
                    SearchHttpResponse(req.url, 200, body = resultsHtml)
                else -> SearchHttpResponse(req.url, 404)
            }
        }
        val outcome = SourceProber.probe(source(), transport)
        assertTrue(outcome.success)
        assertNotNull(outcome.rule)
        assertEquals(SearchRuleKind.EMPIRE_GET, outcome.rule!!.kind)
        assertEquals(1, outcome.itemCount)
        // 探测过程：首页 GET → 表单 POST → 302 的 GET → 标准路径 GET（命中）
        val methods = transport.requests.map { it.method }
        assertTrue(methods.contains("POST"))
        assertTrue(transport.requests.any { it.url.contains("/e/search/?keyboard=") })
    }

    @Test
    fun executeSearchFollowsPost302AsGet() {
        val rule = SearchRule(
            kind = SearchRuleKind.EMPIRE_FORM,
            method = SearchMethod.POST,
            urlTemplate = "/e/search/so.php",
            keywordParam = "keyboard",
            staticParams = mapOf("show" to "title", "tempid" to "1", "tbname" to "article", "mid" to "1", "dopost" to "search"),
            followRedirectAsGet = true
        )
        val transport = FakeTransport { req ->
            when {
                req.method == "POST" -> SearchHttpResponse(req.url, 302, location = "result/?searchid=18708")
                req.method == "GET" && req.url.contains("searchid=18708") ->
                    SearchHttpResponse(req.url, 200, body = resultsHtml)
                else -> SearchHttpResponse(req.url, 500)
            }
        }
        val body = SourceProber.executeSearch(rule, base, "测试", transport)
        assertNotNull(body)
        assertTrue(body!!.contains("测试影片"))
        // 第一次 POST，第二次用 GET 跟随相对 Location（不是重发 POST）
        assertEquals("POST", transport.requests[0].method)
        assertEquals("GET", transport.requests[1].method)
        assertTrue(transport.requests[1].url.endsWith("/e/search/result/?searchid=18708"))
    }

    @Test
    fun probeFailsWhenAllCandidatesMiss() {
        val transport = FakeTransport { req -> SearchHttpResponse(req.url, 200, body = "<html><p>空页面</p></html>") }
        val outcome = SourceProber.probe(source(), transport)
        assertFalse(outcome.success)
        assertNotNull(outcome.error)
    }

    // ==================== 粘的不是首页 + AI 提候选入口 ====================

    /** 假模型：返回固定 JSON，不联网。 */
    private fun fakeAi(json: String) = DeepSeekClient(
        transport = LlmTransport { _, _, _, _ -> json }, apiKey = "k", model = "deepseek-chat"
    )

    @Test
    fun pastedPlayPageStillProbesAgainstSiteRoot() {
        // 用户粘播放页：确定性候选按站点根拼就能命中，不需要模型。
        val transport = FakeTransport { req ->
            when {
                req.url.contains("/vodplay/") -> SearchHttpResponse(req.url, 200, body = "<html><body>播放页</body></html>")
                req.url == "$base/e/search/?keyboard=%E6%B5%8B%E8%AF%95" ->
                    SearchHttpResponse(req.url, 200, body = resultsHtml)
                else -> SearchHttpResponse(req.url, 404)
            }
        }
        val playPage = OnlineVideoSource(id = "empirecms", name = "6v电影", base = "$base/html/tv/20830.html")

        val outcome = SourceProber.probe(playPage, transport)

        assertTrue(outcome.success)
        // 关键：请求地址里不能出现「播放页路径 + /e/search/」这种拼接。
        assertTrue(transport.requests.none { it.url.contains("20830.html/e/search") })
        assertTrue(transport.requests.any { it.url.startsWith("$base/e/search/") })
    }

    @Test
    fun aiCandidateIsVerifiedByRealRequestBeforeSourceIsEnabled() {
        // 确定性候选全失败；AI 提的入口真发请求后命中 → 成功，且标记 aiAssisted。
        // 入口路径刻意用 /custom-search：不在确定性候选清单里，才能证明是 AI 那条路走通的。
        val transport = FakeTransport { req ->
            if (req.url.contains("/custom-search")) SearchHttpResponse(req.url, 200, body = resultsHtml)
            else SearchHttpResponse(req.url, 200, body = "<html><body>没有表单的页面</body></html>")
        }
        val playPage = OnlineVideoSource(id = "empirecms", name = "6v电影", base = "$base/vodplay/20830-1-1.html")
        val ai = fakeAi("""{"items":[{"url":"$base/custom-search?q={kw}","keywordParam":"q"}]}""")

        val outcome = SourceProber.probeWithAi(playPage, transport, ai)

        assertTrue(outcome.success)
        assertTrue(outcome.aiAssisted)
        assertEquals("$base/custom-search?q={kw}", outcome.rule!!.urlTemplate)
        assertEquals(1, outcome.itemCount)
    }

    @Test
    fun aiSuggestionThatReturnsNothingUsableDoesNotEnableSource() {
        // 模型给了模板但实测拿不到结果 → 仍然失败：可用与否由真实请求判定，不采信模型。
        val transport = FakeTransport { req -> SearchHttpResponse(req.url, 200, body = "<html><body>空</body></html>") }
        val ai = fakeAi("""{"items":[{"url":"$base/vodsearch/{kw}----------.html"}]}""")

        val outcome = SourceProber.probeWithAi(source(), transport, ai)

        assertFalse(outcome.success)
        assertNotNull(outcome.error)
    }

    @Test
    fun aiSuggestionFromAnotherSiteIsRejected() {
        // 模型顺手给出别的域名（它记得的公共 CMS 接口）：不是用户添加的这个源，直接丢。
        val transport = FakeTransport { req -> SearchHttpResponse(req.url, 200, body = "<html><body>空</body></html>") }
        val ai = fakeAi("""{"items":[{"url":"https://other.example.com/vodsearch/{kw}.html"}]}""")

        val outcome = SourceProber.probeWithAi(source(), transport, ai)

        assertFalse(outcome.success)
        assertTrue(transport.requests.none { it.url.contains("other.example.com") })
    }

    @Test
    fun deterministicSuccessSkipsAiCall() {
        // 确定性路径已经命中，就不该再花模型额度。
        val transport = FakeTransport { req ->
            if (req.url.contains("/e/search/?keyboard=")) SearchHttpResponse(req.url, 200, body = resultsHtml)
            else SearchHttpResponse(req.url, 200, body = homepage)
        }
        var aiCalls = 0
        val ai = DeepSeekClient(
            transport = LlmTransport { _, _, _, _ -> aiCalls++; """{"items":[]}""" }, apiKey = "k", model = "m"
        )

        val outcome = SourceProber.probeWithAi(source(), transport, ai)

        assertTrue(outcome.success)
        assertEquals(0, aiCalls)
    }

    @Test
    fun probeEmitsStepEventsForSessionFlowUi() {
        // 会话流 UI 依赖事件：抓页 → 逐条候选（进行中→结果）→ 最后一步是命中结论。
        val transport = FakeTransport { req ->
            if (req.url.contains("/e/search/?keyboard=")) SearchHttpResponse(req.url, 200, body = resultsHtml)
            else SearchHttpResponse(req.url, 200, body = homepage)
        }
        val events = mutableListOf<ProbeEvent>()

        val outcome = SourceProber.probe(source(), transport) { events += it }

        assertTrue(outcome.success)
        // 第一条是「抓取站点页面」的进行中事件。
        assertTrue(events.first().label.contains("抓取站点页面"))
        assertEquals(ProbeStepStatus.RUNNING, events.first().status)
        assertTrue(events.last().label.contains("命中"))
        assertEquals(ProbeStepStatus.OK, events.last().status)
        // 每条候选规则先「进行中」再出结果，成对出现。
        assertTrue(events.count { it.status == ProbeStepStatus.RUNNING } >= 1)
        assertTrue(events.count { it.status != ProbeStepStatus.RUNNING } >=
            events.count { it.status == ProbeStepStatus.RUNNING })
    }
}
