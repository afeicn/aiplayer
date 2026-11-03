package com.afeicn.aiplayer

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * 一个可投屏的 DLNA 媒体渲染器（电视 / 盒子）。
 * [avTransportControlUrl] 是 AVTransport 服务的 SOAP 控制地址，已经按设备描述解析成绝对地址。
 */
internal data class DlnaDevice(
    val friendlyName: String,
    val modelName: String,
    val location: String,
    val avTransportControlUrl: String
)

/** SSDP 搜索响应里我们关心的字段。 */
internal data class SsdpResponse(
    val location: String,
    val searchTarget: String,
    val usn: String
)

/**
 * DLNA / UPnP AVTransport 协议拼装与解析（纯 JVM，单测覆盖）。
 *
 * 只做「投屏播放」需要的最小集合：
 * SSDP 搜索 → 设备描述 XML → AVTransport 的 SetAVTransportURI / Play / Pause / Stop / Seek。
 */
internal object DlnaProtocol {

    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    private const val SOAP_ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val DIDL_NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val UPNP_NS = "urn:schemas-upnp-org:metadata-1-0/upnp/"

    /** SSDP M-SEARCH 报文。MX 是设备随机等待的上限（秒），给 3 秒足够局域网响应。 */
    fun searchRequest(mxSeconds: Int = 3): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mxSeconds\r\n")
        append("ST: $SEARCH_TARGET\r\n")
        append("USER-AGENT: Android/UPnP/1.1 AIPlayer/1.0\r\n")
        append("\r\n")
    }

    /**
     * 解析 SSDP 响应。header 名大小写不敏感；不是 200 或没有 LOCATION 的直接丢弃。
     * 同一设备可能回多条（不同 ST），由调用方按 LOCATION 去重。
     */
    fun parseSsdpResponse(raw: String): SsdpResponse? {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val status = lines.first()
        if (!status.startsWith("HTTP/1.1 200", ignoreCase = true) &&
            !status.startsWith("HTTP/1.0 200", ignoreCase = true)
        ) return null
        val headers = parseHeaders(lines.drop(1))
        val location = headers["location"]?.takeIf { it.isNotBlank() } ?: return null
        return SsdpResponse(
            location = location,
            searchTarget = headers["st"].orEmpty(),
            usn = headers["usn"].orEmpty()
        )
    }

    /**
     * 解析设备描述 XML，找到 AVTransport 服务并解析 controlURL。
     * 只有声明了 AVTransport 的设备才能投屏；没有的返回 null。
     */
    fun parseDeviceDescription(location: String, xml: String): DlnaDevice? {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // 局域网设备描述不该带 DTD；支持就禁掉，不支持也不影响解析。
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        val friendlyName = document.textOfFirst("friendlyName")
        val modelName = document.textOfFirst("modelName")
        val services = document.getElementsByTagNameNS("*", "service")
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            if (service.textOfFirst("serviceType") != AV_TRANSPORT_SERVICE) continue
            val controlUrl = resolveUrl(location, service.textOfFirst("controlURL")) ?: return null
            return DlnaDevice(
                friendlyName = friendlyName.ifBlank { modelName.ifBlank { "DLNA 电视" } },
                modelName = modelName,
                location = location,
                avTransportControlUrl = controlUrl
            )
        }
        return null
    }

    fun setAvTransportUri(mediaUrl: String, metadata: String): String = soapEnvelope(
        "SetAVTransportURI",
        "<CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>" +
            "<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>"
    )

    fun play(): String = soapEnvelope("Play", "<Speed>1</Speed>")

    fun pause(): String = soapEnvelope("Pause", "")

    fun stop(): String = soapEnvelope("Stop", "")

    fun seek(positionMs: Long): String = soapEnvelope(
        "Seek",
        "<Unit>REL_TIME</Unit><Target>${formatDuration(positionMs)}</Target>"
    )

    fun soapEnvelope(action: String, body: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"$SOAP_ENVELOPE_NS\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT_SERVICE\">")
        append("<InstanceID>0</InstanceID>")
        append(body)
        append("</u:$action></s:Body></s:Envelope>")
    }

    /**
     * DIDL-Lite 元数据：标题 + 类型 + 资源地址。大多数电视只认真实地址，元数据是加分项。
     * 地址用电视实际要拉取的 [mediaUrl]（通常是手机代理地址），类型从原始 [sourceUrl] 推断。
     */
    fun metadata(title: String, mediaUrl: String, sourceUrl: String, kind: MediaKind): String {
        val upnpClass = if (kind == MediaKind.AUDIO) "object.item.audioItem" else "object.item.videoItem"
        return buildString {
            append("<DIDL-Lite xmlns=\"$DIDL_NS\" xmlns:dc=\"$DC_NS\" xmlns:upnp=\"$UPNP_NS\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>${escapeXml(title.ifBlank { "视频" })}</dc:title>")
            append("<upnp:class>$upnpClass</upnp:class>")
            append("<res protocolInfo=\"${protocolInfo(sourceUrl, kind)}\">${escapeXml(mediaUrl)}</res>")
            append("</item></DIDL-Lite>")
        }
    }

    /** DLNA 的 protocolInfo 形如 `http-get:*:video/mp4:*`。 */
    fun protocolInfo(url: String, kind: MediaKind): String {
        val mime = when (pathExtension(url)) {
            "m3u8", "m3u" -> "application/vnd.apple.mpegurl"
            "mp4", "m4v" -> "video/mp4"
            "ts" -> "video/mp2t"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            else -> if (kind == MediaKind.AUDIO) "audio/mpeg" else "video/mp4"
        }
        return "http-get:*:$mime:*"
    }

    /** AVTransport 的 REL_TIME 格式：HH:MM:SS。 */
    fun formatDuration(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000
        return "%02d:%02d:%02d".format(
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60
        )
    }

    /** 只有 http(s) 地址能交给电视自己拉取；离线缓存不走这条路。 */
    fun isCastable(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    fun pathExtension(url: String): String = url
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()

    fun resolveUrl(baseUrl: String, reference: String): String? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val ref = URI(trimmed)
            if (ref.isAbsolute) ref.toString() else URI(baseUrl).resolve(ref).toString()
        }.getOrNull()
    }

    fun escapeXml(value: String): String = buildString(value.length + 16) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> = buildMap {
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
        }
    }

    private fun org.w3c.dom.Document.textOfFirst(name: String): String =
        getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()

    private fun Element.textOfFirst(name: String): String =
        getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()
}
