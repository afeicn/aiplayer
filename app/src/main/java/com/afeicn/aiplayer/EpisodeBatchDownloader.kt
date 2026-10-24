package com.afeicn.aiplayer

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 「确认范围」后的批量离线下载编排：与离线缓存共用同一条队列（[OfflineDownloadManager]），
 * 支持单集 2 并发、集间留间隔、失败重试 2 次后汇总。核心 [enqueueBatch] / [retryPlan] 是
 * 纯函数（注入 enqueue 闭包即可单测），真实适配在 [enqueueEpisodes] / [retryFailedDownloads]。
 */

data class BatchDownloadSummary(
    val requested: Int,
    val started: Int,
    val duplicate: Int,
    val skipped: Int,
    val failed: List<String> = emptyList()
) {
    val finishedOk: Int get() = started + duplicate
}

/** 失败任务的重试计划：仍可重试的 id，与已达上限、最终放弃的 id。 */
data class RetryPlan(val toRetry: List<String>, val exhausted: List<String>)

object EpisodeBatchDownloader {

    const val DEFAULT_CONCURRENCY = 2
    const val DEFAULT_INTERVAL_MS = 500L
    const val DEFAULT_MAX_RETRIES = 2

    /**
     * 批量入队：最多 [concurrency] 个同时在「发起入队」，[intervalMs] 集间间隔。
     * 每个任务的结果映射到 [BatchDownloadSummary]，便于「失败重试 2 次后汇总」。
     */
    suspend fun enqueueBatch(
        items: List<OfflineItem>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        enqueue: suspend (OfflineItem) -> EnqueueResult
    ): BatchDownloadSummary {
        if (items.isEmpty()) return BatchDownloadSummary(0, 0, 0, 0)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        var started = 0
        var duplicate = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        for (item in items) {
            semaphore.withPermit {
                when (enqueue(item)) {
                    EnqueueResult.STARTED -> started++
                    EnqueueResult.DUPLICATE -> duplicate++
                    EnqueueResult.NO_SPACE -> failed += item.title
                    EnqueueResult.INVALID_URL -> skipped++
                    // 移动数据闸门拦下的任务仍在队列里，WiFi/开启开关后自动继续，计入 started。
                    EnqueueResult.WAITING_NETWORK -> started++
                }
            }
            if (intervalMs > 0) delay(intervalMs)
        }
        return BatchDownloadSummary(items.size, started, duplicate, skipped, failed)
    }

    /**
     * 纯重试计划：对每个失败 id 按已重试次数判断——不足 [maxRetries] 继续重试，否则判为「放弃」。
     * 供 UI「失败重试 2 次后汇总」与单测复用。
     */
    fun retryPlan(
        failedIds: List<String>,
        retryCounts: Map<String, Int>,
        maxRetries: Int = DEFAULT_MAX_RETRIES
    ): RetryPlan {
        val toRetry = mutableListOf<String>()
        val exhausted = mutableListOf<String>()
        for (id in failedIds) {
            if ((retryCounts[id] ?: 0) < maxRetries) toRetry += id else exhausted += id
        }
        return RetryPlan(toRetry, exhausted)
    }

    // ---- 真实适配：接 [OfflineDownloadManager]（与离线缓存共用队列） ----

    /** 把一批离线项（剧集/音频）入队，返回汇总。 */
    suspend fun enqueueEpisodes(context: Context, items: List<OfflineItem>): BatchDownloadSummary =
        enqueueBatch(items) { item ->
            OfflineDownloadManager.enqueue(context, item)
        }

    /** 对当前所有 FAILED 任务按上限重试，返回「仍失败 / 已放弃」的 id 列表。 */
    fun retryFailedDownloads(context: Context, maxRetries: Int = DEFAULT_MAX_RETRIES): RetryPlan {
        val failed = OfflineDownloadManager.downloads.value
            .filter { it.state == DownloadTaskState.FAILED }
            .map { it.item.id }
        val plan = retryPlan(failed, FailedRetryCounter.counts(), maxRetries)
        plan.toRetry.forEach { id ->
            OfflineDownloadManager.retry(context, id)
            FailedRetryCounter.bump(id)
        }
        return plan
    }
}

/** 进程内记录每个失败任务已重试过几次（重试计数不落盘，App 重启后清零可接受）。 */
object FailedRetryCounter {
    private val counts = mutableMapOf<String, Int>()

    fun counts(): Map<String, Int> = counts.toMap()

    fun bump(id: String) {
        counts[id] = (counts[id] ?: 0) + 1
    }

    fun reset() = counts.clear()
}
