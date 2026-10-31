package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 队列映射：UI 下标 ↔ Media3 播放列表下标、集号兜底、下一集名称。
 * 全部走纯数据（集号/名称列表），不依赖 android.net.Uri。
 */
class PlaybackQueueRulesTest {

    @Test
    fun 起播下标空队列归零且越界收敛() {
        assertEquals(0, PlaybackQueueRules.startIndex(3, 0))
        assertEquals(0, PlaybackQueueRules.startIndex(-2, 5))
        assertEquals(0, PlaybackQueueRules.startIndex(0, 5))
        assertEquals(4, PlaybackQueueRules.startIndex(9, 5))
        assertEquals(2, PlaybackQueueRules.startIndex(2, 5))
    }

    @Test
    fun 条目自带集号优先() {
        assertEquals(12, PlaybackQueueRules.episodeNumber(listOf(12, 13, 14), 0))
    }

    @Test
    fun 缺集号时不冒险反推() {
        // 当前集没有集号：即使前后集看着连续也不反推，宁可退回条目自身记录。
        assertNull(PlaybackQueueRules.episodeNumber(listOf(1, null, 3), 1))
        // 离线队列只下载了 11–13：下标 0 的条目没集号，不能反推成第 1 集。
        assertNull(PlaybackQueueRules.episodeNumber(listOf(null, null, null), 0))
        // 有集号但不连续（11,12,14）：空集号处也不反推。
        assertNull(PlaybackQueueRules.episodeNumber(listOf(11, null, 14), 1))
    }

    @Test
    fun 连续且集号齐全时按下标取真实集号() {
        assertEquals(1, PlaybackQueueRules.episodeNumber(listOf(1, 2, 3), 0))
        assertEquals(3, PlaybackQueueRules.episodeNumber(listOf(1, 2, 3), 2))
    }

    @Test
    fun 单条队列没有兜底集号() {
        assertNull(PlaybackQueueRules.episodeNumber(listOf(null), 0))
        assertEquals(8, PlaybackQueueRules.episodeNumber(listOf(8), 0))
    }

    @Test
    fun 集号为零或越界视为没有() {
        assertNull(PlaybackQueueRules.episodeNumber(listOf(0, -3), 0))
        assertNull(PlaybackQueueRules.episodeNumber(listOf(1, 2), -1))
        assertNull(PlaybackQueueRules.episodeNumber(listOf(1, 2), 5))
    }

    @Test
    fun 下一集名称取队列下一条() {
        assertEquals("第 2 集", PlaybackQueueRules.nextEntryName(listOf("第 1 集", "第 2 集"), 0))
        assertNull(PlaybackQueueRules.nextEntryName(listOf("第 1 集", "第 2 集"), 1))
        assertNull(PlaybackQueueRules.nextEntryName(listOf("第 1 集"), 0))
        assertNull(PlaybackQueueRules.nextEntryName(emptyList(), 0))
    }
}
