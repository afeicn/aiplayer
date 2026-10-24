package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「继续下载」兜底定位来源的单测（不联网）。
 *
 * 用两个**真实抓来的 fixture** 跑完整链路：
 *  - `empirecms-home-form.html`：empirecms 首页真实的搜索表单（POST `/e/search/so.php`，关键词字段 `keyboard`）；
 *  - `empirecms-search-result.html`：搜索「我不是大师」的真实结果页（脏标题 `[全集]">…` 就在里面）。
 *
 * 覆盖：有 detailUrl 直接用 / 无 detailUrl 但能搜到 / 无 detailUrl 且搜不到（提示文案）/
 * 搜到别的剧不硬凑 / detailUrl 回写到该剧所有条目 / 站点基址解析。
 */
class ContinueSourceLocatorTest {

    private val detailUrl = "https://cms.example.com/dianshiju/guoju/29683.html"
    private val title = "我不是大师[全集]"
    private val dirtyTitle = "我不是大师[全集]\">我不是大师[全集]"

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private class FakeTransport(
        private val handler: (SearchHttpRequest) -> SearchHttpResponse
    ) : SearchTransport {
        val requests = mutableListOf<SearchHttpRequest>()
        override fun execute(request: SearchHttpRequest): SearchHttpResponse {
            requests += request
            return handler(request)
        }
    }

    /** 真实站点行为的假传输：首页给真实表单，POST 搜索 302 到真实结果页。 */
    private fun realSiteTransport(): FakeTransport {
        val home = resource("empirecms-home-form.html")
        val result = resource("empirecms-search-result.html")
        return FakeTransport { request ->
            when {
                request.url.endsWith("/e/search/so.php") -> SearchHttpResponse(
                    url = request.url, status = 302, location = "/e/search/result/?searchid=115327"
                )
                request.url.contains("/e/search/result/") ->
                    SearchHttpResponse(url = request.url, status = 200, body = result, contentType = "text/html")
                request.url == "https://cms.example.com/" ->
                    SearchHttpResponse(url = request.url, status = 200, body = home, contentType = "text/html")
                else -> SearchHttpResponse(url = request.url, status = 404, body = "")
            }
        }
    }

    private fun legacyTarget(sourceName: String = "https://cms.example.com/") = ContinueTarget(
        title = title,
        detailUrl = "",
        sourceName = sourceName
    )

    // ==================== A. 直接复用 / 兜底定位 ====================

    @Test
    fun 有detailUrl时直接用不发起搜索() {
        val transport = FakeTransport { error("有详情页就不该联网") }
        val target = ContinueTarget(title = title, detailUrl = detailUrl, sourceName = "https://cms.example.com/")
        assertFalse(ContinueDownloadRules.needsLookup(target.detailUrl))
        val lookup = ContinueSourceLocator.locate(target, transport)
        assertNotNull(lookup)
        assertEquals(detailUrl, lookup!!.detailUrl)
        assertEquals("https://cms.example.com", lookup.baseUrl)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun 老数据无detailUrl时按剧名加站点重新定位到详情页() {
        val transport = realSiteTransport()
        val lookup = ContinueSourceLocator.locate(legacyTarget(), transport)
        assertNotNull("真实搜索结果页里能定位到详情页", lookup)
        assertEquals(detailUrl, lookup!!.detailUrl)
        assertEquals("https://cms.example.com", lookup.baseUrl)
        // 走的是站内搜索：首页探测表单 → POST 搜索 → 跟随 302 拿结果页
        assertTrue(transport.requests.any { it.method == "GET" && it.url == "https://cms.example.com/" })
        assertTrue(transport.requests.any { it.method == "POST" && it.url.endsWith("/e/search/so.php") })
        assertTrue(transport.requests.any { it.url.contains("/e/search/result/") })
    }

    @Test
    fun 搜索关键词用剧名主干先搜再试完整剧名() {
        assertEquals(listOf("我不是大师", title), ContinueSourceLocator.keywordsOf(title))
        assertEquals(listOf("庆余年"), ContinueSourceLocator.keywordsOf("庆余年"))
        assertTrue(ContinueSourceLocator.keywordsOf("   ").isEmpty())
    }

    @Test
    fun 无detailUrl且搜不到时返回空并给可操作提示() {
        val transport = FakeTransport { request ->
            SearchHttpResponse(url = request.url, status = 200, body = "<html><body>没有搜到相关内容</body></html>", contentType = "text/html")
        }
        assertNull(ContinueSourceLocator.locate(legacyTarget(), transport))
        assertEquals("这部作品的来源页找不到了，请重新搜索后下载", ContinueDownloadRules.LOOKUP_FAILED)
        assertEquals(ContinueDownloadRules.LOOKUP_FAILED, ContinueDownloadRules.lookupMessage(false))
        assertNull(ContinueDownloadRules.lookupMessage(true))
    }

    @Test
    fun 搜到别的剧时不硬凑() {
        val html = """
            <ul>
              <li class="post"><div class="article"><h2>
                <a href="/dianshiju/guoju/99999.html" title="另一部剧">另一部剧</a>
              </h2></div></li>
            </ul>
            <p>关键词：我不是大师</p>
        """.trimIndent()
        val transport = FakeTransport { request ->
            SearchHttpResponse(url = request.url, status = 200, body = html, contentType = "text/html")
        }
        assertNull(ContinueSourceLocator.locate(legacyTarget(), transport))
    }

    @Test
    fun 站点搜不到时顺带试已配置的其它源() {
        val result = """
            <ul>
              <li class="post"><h2>
                <a href="https://other.example.com/dianshiju/1.html" title="我不是大师[全集]">我不是大师[全集]</a>
              </h2></li>
            </ul>
            <p>关键词：我不是大师</p>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when {
                request.url.startsWith("https://other.example.com") ->
                    SearchHttpResponse(url = request.url, status = 200, body = result, contentType = "text/html")
                else -> SearchHttpResponse(url = request.url, status = 200, body = "<html>没有结果</html>", contentType = "text/html")
            }
        }
        val other = OnlineVideoSource(id = "other", name = "备用源", base = "https://other.example.com")
        val lookup = ContinueSourceLocator.locate(legacyTarget(), transport, extraSources = listOf(other))
        assertNotNull(lookup)
        assertEquals("https://other.example.com/dianshiju/1.html", lookup!!.detailUrl)
        assertEquals("备用源", lookup.sourceName)
    }

    // ==================== B. 候选源与站点基址 ====================

    @Test
    fun 站点基址从sourceName里取() {
        assertEquals("https://cms.example.com", ContinueSourceLocator.baseUrlOf("https://cms.example.com/"))
        assertEquals("https://cms.example.com", ContinueSourceLocator.baseUrlOf("https://cms.example.com"))
        assertEquals("https://example.com", ContinueSourceLocator.baseUrlOf("https://example.com/"))
        assertEquals("https://cms.example.com", ContinueSourceLocator.baseUrlOf("cms.example.com"))
        // 展示用的源名不是地址 → 取不出基址
        assertEquals("", ContinueSourceLocator.baseUrlOf("6v电影"))
        assertEquals("", ContinueSourceLocator.baseUrlOf(""))
    }

    @Test
    fun 候选源优先真实站点再顺带其它源() {
        val other = OnlineVideoSource(id = "o1", name = "备用", base = "https://other.example.com")
        val disabled = OnlineVideoSource(id = "o2", name = "停用", base = "https://off.example.com", enabled = false)
        val same = OnlineVideoSource(id = "o3", name = "重复", base = "https://cms.example.com/")
        val sources = ContinueSourceLocator.candidateSources(
            legacyTarget(), listOf(other, disabled, same)
        )
        assertEquals(2, sources.size)
        assertEquals("https://cms.example.com", sources[0].normalizedBase)
        assertEquals("https://other.example.com", sources[1].normalizedBase)
    }

    @Test
    fun 来源标签只显示域名() {
        assertEquals("cms.example.com", ContinueDownloadRules.sourceLabel("https://cms.example.com/"))
        assertEquals("6v电影", ContinueDownloadRules.sourceLabel("6v电影"))
        assertEquals("", ContinueDownloadRules.sourceLabel("  "))
    }

    @Test
    fun 同域名结果优先且精确匹配得分最高() {
        val base = "https://cms.example.com"
        val wanted = SearchResultItem(title = "我不是大师[全集]", url = "$base/dianshiju/guoju/29683.html")
        val otherHost = SearchResultItem(title = "我不是大师[全集]", url = "https://other.example.com/1.html")
        val coreOnly = SearchResultItem(title = "我不是大师 2026", url = "$base/x/2.html")
        assertEquals(wanted, ContinueSourceLocator.pick(listOf(otherHost, coreOnly, wanted), title, base))
        assertTrue(ContinueSourceLocator.score(wanted, title, base) > ContinueSourceLocator.score(otherHost, title, base))
        assertEquals(0, ContinueSourceLocator.score(SearchResultItem("三体", "$base/3.html"), title, base))
    }

    // ==================== C. detailUrl 回写 ====================

    private fun item(id: String, title: String, detailUrl: String = "", sourceName: String = "https://cms.example.com/") =
        OfflineItem(
            id = id,
            title = title,
            url = "https://cdn.example.com/$id.m3u8",
            sourceName = sourceName,
            episodeName = "第0${id}集",
            totalEpisodes = 24,
            detailUrl = detailUrl
        )

    @Test
    fun 定位成功后回写到该剧所有条目() {
        val items = listOf(
            item("1", dirtyTitle),
            item("2", dirtyTitle),
            item("3", dirtyTitle),
            item("4", "庆余年"),
            item("5", "庆余年", detailUrl = "https://cms.example.com/dianshiju/guoju/10000.html")
        )
        val patched = ContinueDownloadRules.withDetailUrl(items, dirtyTitle, detailUrl, "")
        // 脏标题的三条都拿到同一条详情页
        assertEquals(listOf(detailUrl, detailUrl, detailUrl), patched.take(3).map { it.detailUrl })
        // 别的剧不受影响
        assertEquals("", patched[3].detailUrl)
        assertEquals("https://cms.example.com/dianshiju/guoju/10000.html", patched[4].detailUrl)
    }

    @Test
    fun 回写时空剧名或空详情页都幂等() {
        val single = item("1", dirtyTitle)
        assertEquals(single, ContinueDownloadRules.patchDetailUrl(single, title, ""))
        assertEquals(single, ContinueDownloadRules.patchDetailUrl(single, "庆余年", detailUrl))
        val patched = ContinueDownloadRules.patchDetailUrl(single, title, detailUrl)
        assertEquals(detailUrl, patched.detailUrl)
        // sourceName 已有值时不覆盖
        assertEquals("https://cms.example.com/", patched.sourceName)
        // 二次回写结果相同
        assertEquals(patched, ContinueDownloadRules.patchDetailUrl(patched, title, detailUrl))
    }

    @Test
    fun 回写后目标能直接用详情页不再搜索() {
        val items = ContinueDownloadRules.withDetailUrl(listOf(item("1", dirtyTitle), item("2", dirtyTitle)), dirtyTitle, detailUrl, "")
        val target = ContinueDownloadRules.targetOf(items, dirtyTitle)!!
        assertEquals(detailUrl, target.detailUrl)
        assertFalse(ContinueDownloadRules.needsLookup(target.detailUrl))
        assertEquals("我不是大师[全集]", target.title)
    }
}
