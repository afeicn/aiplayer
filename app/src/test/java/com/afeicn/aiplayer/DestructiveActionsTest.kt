package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 破坏性操作防护策略 + 离线缓存多选删除纯逻辑的单测。
 * 覆盖：勾选集合、体积汇总、全选/取消全选、N=0 禁用口径、确认弹窗触发条件、文案口径。
 */
class DestructiveActionsTest {

    private fun download(
        id: String,
        state: DownloadTaskState = DownloadTaskState.COMPLETED,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L
    ) = OfflineDownload(
        item = OfflineItem(id = id, title = "标题$id", url = "https://example.com/$id"),
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        percent = if (state == DownloadTaskState.COMPLETED) 100f else 0f,
        failure = OfflineFailureReason.NONE
    )

    // ==================== 勾选集合 ====================

    @Test
    fun toggleAddsThenRemovesId() {
        val selection = OfflineSelection()
        val afterAdd = selection.toggle("a")
        assertEquals(setOf("a"), afterAdd.selected)
        assertTrue(afterAdd.isSelected("a"))
        assertEquals(1, afterAdd.count)

        val afterRemove = afterAdd.toggle("a")
        assertTrue(afterRemove.selected.isEmpty())
        assertFalse(afterRemove.isSelected("a"))
        assertEquals(0, afterRemove.count)
    }

    @Test
    fun selectAllCollectsEveryId() {
        val selection = OfflineSelection().selectAll(listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), selection.selected)
        assertEquals(3, selection.count)
    }

    @Test
    fun isAllSelectedRequiresNonEmptyAndFullCoverage() {
        val selection = OfflineSelection(setOf("a", "b"))
        assertTrue(selection.isAllSelected(listOf("a", "b")))
        // 少选一条不算全选
        assertFalse(selection.isAllSelected(listOf("a", "b", "c")))
        // 空列表不算全选（避免空态误显示为已全选）
        assertFalse(OfflineSelection().isAllSelected(emptyList()))
    }

    @Test
    fun toggleAllFlipsBetweenSelectAllAndClear() {
        val ids = listOf("a", "b", "c")
        val all = OfflineSelection().toggleAll(ids)
        assertEquals(setOf("a", "b", "c"), all.selected)

        // 已全选 → 取消全选
        val cleared = all.toggleAll(ids)
        assertTrue(cleared.selected.isEmpty())
    }

    @Test
    fun clearEmptiesSelection() {
        val selection = OfflineSelection(setOf("a", "b")).clear()
        assertTrue(selection.selected.isEmpty())
        assertEquals(0, selection.count)
    }

    // ==================== 体积汇总 ====================

    @Test
    fun sizeBytesUsesDownloadedBytesWhenPresent() {
        // 下载中：按已下载字节算，而不是 totalBytes
        val downloading = download("a", DownloadTaskState.DOWNLOADING, downloadedBytes = 500L, totalBytes = 1000L)
        assertEquals(500L, OfflineSelectionRules.sizeBytes(downloading))
    }

    @Test
    fun sizeBytesFallsBackToTotalBytesForCompleted() {
        // 已完成但已下载字节为 0（异常态），退回总字节
        val completed = download("a", DownloadTaskState.COMPLETED, downloadedBytes = 0L, totalBytes = 2048L)
        assertEquals(2048L, OfflineSelectionRules.sizeBytes(completed))
    }

    @Test
    fun sizeBytesIsZeroForQueuedWithNoBytes() {
        // 排队未下任何字节：不虚报占用
        val queued = download("a", DownloadTaskState.QUEUED, downloadedBytes = 0L, totalBytes = 1024L)
        assertEquals(0L, OfflineSelectionRules.sizeBytes(queued))
    }

    @Test
    fun selectedSizeBytesSumsOnlySelected() {
        val downloads = listOf(
            download("a", DownloadTaskState.COMPLETED, downloadedBytes = 1024L, totalBytes = 1024L),
            download("b", DownloadTaskState.COMPLETED, downloadedBytes = 2048L, totalBytes = 2048L),
            download("c", DownloadTaskState.COMPLETED, downloadedBytes = 4096L, totalBytes = 4096L)
        )
        assertEquals(1024L + 4096L, OfflineSelectionRules.selectedSizeBytes(downloads, setOf("a", "c")))
        assertEquals(0L, OfflineSelectionRules.selectedSizeBytes(downloads, emptySet()))
    }

    @Test
    fun selectedSizeTextFormatsReadably() {
        val downloads = listOf(
            download("a", DownloadTaskState.COMPLETED, downloadedBytes = 1024L * 1024L, totalBytes = 1024L * 1024L)
        )
        assertEquals("1.0 MB", OfflineSelectionRules.selectedSizeText(downloads, setOf("a")))
        assertEquals("0 B", OfflineSelectionRules.selectedSizeText(downloads, emptySet()))
    }

    // ==================== 确认弹窗触发条件 ====================

    @Test
    fun batchAndClearOpsRequireConfirmDialog() {
        assertTrue(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.CLEAR_ALL_OFFLINE))
        assertTrue(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.DELETE_SELECTED_OFFLINE))
        assertTrue(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.CLEAR_AI_LYRICS))
        assertTrue(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.DELETE_SELECTED_SOURCES))
    }

    @Test
    fun singleDeleteUsesUndoInsteadOfConfirm() {
        assertTrue(DestructiveActionPolicy.usesUndo(DestructiveOp.DELETE_SINGLE_OFFLINE))
        assertFalse(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.DELETE_SINGLE_OFFLINE))
    }

    @Test
    fun everyDestructiveOpHasExactlyOneMechanism() {
        DestructiveOp.entries.forEach { op ->
            val requiresConfirm = DestructiveActionPolicy.requiresConfirmDialog(op)
            val usesUndo = DestructiveActionPolicy.usesUndo(op)
            // 二者互斥，且每个操作都命中一种机制
            assertTrue("$op 必须命中某一种机制", requiresConfirm || usesUndo)
            assertFalse("$op 不能同时是两种机制", requiresConfirm && usesUndo)
        }
    }

    // ==================== 文案口径 ====================

    @Test
    fun deleteSelectedCopyStatesCountAndSize() {
        assertEquals("删除 3 项？", DestructiveCopy.deleteSelectedTitle(3))
        assertEquals(
            "将删除 3 项离线缓存，共 1.2 GB。删除后需要重新下载。",
            DestructiveCopy.deleteSelectedBody(3, "1.2 GB")
        )
    }

    @Test
    fun clearAllCopyStatesSize() {
        assertEquals("清空全部离线缓存？", DestructiveCopy.clearAllTitle())
        assertEquals(
            "将清空全部离线缓存，共 5.0 GB。删除后需要重新下载。",
            DestructiveCopy.clearAllBody("5.0 GB")
        )
    }

    @Test
    fun clearLyricsCopyStatesCount() {
        assertEquals("清空歌词缓存？", DestructiveCopy.clearLyricsTitle())
        assertEquals(
            "将清空本机 AI 歌词缓存（12 首），已生成的时间轴需要重新分析。",
            DestructiveCopy.clearLyricsBody(12)
        )
    }

    @Test
    fun deleteSourcesCopyStatesCount() {
        assertEquals("删除 2 个订阅源？", DestructiveCopy.deleteSourcesTitle(2))
        assertEquals(
            "仅移除这些源，已下载视频不会受到影响。删除后需要重新添加才能搜索。",
            DestructiveCopy.deleteSourcesBody()
        )
        assertEquals("已删除 2 个订阅源", DestructiveCopy.deletedSourcesToast(2))
    }

    // ==================== 订阅源管理模式勾选集合 ====================

    @Test
    fun sourceSelectionToggleAndClear() {
        val s = SourceSelection()
        assertEquals(0, s.count)
        val one = s.toggle("a")
        assertTrue(one.isSelected("a"))
        assertEquals(1, one.count)
        // 再点一次取消勾选
        assertEquals(0, one.toggle("a").count)
        assertEquals(2, one.toggle("b").count)
        assertEquals(0, one.toggle("b").clear().count)
    }

    @Test
    fun sourceSelectionAllSemantics() {
        val ids = listOf("a", "b", "c")
        val s = SourceSelection()
        // 空列表不算全选，「全选」在空列表上还是空集合
        assertFalse(s.isAllSelected(emptyList()))
        assertEquals(0, s.toggleAll(emptyList()).count)
        val all = s.toggleAll(ids)
        assertTrue(all.isAllSelected(ids))
        assertEquals(3, all.count)
        // 已全选时再切 → 清空
        assertEquals(0, all.toggleAll(ids).count)
        // 部分勾选不算全选
        assertFalse(all.toggle("a").isAllSelected(ids))
        assertEquals(3, all.selectAll(ids).count)
    }

    @Test
    fun toastAndUndoCopyAreStable() {
        assertEquals("已删除 5 项", DestructiveCopy.deletedCountToast(5))
        assertEquals("已删除，5 秒内可撤销", DestructiveCopy.singleDeleteUndoMessage())
    }
}
