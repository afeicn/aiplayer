package com.afeicn.aiplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载网络闸门纯函数：网络类型 × 开关 → 是否允许下载。 */
class DownloadNetworkGateTest {

    @Test
    fun wifiAlwaysAllowed() {
        assertTrue(DownloadNetworkGate.isAllowed(NetworkKind.WIFI, allowMobileData = false))
        assertTrue(DownloadNetworkGate.isAllowed(NetworkKind.WIFI, allowMobileData = true))
    }

    @Test
    fun cellularRequiresAllowMobileData() {
        assertFalse(DownloadNetworkGate.isAllowed(NetworkKind.CELLULAR, allowMobileData = false))
        assertTrue(DownloadNetworkGate.isAllowed(NetworkKind.CELLULAR, allowMobileData = true))
    }

    @Test
    fun noNetworkNeverAllowed() {
        assertFalse(DownloadNetworkGate.isAllowed(NetworkKind.NONE, allowMobileData = false))
        assertFalse(DownloadNetworkGate.isAllowed(NetworkKind.NONE, allowMobileData = true))
    }

    @Test
    fun blockedHintIsChineseAndOnlyWhenBlocked() {
        assertTrue(
            DownloadNetworkGate.blockedHint(NetworkKind.CELLULAR)!!
                .contains("移动网络")
        )
        assertTrue(
            DownloadNetworkGate.blockedHint(NetworkKind.NONE)!!
                .contains("没有网络")
        )
        assertTrue(DownloadNetworkGate.blockedHint(NetworkKind.WIFI) == null)
    }
}
