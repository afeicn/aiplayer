package com.afeicn.aiplayer

/**
 * 音频输出路由变化的暂停策略（纯 JVM，便于单测）。
 *
 * Android 没有「耳机被摘下」这个通用事件，能拿到的是输出路由变化：
 * - [ACTION_AUDIO_BECOMING_NOISY]：有线耳机拔出、蓝牙断开、输出从耳机切到扬声器时由系统发出；
 * - [ACTION_ACL_DISCONNECTED]：蓝牙 ACL 真正断开，部分 ROM 不发 becoming-noisy 时兜底。
 *
 * 耳机自己是否在摘下的瞬间发 AVRCP PAUSE 属于耳机固件行为，App 侧只能被动接收媒体键，
 * 不能把「所有耳机摘下都暂停」当成可实现需求（详见 docs/audio-route-pause.md）。
 */
internal object AudioRouteRules {

    const val ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY"

    const val ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"

    /** 该广播是否意味着「声音不该继续按原路由外放」。 */
    fun isRouteChange(action: String?): Boolean = when (action) {
        ACTION_AUDIO_BECOMING_NOISY, ACTION_ACL_DISCONNECTED -> true
        else -> false
    }

    /**
     * 收到广播时是否真的暂停。
     *
     * 用 `playWhenReady` 而不是 `isPlaying`：缓冲中（还没出声）拔耳机也要暂停，
     * 否则缓冲完会直接在扬声器上响。已经暂停时 `playWhenReady=false`，不碰状态，
     * 用户手动暂停的播放不会被路由抖动重新拉起；两条广播同时到达也不会二次改变状态。
     */
    fun shouldPause(action: String?, playWhenReady: Boolean): Boolean =
        playWhenReady && isRouteChange(action)
}
