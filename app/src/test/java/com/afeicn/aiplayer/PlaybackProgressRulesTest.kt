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
}
