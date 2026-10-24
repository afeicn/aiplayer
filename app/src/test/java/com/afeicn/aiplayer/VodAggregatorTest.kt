package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodAggregatorTest {

    private fun source(id: String, name: String = id, referer: String = "", userAgent: String = "") =
        OnlineVideoSource(id = id, name = name, base = "http://$id.example.com", referer = referer, userAgent = userAgent)

    private fun hit(source: OnlineVideoSource, vodName: String, year: String = "", remarks: String = "") =
        SourceHit(source, VodItem(vodName = vodName, vodYear = year, vodRemarks = remarks))

    @Test
    fun sameNameMergesAcrossSources() {
        val a = source("a", "源A")
        val b = source("b", "源B")
        val hits = listOf(hit(a, "流浪地球2"), hit(b, "流浪地球2"))

        val result = VodAggregator.aggregate(hits)

        assertEquals(1, result.size)
        assertEquals("流浪地球2", result[0].name)
        assertEquals(2, result[0].sourceCount)
    }

    @Test
    fun differentNamesStaySeparate() {
        val a = source("a")
        val result = VodAggregator.aggregate(listOf(hit(a, "流浪地球2"), hit(a, "满江红")))

        assertEquals(2, result.size)
    }

    @Test
    fun nameMergeIsCaseInsensitiveAndTrimmed() {
        val a = source("a")
        val b = source("b")
        val result = VodAggregator.aggregate(listOf(hit(a, "Avatar"), hit(b, "  avatar  ")))

        assertEquals(1, result.size)
        assertEquals("Avatar", result[0].name)
        assertEquals(2, result[0].sourceCount)
    }

    @Test
    fun sortsBySourceCountDescThenName() {
        val a = source("a")
        val b = source("b")
        val c = source("c")
        val hits = listOf(
            hit(a, "甲"), hit(b, "甲"),      // 甲：2 个源
            hit(c, "乙"),                     // 乙：1 个源
            hit(a, "丙"), hit(b, "丙"), hit(c, "丙") // 丙：3 个源
        )

        val names = VodAggregator.aggregate(hits).map { it.name }

        assertEquals(listOf("丙", "甲", "乙"), names)
    }

    @Test
    fun playableLinesFlattenSourcesGroupsAndEpisodes() {
        val a = source("a")
        val itemA = VodItem(
            vodName = "某剧",
            playGroups = listOf(
                VodPlayGroup("正片", listOf(VodPlayLine("第1集", "https://x/1.m3u8"), VodPlayLine("第2集", "https://x/2.m3u8"))),
                VodPlayGroup("备用", listOf(VodPlayLine("第1集", "https://x/alt.m3u8")))
            )
        )
        val b = source("b")
        val itemB = VodItem(vodName = "某剧", playGroups = listOf(VodPlayGroup("正片", listOf(VodPlayLine("第1集", "https://y/1.m3u8")))))
        val vod = AggregatedVod("某剧", "", "", "", "", listOf(SourceHit(a, itemA), SourceHit(b, itemB)))

        val lines = vod.playableLines()

        assertEquals(4, lines.size)
        assertEquals("a", lines[0].source.id)
        assertEquals("正片", lines[0].groupLabel)
        assertEquals(0, lines[0].lineIndex)
        assertEquals("备用", lines[2].groupLabel)
        assertEquals("b", lines[3].source.id)
    }

    @Test
    fun playAddressExtractionBuildsStreamWithSourceHeaders() {
        val source = source("a", "源A", referer = "https://a.example.com/", userAgent = "观己 Player")
        val line = VodPlayLine("正片", "https://x/a.m3u8")

        val stream = onlineStream(source, line, "某片")

        assertEquals("https://x/a.m3u8", stream.url)
        assertEquals("某片", stream.title)
        assertEquals("https://a.example.com/", stream.headers["Referer"])
        assertEquals("观己 Player", stream.headers["User-Agent"])
        assertTrue(stream.isHls)
    }
}
