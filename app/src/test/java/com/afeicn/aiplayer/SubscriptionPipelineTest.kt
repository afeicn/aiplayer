package com.afeicn.aiplayer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「确认范围 → 逐集解析流 → 音频直链分流 → 离线项」闭环的纯逻辑单测。
 * 用假 [PageFetcher] 注入 empirecms 详情页 / 播放页 / iframe → m3u8，以及音频详情页，不联网。
 */
class SubscriptionPipelineTest {

    private val detailUrl = "https://cms.example.com/dianshiju/guoju/12320.html"

    private val detailHtml = """
        <div>
          <a href="/e/DownSys/play/?classid=6&id=12320&pathid1=0&bf=0">第01集</a>
          <a href="/e/DownSys/play/?classid=6&id=12320&pathid1=1&bf=0">第02集</a>
          <a href="/e/DownSys/play/?classid=6&id=12320&pathid1=2&bf=0">第03集</a>
        </div>
    """.trimIndent()

    private val playHtml = """<iframe src="https://player.example.com/play/Xe0Z2W3b"></iframe>"""
    private val iframeHtml = """<video src="https://player.example.com/play/Xe0Z2W3b/index.m3u8"></video>"""

    private class FakeFetcher(private val handler: (String) -> FetchedPage) : PageFetcher {
        val requested = mutableListOf<String>()
        override fun fetch(url: String, referer: String?): FetchedPage {
            requested += url
            return handler(url)
        }
    }

    private fun videoFetcher(): FakeFetcher = FakeFetcher { url ->
        val body = when {
            url == detailUrl -> detailHtml
            url.contains("/e/DownSys/play/") -> playHtml
            url.contains("player.example.com/play/") -> iframeHtml
            else -> "<html></html>"
        }
        FetchedPage(url = url, contentType = "text/html", body = body)
    }

    @Test
    fun resolvesSelectedEpisodesToOfflineItemsThroughIframeToM3u8() = runBlocking {
        val source = OnlineVideoSource(id = "empirecms", name = "6v电影", base = "https://cms.example.com")
        val items = SubscriptionPipeline.resolveItems("庆余年", source, detailUrl, listOf(1, 3), videoFetcher())
        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaKind.VIDEO })
        assertTrue(items.all { it.url.endsWith("index.m3u8") })
        assertTrue(items.all { it.headers["Referer"]?.contains("player.example.com") == true })
        assertEquals("第01集", items[0].episodeName)
        assertEquals("第03集", items[1].episodeName)
        assertEquals("6v电影", items[0].sourceName)
    }

    @Test
    fun audioSourceSplitsToDirectLinksNotHls() = runBlocking {
        val audioDetail = "https://audio.example.com/album.html"
        val fetcher = FakeFetcher { url ->
            FetchedPage(
                url = url,
                contentType = "text/html",
                body = if (url == audioDetail) {
                    """<audio src="/music/song1.mp3"></audio><audio src="/music/song2.m4a"></audio>"""
                } else "<html></html>"
            )
        }
        val source = OnlineVideoSource(
            id = "a", name = "音频站", base = "https://audio.example.com",
            type = SourceType.AUDIO
        )
        val items = SubscriptionPipeline.resolveItems("某专辑", source, audioDetail, listOf(1, 2), fetcher)
        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaKind.AUDIO })
        assertEquals("https://audio.example.com/music/song1.mp3", items[0].url)
        assertEquals("mp3", items[0].extension)
        assertEquals("m4a", items[1].extension)
    }

    @Test
    fun outOfRangeIndicesAreSkipped() = runBlocking {
        val source = OnlineVideoSource(id = "empirecms", name = "6v电影", base = "https://cms.example.com")
        val items = SubscriptionPipeline.resolveItems("庆余年", source, detailUrl, listOf(1, 99), videoFetcher())
        assertEquals(1, items.size)
        assertEquals("第01集", items[0].episodeName)
    }

    @Test
    fun blurbFromDetailPageMetaIsStoredOnOfflineItems() = runBlocking {
        val source = OnlineVideoSource(id = "empirecms", name = "6v电影", base = "https://cms.example.com")
        val fetcher = FakeFetcher { url ->
            val body = when {
                url == detailUrl -> """<meta name="description" content="青年范闲身世神秘，一路卷入庙堂江湖。">""" + detailHtml
                url.contains("/e/DownSys/play/") -> playHtml
                url.contains("player.example.com/play/") -> iframeHtml
                else -> "<html></html>"
            }
            FetchedPage(url = url, contentType = "text/html", body = body)
        }
        val items = SubscriptionPipeline.resolveItems("庆余年", source, detailUrl, listOf(1, 3), fetcher)
        assertEquals(2, items.size)
        assertTrue(items.all { it.blurb == "青年范闲身世神秘，一路卷入庙堂江湖。" })
    }
}
