package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频源直链识别与下载类型分流单测：扩展名判定、页面里 `<audio src>` 与引号直链提取、
 * HLS 分流。不联网。
 */
class AudioDirectLinkTest {

    @Test
    fun recognizesAudioExtensions() {
        assertTrue(AudioDirectLink.isAudioLink("https://x.com/a.mp3"))
        assertTrue(AudioDirectLink.isAudioLink("https://x.com/b.m4a?token=1"))
        assertTrue(AudioDirectLink.isAudioLink("https://x.com/c.FLAC"))
        assertFalse(AudioDirectLink.isAudioLink("https://x.com/a.m3u8"))
        assertFalse(AudioDirectLink.isAudioLink("https://x.com/a.mp4"))
        assertNull(AudioDirectLink.audioExtension("https://x.com/a.mp4"))
        assertEquals("m4a", AudioDirectLink.audioExtension("https://x.com/a.m4a?t=1"))
    }

    @Test
    fun findsAudioLinksInHtml() {
        val html = """
            <html><body>
              <audio controls src="/music/song1.mp3"></audio>
              <a href="https://cdn.example.com/song2.flac">下载</a>
              <img src="/cover.jpg">
            </body></html>
        """.trimIndent()
        val links = AudioDirectLink.findAudioLinks(html)
        assertTrue(links.contains("/music/song1.mp3"))
        assertTrue(links.contains("https://cdn.example.com/song2.flac"))
        assertFalse(links.any { it.endsWith(".jpg") })
    }

    @Test
    fun routesHlsVsDirectDownload() {
        assertTrue(AudioDirectLink.isHls("https://x.com/v/index.m3u8"))
        assertTrue(AudioDirectLink.isHls("https://x.com/v/index.m3u8?token=1"))
        assertFalse(AudioDirectLink.isHls("https://x.com/a.mp3"))
        assertFalse(AudioDirectLink.isHls("https://x.com/a.m4a"))
    }

    @Test
    fun resolvesRelativeAgainstPage() {
        assertEquals(
            "https://x.com/music/song1.mp3",
            AudioDirectLink.resolve("https://x.com/page.html", "/music/song1.mp3")
        )
        assertEquals("https://x.com/a.mp3", AudioDirectLink.resolve("https://x.com", "https://x.com/a.mp3"))
    }
}
