package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机侧投屏代理的 URL 与 HLS 重写规则。
 *
 * 关键约束：
 * - 电视只能访问手机代理，播放列表里的每个分片都要换成代理地址；
 * - 令牌不对或不是 http(s) 的地址不能被代理借用；
 * - 注释行、data: 资源原样保留。
 */
class CastProxyProtocolTest {

    private val host = "192.168.1.5"
    private val port = 45678
    private val token = "abc123"

    @Test
    fun 代理地址可以还原出原始地址() {
        val original = "https://cdn.example.com/live/index.m3u8?sign=a&t=1"
        val proxied = CastProxyProtocol.proxyUrl(host, port, token, original)
        assertTrue(proxied.startsWith("http://$host:$port${CastProxyProtocol.STREAM_PATH}?"))
        assertEquals(
            original,
            CastProxyProtocol.parseRequestTarget(proxied.substringAfter("http://$host:$port"), token)
        )
    }

    @Test
    fun 令牌错误或路径不对拒绝代理() {
        val target = "/stream?t=wrong&u=https%3A%2F%2Fcdn.example.com%2Fa.m3u8"
        assertNull(CastProxyProtocol.parseRequestTarget(target, token))
        assertNull(CastProxyProtocol.parseRequestTarget("/other?t=$token&u=https%3A%2F%2Fa.m3u8", token))
        assertNull(CastProxyProtocol.parseRequestTarget("/stream?t=$token", token))
        // 非 http(s) 的地址（本地文件 / content）不代理。
        assertNull(
            CastProxyProtocol.parseRequestTarget(
                "/stream?t=$token&u=${java.net.URLEncoder.encode("file:///sdcard/a.mp4", "UTF-8")}", token
            )
        )
    }

    @Test
    fun 识别HLS按后缀或ContentType() {
        assertTrue(CastProxyProtocol.isPlaylist("https://x/a.m3u8", null))
        assertTrue(CastProxyProtocol.isPlaylist("https://x/a.M3U", "text/plain"))
        assertTrue(CastProxyProtocol.isPlaylist("https://x/play", "application/vnd.apple.mpegurl"))
        assertTrue(CastProxyProtocol.isPlaylist("https://x/play", "application/x-mpegURL; charset=utf-8"))
        assertFalse(CastProxyProtocol.isPlaylist("https://x/a.mp4", "video/mp4"))
        assertFalse(CastProxyProtocol.isPlaylist("https://x/a.mp4", null))
    }

    @Test
    fun 播放列表的分片与子列表都改写成代理地址() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
            https://other.example.com/high/index.m3u8
        """.trimIndent()
        val rewritten = CastProxyProtocol.rewritePlaylist(
            playlist, "https://cdn.example.com/vod/master.m3u8", host, port, token
        )
        val lines = rewritten.lines()
        assertEquals("#EXTM3U", lines[0])
        assertEquals("#EXT-X-VERSION:3", lines[1])
        assertEquals("#EXT-X-STREAM-INF:BANDWIDTH=800000", lines[2])
        assertEquals(
            "https://cdn.example.com/vod/low/index.m3u8",
            CastProxyProtocol.parseRequestTarget(lines[3].substringAfter("http://$host:$port"), token)
        )
        assertEquals(
            "https://other.example.com/high/index.m3u8",
            CastProxyProtocol.parseRequestTarget(lines[4].substringAfter("http://$host:$port"), token)
        )
    }

    @Test
    fun 带URI属性的标签只替换属性值() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1234
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg-1.ts
        """.trimIndent()
        val rewritten = CastProxyProtocol.rewritePlaylist(
            playlist, "https://cdn.example.com/vod/index.m3u8", host, port, token
        )
        val keyLine = rewritten.lines()[1]
        assertTrue(keyLine.startsWith("#EXT-X-KEY:METHOD=AES-128,URI=\""))
        assertTrue(keyLine.contains(",IV=0x1234"))
        assertEquals(
            "https://cdn.example.com/vod/key.bin",
            CastProxyProtocol.parseRequestTarget(
                keyLine.substringAfter("URI=\"").substringBefore("\"").substringAfter("http://$host:$port"),
                token
            )
        )
        assertTrue(rewritten.lines()[2].startsWith("#EXT-X-MAP:URI=\""))
        assertTrue(rewritten.lines()[4].startsWith("http://$host:$port"))
    }

    @Test
    fun 空行和注释原样保留() {
        val playlist = "#EXTM3U\n\n# comment\nseg.ts"
        val rewritten = CastProxyProtocol.rewritePlaylist(
            playlist, "https://cdn.example.com/a/index.m3u8", host, port, token
        )
        val lines = rewritten.lines()
        assertEquals("#EXTM3U", lines[0])
        assertEquals("", lines[1])
        assertEquals("# comment", lines[2])
        assertTrue(lines[3].startsWith("http://$host:$port"))
    }

    @Test
    fun 相对地址按播放列表最终地址解析() {
        assertEquals(
            "https://cdn.example.com/vod/seg.ts",
            CastProxyProtocol.resolveUrl("https://cdn.example.com/vod/index.m3u8", "seg.ts")
        )
        assertEquals(
            "https://other.example.com/seg.ts",
            CastProxyProtocol.resolveUrl("https://cdn.example.com/vod/index.m3u8", "https://other.example.com/seg.ts")
        )
        // data: 资源不是网络请求，原样保留。
        assertEquals(
            "data:text/plain;base64,AAAA",
            CastProxyProtocol.resolveUrl("https://x/a.m3u8", "data:text/plain;base64,AAAA")
        )
        assertNull(CastProxyProtocol.resolveUrl("https://x/a.m3u8", "   "))
    }

    @Test
    fun 上游缺ContentType时按后缀兜底() {
        assertEquals("video/mp4", CastProxyProtocol.contentTypeFor("https://x/a.mp4?t=1"))
        assertEquals("video/mp2t", CastProxyProtocol.contentTypeFor("https://x/a.ts"))
        assertEquals("application/vnd.apple.mpegurl", CastProxyProtocol.contentTypeFor("https://x/a.m3u8"))
        assertEquals("application/octet-stream", CastProxyProtocol.contentTypeFor("https://x/a.bin"))
    }
}
