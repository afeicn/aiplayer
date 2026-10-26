package com.afeicn.aiplayer

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressRulesTest {
    @Test fun reopeningCurrentMediaUsesLiveBackgroundPosition() {
        assertEquals(139_000L, PlaybackProgressRules.resumePosition(true, 139_000, 120_000))
    }
    @Test fun backwardSeekKeepsLatestPositionRatherThanFurthestPosition() {
        assertEquals(120_000L, PlaybackProgressRules.resumePosition(true, 120_000, 139_000))
    }
    @Test fun differentMediaUsesItsOwnSavedPosition() {
        assertEquals(120_000L, PlaybackProgressRules.resumePosition(false, 900_000, 120_000))
        assertEquals(0L, PlaybackProgressRules.resumePosition(false, 900_000, -1))
    }
    @Test fun switchingOrClearingQueueSavesOutgoingItemOnly() {
        assertTrue(PlaybackProgressRules.saveOutgoing("a", "b"))
        assertTrue(PlaybackProgressRules.saveOutgoing("a", null))
        assertFalse(PlaybackProgressRules.saveOutgoing("a", "a"))
        assertFalse(PlaybackProgressRules.saveOutgoing(null, "b"))
    }

    // 离线队列起点：下载了 11–24 集、上次播到第 17 集 → 必须落在第 17 集，
    // 不能回到「第一条有断点记录」的第 11 集（老逻辑的 bug）。
    private val downloaded11to24 = (11..24).toList()

    @Test fun offlineQueueStartsAtRecordedEpisodeNotFirstWatched() {
        val positions = downloaded11to24.map { if (it <= 17) 60_000L else 0L }
        assertEquals(6, PlaybackProgressRules.offlineResumeIndex(downloaded11to24, 17, positions))
    }

    @Test fun offlineQueueFallsBackToNearestDownloadedBeforeRecord() {
        // 记录停在第 17 集，但只下载到 14 集：退到已下载里最靠前它的那一集。
        val downloaded = (11..14).toList()
        assertEquals(3, PlaybackProgressRules.offlineResumeIndex(downloaded, 17, downloaded.map { 0L }))
    }

    @Test fun offlineQueueWithoutEpisodeRecordUsesFirstPositionedEpisode() {
        val numbers = (11..14).toList()
        val positions = listOf(0L, 0L, 90_000L, 45_000L)
        assertEquals(2, PlaybackProgressRules.offlineResumeIndex(numbers, 0, positions))
    }

    @Test fun offlineQueueRecordBeforeDownloadedRangeUsesPositionFallback() {
        // 记录在第 5 集（在线看的），下载的是 11–24：都对不上，退回断点兜底。
        val positions = (11..24).map { if (it == 13) 30_000L else 0L }
        assertEquals(2, PlaybackProgressRules.offlineResumeIndex((11..24).toList(), 5, positions))
    }

    @Test fun offlineQueueEmptyStoreStartsAtFirstEpisode() {
        assertEquals(0, PlaybackProgressRules.offlineResumeIndex(downloaded11to24, 0, downloaded11to24.map { 0L }))
    }

    @Test fun offlineQueueToleratesUnresolvedEpisodeNumbers() {
        val numbers = listOf(null, null, 13, null)
        val positions = listOf(90_000L, 0L, 0L, 0L)
        assertEquals(2, PlaybackProgressRules.offlineResumeIndex(numbers, 13, positions))
        assertEquals(0, PlaybackProgressRules.offlineResumeIndex(numbers, 0, positions))
    }
}
