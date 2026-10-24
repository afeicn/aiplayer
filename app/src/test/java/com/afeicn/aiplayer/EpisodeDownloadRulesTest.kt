package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 选集网格「角标状态 + 防重复拆分」纯函数的单测。
 * 不碰 Android 框架，只验证 [EpisodeDownloadRules] 把下载状态映射到网格角标、
 * 拆分可加入/跳过集合、生成提示文案与可下载集号。
 */
class EpisodeDownloadRulesTest {

    @Test
    fun statusOf_映射每种状态到角标() {
        assertEquals(EpisodeGridStatus.DOWNLOADED, EpisodeDownloadRules.statusOf(DownloadTaskState.COMPLETED))
        assertEquals(EpisodeGridStatus.RESOLVING, EpisodeDownloadRules.statusOf(DownloadTaskState.RESOLVING))
        assertEquals(EpisodeGridStatus.ACTIVE, EpisodeDownloadRules.statusOf(DownloadTaskState.QUEUED))
        assertEquals(EpisodeGridStatus.ACTIVE, EpisodeDownloadRules.statusOf(DownloadTaskState.DOWNLOADING))
        assertEquals(EpisodeGridStatus.ACTIVE, EpisodeDownloadRules.statusOf(DownloadTaskState.PAUSED))
        assertEquals(EpisodeGridStatus.ACTIVE, EpisodeDownloadRules.statusOf(DownloadTaskState.WAITING_NETWORK))
        assertEquals(EpisodeGridStatus.ACTIVE, EpisodeDownloadRules.statusOf(DownloadTaskState.WAITING_WIFI))
        // 失败与删除（占位/已删）都允许重新选择。
        assertEquals(EpisodeGridStatus.AVAILABLE, EpisodeDownloadRules.statusOf(DownloadTaskState.FAILED))
        assertEquals(EpisodeGridStatus.AVAILABLE, EpisodeDownloadRules.statusOf(DownloadTaskState.REMOVED))
        assertEquals(EpisodeGridStatus.AVAILABLE, EpisodeDownloadRules.statusOf(null))
    }

    @Test
    fun hintFor_按状态给出不可选提示() {
        assertEquals("第 3 集已下载", EpisodeDownloadRules.hintFor(3, DownloadTaskState.COMPLETED))
        assertEquals("第 5 集正在获取地址", EpisodeDownloadRules.hintFor(5, DownloadTaskState.RESOLVING))
        assertEquals("第 1 集已在下载中", EpisodeDownloadRules.hintFor(1, DownloadTaskState.QUEUED))
        assertEquals("第 2 集已在下载中", EpisodeDownloadRules.hintFor(2, DownloadTaskState.DOWNLOADING))
        assertEquals("第 4 集已在下载队列", EpisodeDownloadRules.hintFor(4, DownloadTaskState.PAUSED))
        assertEquals("第 6 集已在下载队列", EpisodeDownloadRules.hintFor(6, DownloadTaskState.WAITING_NETWORK))
    }

    @Test
    fun partition_拆分可加入与跳过() {
        val statuses = mapOf(
            1 to DownloadTaskState.COMPLETED,
            2 to DownloadTaskState.RESOLVING,
            3 to DownloadTaskState.DOWNLOADING,
            4 to DownloadTaskState.FAILED
        )
        val result = EpisodeDownloadRules.partition(setOf(1, 2, 3, 4, 5, 6), statuses)

        // 4(失败) 可重下、5/6 无记录，均可加入；1/2/3 跳过。
        assertEquals(setOf(4, 5, 6), result.addable)
        assertEquals(setOf(1, 2, 3), result.skipped)
        assertEquals(3, result.addableCount)
        assertEquals(3, result.skippedCount)
        assertEquals(listOf("第 1 集已下载", "第 2 集正在获取地址", "第 3 集已在下载中"), result.hints)
    }

    @Test
    fun partition_空选择返回空拆分() {
        val result = EpisodeDownloadRules.partition(emptySet(), mapOf(1 to DownloadTaskState.COMPLETED))
        assertEquals(emptySet<Int>(), result.addable)
        assertEquals(emptySet<Int>(), result.skipped)
        assertEquals(emptyList<String>(), result.hints)
    }

    @Test
    fun partition_全可加入时跳过与提示为空() {
        val result = EpisodeDownloadRules.partition(setOf(1, 3, 5), emptyMap())
        assertEquals(setOf(1, 3, 5), result.addable)
        assertEquals(emptySet<Int>(), result.skipped)
        assertEquals(emptyList<String>(), result.hints)
    }

    @Test
    fun availableIndices_只返回可下载集号() {
        val statuses = mapOf(
            1 to DownloadTaskState.COMPLETED,
            2 to DownloadTaskState.DOWNLOADING,
            5 to DownloadTaskState.RESOLVING,
            6 to DownloadTaskState.FAILED
        )
        // 共 8 集：1/2/5 不可选，3/4/6(失败可重下)/7/8 可选。
        assertEquals(setOf(3, 4, 6, 7, 8), EpisodeDownloadRules.availableIndices(8, statuses))
    }

    @Test
    fun availableIndices_无记录时全部可选() {
        assertEquals(setOf(1, 2, 3), EpisodeDownloadRules.availableIndices(3, emptyMap()))
    }
}
