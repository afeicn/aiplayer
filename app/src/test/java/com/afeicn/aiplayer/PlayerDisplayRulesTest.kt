package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向 / 全屏状态机：
 * 正常竖屏；系统转横屏自动全屏；手动按钮也能进/退全屏；音频不参与全屏。
 */
class PlayerDisplayRulesTest {

    @Test
    fun 竖屏是默认形态() {
        assertFalse(PlayerDisplayRules.isFullscreen(MediaKind.VIDEO, false, false))
    }

    @Test
    fun 系统转到横屏自动全屏() {
        assertTrue(PlayerDisplayRules.isFullscreen(MediaKind.VIDEO, false, true))
    }

    @Test
    fun 手动全屏按钮在竖屏也进全屏() {
        assertTrue(PlayerDisplayRules.isFullscreen(MediaKind.VIDEO, true, false))
    }

    @Test
    fun 音频条目永远不全屏() {
        assertFalse(PlayerDisplayRules.isFullscreen(MediaKind.AUDIO, true, true))
        assertFalse(PlayerDisplayRules.isFullscreen(MediaKind.AUDIO, false, true))
    }

    @Test
    fun 没有播放条目时不是全屏() {
        assertFalse(PlayerDisplayRules.isFullscreen(null, true, true))
    }

    @Test
    fun 非播放页恢复跟随系统方向() {
        assertEquals(
            PlayerOrientation.UNSPECIFIED,
            PlayerDisplayRules.orientationRequest(false, MediaKind.VIDEO, true, false)
        )
    }

    @Test
    fun 视频竖屏跟随重力感应() {
        assertEquals(
            PlayerOrientation.UNSPECIFIED,
            PlayerDisplayRules.orientationRequest(true, MediaKind.VIDEO, false, false)
        )
    }

    @Test
    fun 视频手动全屏请求横屏() {
        assertEquals(
            PlayerOrientation.SENSOR_LANDSCAPE,
            PlayerDisplayRules.orientationRequest(true, MediaKind.VIDEO, true, false)
        )
    }

    @Test
    fun 音频锁竖屏避免横屏黑屏() {
        assertEquals(
            PlayerOrientation.PORTRAIT,
            PlayerDisplayRules.orientationRequest(true, MediaKind.AUDIO, false, false)
        )
        // 即使手动全屏标记残留，音频也不请求横屏。
        assertEquals(
            PlayerOrientation.PORTRAIT,
            PlayerDisplayRules.orientationRequest(true, MediaKind.AUDIO, true, false)
        )
    }

    @Test
    fun PiP里不跟系统抢方向() {
        assertEquals(
            PlayerOrientation.UNSPECIFIED,
            PlayerDisplayRules.orientationRequest(true, MediaKind.VIDEO, true, true)
        )
    }
}
