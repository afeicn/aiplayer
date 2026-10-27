package com.afeicn.aiplayer

import org.junit.Assert.*
import org.junit.Test

class ProgressSaveRulesTest {
    @Test fun savesWhenPlayheadAdvancedPastInterval() {
        assertTrue(ProgressSaveRules.shouldSave(0L, 5_000L))
        assertTrue(ProgressSaveRules.shouldSave(120_000L, 125_000L))
    }

    @Test fun skipsTinyMovements() {
        // 暂停、缓冲时位置基本没动，不必每 5 秒空写一次 SharedPreferences。
        assertFalse(ProgressSaveRules.shouldSave(120_000L, 120_000L))
        assertFalse(ProgressSaveRules.shouldSave(120_000L, 122_999L))
    }

    @Test fun backwardSeekIsSavedToo() {
        assertTrue(ProgressSaveRules.shouldSave(600_000L, 120_000L))
    }

    @Test fun thresholdIsInclusiveAtMinimumDelta() {
        assertTrue(ProgressSaveRules.shouldSave(10_000L, 10_000L + ProgressSaveRules.MIN_DELTA_MS))
        assertFalse(ProgressSaveRules.shouldSave(10_000L, 10_000L + ProgressSaveRules.MIN_DELTA_MS - 1))
    }

    @Test fun savesLessOftenThanEverySecond() {
        // 兜底间隔要明显大于位置轮询的 500ms，避免每个轮询周期都写一次盘。
        assertTrue(ProgressSaveRules.INTERVAL_MS >= 3_000L)
    }
}
