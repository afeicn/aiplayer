package com.afeicn.aiplayer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DLNA AVTransport 的 SOAP 控制端。
 *
 * 每次动作都是一条独立的 HTTP POST，`SOAPACTION` 头带上服务类型和动作名。
 * 电视返回非 2xx 就抛 [IOException]（带上响应体，方便在投屏面板里给出人话错误）。
 */
internal object DlnaController {

    suspend fun setUriAndPlay(device: DlnaDevice, mediaUrl: String, metadata: String) =
        withContext(Dispatchers.IO) {
            post(device.avTransportControlUrl, "SetAVTransportURI",
                DlnaProtocol.setAvTransportUri(mediaUrl, metadata))
            post(device.avTransportControlUrl, "Play", DlnaProtocol.play())
        }

    suspend fun play(device: DlnaDevice) = withContext(Dispatchers.IO) {
        post(device.avTransportControlUrl, "Play", DlnaProtocol.play())
    }

    suspend fun pause(device: DlnaDevice) = withContext(Dispatchers.IO) {
        post(device.avTransportControlUrl, "Pause", DlnaProtocol.pause())
    }

    suspend fun stop(device: DlnaDevice) = withContext(Dispatchers.IO) {
        post(device.avTransportControlUrl, "Stop", DlnaProtocol.stop())
    }

    suspend fun seek(device: DlnaDevice, positionMs: Long) = withContext(Dispatchers.IO) {
        post(device.avTransportControlUrl, "Seek", DlnaProtocol.seek(positionMs))
    }

    private fun post(controlUrl: String, action: String, body: String) {
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            // 规范要求 SOAPACTION 带双引号，部分电视缺了会直接 500。
            connection.setRequestProperty(
                "SOAPACTION",
                "\"${DlnaProtocol.AV_TRANSPORT_SERVICE}#$action\""
            )
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("电视拒绝 $action（HTTP $status）${detail.take(160)}")
            }
            // 响应体只是 SOAP 回执，读掉即可，不解析。
            connection.inputStream?.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
