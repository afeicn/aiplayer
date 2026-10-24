package com.afeicn.aiplayer

/**
 * 离线下载的「移动数据闸门」纯逻辑层：只判断网络类型 + 开关，不碰 Android 框架，方便纯 JVM 单测。
 *
 * 规则：
 *  - WiFi（或以太网等非计费网络）→ 始终允许；
 *  - 移动数据 → 只有开启「允许移动数据下载」才允许；
 *  - 无网 → 一律不允许（下载无从谈起）。
 */

/** 网络类型：只区分 WiFi / 移动数据 / 无网三类。 */
enum class NetworkKind { WIFI, CELLULAR, NONE }

object DownloadNetworkGate {

    /** 是否允许下载：开关关闭时只在 WiFi 下下载，开启后移动数据也允许。 */
    fun isAllowed(kind: NetworkKind, allowMobileData: Boolean): Boolean = when (kind) {
        NetworkKind.WIFI -> true
        NetworkKind.CELLULAR -> allowMobileData
        NetworkKind.NONE -> false
    }

    /** 被闸门拦下时的中文提示；WiFi 不会被拦，返回 null。 */
    fun blockedHint(kind: NetworkKind): String? = when (kind) {
        NetworkKind.WIFI -> null
        NetworkKind.CELLULAR -> "当前是移动网络，已暂停下载（可在设置里允许移动数据下载）"
        NetworkKind.NONE -> "当前没有网络，已暂停下载（联网后自动继续）"
    }
}
