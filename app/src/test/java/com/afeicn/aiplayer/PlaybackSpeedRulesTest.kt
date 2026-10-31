package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 倍速控件状态：按钮文案、档位回推、越界兜底。
 */
class PlaybackSpeedRulesTest {

    @Test
    fun 默认档位文案带一位小数() {
        assertEquals("1.0x", PlaybackSpeedRules.label(PlaybackSpeedRules.DEFAULT))
        assertEquals("1.0x", PlaybackSpeedRules.label(PlaybackSpeedRules.speedAt(0)))
    }

    @Test
    fun 四档文案完整() {
        assertEquals(
            listOf("1.0x", "1.25x", "1.5x", "2.0x"),
            PlaybackSpeedRules.OPTIONS.map(PlaybackSpeedRules::label)
        )
    }

    @Test
    fun 精确速度定位到对应档位() {
        PlaybackSpeedRules.OPTIONS.forEachIndexed { index, speed ->
            assertEquals(index, PlaybackSpeedRules.indexOf(speed))
            assertEquals(speed, PlaybackSpeedRules.speedAt(index))
        }
    }

    @Test
    fun 临时速度收敛到最近档位() {
        // 长按手势可能留下 1.8x 这类非档位速度，回到页面时显示最近的 2.0x。
        assertEquals(3, PlaybackSpeedRules.indexOf(1.8f))
        assertEquals(0, PlaybackSpeedRules.indexOf(1.1f))
        assertEquals(0, PlaybackSpeedRules.indexOf(0.5f))
        assertEquals(3, PlaybackSpeedRules.indexOf(3.0f))
    }

    @Test
    fun 非法速度回落默认档() {
        assertEquals(0, PlaybackSpeedRules.indexOf(Float.NaN))
        assertEquals(0, PlaybackSpeedRules.indexOf(Float.POSITIVE_INFINITY))
    }

    @Test
    fun 非法下标回落默认速度() {
        assertEquals(PlaybackSpeedRules.DEFAULT, PlaybackSpeedRules.speedAt(-1))
        assertEquals(PlaybackSpeedRules.DEFAULT, PlaybackSpeedRules.speedAt(99))
    }
}
