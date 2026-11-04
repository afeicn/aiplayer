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

    @Test
    fun 竖屏右上角是进全屏入口() {
        assertEquals(
            OrientationToggle.ENTER_FULLSCREEN,
            PlayerDisplayRules.orientationToggle(MediaKind.VIDEO, isFullscreen = false)
        )
    }

    @Test
    fun 横屏右上角是退全屏入口() {
        assertEquals(
            OrientationToggle.EXIT_FULLSCREEN,
            PlayerDisplayRules.orientationToggle(MediaKind.VIDEO, isFullscreen = true)
        )
    }

    @Test
    fun 音频没有方向切换入口() {
        assertEquals(
            OrientationToggle.NONE,
            PlayerDisplayRules.orientationToggle(MediaKind.AUDIO, isFullscreen = false)
        )
        assertEquals(
            OrientationToggle.NONE,
            PlayerDisplayRules.orientationToggle(null, isFullscreen = true)
        )
    }

    @Test
    fun 竖屏点方向切换进入手动全屏() {
        val next = PlayerDisplayRules.fullscreenToggleState(isFullscreen = false)
        assertTrue(next.manualFullscreen)
        assertFalse(next.restoreAutoRotate)
    }

    @Test
    fun 全屏点方向切换退出并先锁竖屏() {
        val next = PlayerDisplayRules.fullscreenToggleState(isFullscreen = true)
        assertFalse(next.manualFullscreen)
        assertTrue(next.restoreAutoRotate)
    }

    // ==================== 竖屏播放区画幅 ====================

    @Test
    fun 标准十六比九片源保持原有画幅() {
        assertEquals(
            16f / 9f,
            PlayerDisplayRules.videoStageAspect(1920, 1080),
            0.001f
        )
    }

    @Test
    fun 四比三片源不再按十六比九留左右白边() {
        assertEquals(
            4f / 3f,
            PlayerDisplayRules.videoStageAspect(1440, 1080),
            0.001f
        )
    }

    @Test
    fun 超宽片源收敛到二十一比九() {
        assertEquals(
            PlayerDisplayRules.MAX_VIDEO_ASPECT,
            PlayerDisplayRules.videoStageAspect(1920, 800),
            0.001f
        )
    }

    @Test
    fun 竖屏短视频收敛到三比四避免撑爆屏幕() {
        assertEquals(
            PlayerDisplayRules.MIN_VIDEO_ASPECT,
            PlayerDisplayRules.videoStageAspect(1080, 1920),
            0.001f
        )
    }

    @Test
    fun 尺寸未知时退回十六比九() {
        assertEquals(
            PlayerDisplayRules.DEFAULT_VIDEO_ASPECT,
            PlayerDisplayRules.videoStageAspect(0, 0),
            0.001f
        )
        assertEquals(
            PlayerDisplayRules.DEFAULT_VIDEO_ASPECT,
            PlayerDisplayRules.videoStageAspect(-1, 1080),
            0.001f
        )
    }

    @Test
    fun 变形宽银幕乘上像素宽高比() {
        // 1440x1080 的 4:3 存储，按 1.333 像素比显示就是 16:9。
        assertEquals(
            16f / 9f,
            PlayerDisplayRules.videoStageAspect(1440, 1080, pixelWidthHeightRatio = 4f / 3f),
            0.001f
        )
        // 非法像素比退回 16:9，不能算出 0 或 NaN。
        assertEquals(
            PlayerDisplayRules.DEFAULT_VIDEO_ASPECT,
            PlayerDisplayRules.videoStageAspect(1920, 1080, pixelWidthHeightRatio = 0f),
            0.001f
        )
    }
}
