package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源健康状况的纯规则单测：内置封锁清单、Cloudflare 挑战失败 30 分钟冷却、备注位。
 * 不碰 Android Context，也不联网。
 */
class SourceStatusRulesTest {

    private val now = 1_000_000L

    @Test
    fun `witzsb 永远判封锁，即使本地记录是正常的`() {
        val stored = SourceStatusRules.recovered(now)
        val status = SourceStatusRules.effective("https://www.witzsb.com/", stored, now)
        assertEquals(SourceAvailability.BLOCKED, status.availability)
        assertEquals(SourceStatusRules.BLOCKED_REASON, status.reason)
        assertEquals(
            SourceStatusRules.BLOCKED_REASON,
            SourceStatusRules.skipReason("witzsb.com", stored, now)
        )
    }

    @Test
    fun `witzsb 子域名同样封锁`() {
        assertEquals(
            SourceStatusRules.BLOCKED_REASON,
            SourceStatusRules.skipReason("m.witzsb.com", null, now)
        )
    }

    @Test
    fun `booksru 只给备注，不跳过搜索`() {
        assertNull(SourceStatusRules.skipReason("booksru.net", null, now))
        assertEquals(SourceStatusRules.CLOUDFLARE_NOTE, SourceStatusRules.noteFor("booksru.net", null, now))
    }

    @Test
    fun `挑战失败后 30 分钟内跳过，到期自动恢复`() {
        val failed = SourceStatusRules.challengeFailed(now)
        assertEquals(SourceStatusRules.CHALLENGE_REASON, SourceStatusRules.skipReason("booksru.net", failed, now + 1))
        assertEquals(
            SourceStatusRules.CHALLENGE_REASON,
            SourceStatusRules.skipReason(
                "booksru.net", failed, now + SourceStatusRules.CHALLENGE_RETRY_MS - 1
            )
        )
        assertNull(
            SourceStatusRules.skipReason(
                "booksru.net", failed, now + SourceStatusRules.CHALLENGE_RETRY_MS
            )
        )
    }

    @Test
    fun `预热成功后冷却记录被清除`() {
        val failed = SourceStatusRules.challengeFailed(now)
        val recovered = SourceStatusRules.recovered(now + 1)
        assertNull(SourceStatusRules.skipReason("booksru.net", recovered, now + 2))
        // 成功记录本身不会被误判成失败。
        assertEquals(SourceAvailability.OK, SourceStatusRules.effective("booksru.net", recovered, now + 2).availability)
        assertEquals(SourceAvailability.CHALLENGE_FAILED, failed.availability)
    }

    @Test
    fun `未知域名没有记录时不跳过`() {
        assertNull(SourceStatusRules.skipReason("example.com", null, now))
        assertFalse(SourceStatusRules.isChallengeCoolingDown(null, now))
        assertTrue(SourceStatusRules.isChallengeCoolingDown(SourceStatusRules.challengeFailed(now), now))
    }
}
