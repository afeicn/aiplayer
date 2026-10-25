package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLS 媒体播放列表纯解析单测：主列表选变体、分片相对地址解析、总字节估算。
 * 用合成夹具，结构取自通用 MacCMS 风格：主列表 → 3000k/hls/mixed.m3u8 → 带 ?hash= 的 .ts 分片。
 */
class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=1920x960
        800k/hls/mixed.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3000000,RESOLUTION=1920x960
        3000k/hls/mixed.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        seg1.ts?hash=aaa
        #EXTINF:9.5,
        https://cdn.other.com/abs/seg2.ts?hash=bbb
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun 主列表识别与最高码率变体选择() {
        assertTrue(HlsPlaylist.isMaster(master))
        assertTrue(!HlsPlaylist.isMaster(media))
        val variant = HlsPlaylist.bestVariantUri(master, "https://vip.example.com/2026/a/index.m3u8?sign=x")
        assertEquals("https://vip.example.com/2026/a/3000k/hls/mixed.m3u8", variant)
    }

    @Test
    fun 分片地址解析保留查询参数与绝对地址() {
        val segs = HlsPlaylist.segmentUris(media, "https://vip.example.com/2026/a/3000k/hls/mixed.m3u8")
        assertEquals(2, segs.size)
        assertEquals("https://vip.example.com/2026/a/3000k/hls/seg1.ts?hash=aaa", segs[0])
        assertEquals("https://cdn.other.com/abs/seg2.ts?hash=bbb", segs[1])
    }

    @Test
    fun 分片时长与地址配对缺失EXTINF记零() {
        val segs = HlsPlaylist.segments(media, "https://vip.example.com/2026/a/3000k/hls/mixed.m3u8")
        assertEquals(2, segs.size)
        assertEquals("https://vip.example.com/2026/a/3000k/hls/seg1.ts?hash=aaa", segs[0].uri)
        assertEquals(10.0, segs[0].durationSec, 0.001)
        assertEquals(9.5, segs[1].durationSec, 0.001)
        // 没有 EXTINF 的分片时长记 0；EXT-X-ENDLIST 等注释不产生分片。
        val bare = HlsPlaylist.segments("#EXTM3U\n#EXT-X-ENDLIST\nseg.ts\n", "https://a.com/x.m3u8")
        assertEquals(1, bare.size)
        assertEquals(0.0, bare[0].durationSec, 0.001)
        // segmentUris 与 segments 保持同一解析结果。
        assertEquals(segs.map { it.uri }, HlsPlaylist.segmentUris(media, "https://vip.example.com/2026/a/3000k/hls/mixed.m3u8"))
    }

    @Test
    fun 总字节按EXTINF时长乘码率估算() {
        // (10.0 + 9.5) 秒 × 3_000_000 bps ÷ 8 = 7_312_500 字节
        assertEquals(7_312_500L, HlsPlaylist.estimateBytes(media, 3_000_000L))
        assertEquals(-1L, HlsPlaylist.estimateBytes(media, -1L))
        assertEquals(-1L, HlsPlaylist.estimateBytes("#EXTM3U\n", 3_000_000L))
    }

    @Test
    fun 变体码率提取与缺失兜底() {
        assertEquals(3000000L, HlsPlaylist.maxBandwidth(master))
        assertEquals(800000L, HlsPlaylist.variantBandwidth(master)) // 第一个 STREAM-INF
        assertEquals(-1L, HlsPlaylist.variantBandwidth(media))
        assertEquals(-1L, HlsPlaylist.maxBandwidth(media))
        assertNull(HlsPlaylist.bestVariantUri(media, "https://x.com/a.m3u8"))
    }

    @Test
    fun 相对解析支持上级目录与解析失败兜底() {
        assertEquals("https://a.com/b/up.ts", HlsPlaylist.resolveUri("https://a.com/b/c/x.m3u8", "../up.ts"))
        assertEquals("https://a.com/x.ts", HlsPlaylist.resolveUri("https://a.com/x.ts", "https://a.com/x.ts"))
        // 非法 base（含空格等）走目录拼接兜底：按最后一个 / 截断直接拼接。
        assertEquals("https://a.com b/y.ts", HlsPlaylist.resolveUri("https://a.com b/x.m3u8", "y.ts"))
    }
}
