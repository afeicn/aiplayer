package com.afeicn.aiplayer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量下载编排单测：入队结果汇总（成功/重复/跳无效/空间不足）、并发与间隔、失败重试计划。
 * 注入 enqueue 闭包，不联网、不碰 Android。
 */
class EpisodeBatchDownloaderTest {

    private fun item(title: String) = OfflineItem(id = "", title = title, url = "https://x.com/$title.m3u8")

    @Test
    fun enqueueBatchSummarizesEachResult() = runBlocking {
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        val results = mapOf(
            "a" to EnqueueResult.STARTED,
            "b" to EnqueueResult.DUPLICATE,
            "c" to EnqueueResult.NO_SPACE,
            "d" to EnqueueResult.INVALID_URL
        )
        val summary = EpisodeBatchDownloader.enqueueBatch(items, concurrency = 2, intervalMs = 0) { it ->
            results[it.title] ?: EnqueueResult.STARTED
        }
        assertEquals(4, summary.requested)
        assertEquals(1, summary.started)
        assertEquals(1, summary.duplicate)
        assertEquals(1, summary.skipped)
        assertEquals(listOf("c"), summary.failed)
        assertEquals(2, summary.finishedOk)
    }

    @Test
    fun emptyBatchReturnsZeroSummary() = runBlocking {
        val summary = EpisodeBatchDownloader.enqueueBatch(emptyList()) { EnqueueResult.STARTED }
        assertEquals(0, summary.requested)
        assertEquals(0, summary.started)
    }

    @Test
    fun retryPlanKeepsUnderLimitAndExhaustsTheRest() {
        val plan = EpisodeBatchDownloader.retryPlan(
            failedIds = listOf("a", "b", "c"),
            retryCounts = mapOf("a" to 0, "b" to 1, "c" to 2),
            maxRetries = 2
        )
        assertEquals(listOf("a", "b"), plan.toRetry)
        assertEquals(listOf("c"), plan.exhausted)
        assertTrue(plan.toRetry.all { it != "c" })
    }
}
