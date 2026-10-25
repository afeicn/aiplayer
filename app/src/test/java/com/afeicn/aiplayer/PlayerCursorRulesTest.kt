package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放队列游标的边界规则：覆盖空队列、单条、首位、末位、index 越界五种情况。
 * 规则是纯函数，不依赖 MediaEntry / ExoPlayer，可直接在 JVM 上跑。
 */
class PlayerCursorRulesTest {

    @Test
    fun 空队列没有上一集下一集() {
        listOf(-1, 0, 1).forEach { index ->
            assertFalse("空队列 index=$index 不应有上一集", PlayerCursorRules.hasPrev(index, 0))
            assertFalse("空队列 index=$index 不应有下一集", PlayerCursorRules.hasNext(index, 0))
            assertEquals(-1, PlayerCursorRules.position(index, 0))
        }
    }

    @Test
    fun 单条队列两边都不可用() {
        assertFalse(PlayerCursorRules.hasPrev(0, 1))
        assertFalse(PlayerCursorRules.hasNext(0, 1))
        assertEquals(0, PlayerCursorRules.position(0, 1))
    }

    @Test
    fun 队列首位只有下一集() {
        assertFalse(PlayerCursorRules.hasPrev(0, 3))
        assertTrue(PlayerCursorRules.hasNext(0, 3))
        assertEquals(0, PlayerCursorRules.position(0, 3))
    }

    @Test
    fun 队列末位只有上一集() {
        assertTrue(PlayerCursorRules.hasPrev(2, 3))
        assertFalse(PlayerCursorRules.hasNext(2, 3))
        assertEquals(2, PlayerCursorRules.position(2, 3))
    }

    @Test
    fun 队列中间两边都可用() {
        assertTrue(PlayerCursorRules.hasPrev(1, 3))
        assertTrue(PlayerCursorRules.hasNext(1, 3))
        assertEquals(1, PlayerCursorRules.position(1, 3))
    }

    @Test
    fun index越界时两边都不可用() {
        listOf(-2, -1, 3, 4).forEach { index ->
            assertFalse("越界 index=$index 不应有上一集", PlayerCursorRules.hasPrev(index, 3))
            assertFalse("越界 index=$index 不应有下一集", PlayerCursorRules.hasNext(index, 3))
            assertEquals(-1, PlayerCursorRules.position(index, 3))
        }
    }
}
