package com.afeicn.aiplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存占用统计的刷新闸门：
 * - 首次（last = 0）要算；
 * - TTL 内不重复扫盘；
 * - 已经有统计在跑时不并发扫第二次；
 * - 强制刷新（如下载完成）无视 TTL，但也不和进行中的统计并发。
 */
class CacheSpaceRulesTest {

    @Test
    fun 首次调用需要统计() {
        assertTrue(CacheSpaceRules.shouldRefresh(nowMs = 1_000, lastComputedAtMs = 0, running = false, force = false))
    }

    @Test
    fun TTL内不重复统计() {
        val last = 10_000L
        assertFalse(
            CacheSpaceRules.shouldRefresh(
                nowMs = last + CacheSpaceRules.TTL_MS - 1, lastComputedAtMs = last,
                running = false, force = false
            )
        )
    }

    @Test
    fun TTL到期后重新统计() {
        val last = 10_000L
        assertTrue(
            CacheSpaceRules.shouldRefresh(
                nowMs = last + CacheSpaceRules.TTL_MS, lastComputedAtMs = last,
                running = false, force = false
            )
        )
    }

    @Test
    fun 已有统计在跑时不重复启动() {
        assertFalse(
            CacheSpaceRules.shouldRefresh(
                nowMs = 1_000_000, lastComputedAtMs = 0, running = true, force = false
            )
        )
        assertFalse(
            CacheSpaceRules.shouldRefresh(
                nowMs = 1_000_000, lastComputedAtMs = 0, running = true, force = true
            )
        )
    }

    @Test
    fun 强制刷新无视TTL() {
        val last = 20_000L
        assertTrue(
            CacheSpaceRules.shouldRefresh(
                nowMs = last + 1, lastComputedAtMs = last, running = false, force = true
            )
        )
    }
}
