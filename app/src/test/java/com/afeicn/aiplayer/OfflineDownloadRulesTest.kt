package com.afeicn.aiplayer

import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadRulesTest {

    // ==================== 下载任务状态机 ====================

    @Test
    fun stateMachineAllowsPauseResumeDeleteRetryTransitions() {
        // 排队：可暂停、可删除，不可继续/重试
        assertEquals(
            DownloadTaskState.PAUSED,
            DownloadTaskState.QUEUED.transition(OfflineAction.PAUSE)
        )
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.QUEUED.transition(OfflineAction.DELETE)
        )
        assertEquals(
            DownloadTaskState.QUEUED,
            DownloadTaskState.QUEUED.transition(OfflineAction.RESUME)
        )

        // 下载中：可暂停、可删除
        assertEquals(
            DownloadTaskState.PAUSED,
            DownloadTaskState.DOWNLOADING.transition(OfflineAction.PAUSE)
        )
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.DOWNLOADING.transition(OfflineAction.DELETE)
        )

        // 暂停：可继续、可删除，不可重试
        assertEquals(
            DownloadTaskState.QUEUED,
            DownloadTaskState.PAUSED.transition(OfflineAction.RESUME)
        )
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.PAUSED.transition(OfflineAction.DELETE)
        )
        assertEquals(
            DownloadTaskState.PAUSED,
            DownloadTaskState.PAUSED.transition(OfflineAction.RETRY)
        )

        // 完成：只能删除
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.COMPLETED.transition(OfflineAction.DELETE)
        )
        assertEquals(
            DownloadTaskState.COMPLETED,
            DownloadTaskState.COMPLETED.transition(OfflineAction.PAUSE)
        )

        // 失败：可重试、可删除
        assertEquals(
            DownloadTaskState.QUEUED,
            DownloadTaskState.FAILED.transition(OfflineAction.RETRY)
        )
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.FAILED.transition(OfflineAction.DELETE)
        )

        // 已删除：终态，任何动作都保持已删除
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.REMOVED.transition(OfflineAction.RESUME)
        )
        assertEquals(
            DownloadTaskState.REMOVED,
            DownloadTaskState.REMOVED.transition(OfflineAction.DELETE)
        )
    }

    @Test
    fun stateMachineExposesAllowedActions() {
        assertEquals(
            setOf(OfflineAction.PAUSE, OfflineAction.DELETE),
            DownloadTaskState.QUEUED.allowedActions()
        )
        assertEquals(
            setOf(OfflineAction.RESUME, OfflineAction.DELETE),
            DownloadTaskState.PAUSED.allowedActions()
        )
        assertEquals(
            setOf(OfflineAction.RETRY, OfflineAction.DELETE),
            DownloadTaskState.FAILED.allowedActions()
        )
        assertEquals(
            setOf(OfflineAction.DELETE),
            DownloadTaskState.COMPLETED.allowedActions()
        )
        assertTrue(DownloadTaskState.REMOVED.allowedActions().isEmpty())
    }

    @Test
    fun media3StateMapsToUiState() {
        assertEquals(DownloadTaskState.QUEUED, OfflineDownloadRules.mapState(Download.STATE_QUEUED))
        assertEquals(DownloadTaskState.DOWNLOADING, OfflineDownloadRules.mapState(Download.STATE_DOWNLOADING))
        assertEquals(DownloadTaskState.DOWNLOADING, OfflineDownloadRules.mapState(Download.STATE_RESTARTING))
        assertEquals(DownloadTaskState.PAUSED, OfflineDownloadRules.mapState(Download.STATE_STOPPED))
        assertEquals(DownloadTaskState.COMPLETED, OfflineDownloadRules.mapState(Download.STATE_COMPLETED))
        assertEquals(DownloadTaskState.FAILED, OfflineDownloadRules.mapState(Download.STATE_FAILED))
    }

    @Test
    fun stoppedStateDistinguishesWaitingNetworkFromWaitingWifi() {
        assertEquals(
            DownloadTaskState.WAITING_NETWORK,
            OfflineDownloadRules.mapState(Download.STATE_STOPPED, OfflineDownloadRules.STOP_REASON_WAITING_NETWORK)
        )
        assertEquals(
            DownloadTaskState.WAITING_WIFI,
            OfflineDownloadRules.mapState(Download.STATE_STOPPED, OfflineDownloadRules.STOP_REASON_WAITING_WIFI)
        )
        // 用户手动暂停仍映射为已暂停。
        assertEquals(
            DownloadTaskState.PAUSED,
            OfflineDownloadRules.mapState(Download.STATE_STOPPED, OfflineDownloadRules.STOP_REASON_PAUSED)
        )
    }

    // ==================== 失败原因分类 ====================

    @Test
    fun classifyFailureDetectsLinkExpired403And404() {
        assertEquals(
            OfflineFailureReason.LINK_EXPIRED,
            OfflineDownloadRules.classifyFailure(IllegalStateException("Response code: 403"))
        )
        assertEquals(
            OfflineFailureReason.LINK_EXPIRED,
            OfflineDownloadRules.classifyFailure(IllegalStateException("Response code: 404"))
        )
        // 深层 cause 也要能命中
        assertEquals(
            OfflineFailureReason.LINK_EXPIRED,
            OfflineDownloadRules.classifyFailure(Exception("download failed", IllegalStateException("Response code: 410")))
        )
    }

    @Test
    fun classifyFailureDetectsNetworkErrors() {
        assertEquals(
            OfflineFailureReason.NETWORK,
            OfflineDownloadRules.classifyFailure(Exception("java.net.UnknownHostException: unable to resolve host"))
        )
        assertEquals(
            OfflineFailureReason.NETWORK,
            OfflineDownloadRules.classifyFailure(Exception("java.net.SocketTimeoutException: timeout"))
        )
        assertEquals(
            OfflineFailureReason.NETWORK,
            OfflineDownloadRules.classifyFailure(Exception("failed to connect to /1.2.3.4"))
        )
    }

    @Test
    fun classifyFailureDetectsNoSpaceAndFormat() {
        assertEquals(
            OfflineFailureReason.NO_SPACE,
            OfflineDownloadRules.classifyFailure(Exception("ENOSPC: No space left on device"))
        )
        assertEquals(
            OfflineFailureReason.FORMAT_UNSUPPORTED,
            OfflineDownloadRules.classifyFailure(Exception("UnrecognizedInputFormatException: did not match"))
        )
    }

    @Test
    fun classifyFailureReturnsNoneForNullAndUnknownOtherwise() {
        assertEquals(OfflineFailureReason.NONE, OfflineDownloadRules.classifyFailure(null))
        assertEquals(OfflineFailureReason.UNKNOWN, OfflineDownloadRules.classifyFailure(Exception("some random thing")))
    }

    // ==================== 断点续传判定 ====================

    @Test
    fun resumableOnlyWhenBytesExistAndNotTerminal() {
        assertTrue(OfflineDownloadRules.isResumable(1024L, Download.STATE_STOPPED))
        assertTrue(OfflineDownloadRules.isResumable(1024L, Download.STATE_QUEUED))
        assertTrue(OfflineDownloadRules.isResumable(1024L, Download.STATE_DOWNLOADING))
        assertFalse(OfflineDownloadRules.isResumable(0L, Download.STATE_STOPPED))
        assertFalse(OfflineDownloadRules.isResumable(1024L, Download.STATE_COMPLETED))
        assertFalse(OfflineDownloadRules.isResumable(1024L, Download.STATE_FAILED))
    }

    // ==================== 重复任务复用 ====================

    @Test
    fun downloadIdIsStableForSameUrl() {
        val url = "https://example.com/a/b.m3u8?token=1"
        assertEquals(OfflineDownloadRules.downloadIdFor(url), OfflineDownloadRules.downloadIdFor(url))
        assertEquals(64, OfflineDownloadRules.downloadIdFor(url).length)
    }

    @Test
    fun duplicateDetectionReusesExistingTask() {
        val url = "https://example.com/movie/index.m3u8"
        val id = OfflineDownloadRules.downloadIdFor(url)
        val existing = setOf("other-id", id)

        assertEquals(id, OfflineDownloadRules.findDuplicateId(existing, url))
        // 不同 URL 不命中
        assertNull(OfflineDownloadRules.findDuplicateId(existing, "https://example.com/other.m3u8"))
    }

    @Test
    fun duplicateDetectionIgnoresWhitespaceVariation() {
        val base = "https://example.com/movie/index.m3u8"
        assertEquals(
            OfflineDownloadRules.downloadIdFor(base),
            OfflineDownloadRules.downloadIdFor("  $base  ")
        )
    }

    // ==================== 磁盘空间判断 ====================

    @Test
    fun diskSpaceRequiresMinimumFreeWhenSizeUnknown() {
        assertTrue(OfflineDownloadRules.hasEnoughSpace(OfflineDownloadRules.MIN_FREE_BYTES, 0L))
        assertTrue(OfflineDownloadRules.hasEnoughSpace(OfflineDownloadRules.MIN_FREE_BYTES + 1L, 0L))
        assertFalse(OfflineDownloadRules.hasEnoughSpace(OfflineDownloadRules.MIN_FREE_BYTES - 1L, 0L))
        assertFalse(OfflineDownloadRules.hasEnoughSpace(0L, 0L))
    }

    @Test
    fun diskSpaceAddsTenPercentMarginForKnownSize() {
        val content = 1_000_000_000L
        val minimum = OfflineDownloadRules.MIN_FREE_BYTES.coerceAtLeast(content * 11L / 10L)
        assertTrue(OfflineDownloadRules.hasEnoughSpace(minimum, content))
        assertFalse(OfflineDownloadRules.hasEnoughSpace(minimum - 1L, content))
    }

    // ==================== 缓存命中选择（在线/离线） ====================

    @Test
    fun onlinePlaybackUsesNormalCacheLookup() {
        assertEquals(0, OfflineDownloadRules.playbackCacheFlags(offlineOnly = false))
    }

    @Test
    fun offlinePlaybackBlocksOnCache() {
        assertEquals(
            CacheDataSource.FLAG_BLOCK_ON_CACHE,
            OfflineDownloadRules.playbackCacheFlags(offlineOnly = true)
        )
    }

    @Test
    fun offlinePlayableOnlyWhenCompletedAndFullyDownloaded() {
        assertTrue(OfflineDownloadRules.isOfflinePlayable(Download.STATE_COMPLETED, 100f))
        assertFalse(OfflineDownloadRules.isOfflinePlayable(Download.STATE_COMPLETED, 99.9f))
        assertFalse(OfflineDownloadRules.isOfflinePlayable(Download.STATE_DOWNLOADING, 100f))
        assertFalse(OfflineDownloadRules.isOfflinePlayable(Download.STATE_FAILED, 100f))
    }

    // ==================== 状态展示文案 ====================

    @Test
    fun statusLabelSurfacesSpacePaused() {
        assertEquals(
            "因空间不足暂停",
            OfflineDownloadRules.statusLabel(DownloadTaskState.PAUSED, OfflineFailureReason.NO_SPACE)
        )
        assertEquals(
            "因空间不足暂停",
            OfflineDownloadRules.statusLabel(DownloadTaskState.FAILED, OfflineFailureReason.NO_SPACE)
        )
        assertEquals(
            "下载中",
            OfflineDownloadRules.statusLabel(DownloadTaskState.DOWNLOADING, OfflineFailureReason.NONE)
        )
        assertEquals(
            "已暂停",
            OfflineDownloadRules.statusLabel(DownloadTaskState.PAUSED, OfflineFailureReason.NONE)
        )
    }

    // ==================== 获取地址中（RESOLVING）流转 ====================

    @Test
    fun resolvingAfterResolvePromotesToQueuedOrFailed() {
        assertEquals(DownloadTaskState.QUEUED, DownloadTaskState.RESOLVING.afterResolve(true))
        assertEquals(DownloadTaskState.FAILED, DownloadTaskState.RESOLVING.afterResolve(false))
    }

    @Test
    fun afterResolveIsIdempotentForNonResolvingStates() {
        assertEquals(DownloadTaskState.QUEUED, DownloadTaskState.QUEUED.afterResolve(true))
        assertEquals(DownloadTaskState.DOWNLOADING, DownloadTaskState.DOWNLOADING.afterResolve(false))
        assertEquals(DownloadTaskState.COMPLETED, DownloadTaskState.COMPLETED.afterResolve(true))
        assertEquals(DownloadTaskState.FAILED, DownloadTaskState.FAILED.afterResolve(true))
        assertEquals(DownloadTaskState.PAUSED, DownloadTaskState.PAUSED.afterResolve(false))
    }

    @Test
    fun resolvingOnlyAllowsDelete() {
        assertEquals(setOf(OfflineAction.DELETE), DownloadTaskState.RESOLVING.allowedActions())
        // 获取地址中不可暂停/继续/重试，非法动作保持原状。
        assertEquals(DownloadTaskState.RESOLVING, DownloadTaskState.RESOLVING.transition(OfflineAction.PAUSE))
        assertEquals(DownloadTaskState.RESOLVING, DownloadTaskState.RESOLVING.transition(OfflineAction.RESUME))
        assertEquals(DownloadTaskState.RESOLVING, DownloadTaskState.RESOLVING.transition(OfflineAction.RETRY))
        assertEquals(DownloadTaskState.REMOVED, DownloadTaskState.RESOLVING.transition(OfflineAction.DELETE))
    }

    @Test
    fun resolvingStatusLabelSurfacesGetAddressing() {
        assertEquals("获取地址中", OfflineDownloadRules.statusLabel(DownloadTaskState.RESOLVING, OfflineFailureReason.NONE))
    }

    // ==================== 可读字节格式化 ====================

    @Test
    fun formatBytesScalesUnits() {
        assertEquals("0 B", OfflineDownloadRules.formatBytes(0L))
        assertEquals("512 B", OfflineDownloadRules.formatBytes(512L))
        assertEquals("1.0 KB", OfflineDownloadRules.formatBytes(1024L))
        assertEquals("1.5 MB", OfflineDownloadRules.formatBytes((1.5 * 1024 * 1024).toLong()))
    }

    // ==================== 海报回填 ====================

    @Test
    fun patchCoverOnlyFillsBlankSameTitle() {
        val blank = OfflineItem(id = "1", title = "我不是大师", url = "http://a/1.m3u8")
        val patched = OfflineDownloadRules.patchCover(blank, "我不是大师", "http://c/p.jpg")
        assertEquals("http://c/p.jpg", patched.cover)
        // 已有海报不覆盖。
        val filled = blank.copy(cover = "http://c/old.jpg")
        assertEquals("http://c/old.jpg", OfflineDownloadRules.patchCover(filled, "我不是大师", "http://c/new.jpg").cover)
        // 不同名不动。
        val other = OfflineItem(id = "2", title = "庆余年", url = "http://a/2.m3u8")
        assertEquals("", OfflineDownloadRules.patchCover(other, "我不是大师", "http://c/p.jpg").cover)
        // 空海报不写。
        assertEquals("", OfflineDownloadRules.patchCover(blank, "我不是大师", "").cover)
    }
}
