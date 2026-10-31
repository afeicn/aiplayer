package com.afeicn.aiplayer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * [HttpPageFetcher] 的 Cloudflare 接线单测：起一个本地 HTTP 服务器，真实跑
 * HttpURLConnection，验证 Cookie 头、403 挑战判定、预热重试与向后兼容。
 */
class HttpPageFetcherTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private var handler: (HttpExchange) -> Unit = {}
    private val requests = mutableListOf<RecordedRequest>()

    private data class RecordedRequest(
        val path: String,
        val cookie: String?,
        val userAgent: String?
    )

    @Before
    fun setUp() {
        CloudflareCookieStore.clearAll()
        CloudflareCookieStore.resetClock()
        requests.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests.add(
                    RecordedRequest(
                        path = exchange.requestURI.path,
                        cookie = exchange.requestHeaders.getFirst("Cookie"),
                        userAgent = exchange.requestHeaders.getFirst("User-Agent")
                    )
                )
            }
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        CloudflareCookieStore.clearAll()
        CloudflareCookieStore.resetClock()
    }

    @Test
    fun noArgConstructorKeepsLegacyBehaviorWithoutCookie() {
        handler = { exchange -> respond(exchange, 200, "<html><body>ok</body></html>") }

        val page = HttpPageFetcher().fetch("$baseUrl/page", null)

        assertEquals("<html><body>ok</body></html>", page.body)
        assertEquals("text/html", page.contentType)
        assertNull(requests.single().cookie)
    }

    @Test
    fun cookieStoreAddsCookieHeaderAndReusesWarmedUserAgent() {
        CloudflareCookieStore.put("127.0.0.1", "cf_clearance=abc; __cf_bm=xyz", "WarmMobileUA")
        handler = { exchange -> respond(exchange, 200, "<html>ok</html>") }

        HttpPageFetcher(cookieStore = CloudflareCookieStore).fetch("$baseUrl/page", null)

        assertEquals("cf_clearance=abc; __cf_bm=xyz", requests.single().cookie)
        // cf_clearance 与 UA 绑定：带 cookie 时必须用预热时的 UA，而不是桌面 UA。
        assertEquals("WarmMobileUA", requests.single().userAgent)
    }

    @Test
    fun withoutCookieUsesDesktopUserAgent() {
        handler = { exchange -> respond(exchange, 200, "<html>ok</html>") }

        HttpPageFetcher(cookieStore = CloudflareCookieStore).fetch("$baseUrl/page", null)

        assertEquals(DEFAULT_DESKTOP_UA, requests.single().userAgent)
        assertNull(requests.single().cookie)
    }

    @Test
    fun cloudflare403TriggersOneWarmAndRetriesWithCookie() {
        var warmCount = 0
        handler = { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            if (cookie?.contains("cf_clearance=ok") == true) {
                respond(exchange, 200, """{"code":1}""", "application/json")
            } else {
                respond(
                    exchange,
                    403,
                    """<html><head><title>Just a moment...</title></head>
                       <body><div class="cf-error-details">Cloudflare Ray ID</div></body></html>"""
                )
            }
        }
        val warmer = CloudflareWarmup { host ->
            warmCount++
            CloudflareCookieStore.put(host, "cf_clearance=ok", "WarmMobileUA")
            true
        }
        val fetcher = HttpPageFetcher(
            cookieStore = CloudflareCookieStore,
            warmer = { warmer }
        )

        val page = fetcher.fetch("$baseUrl/api.php/provide/vod/?ac=list", null)

        assertEquals("""{"code":1}""", page.body)
        assertEquals(1, warmCount)
        assertEquals(2, requests.size)
        assertNull(requests[0].cookie)
        assertEquals(DEFAULT_DESKTOP_UA, requests[0].userAgent)
        assertEquals("cf_clearance=ok", requests[1].cookie)
        assertEquals("WarmMobileUA", requests[1].userAgent)
    }

    @Test
    fun plain403DoesNotTriggerWarm() {
        var warmCount = 0
        handler = { exchange -> respond(exchange, 403, "<html><body>forbidden</body></html>") }
        val warmer = CloudflareWarmup {
            warmCount++
            true
        }
        val fetcher = HttpPageFetcher(
            cookieStore = CloudflareCookieStore,
            warmer = { warmer }
        )

        try {
            fetcher.fetch("$baseUrl/private", null)
            fail("普通 403 应当抛异常")
        } catch (e: WebLinkFetchException) {
            assertEquals(403, e.httpStatus)
            assertEquals("HTTP 403", e.message)
        }
        assertEquals(0, warmCount)
        assertEquals(1, requests.size)
    }

    @Test
    fun cloudflare403WithoutWarmerKeepsLegacyHttp403() {
        handler = { exchange ->
            respond(exchange, 403, "<html><title>Just a moment...</title></html>")
        }

        try {
            HttpPageFetcher(cookieStore = CloudflareCookieStore).fetch("$baseUrl/api", null)
            fail("没有 warmer 时应保持原有 HTTP 403 行为")
        } catch (e: WebLinkFetchException) {
            assertEquals(403, e.httpStatus)
            assertEquals("HTTP 403", e.message)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun warmFailureThrowsCloudflareBlocked() {
        var warmCount = 0
        handler = { exchange ->
            respond(
                exchange,
                403,
                "<html><head><title>Attention Required! | Cloudflare</title></head></html>"
            )
        }
        val warmer = CloudflareWarmup {
            warmCount++
            false
        }
        val fetcher = HttpPageFetcher(
            cookieStore = CloudflareCookieStore,
            warmer = { warmer }
        )

        try {
            fetcher.fetch("$baseUrl/api", null)
            fail("预热失败应当抛 Cloudflare 拦截")
        } catch (e: WebLinkFetchException) {
            assertEquals(403, e.httpStatus)
            assertEquals("Cloudflare 拦截", e.message)
        }
        assertEquals(1, warmCount)
        // 预热失败不重试。
        assertEquals(1, requests.size)
    }

    @Test
    fun stillBlockedAfterWarmRetriesOnlyOnce() {
        var warmCount = 0
        handler = { exchange ->
            respond(exchange, 403, "<html><body>cloudflare challenge</body></html>")
        }
        val warmer = CloudflareWarmup {
            warmCount++
            true
        }
        val fetcher = HttpPageFetcher(
            cookieStore = CloudflareCookieStore,
            warmer = { warmer }
        )

        try {
            fetcher.fetch("$baseUrl/api", null)
            fail("重试后仍被拦应当抛 Cloudflare 拦截")
        } catch (e: WebLinkFetchException) {
            assertEquals(403, e.httpStatus)
            assertEquals("Cloudflare 拦截", e.message)
        }
        assertEquals(1, warmCount)
        assertEquals(2, requests.size)
    }

    @Test
    fun redirectStillWorksWithoutCloudflare() {
        handler = { exchange ->
            if (exchange.requestURI.path == "/old") {
                exchange.responseHeaders.set("Location", "$baseUrl/new")
                exchange.sendResponseHeaders(302, -1)
            } else {
                respond(exchange, 200, "<html>new page</html>")
            }
        }

        val page = HttpPageFetcher().fetch("$baseUrl/old", null)

        assertEquals("$baseUrl/new", page.url)
        assertEquals("<html>new page</html>", page.body)
        assertTrue(requests.map { it.path }.containsAll(listOf("/old", "/new")))
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        body: String,
        contentType: String = "text/html"
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
