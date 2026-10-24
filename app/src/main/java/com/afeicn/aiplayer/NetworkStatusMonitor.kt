package com.afeicn.aiplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络状态监听：把 ConnectivityManager 的默认网络变化转成 StateFlow<NetworkKind>。
 *
 * - WiFi / 以太网等非计费网络归为 [NetworkKind.WIFI]；
 * - 移动数据归为 [NetworkKind.CELLULAR]；
 * - 没有网络归为 [NetworkKind.NONE]。
 * 由 [OfflineDownloadManager] 在初始化时启动，网络变化时触发下载队列自动暂停/恢复。
 */

/** 「允许移动数据下载」偏好（默认关闭）。 */
class DownloadPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("download_prefs", Context.MODE_PRIVATE)

    fun allowMobileData(): Boolean = prefs.getBoolean("allow_mobile_data", false)

    fun setAllowMobileData(value: Boolean) {
        prefs.edit().putBoolean("allow_mobile_data", value).apply()
    }
}

object NetworkStatusMonitor {

    private val _current = MutableStateFlow(NetworkKind.WIFI)
    val current: StateFlow<NetworkKind> = _current.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 供回调之外的代码同步取当前网络类型。 */
    fun currentKind(): NetworkKind = _current.value

    @Synchronized
    fun start(context: Context) {
        if (connectivityManager != null) {
            recompute()
            return
        }
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            _current.value = NetworkKind.NONE
            return
        }
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recompute()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = recompute()
            override fun onLost(network: Network) = recompute()
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb, handler) }
        recompute()
    }

    @Synchronized
    fun stop() {
        val cm = connectivityManager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        connectivityManager = null
    }

    private fun recompute() {
        val cm = connectivityManager ?: return
        _current.value = kindOf(cm.getNetworkCapabilities(cm.activeNetwork))
    }

    private fun kindOf(capabilities: NetworkCapabilities?): NetworkKind {
        if (capabilities == null) return NetworkKind.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            else -> NetworkKind.NONE
        }
    }
}
