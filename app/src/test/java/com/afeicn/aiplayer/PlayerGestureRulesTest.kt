package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放页手势纯计算：方向锁定、双击分区、长按倍速档位、亮度音量换算、横向拖动加速快进。 */
class PlayerGestureRulesTest {

    // ==================== 方向判定 ====================

    @Test
    fun axisOf_未过阈值方向未定_占优轴锁定() {
        // 两条轴都没超过 touchSlop：还不定方向。
        assertEquals(PlayerGestureRules.Axis.NONE, PlayerGestureRules.axisOf(5f, 5f, 10f))
        assertEquals(PlayerGestureRules.Axis.NONE, PlayerGestureRules.axisOf(-10f, 10f, 10f))
        // 横向占优走横向，纵向占优走纵向。
        assertEquals(PlayerGestureRules.Axis.HORIZONTAL, PlayerGestureRules.axisOf(30f, 12f, 10f))
        assertEquals(PlayerGestureRules.Axis.VERTICAL, PlayerGestureRules.axisOf(12f, 30f, 10f))
        // 纵轴占优即使横轴也过了阈值。
        assertEquals(PlayerGestureRules.Axis.VERTICAL, PlayerGestureRules.axisOf(40f, 60f, 10f))
    }

    @Test
    fun sideOf_左半边调亮度_右半边调音量() {
        assertEquals(PlayerGestureRules.Side.BRIGHTNESS, PlayerGestureRules.sideOf(0f, 1000f))
        assertEquals(PlayerGestureRules.Side.BRIGHTNESS, PlayerGestureRules.sideOf(499f, 1000f))
        assertEquals(PlayerGestureRules.Side.VOLUME, PlayerGestureRules.sideOf(500f, 1000f))
        assertEquals(PlayerGestureRules.Side.VOLUME, PlayerGestureRules.sideOf(999f, 1000f))
    }

    // ==================== 双击分区 ====================

    @Test
    fun doubleTapZone_左右各一成快退快进_中间播放暂停() {
        assertEquals(PlayerGestureRules.DoubleTapZone.BACK, PlayerGestureRules.doubleTapZone(0f, 1000f))
        assertEquals(PlayerGestureRules.DoubleTapZone.BACK, PlayerGestureRules.doubleTapZone(100f, 1000f))
        assertEquals(PlayerGestureRules.DoubleTapZone.CENTER, PlayerGestureRules.doubleTapZone(101f, 1000f))
        assertEquals(PlayerGestureRules.DoubleTapZone.CENTER, PlayerGestureRules.doubleTapZone(500f, 1000f))
        assertEquals(PlayerGestureRules.DoubleTapZone.FORWARD, PlayerGestureRules.doubleTapZone(900f, 1000f))
        assertEquals(PlayerGestureRules.DoubleTapZone.FORWARD, PlayerGestureRules.doubleTapZone(1000f, 1000f))
    }

    // ==================== 长按倍速 ====================

    @Test
    fun longPressSpeed_56dp分档上下对称() {
        // 不动或小幅抖动：不改速。
        assertNull(PlayerGestureRules.longPressSpeed(0f))
        assertNull(PlayerGestureRules.longPressSpeed(-8f))
        assertNull(PlayerGestureRules.longPressSpeed(8f))
        // 轻微上/下滑先到 1.5x。
        assertEquals(1.5f, PlayerGestureRules.longPressSpeed(-20f)!!, 0f)
        assertEquals(1.5f, PlayerGestureRules.longPressSpeed(20f)!!, 0f)
        // 上滑一档 2x、两档 3x，更远还是 3x。
        assertEquals(2f, PlayerGestureRules.longPressSpeed(-56f)!!, 0f)
        assertEquals(2f, PlayerGestureRules.longPressSpeed(-100f)!!, 0f)
        assertEquals(3f, PlayerGestureRules.longPressSpeed(-112f)!!, 0f)
        assertEquals(3f, PlayerGestureRules.longPressSpeed(-300f)!!, 0f)
        // 下滑一档 0.75x、两档 0.5x，更远还是 0.5x。
        assertEquals(0.75f, PlayerGestureRules.longPressSpeed(56f)!!, 0f)
        assertEquals(0.5f, PlayerGestureRules.longPressSpeed(112f)!!, 0f)
        assertEquals(0.5f, PlayerGestureRules.longPressSpeed(400f)!!, 0f)
    }

    @Test
    fun longPressSpeed_阈值按档位缩放() {
        // 自定义档位 40dp：上滑 60dp（一档半）到 2x，80dp（两档）到 3x，3dp 抖动仍不改速。
        assertEquals(2f, PlayerGestureRules.longPressSpeed(-60f, stepDp = 40f)!!, 0f)
        assertEquals(3f, PlayerGestureRules.longPressSpeed(-80f, stepDp = 40f)!!, 0f)
        assertNull(PlayerGestureRules.longPressSpeed(-3f, stepDp = 40f))
    }

    // ==================== 亮度 / 音量 ====================

    @Test
    fun adjustLevel_按屏高比例加减并夹紧() {
        assertEquals(0.8f, PlayerGestureRules.adjustLevel(0.2f, 0.4f), 1e-4f)
        // 上滑到顶夹到 1。
        assertEquals(1f, PlayerGestureRules.adjustLevel(0.9f, 0.5f), 0f)
        // 下滑到底夹到下限：音量 0，亮度留 5% 可见。
        assertEquals(0f, PlayerGestureRules.adjustLevel(0.2f, -1f), 0f)
        assertEquals(0.05f, PlayerGestureRules.adjustLevel(0.2f, -1f, min = 0.05f), 1e-6f)
    }

    // ==================== 横向拖动加速快进（移植自 104vplayer） ====================

    @Test
    fun sustainedDirectionAcceleratesSymmetrically() {
        val right = AcceleratedSeek(500_000, 2_000_000)
        val first = right.move(20f) - 500_000
        right.move(100f)
        val at100 = right.move(100f)
        val later = right.move(120f) - at100
        assertTrue(later > first)
        val left = AcceleratedSeek(500_000, 2_000_000)
        assertEquals(right.move(120f) - 500_000, 500_000 - left.move(-120f))
    }

    @Test
    fun reversingResetsAccelerationWithoutJump() {
        val seek = AcceleratedSeek(500_000, 2_000_000)
        val fast = seek.move(200f)
        val reversed = seek.move(190f)
        assertTrue(fast - reversed in 1000L..1200L)
        assertEquals(reversed, seek.move(190f))
    }

    @Test
    fun movementIsIndependentOfEventFrequencyAndClamped() {
        val one = AcceleratedSeek(100_000, 2_000_000)
        val many = AcceleratedSeek(100_000, 2_000_000)
        for (i in 1..200) many.move(i.toFloat())
        assertEquals(one.move(200f), many.move(200f))
        assertEquals(0L, AcceleratedSeek(0, 1000).move(-100f))
        assertEquals(1000L, AcceleratedSeek(0, 1000).move(100f))
    }
}
