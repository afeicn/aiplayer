package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「粘贴网页链接自动解析视频流」纯逻辑单测：全部用假 HTML 字符串，不联网。
 */
class WebLinkResolverTest {

    // ==================== 抓取抽象（测试替身） ====================

    private class FakeFetcher(
        private val pages: Map<String, FetchedPage>,
        private val fail: (String) -> WebLinkFetchException? = { null }
    ) : PageFetcher {
        var fetchCount = 0
            private set
        override fun fetch(url: String, referer: String?): FetchedPage {
            fetchCount++
            fail(url)?.let { throw it }
            return pages[url] ?: throw WebLinkFetchException("HTTP 404", 404)
        }
    }

    private fun page(url: String, html: String) = FetchedPage(url = url, contentType = "text/html", body = html)

    // ==================== 真实 empirecms 页面片段 ====================

    private val EMPIRE_CMS_PLAY_URL = "https://cms.example.com/e/DownSys/play/?classid=8&id=29827&bf=2&pathid3=5"
    private val EMPIRE_CMS_PLAY_HTML = """
        <div class="video"><iframe width="100%" height="100%" allowTransparency="true" frameborder="0" scrolling="no" src="https://play.cms.example.com/share/db17bc578c383f5bb0cb9be70c42331c"></iframe><script src="/d/ewfdsg456y.js"></script></div>
    """.trimIndent()
    private val EMPIRE_CMS_SHARE_URL = "https://play.cms.example.com/share/db17bc578c383f5bb0cb9be70c42331c"
    private val EMPIRE_CMS_SHARE_HTML = """
        <script>
            const vid = "db17bc578c383f5bb0cb9be70c42331c";
            const url = "/20261016/32679_db17bc57/index.m3u8?sign=5ee1f2c380f35c7481079f3b09ef9a88";
            const pic = "/20261016/32679_db17bc57/1.jpg";
            const resumeKey = "db17bc578c383f5bb0cb9be70c42331c:progress";
        </script>
    """.trimIndent()
    private val EMPIRE_CMS_EXPECTED_STREAM =
        "https://play.cms.example.com/20261016/32679_db17bc57/index.m3u8?sign=5ee1f2c380f35c7481079f3b09ef9a88"

    // ==================== 流地址 / 网页判定 ====================

    @Test
    fun streamUrlsAreRecognizedByExtension() {
        assertTrue(WebLinkParser.looksLikeStreamUrl("https://x/live.m3u8"))
        assertTrue(WebLinkParser.looksLikeStreamUrl("https://x/live.m3u8?sign=abc"))
        assertTrue(WebLinkParser.looksLikeStreamUrl("https://x/v.mp4"))
        assertTrue(WebLinkParser.looksLikeStreamUrl("https://x/s.ts"))
        assertTrue(WebLinkParser.looksLikeStreamUrl("https://x/list.m3u"))
        assertFalse(WebLinkParser.looksLikeStreamUrl(EMPIRE_CMS_PLAY_URL))
        assertFalse(WebLinkParser.looksLikeStreamUrl("https://x.com/watch/12345"))
    }

    @Test
    fun htmlPagesAreDetectedByContentTypeOrBody() {
        assertTrue(WebLinkParser.isHtmlPage("text/html; charset=utf-8", ""))
        assertTrue(WebLinkParser.isHtmlPage("", "<!DOCTYPE html><html></html>"))
        // 带 BOM 的网页也要识别。
        assertTrue(WebLinkParser.isHtmlPage("", "\uFEFF<!DOCTYPE html>"))
        assertFalse(WebLinkParser.isHtmlPage("application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-STREAM-INF"))
        assertFalse(WebLinkParser.isHtmlPage("video/mp4", ""))
    }

    // ==================== URL 补全 / Referer ====================

    @Test
    fun absoluteUrlIsKeptAsIs() {
        assertEquals(
            "https://cdn.example.com/v.m3u8",
            WebLinkParser.resolveUrl("https://example.com/watch.html", "https://cdn.example.com/v.m3u8")
        )
    }

    @Test
    fun rootRelativeUrlIsResolvedAgainstHost() {
        assertEquals(
            "https://example.com/videos/ep1.m3u8",
            WebLinkParser.resolveUrl("https://example.com/watch/page.html", "/videos/ep1.m3u8")
        )
    }

    @Test
    fun relativeUrlIsResolvedAgainstDirectory() {
        assertEquals(
            "https://example.com/watch/videos/ep1.m3u8",
            WebLinkParser.resolveUrl("https://example.com/watch/page.html", "videos/ep1.m3u8")
        )
    }

    @Test
    fun protocolRelativeUrlKeepsScheme() {
        assertEquals(
            "https://cdn.example.com/v.m3u8",
            WebLinkParser.resolveUrl("https://example.com/watch.html", "//cdn.example.com/v.m3u8")
        )
    }

    @Test
    fun refererIsOriginWithTrailingSlash() {
        assertEquals("https://play.cms.example.com/", WebLinkParser.refererOrigin(EMPIRE_CMS_SHARE_URL))
        assertEquals("https://example.com:8080/", WebLinkParser.refererOrigin("https://example.com:8080/a/b.html"))
    }

    // ==================== 直链 / JS 变量 / iframe 提取 ====================

    @Test
    fun directM3u8InVideoSrcIsExtracted() {
        val html = """<video controls src="https://cdn.example.com/movie.m3u8"></video>"""
        assertEquals(listOf("https://cdn.example.com/movie.m3u8"), WebLinkParser.extractDirectM3u8(html))
    }

    @Test
    fun relativeM3u8InHrefIsExtractedAsIs() {
        val html = """<a href="/videos/ep1.m3u8">播放</a>"""
        assertEquals(listOf("/videos/ep1.m3u8"), WebLinkParser.extractDirectM3u8(html))
    }

    @Test
    fun jsVariableM3u8IsExtracted() {
        val html = """<script>var playUrl = "https://cdn.example.com/s.m3u8?token=1";</script>"""
        assertEquals(listOf("https://cdn.example.com/s.m3u8?token=1"), WebLinkParser.extractJsM3u8(html))
    }

    @Test
    fun jsConstM3u8IsExtracted() {
        val html = """<script>const url = "/20261016/32679_db17bc57/index.m3u8?sign=abc";</script>"""
        assertEquals(
            listOf("/20261016/32679_db17bc57/index.m3u8?sign=abc"),
            WebLinkParser.extractJsM3u8(html)
        )
    }

    @Test
    fun bareExtensionFromJsVariableIsDroppedFromCandidates() {
        // `var ext = ".m3u8"` 这类字面量不能被当成可播地址（会补全成 / .m3u8）。
        val html = """<script>var ext = ".m3u8"; var base = "/index";</script>"""
        assertTrue(WebLinkParser.extractStreamCandidates(html, "https://site.com/watch.html").isEmpty())
    }

    @Test
    fun iframeSrcIsExtractedAndResolved() {
        val html = """<iframe width="100%" src="https://play.cms.example.com/share/abc"></iframe>"""
        assertEquals(
            listOf("https://play.cms.example.com/share/abc"),
            WebLinkParser.extractIframeSrcs(html, EMPIRE_CMS_PLAY_URL)
        )
    }

    @Test
    fun iframeSrcIgnoresJavascriptPseudoProtocol() {
        val html = """<iframe src="javascript:void(0)"></iframe>"""
        assertTrue(WebLinkParser.extractIframeSrcs(html, EMPIRE_CMS_PLAY_URL).isEmpty())
    }

    // ==================== 解析编排 ====================

    @Test
    fun directStreamUrlPlaysWithoutFetching() {
        val fetcher = FakeFetcher(emptyMap())
        val result = WebLinkResolver.resolve("https://x.com/v.mp4", fetcher)
        assertTrue(result is WebLinkResult.Success)
        assertEquals("https://x.com/v.mp4", (result as WebLinkResult.Success).stream.url)
        assertEquals(0, fetcher.fetchCount)
    }

    @Test
    fun directM3u8InHtmlResolvesAndCarriesHeaders() {
        val fetcher = FakeFetcher(
            mapOf("https://site.com/watch.html" to page("https://site.com/watch.html",
                """<video src="https://cdn.example.com/movie.m3u8"></video>"""))
        )
        val result = WebLinkResolver.resolve("https://site.com/watch.html", fetcher)

        val stream = (result as WebLinkResult.Success).stream
        assertEquals("https://cdn.example.com/movie.m3u8", stream.url)
        assertEquals("https://site.com/", stream.headers["Referer"])
        assertEquals(DEFAULT_DESKTOP_UA, stream.headers["User-Agent"])
    }

    @Test
    fun relativeM3u8IsResolvedAgainstPageUrl() {
        val fetcher = FakeFetcher(
            mapOf("https://example.com/watch/ep1.html" to page("https://example.com/watch/ep1.html",
                """<a href="/videos/ep1.m3u8">播放</a>"""))
        )
        val result = WebLinkResolver.resolve("https://example.com/watch/ep1.html", fetcher)

        assertEquals(
            "https://example.com/videos/ep1.m3u8",
            (result as WebLinkResult.Success).stream.url
        )
    }

    @Test
    fun jsVariableM3u8Resolves() {
        val fetcher = FakeFetcher(
            mapOf("https://site.com/player.html" to page("https://site.com/player.html",
                """<script>var url = "https://cdn.example.com/s.m3u8";</script>"""))
        )
        val result = WebLinkResolver.resolve("https://site.com/player.html", fetcher)

        assertEquals("https://cdn.example.com/s.m3u8", (result as WebLinkResult.Success).stream.url)
    }

    @Test
    fun iframeOneLayerRecursionFindsStream() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://a.com/page" to page("https://a.com/page", """<iframe src="https://b.com/embed"></iframe>"""),
                "https://b.com/embed" to page("https://b.com/embed", """<video src="https://cdn/m.m3u8"></video>""")
            )
        )
        val result = WebLinkResolver.resolve("https://a.com/page", fetcher)

        val stream = (result as WebLinkResult.Success).stream
        assertEquals("https://cdn/m.m3u8", stream.url)
        assertEquals("https://b.com/", stream.headers["Referer"])
        assertEquals(2, fetcher.fetchCount)
    }

    @Test
    fun iframeTwoLayerRecursionFindsStream() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://a.com/page" to page("https://a.com/page", """<iframe src="https://b.com/embed"></iframe>"""),
                "https://b.com/embed" to page("https://b.com/embed", """<iframe src="https://c.com/embed2"></iframe>"""),
                "https://c.com/embed2" to page("https://c.com/embed2", """<video src="https://cdn/m.m3u8"></video>""")
            )
        )
        val result = WebLinkResolver.resolve("https://a.com/page", fetcher)

        assertEquals("https://cdn/m.m3u8", (result as WebLinkResult.Success).stream.url)
        assertEquals("https://c.com/", result.stream.headers["Referer"])
        assertEquals(3, fetcher.fetchCount)
    }

    @Test
    fun iframeBeyondTwoLayersGivesUp() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://a.com/page" to page("https://a.com/page", """<iframe src="https://b.com/e1"></iframe>"""),
                "https://b.com/e1" to page("https://b.com/e1", """<iframe src="https://c.com/e2"></iframe>"""),
                // 第 3 层 iframe：本应再抓一次，但超过 2 层要放弃。
                "https://c.com/e2" to page("https://c.com/e2", """<iframe src="https://d.com/e3"></iframe>"""),
                "https://d.com/e3" to page("https://d.com/e3", """<video src="https://cdn/m.m3u8"></video>""")
            )
        )
        val result = WebLinkResolver.resolve("https://a.com/page", fetcher)

        assertTrue(result is WebLinkResult.Failure)
        assertEquals(WebLinkFailureKind.NO_STREAM, (result as WebLinkResult.Failure).kind)
        // 只抓到第 3 层（depth 2）就停，绝不碰 d.com。
        assertEquals(3, fetcher.fetchCount)
    }

    @Test
    fun noStreamReportsReadableError() {
        val fetcher = FakeFetcher(
            mapOf("https://a.com/page" to page("https://a.com/page", "<html><body>广告页</body></html>"))
        )
        val result = WebLinkResolver.resolve("https://a.com/page", fetcher)

        val failure = result as WebLinkResult.Failure
        assertEquals(WebLinkFailureKind.NO_STREAM, failure.kind)
        assertEquals("没解析出可播放内容", failure.message)
    }

    @Test
    fun nonHtmlResponseIsPlayedAsStream() {
        val fetcher = FakeFetcher(
            mapOf("https://x.com/stream/123" to FetchedPage(
                url = "https://x.com/stream/123",
                contentType = "application/vnd.apple.mpegurl",
                body = "#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000\n3000k/hls/mixed.m3u8\n"
            ))
        )
        val result = WebLinkResolver.resolve("https://x.com/stream/123", fetcher)

        assertEquals("https://x.com/stream/123", (result as WebLinkResult.Success).stream.url)
    }

    @Test
    fun http403ReportsNeedLogin() {
        val fetcher = FakeFetcher(
            emptyMap(),
            fail = { throw WebLinkFetchException("HTTP 403", 403) }
        )
        val result = WebLinkResolver.resolve("https://x.com/watch", fetcher)

        val failure = result as WebLinkResult.Failure
        assertEquals(WebLinkFailureKind.NEEDS_LOGIN, failure.kind)
        assertTrue(failure.message, failure.message.contains("403"))
    }

    @Test
    fun networkErrorReportsReadableReason() {
        val fetcher = FakeFetcher(
            emptyMap(),
            fail = { throw WebLinkFetchException("connect timeout") }
        )
        val result = WebLinkResolver.resolve("https://x.com/watch", fetcher)

        assertEquals(WebLinkFailureKind.NETWORK, (result as WebLinkResult.Failure).kind)
    }

    // ==================== empirecms 站点适配 ====================

    @Test
    fun empirecmsPlayPageIsRecognized() {
        assertTrue(EmpireCmsRule.isPlayPage(EMPIRE_CMS_PLAY_URL))
        assertFalse(EmpireCmsRule.isPlayPage("https://cms.example.com/xijupian/"))
        // 规则按路径模式识别，不绑定任何站点域名。
        assertTrue(EmpireCmsRule.isPlayPage("https://other.com/e/DownSys/play/"))
    }

    @Test
    fun empirecmsShareRegexExtractsStreamPath() {
        assertEquals(
            "/20261016/32679_db17bc57/index.m3u8?sign=5ee1f2c380f35c7481079f3b09ef9a88",
            EmpireCmsRule.extractShareStreamPath(EMPIRE_CMS_SHARE_HTML)
        )
    }

    @Test
    fun empirecmsRealCaseResolvesThroughIframeToStreamWithReferer() {
        val fetcher = FakeFetcher(
            mapOf(
                EMPIRE_CMS_PLAY_URL to page(EMPIRE_CMS_PLAY_URL, EMPIRE_CMS_PLAY_HTML),
                EMPIRE_CMS_SHARE_URL to page(EMPIRE_CMS_SHARE_URL, EMPIRE_CMS_SHARE_HTML)
            )
        )
        val result = WebLinkResolver.resolve(EMPIRE_CMS_PLAY_URL, fetcher)

        val stream = (result as WebLinkResult.Success).stream
        assertEquals(EMPIRE_CMS_EXPECTED_STREAM, stream.url)
        assertEquals("https://play.cms.example.com/", stream.headers["Referer"])
        assertEquals(DEFAULT_DESKTOP_UA, stream.headers["User-Agent"])
        assertTrue(stream.isHls)
        assertEquals(2, fetcher.fetchCount)
    }

    // ==================== 请求头拼装（复用第一块，此处验证解析流确实带上） ====================

    @Test
    fun resolvedStreamHeadersAssembleRefererAndUa() {
        val headers = buildRequestHeaders(
            referer = "https://play.cms.example.com/",
            userAgent = DEFAULT_DESKTOP_UA
        )
        assertEquals("https://play.cms.example.com/", headers["Referer"])
        assertEquals(DEFAULT_DESKTOP_UA, headers["User-Agent"])
    }
}
