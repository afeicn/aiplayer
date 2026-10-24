package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun normalChannelListParsesNameGroupLogoAndLive() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="http://x/cctv1.png" group-title="央视",CCTV-1 综合
            http://example.com/live/cctv1.m3u8
            #EXTINF:-1 tvg-id="hunan" tvg-name="HNTV" group-title="卫视",湖南卫视
            http://example.com/live/hunan.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(text)

        assertEquals(2, channels.size)
        val first = channels[0]
        assertEquals("CCTV-1 综合", first.name)
        assertEquals("CCTV1", first.tvgName)
        assertEquals("央视", first.group)
        assertEquals("http://x/cctv1.png", first.logo)
        assertEquals("http://example.com/live/cctv1.m3u8", first.url)
        assertTrue(first.isLive)
        assertEquals("湖南卫视", channels[1].name)
        assertTrue(channels[1].isLive)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val text = """
            #EXTM3U
            #EXTINF:-1,只有标题
            http://example.com/only-title.m3u8
            #EXTINF:-1 tvg-name="只有tvg名"
            http://example.com/tvg-only.m3u8
            #EXTINF:-1
            http://example.com/empty-inf.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(text)

        assertEquals(3, channels.size)
        assertEquals("只有标题", channels[0].name)
        assertEquals("", channels[0].group)
        assertEquals("只有tvg名", channels[1].name)
        assertEquals("empty-inf.m3u8", channels[2].name)
        assertTrue(channels[2].url.endsWith("empty-inf.m3u8"))
    }

    @Test
    fun positiveDurationIsNotLive() {
        val text = """
            #EXTM3U
            #EXTINF:5400 tvg-name="电影" group-title="点播",一部电影
            https://example.com/movie.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(text).single()

        assertEquals("一部电影", channel.name)
        assertFalse(channel.isLive)
    }

    @Test
    fun bareUrlWithoutExtinfStillBecomesChannel() {
        val channels = M3uParser.parse("http://example.com/live/raw.m3u8")

        assertEquals(1, channels.size)
        assertEquals("raw.m3u8", channels[0].name)
        assertTrue(channels[0].isLive)
    }

    @Test
    fun malformedExtinfWithoutFollowingUrlIsSkipped() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="断了" ,这个没有 URL
            #EXTM3U
            #EXTINF:-1,有URL的
            http://example.com/ok.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(text)

        assertEquals(1, channels.size)
        assertEquals("有URL的", channels[0].name)
    }

    @Test
    fun attributeValueWithSpaceInsideQuotesStaysWhole() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="央视 综合",CCTV
            http://example.com/cctv.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(text).single()

        assertEquals("央视 综合", channel.group)
    }

    @Test
    fun garbageInputYieldsNoChannels() {
        assertTrue(M3uParser.parse("").isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U\n#EXTINF:-1\n\n\n").isEmpty())
        assertTrue(M3uParser.parse("just some random text\nno urls here").isEmpty())
    }

    @Test
    fun hlsMediaPlaylistIsNotAChannelList() {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            segment0.ts
            #EXTINF:6.0,
            segment1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertFalse(M3uParser.looksLikeChannelList(mediaPlaylist))
    }

    @Test
    fun channelListIsRecognized() {
        val channelList = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV1",CCTV-1
            http://example.com/live/cctv1.m3u8
        """.trimIndent()

        assertTrue(M3uParser.looksLikeChannelList(channelList))
    }
}
