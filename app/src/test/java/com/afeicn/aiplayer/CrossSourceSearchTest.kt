package com.afeicn.aiplayer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源搜索聚合单测：并行多源、同名合并标「N 个源」、每条结果记来源、区分「没结果」与「网络失败」。
 * 用内存 [SourceRegistry] + 假 transport，不联网、不碰 Android Context。
 */
class CrossSourceSearchTest {

    private class MemoryRegistry : SourceRegistry {
        private val map = mutableMapOf<String, OnlineVideoSource>()
        private val health = mutableMapOf<String, SourceHealth>()
        var saved = 0

        fun put(source: OnlineVideoSource) {
            map[source.id] = source
        }

        override fun sources(): List<OnlineVideoSource> = map.values.toList()
        override fun health(id: String): SourceHealth = health[id] ?: SourceHealth()
        override fun save(source: OnlineVideoSource) {
            map[source.id] = source
            saved++
        }
        override fun recordSuccess(id: String) {
            health[id] = SourceHealth(0, 0L, System.currentTimeMillis(), 0L)
        }
        override fun recordFailure(id: String) {
            val h = health[id] ?: SourceHealth()
            health[id] = h.copy(consecutiveFailures = h.consecutiveFailures + 1, lastFailureAt = System.currentTimeMillis())
        }
    }

    private class FakeTransport(
        private val handler: (SearchHttpRequest) -> SearchHttpResponse
    ) : SearchTransport {
        override fun execute(request: SearchHttpRequest): SearchHttpResponse = handler(request)
    }

    private fun html(title: String, href: String) =
        """<li class="post box row fixed-hight"><div class="article">
          <h2><a href="$href">$title</a></h2>
        </div></li>"""

    @Test
    fun parallelSearchAggregatesSameTitleAcrossSourcesAndMarksOrigin() = runBlocking {
        val a = OnlineVideoSource(
            id = "a", name = "源A", base = "https://a.com",
            searchRule = SearchRule.manual("https://a.com/s?q={kw}")
        )
        val b = OnlineVideoSource(
            id = "b", name = "源B", base = "https://b.com",
            searchRule = SearchRule.manual("https://b.com/s?q={kw}")
        )
        val registry = MemoryRegistry().apply { put(a); put(b) }

        val transport = FakeTransport { req ->
            val body = when {
                req.url.startsWith("https://a.com/s?q=") ->
                    html("流浪地球", "/a/1.html") + html("庆余年", "/a/2.html")
                req.url.startsWith("https://b.com/s?q=") ->
                    html("流浪地球", "/b/1.html")
                else -> "<html></html>"
            }
            SearchHttpResponse(req.url, 200, body = body)
        }

        val outcomes = CrossSourceSearchEngine.search(registry, "流浪地球", transport)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it.success })

        val hits = outcomes.flatMap { it.hits }
        assertEquals(3, hits.size)

        val aggregated = VodAggregator.aggregate(hits)
        assertEquals(2, aggregated.size)

        val liulang = aggregated.first { it.name == "流浪地球" }
        assertEquals(2, liulang.sourceCount)
        assertTrue(liulang.hits.any { it.source.id == "a" && it.detailUrl == "https://a.com/a/1.html" })
        assertTrue(liulang.hits.any { it.source.id == "b" && it.detailUrl == "https://b.com/b/1.html" })

        val qing = aggregated.first { it.name == "庆余年" }
        assertEquals(1, qing.sourceCount)
        assertEquals("a", qing.hits.single().source.id)
    }

    @Test
    fun distinguishesNoResultFromNetworkFailure() = runBlocking {
        val ok = OnlineVideoSource(
            id = "ok", name = "正常源", base = "https://ok.com",
            searchRule = SearchRule.manual("https://ok.com/s?q={kw}")
        )
        val dead = OnlineVideoSource(
            id = "dead", name = "挂掉的源", base = "https://dead.com",
            searchRule = SearchRule.manual("https://dead.com/s?q={kw}")
        )
        val registry = MemoryRegistry().apply { put(ok); put(dead) }

        val transport = FakeTransport { req ->
            when {
                req.url.startsWith("https://ok.com/s?q=") -> SearchHttpResponse(req.url, 200, body = "<html>没有结果</html>")
                else -> SearchHttpResponse(req.url, -1) // 网络失败
            }
        }

        val outcomes = CrossSourceSearchEngine.search(registry, "某片", transport)
        val okOutcome = outcomes.first { it.source.id == "ok" }
        val deadOutcome = outcomes.first { it.source.id == "dead" }

        // 正常源：没结果（不算网络失败）
        assertFalse(okOutcome.success)
        assertFalse(okOutcome.networkFailure)
        // 挂掉的源：网络失败
        assertFalse(deadOutcome.success)
        assertTrue(deadOutcome.networkFailure)
    }

    @Test
    fun probeCachesRuleOnFirstHit() = runBlocking {
        val registry = MemoryRegistry()
        val source = OnlineVideoSource(id = "x", name = "X", base = "https://x.com")
        registry.put(source)

        val transport = FakeTransport { req ->
            when {
                req.method == "GET" && req.url == "https://x.com/" ->
                    SearchHttpResponse(req.url, 200, body = "<html>无表单</html>")
                req.url.contains("/e/search/?keyboard=") || req.url.contains("keyboard=") ->
                    SearchHttpResponse(req.url, 200, body = html("测试", "/1.html"))
                else -> SearchHttpResponse(req.url, 200, body = "<html>无表单</html>")
            }
        }

        val outcome = CrossSourceSearchEngine.searchSource(source, registry, "测试", transport)
        assertTrue(outcome.success)
        // 探测成功后把规则缓存回仓库（save 被调用，源带上了规则）
        assertTrue(registry.saved >= 1)
        val cached = registry.sources().first { it.id == "x" }
        assertTrue(cached.searchRule != null)
    }

    @Test
    fun html搜索零结果不扣源健康度_网络失败才扣() = runBlocking {
        val source = OnlineVideoSource(
            id = "h", name = "源H", base = "https://h.com",
            searchRule = SearchRule.manual("https://h.com/s?q={kw}")
        )
        val registry = MemoryRegistry().apply { put(source) }

        // 源正常响应但这个词没结果：健康度不动。
        val emptyTransport = FakeTransport { SearchHttpResponse(it.url, 200, body = "<html><body>没找到相关影片</body></html>") }
        val empty = CrossSourceSearchEngine.searchSource(source, registry, "冷门片名", emptyTransport)
        assertFalse(empty.success)
        assertFalse(empty.networkFailure)
        assertEquals(0, registry.health("h").consecutiveFailures)

        // HTTP 500：才算源故障。
        val downTransport = FakeTransport { SearchHttpResponse(it.url, 500, body = "") }
        val down = CrossSourceSearchEngine.searchSource(source, registry, "任意词", downTransport)
        assertFalse(down.success)
        assertTrue(down.networkFailure)
        assertEquals(1, registry.health("h").consecutiveFailures)
    }
}
