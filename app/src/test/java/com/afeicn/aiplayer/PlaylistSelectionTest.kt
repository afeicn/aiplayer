package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「粘贴网页链接自动解析」第二层兜底（隐藏 WebView + 请求拦截）的候选选择纯函数单测：
 * 哪个 URL 算播放列表 / 怎么排除广告 / 多个候选怎么选。不联网、不碰 WebView。
 */
class PlaylistSelectionTest {

    // ==================== 哪个 URL 算播放列表 ====================

    @Test
    fun hlsUrlsAreRecognizedByM3u8M3uMpegurl() {
        assertTrue(PlaylistSelection.isHlsUrl("https://cdn.com/live/index.m3u8"))
        assertTrue(PlaylistSelection.isHlsUrl("https://cdn.com/live/index.m3u8?sign=abc"))
        assertTrue(PlaylistSelection.isHlsUrl("https://cdn.com/live/list.m3u"))
        assertTrue(PlaylistSelection.isHlsUrl("https://cdn.com/stream?type=mpegurl"))
        assertTrue(PlaylistSelection.isHlsUrl("https://cdn.com/a/b.m3u8#frag"))
        assertFalse(PlaylistSelection.isHlsUrl("https://cdn.com/video.mp4"))
        assertFalse(PlaylistSelection.isHlsUrl("https://cdn.com/page.html"))
    }

    @Test
    fun masterPlaylistIsRecognizedByPathFeature() {
        assertTrue(PlaylistSelection.looksLikeMaster("https://cdn.com/2026/x/index.m3u8?sign=1"))
        assertTrue(PlaylistSelection.looksLikeMaster("https://cdn.com/master/playlist.m3u8"))
        assertTrue(PlaylistSelection.looksLikeMaster("https://cdn.com/playlist.m3u8"))
        assertTrue(PlaylistSelection.looksLikeMaster("https://cdn.com/live/master.m3u8"))
        // 普通分片 / 媒体清单不算主列表。
        assertFalse(PlaylistSelection.looksLikeMaster("https://cdn.com/2026/x/seg.m3u8"))
        assertFalse(PlaylistSelection.looksLikeMaster("https://cdn.com/hls/1000/0.m3u8"))
    }

    // ==================== 排除广告 ====================

    @Test
    fun adPathsAreFlagged() {
        assertTrue(PlaylistSelection.isAdPath("https://cdn.com/ad/video.m3u8"))
        assertTrue(PlaylistSelection.isAdPath("https://cdn.com/ads/pre-roll.m3u8"))
        assertTrue(PlaylistSelection.isAdPath("https://cdn.com/preroll.mp4"))
        assertTrue(PlaylistSelection.isAdPath("https://cdn.com/advert_720.m3u8"))
        assertTrue(PlaylistSelection.isAdPath("https://cdn.com/promo.m3u8"))
    }

    @Test
    fun nonAdPathsAreNotFlagged() {
        assertFalse(PlaylistSelection.isAdPath("https://cdn.com/video.m3u8"))
        assertFalse(PlaylistSelection.isAdPath("https://cdn.com/downloads/movie.m3u8"))
        assertFalse(PlaylistSelection.isAdPath("https://cdn.com/lead/in.m3u8"))
        assertFalse(PlaylistSelection.isAdPath("https://cdn.com/ladder/0.m3u8"))
    }

    @Test
    fun shortNonMasterListIsExcludedAsAd() {
        val short = PlaylistCandidate(url = "https://cdn.com/seg.m3u8", segmentCount = 1)
        assertTrue(PlaylistSelection.shouldExcludeAsAd(short))
        assertTrue(PlaylistSelection.shouldExcludeAsAd(short.copy(segmentCount = 2)))
        // 3 个条目就不再当短清单广告。
        assertFalse(PlaylistSelection.shouldExcludeAsAd(short.copy(segmentCount = 3)))
    }

    @Test
    fun masterPlaylistWithFewEntriesIsNotExcluded() {
        val master = PlaylistCandidate(url = "https://cdn.com/index.m3u8", segmentCount = 2)
        assertFalse(PlaylistSelection.shouldExcludeAsAd(master))
    }

    // ==================== 条目计数 / 媒体清单判定 ====================

    @Test
    fun entryCountSkipsCommentsAndBlankLines() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts

            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()
        assertEquals(2, PlaylistSelection.countEntries(body))
    }

    @Test
    fun mediaPlaylistIsDistinguishedFromMaster() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg.ts\n"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n1000/index.m3u8\n"
        assertTrue(PlaylistSelection.isMediaPlaylist(media))
        assertFalse(PlaylistSelection.isMediaPlaylist(master))
    }

    // ==================== 多候选选择策略 ====================

    @Test
    fun adCandidatesAreExcluded() {
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/ad/preroll.m3u8", segmentCount = 50, order = 2),
                PlaylistCandidate(url = "https://cdn.com/index.m3u8", segmentCount = 10, order = 1)
            )
        )
        assertEquals("https://cdn.com/index.m3u8", best?.url)
    }

    @Test
    fun masterPlaylistWinsOverMediaPlaylist() {
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/hls/0.m3u8", segmentCount = 80, order = 3),
                PlaylistCandidate(url = "https://cdn.com/index.m3u8", segmentCount = 4, order = 1)
            )
        )
        assertEquals("https://cdn.com/index.m3u8", best?.url)
    }

    @Test
    fun amongNonMasterMostSegmentsWins() {
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/a.m3u8", segmentCount = 5, order = 1),
                PlaylistCandidate(url = "https://cdn.com/b.m3u8", segmentCount = 30, order = 2)
            )
        )
        assertEquals("https://cdn.com/b.m3u8", best?.url)
    }

    @Test
    fun tieBreaksToLastTriggered() {
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/a.m3u8", segmentCount = 10, order = 1),
                PlaylistCandidate(url = "https://cdn.com/b.m3u8", segmentCount = 10, order = 4)
            )
        )
        assertEquals("https://cdn.com/b.m3u8", best?.url)
    }

    @Test
    fun shortListsAreSkippedInFavorOfRealContent() {
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/preroll.m3u8", segmentCount = 2, order = 2),
                PlaylistCandidate(url = "https://cdn.com/main.m3u8", segmentCount = 60, order = 1)
            )
        )
        assertEquals("https://cdn.com/main.m3u8", best?.url)
    }

    @Test
    fun whenAllCandidatesAreAdsReturnsNull() {
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/ad/1.m3u8", segmentCount = 50, order = 1),
                PlaylistCandidate(url = "https://cdn.com/preroll.m3u8", segmentCount = 2, order = 2)
            )
        )
        assertNull(best)
    }

    @Test
    fun emptyCandidateListReturnsNull() {
        assertNull(PlaylistSelection.selectBest(emptyList()))
    }

    @Test
    fun missingSegmentCountFallsBackToOrder() {
        // 没抓到分片数（null）时按 -1 处理 → 全靠 order（最后触发）决定。
        val best = PlaylistSelection.selectBest(
            listOf(
                PlaylistCandidate(url = "https://cdn.com/a.m3u8", order = 1),
                PlaylistCandidate(url = "https://cdn.com/b.m3u8", order = 2)
            )
        )
        assertEquals("https://cdn.com/b.m3u8", best?.url)
    }
}
