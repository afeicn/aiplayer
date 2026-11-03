package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DLNA 协议拼装 / 解析：SSDP 搜索、设备描述、AVTransport SOAP 与 DIDL-Lite 元数据。
 * 全部是纯 JVM 逻辑，真机只负责验证电视兼容性。
 */
class DlnaProtocolTest {

    @Test
    fun 搜索报文包含组播地址与媒体渲染器类型() {
        val request = DlnaProtocol.searchRequest()
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun 解析SSDP响应且header大小写不敏感() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "location: http://192.168.1.20:49152/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:tv-1::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val response = DlnaProtocol.parseSsdpResponse(raw)!!
        assertEquals("http://192.168.1.20:49152/description.xml", response.location)
        assertEquals(DlnaProtocol.SEARCH_TARGET, response.searchTarget)
        assertTrue(response.usn.startsWith("uuid:tv-1"))
    }

    @Test
    fun 非200响应或缺少LOCATION直接丢弃() {
        assertNull(DlnaProtocol.parseSsdpResponse("HTTP/1.1 404 Not Found\r\nST: x\r\n\r\n"))
        assertNull(DlnaProtocol.parseSsdpResponse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
        assertNull(DlnaProtocol.parseSsdpResponse(""))
    }

    @Test
    fun 设备描述解析出AVTransport控制地址() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>客厅电视</friendlyName>
                <modelName>MiTV-4A</modelName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/rc/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>upnp/control/AVTransport1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val device = DlnaProtocol.parseDeviceDescription(
            "http://192.168.1.20:49152/description.xml", xml
        )!!
        assertEquals("客厅电视", device.friendlyName)
        assertEquals("MiTV-4A", device.modelName)
        // 相对 controlURL 要按描述文件地址补成绝对地址。
        assertEquals(
            "http://192.168.1.20:49152/upnp/control/AVTransport1",
            device.avTransportControlUrl
        )
    }

    @Test
    fun 没有AVTransport的设备不可投屏() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <friendlyName>只读设备</friendlyName>
            </device></root>
        """.trimIndent()
        assertNull(DlnaProtocol.parseDeviceDescription("http://192.168.1.9/desc.xml", xml))
        assertNull(DlnaProtocol.parseDeviceDescription("http://192.168.1.9/desc.xml", "not xml"))
    }

    @Test
    fun SetAVTransportURI报文携带地址与转义后的元数据() {
        val metadata = DlnaProtocol.metadata("第 1 集", "http://192.168.1.5:8000/s?t=a&u=b", "https://x/y.m3u8", MediaKind.VIDEO)
        val body = DlnaProtocol.setAvTransportUri("http://192.168.1.5:8000/s?t=a&u=b", metadata)
        assertTrue(body.contains("<u:SetAVTransportURI xmlns:u=\"${DlnaProtocol.AV_TRANSPORT_SERVICE}\">"))
        assertTrue(body.contains("<CurrentURI>http://192.168.1.5:8000/s?t=a&amp;u=b</CurrentURI>"))
        // 元数据是一段 XML，嵌在外层 XML 里必须整体转义。
        assertTrue(body.contains("&lt;DIDL-Lite"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
    }

    @Test
    fun 播放控制动作使用AVTransport服务名() {
        assertTrue(DlnaProtocol.play().contains("<u:Play xmlns:u=\"${DlnaProtocol.AV_TRANSPORT_SERVICE}\">"))
        assertTrue(DlnaProtocol.pause().contains("<u:Pause"))
        assertTrue(DlnaProtocol.stop().contains("<u:Stop"))
        val seek = DlnaProtocol.seek(3_723_000)
        assertTrue(seek.contains("<Unit>REL_TIME</Unit>"))
        assertTrue(seek.contains("<Target>01:02:03</Target>"))
    }

    @Test
    fun 元数据按媒体类型生成DIDL_Lite() {
        val video = DlnaProtocol.metadata("片子", "http://192.168.1.5:8000/s?u=x", "https://x/y.mp4", MediaKind.VIDEO)
        assertTrue(video.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(video.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
        assertTrue(video.contains("<dc:title>片子</dc:title>"))

        val audio = DlnaProtocol.metadata("歌", "http://h/s", "https://x/a.mp3", MediaKind.AUDIO)
        assertTrue(audio.contains("<upnp:class>object.item.audioItem</upnp:class>"))
        assertTrue(audio.contains("protocolInfo=\"http-get:*:audio/mpeg:*\""))
    }

    @Test
    fun 标题里的XML特殊字符会转义() {
        val metadata = DlnaProtocol.metadata("A&B <C>", "http://h/s", "http://h/a.mp4", MediaKind.VIDEO)
        assertTrue(metadata.contains("A&amp;B &lt;C&gt;"))
    }

    @Test
    fun 时长格式为HH_MM_SS() {
        assertEquals("00:00:00", DlnaProtocol.formatDuration(0))
        assertEquals("00:00:01", DlnaProtocol.formatDuration(1_999))
        assertEquals("01:02:03", DlnaProtocol.formatDuration(3_723_000))
        assertEquals("00:00:00", DlnaProtocol.formatDuration(-5))
    }

    @Test
    fun 只有http地址可以投屏() {
        assertTrue(DlnaProtocol.isCastable("http://x/a.mp4"))
        assertTrue(DlnaProtocol.isCastable("HTTPS://x/a.m3u8"))
        assertFalse(DlnaProtocol.isCastable("file:///data/user/0/a.mp4"))
        assertFalse(DlnaProtocol.isCastable("content://media/1"))
    }

    @Test
    fun 后缀识别忽略查询参数() {
        assertEquals("m3u8", DlnaProtocol.pathExtension("https://x/a/b/index.m3u8?token=1"))
        assertEquals("mp4", DlnaProtocol.pathExtension("https://x/a.MP4#t=1"))
        assertEquals("", DlnaProtocol.pathExtension("https://x/a/b"))
    }
}
