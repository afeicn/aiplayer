package com.afeicn.aiplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实联网探测（默认跳过，只有 `LIVE_PROBE=1` 才跑）：
 *
 * `LIVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveSourceProbeTest*' -i --console=plain`
 *
 * 目的：确认「跟随重定向 + 详情页兜底 + 状态码文案」在真站上成立，而不只是在假 transport 上成立。
 * - `www.xb6v.com`（帝国CMS，首页表单 POST `/e/search/so.php`）必须真能探测成功；
 * - `www.witzsb.com`（Cloudflare 后面的大象影视，没有可用关键词搜索）只打印结论，不断言成功：
 *   本机出口在东京会被 Cloudflare 403 拦，打印出「被站点防护拦了」就是正确结果。
 */
class LiveSourceProbeTest {

    private fun live() = System.getenv("LIVE_PROBE") == "1"

    @Test
    fun empireCmsSiteReallyProbes() {
        assumeTrue("设置 LIVE_PROBE=1 才跑真实联网测试", live())
        val source = OnlineVideoSource(id = "xb6v", name = "xb6v", base = "https://www.xb6v.com")

        val outcome = SourceProber.probe(source, HttpSearchTransport(), keyword = "三体")

        println("[LIVE] xb6v success=${outcome.success} items=${outcome.itemCount} " +
            "latency=${outcome.latencyMs}ms rule=${outcome.rule?.urlTemplate} error=${outcome.error}")
        outcome.items.take(3).forEach { println("[LIVE] xb6v  ${it.title} | ${it.url}") }
        assertTrue("xb6v 探测应成功：${outcome.error}", outcome.success)
        assertTrue("xb6v 应至少返回 1 条结果", outcome.items.isNotEmpty())
    }

    @Test
    fun cloudflareMacCmsSiteReportsWhatHappened() {
        assumeTrue("设置 LIVE_PROBE=1 才跑真实联网测试", live())
        val source = OnlineVideoSource(id = "witzsb", name = "大象影视", base = "https://www.witzsb.com")

        val outcome = SourceProber.probe(source, HttpSearchTransport(), keyword = "测试")

        println("[LIVE] witzsb success=${outcome.success} items=${outcome.itemCount} " +
            "latency=${outcome.latencyMs}ms rule=${outcome.rule?.urlTemplate} error=${outcome.error}")
        outcome.items.take(3).forEach { println("[LIVE] witzsb  ${it.title} | ${it.url}") }
        // 只要求「失败原因可读」：不能是空的、不能只说「响应异常」。
        if (!outcome.success) {
            assertFalse(outcome.error.isNullOrBlank())
            assertFalse(
                "失败原因要能说清是 403/404/详情页跳转，而不是笼统的响应异常：${outcome.error}",
                outcome.error == "网络失败或响应异常"
            )
        }
    }
}
