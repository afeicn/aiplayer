package com.afeicn.aiplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 找手机在局域网里的 IPv4 地址：电视要访问 `http://这个地址:端口`。
 *
 * 只认 Wi-Fi / 以太网，不看 VPN / 蜂窝：投屏必须和电视在同一局域网。
 * 拿不到时返回 null，由调用方提示「先连 Wi-Fi」。
 */
internal object CastNetwork {

    fun localIpv4(context: Context): String? {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
            ?: return null
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) return@firstNotNullOfOrNull null
            manager.getLinkProperties(network)?.linkAddresses?.firstNotNullOfOrNull { link ->
                val address = link.address
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    address.hostAddress
                } else {
                    null
                }
            }
        }
    }
}
