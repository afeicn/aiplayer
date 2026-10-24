package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthPolicyTest {

    private val t0 = 1_000_000L

    private fun fail(times: Int, start: SourceHealth = SourceHealth(), now: Long = t0): SourceHealth {
        var health = start
        repeat(times) { health = SourceHealthPolicy.recordFailure(now, health) }
        return health
    }

    @Test
    fun successStartsHealthy() {
        val health = SourceHealthPolicy.recordSuccess(t0)
        assertEquals(0, health.consecutiveFailures)
        assertFalse(SourceHealthPolicy.isCoolingDown(health, t0))
        assertEquals(SourceCooldownTier.ACTIVE, SourceHealthPolicy.tier(health, t0))
    }

    @Test
    fun failuresBelowThresholdHaveNoCooldown() {
        val health = fail(2)
        assertEquals(2, health.consecutiveFailures)
        assertFalse(SourceHealthPolicy.isCoolingDown(health, t0))
        assertEquals(SourceCooldownTier.ACTIVE, SourceHealthPolicy.tier(health, t0))
    }

    @Test
    fun thirdFailureTriggers30MinuteCooldown() {
        val health = fail(3)
        assertEquals(SourceCooldownTier.MINUTES_30, SourceHealthPolicy.tier(health, t0))
        assertTrue(SourceHealthPolicy.isCoolingDown(health, t0))
        assertEquals(t0 + SourceHealthPolicy.COOLDOWN_30MIN_MS, health.cooldownUntil)
    }

    @Test
    fun sixthFailureEscalatesTo24HourCooldown() {
        val health = fail(6)
        assertEquals(SourceCooldownTier.HOURS_24, SourceHealthPolicy.tier(health, t0))
        assertEquals(t0 + SourceHealthPolicy.COOLDOWN_24H_MS, health.cooldownUntil)
    }

    @Test
    fun ninthFailureIsLongTerm() {
        val health = fail(9)
        assertEquals(SourceCooldownTier.LONG_TERM, SourceHealthPolicy.tier(health, t0))
        assertTrue(SourceHealthPolicy.isCoolingDown(health, t0))
    }

    @Test
    fun cooldownExpiresAfterDeadline() {
        val health = fail(3)
        val after = t0 + SourceHealthPolicy.COOLDOWN_30MIN_MS + 1
        assertFalse(SourceHealthPolicy.isCoolingDown(health, after))
        assertEquals(SourceCooldownTier.ACTIVE, SourceHealthPolicy.tier(health, after))
    }

    @Test
    fun successResetsConsecutiveFailures() {
        val afterFail = fail(5)
        val recovered = SourceHealthPolicy.recordSuccess(t0 + 1000)
        assertEquals(5, afterFail.consecutiveFailures)
        assertEquals(0, recovered.consecutiveFailures)
        assertFalse(SourceHealthPolicy.isCoolingDown(recovered, t0 + 1000))
    }

    @Test
    fun recoverClearsEverything() {
        val health = fail(9)
        val recovered = SourceHealthPolicy.recover()
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(0L, recovered.cooldownUntil)
        assertFalse(SourceHealthPolicy.isCoolingDown(recovered, t0))
        assertEquals(SourceCooldownTier.ACTIVE, SourceHealthPolicy.tier(recovered, t0))
    }
}
