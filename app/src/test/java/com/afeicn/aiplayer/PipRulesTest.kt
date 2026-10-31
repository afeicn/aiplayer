package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PiP 进出条件与宽高比：视频播放中才允许，音频/列表/暂停都不进。 */
class PipRulesTest {

    private fun eligible(
        playerPage: Boolean = true,
        kind: MediaKind? = MediaKind.VIDEO,
        playing: Boolean = true,
        sdk: Int = 34,
        feature: Boolean = true
    ) = PipRules.eligible(playerPage, kind, playing, sdk, feature)

    @Test
    fun 视频播放中可以进PiP() {
        assertTrue(eligible())
    }

    @Test
    fun 非播放页不进PiP() {
        assertFalse(eligible(playerPage = false))
    }

    @Test
    fun 音频条目不进PiP() {
        assertFalse(eligible(kind = MediaKind.AUDIO))
        assertFalse(eligible(kind = null))
    }

    @Test
    fun 暂停状态不进PiP() {
        assertFalse(eligible(playing = false))
    }

    @Test
    fun Android8以下或设备不支持不进PiP() {
        assertFalse(eligible(sdk = 25))
        assertTrue(eligible(sdk = PipRules.MIN_SDK))
        assertFalse(eligible(feature = false))
    }

    @Test
    fun 宽高比优先用视频实际尺寸() {
        assertEquals(1920 to 1080, PipRules.aspectRatio(1920, 1080))
        assertEquals(1080 to 1920, PipRules.aspectRatio(1080, 1920))
    }

    @Test
    fun 非法尺寸退回16比9() {
        assertEquals(16 to 9, PipRules.aspectRatio(0, 0))
        assertEquals(16 to 9, PipRules.aspectRatio(-1, 1080))
    }

    @Test
    fun 极端宽高比收敛到系统允许范围() {
        // 1:10 → 1:2.39
        assertEquals(100 to 239, PipRules.aspectRatio(100, 1000))
        // 3:1 → 2.39:1
        assertEquals(239 to 100, PipRules.aspectRatio(3000, 1000))
    }

    @Test
    fun 只有从PiP退出才请求全屏() {
        assertTrue(PipRules.requestFullscreenOnExit(wasInPip = true, isInPip = false))
        assertFalse(PipRules.requestFullscreenOnExit(wasInPip = false, isInPip = true))
        assertFalse(PipRules.requestFullscreenOnExit(wasInPip = false, isInPip = false))
        assertFalse(PipRules.requestFullscreenOnExit(wasInPip = true, isInPip = true))
    }
}
