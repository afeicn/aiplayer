package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频路由变化暂停策略：
 * 有线拔出 / 蓝牙断开要暂停；无关广播和已经暂停的状态都不能动播放器。
 */
class AudioRouteRulesTest {

    @Test
    fun 广播常量与系统一致() {
        // 写成常量是为了纯 JVM 可测；这里守住字符串别手滑改错。
        assertEquals("android.media.AUDIO_BECOMING_NOISY", AudioRouteRules.ACTION_AUDIO_BECOMING_NOISY)
        assertEquals(
            "android.bluetooth.device.action.ACL_DISCONNECTED",
            AudioRouteRules.ACTION_ACL_DISCONNECTED
        )
    }

    @Test
    fun 输出变吵广播在播放时暂停() {
        assertTrue(
            AudioRouteRules.shouldPause(AudioRouteRules.ACTION_AUDIO_BECOMING_NOISY, playWhenReady = true)
        )
    }

    @Test
    fun 蓝牙ACL断开在播放时暂停() {
        assertTrue(
            AudioRouteRules.shouldPause(AudioRouteRules.ACTION_ACL_DISCONNECTED, playWhenReady = true)
        )
    }

    @Test
    fun 无关广播不暂停() {
        assertFalse(AudioRouteRules.isRouteChange("android.intent.action.SCREEN_OFF"))
        assertFalse(AudioRouteRules.shouldPause("android.intent.action.SCREEN_OFF", playWhenReady = true))
    }

    @Test
    fun 缓冲中也要暂停() {
        // 缓冲时 isPlaying=false、playWhenReady=true；只看 isPlaying 会漏掉，缓冲完整在扬声器上响。
        assertTrue(
            AudioRouteRules.shouldPause(AudioRouteRules.ACTION_AUDIO_BECOMING_NOISY, playWhenReady = true)
        )
    }

    @Test
    fun 已经暂停时不重复改状态() {
        assertFalse(
            AudioRouteRules.shouldPause(AudioRouteRules.ACTION_AUDIO_BECOMING_NOISY, playWhenReady = false)
        )
        assertFalse(
            AudioRouteRules.shouldPause(AudioRouteRules.ACTION_ACL_DISCONNECTED, playWhenReady = false)
        )
    }

    @Test
    fun 空action不暂停() {
        assertFalse(AudioRouteRules.isRouteChange(null))
        assertFalse(AudioRouteRules.isRouteChange(""))
        assertFalse(AudioRouteRules.shouldPause(null, playWhenReady = true))
    }
}
