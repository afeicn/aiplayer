package com.afeicn.aiplayer

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 局域网 SSDP 搜索 DLNA 媒体渲染器（电视 / 盒子）。
 *
 * 流程：
 * 1. 组播 M-SEARCH（同一条发两次，降低丢包概率）；
 * 2. 在搜索窗口内收单播响应，收集 LOCATION；
 * 3. 并发拉取每台设备的描述 XML，筛出带 AVTransport 的；
 * 4. 按控制地址去重，按名字排序。
 *
 * 搜索期间持有 MulticastLock：部分 ROM 在锁屏/省电时会过滤组播，不持锁经常搜不到。
 */
internal object DlnaDiscovery {

    suspend fun discover(context: Context, timeoutMs: Long = 3500): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val multicastLock = runCatching {
                context.applicationContext.getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("aiplayer-dlna")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()

            try {
                val locations = searchLocations(timeoutMs)
                coroutineScope {
                    locations.map { location -> async { fetchDevice(location) } }
                        .awaitAll()
                        .filterNotNull()
                        .distinctBy { it.avTransportControlUrl }
                        .sortedBy { it.friendlyName.lowercase() }
                }
            } finally {
                runCatching { multicastLock?.release() }
            }
        }

    private fun searchLocations(timeoutMs: Long): Set<String> {
        val locations = LinkedHashSet<String>()
        val socket = DatagramSocket()
        socket.soTimeout = 350
        try {
            val payload = DlnaProtocol.searchRequest().toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(DlnaProtocol.SSDP_HOST)
            repeat(2) {
                socket.send(DatagramPacket(payload, payload.size, group, DlnaProtocol.SSDP_PORT))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    DlnaProtocol.parseSsdpResponse(raw)?.location?.let { locations += it }
                } catch (_: SocketTimeoutException) {
                    // 没新响应就继续等，直到搜索窗口结束。
                }
            }
        } finally {
            runCatching { socket.close() }
        }
        return locations
    }

    private fun fetchDevice(location: String): DlnaDevice? = runCatching {
        val connection = URL(location).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.setRequestProperty("User-Agent", "Android/UPnP/1.1 AIPlayer/1.0")
            connection.setRequestProperty("Connection", "close")
            val xml = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(4096)
                var total = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 256 * 1024) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
            DlnaProtocol.parseDeviceDescription(location, xml)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
