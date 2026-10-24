package com.afeicn.aiplayer

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLEncoder

/**
 * 用 JDK 自带的 HttpServer 起一个「本地假 MacCMS 接口」，验证整条链路：
 * 搜索 -> 解析 -> 同名聚合 -> 提取可播地址（模拟「点线路起播」）。不依赖真实源、不碰设备。
 */
class OnlineSearchIntegrationTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api.php/provide/vod/") { exchange ->
            val body = """
                {"code":1,"msg":"ok","page":1,"total":2,"list":[
                  {"vod_id":1,"vod_name":"流浪地球2","vod_pic":"","vod_year":"2023","vod_area":"大陆","vod_remarks":"HD",
                   "vod_play_from":"正片","vod_play_url":"正片${'$'}$base/stream/a.m3u8"},
                  {"vod_id":2,"vod_name":"流浪地球2","vod_year":"2023","vod_area":"大陆","vod_remarks":"HD",
                   "vod_play_from":"备用","vod_play_url":"备用${'$'}$base/stream/b.m3u8"}
                ]}
            """.trimIndent()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun localFakeApiSearchAggregatesAndExtractsPlayableUrl() {
        val source = OnlineVideoSource(id = "local", name = "本地假接口", base = base)
        val url = "$base/api.php/provide/vod/?ac=detail&wd=" + URLEncoder.encode("流浪地球", "UTF-8")

        val text = fetchHttpText(url)
        assertTrue(MacCmsParser.looksLikeValidResponse(text))

        val items = MacCmsParser.parseSearchResponse(text)
        assertEquals(2, items.size)

        val aggregated = VodAggregator.aggregate(items.map { SourceHit(source, it) })
        assertEquals(1, aggregated.size)
        assertEquals("流浪地球2", aggregated[0].name)
        assertEquals(2, aggregated[0].playableLines().size)

        // 「点线路起播」：从选中线路提取可播放地址与请求头。
        val chosen = aggregated[0].playableLines().first()
        val stream = onlineStream(chosen.source, chosen.line, aggregated[0].name)
        assertTrue(stream.url.endsWith("a.m3u8"))
        assertTrue(stream.isHls)
        assertEquals(aggregated[0].name, stream.title)
    }
}
