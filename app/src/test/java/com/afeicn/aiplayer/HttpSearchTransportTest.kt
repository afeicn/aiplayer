package com.afeicn.aiplayer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * [HttpSearchTransport] 的 Cloudflare 接线单测：本地起 HTTP 服务器，真实跑 HttpURLConnection，
 * 验证搜索请求会带 cookie、403 挑战触发一次预热并用预热 UA 重试，以及预热失败不重试。
 */
class HttpSearchTransportTest {

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
    fun cookieStoreAddsCookieAndReusesWarmedUserAgent() {
        CloudflareCookieStore.put("127.0.0.1", "cf_clearance=abc", "WarmMobileUA")
        handler = { exchange -> respond(exchange, 200, """{"list":[]}""", "application/json") }

        val response = transport().execute(SearchHttpRequest("GET", "$baseUrl/search?wd=x"))

        assertEquals(200, response.status)
        assertEquals("cf_clearance=abc", requests.single().cookie)
        assertEquals("WarmMobileUA", requests.single().userAgent)
    }

    @Test
    fun cloudflare403TriggersOneWarmAndRetriesWithCookie() {
        var warmCount = 0
        handler = { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            if (cookie?.contains("cf_clearance=ok") == true) {
                respond(exchange, 200, """{"list":[]}""", "application/json")
            } else {
                respond(exchange, 403, CHALLENGE_PAGE)
            }
        }
        val warmer = CloudflareWarmup { host ->
            warmCount++
            CloudflareCookieStore.put(host, "cf_clearance=ok", "WarmMobileUA")
            true
        }

        val response = transport(warmer).execute(SearchHttpRequest("GET", "$baseUrl/search?wd=x"))

        assertEquals(200, response.status)
        assertEquals(1, warmCount)
        assertEquals(2, requests.size)
        assertNull(requests[0].cookie)
        assertEquals(DEFAULT_DESKTOP_UA, requests[0].userAgent)
        assertEquals("cf_clearance=ok", requests[1].cookie)
        assertEquals("WarmMobileUA", requests[1].userAgent)
    }

    @Test
    fun warmFailureReturns403WithoutRetry() {
        var warmCount = 0
        handler = { exchange -> respond(exchange, 403, CHALLENGE_PAGE) }
        val warmer = CloudflareWarmup {
            warmCount++
            false
        }

        val response = transport(warmer).execute(SearchHttpRequest("GET", "$baseUrl/search?wd=x"))

        assertEquals(403, response.status)
        assertEquals(1, warmCount)
        // 预热失败不重试，避免每次搜索都多等一轮。
        assertEquals(1, requests.size)
    }

    @Test
    fun plain403DoesNotTriggerWarm() {
        var warmCount = 0
        handler = { exchange -> respond(exchange, 403, "<html><body>forbidden</body></html>") }
        val warmer = CloudflareWarmup {
            warmCount++
            true
        }

        val response = transport(warmer).execute(SearchHttpRequest("POST", "$baseUrl/search", "wd=x"))

        assertEquals(403, response.status)
        assertEquals(0, warmCount)
        assertEquals(1, requests.size)
    }

    @Test
    fun withoutWarmerKeepsLegacy403() {
        handler = { exchange -> respond(exchange, 403, CHALLENGE_PAGE) }

        val response = HttpSearchTransport(cookieStore = CloudflareCookieStore)
            .execute(SearchHttpRequest("GET", "$baseUrl/search?wd=x"))

        assertEquals(403, response.status)
        assertEquals(1, requests.size)
    }

    private fun transport(warmer: CloudflareWarmup? = null): HttpSearchTransport =
        HttpSearchTransport(
            cookieStore = CloudflareCookieStore,
            warmer = warmer?.let { { it } }
        )

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

    private companion object {
        const val CHALLENGE_PAGE =
            """<html><head><title>Just a moment...</title></head>
               <body><div class="cf-error-details">Cloudflare Ray ID</div></body></html>"""
    }
}
