package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacCmsParserTest {

    private fun macCmsResponse(vararg items: String): String =
        """{"code":1,"msg":"数据列表","page":1,"total":${items.size},"list":[${items.joinToString(",")}]}"""

    @Test
    fun parsesDetailResponseWithAllFields() {
        val item = """
            {"vod_id":1,"vod_name":"流浪地球2","vod_pic":"http://x/p.jpg","vod_year":"2023",
             "vod_area":"大陆","vod_remarks":"HD","vod_play_from":"正片",
             "vod_play_url":"正片${'$'}https://x/a.m3u8"}
        """.trimIndent()

        val list = MacCmsParser.parseSearchResponse(macCmsResponse(item))

        assertEquals(1, list.size)
        val vod = list[0]
        assertEquals("流浪地球2", vod.vodName)
        assertEquals("http://x/p.jpg", vod.vodPic)
        assertEquals("2023", vod.vodYear)
        assertEquals("大陆", vod.vodArea)
        assertEquals("HD", vod.vodRemarks)
        assertEquals(1, vod.playGroups.size)
        assertEquals("正片", vod.playGroups[0].label)
        assertEquals("https://x/a.m3u8", vod.playGroups[0].lines[0].url)
        assertEquals("正片", vod.playGroups[0].lines[0].name)
    }

    @Test
    fun 简介优先取blurb缺省回退content() {
        val withBlurb = """
            {"vod_id":1,"vod_name":"流浪地球2","vod_blurb":"太阳即将毁灭，人类启动流浪地球计划。",
             "vod_content":"<p>全文简介不该被用到</p>","vod_play_from":"正片",
             "vod_play_url":"正片${'$'}https://x/a.m3u8"}
        """.trimIndent()
        val onlyContent = """
            {"vod_id":2,"vod_name":"第二十条","vod_content":"<p>检察官韩明遭遇&nbsp;一连串棘手案件。</p>",
             "vod_play_from":"正片","vod_play_url":"正片${'$'}https://x/b.m3u8"}
        """.trimIndent()

        val list = MacCmsParser.parseSearchResponse(macCmsResponse(withBlurb, onlyContent))
        assertEquals("太阳即将毁灭，人类启动流浪地球计划。", list[0].blurb)
        // 没有 vod_blurb 时回退 vod_content，且去标签、解实体。
        assertEquals("检察官韩明遭遇 一连串棘手案件。", list[1].blurb)
    }

    @Test
    fun parsesMultiplePlayFromGroupsAndEpisodes() {
        val item = """
            {"vod_name":"某剧","vod_play_from":"极速${'$'}${'$'}${'$'}备用",
             "vod_play_url":"第01集${'$'}https://x/1.m3u8#第02集${'$'}https://x/2.m3u8${'$'}${'$'}${'$'}第01集${'$'}https://y/1.m3u8"}
        """.trimIndent()

        val vod = MacCmsParser.parseSearchResponse(macCmsResponse(item)).single()

        assertEquals(listOf("极速", "备用"), vod.playFromLabels)
        assertEquals(2, vod.playGroups.size)
        assertEquals(2, vod.playGroups[0].lines.size)
        assertEquals("第01集", vod.playGroups[0].lines[0].name)
        assertEquals("https://x/1.m3u8", vod.playGroups[0].lines[0].url)
        assertEquals("第02集", vod.playGroups[0].lines[1].name)
        assertEquals("https://y/1.m3u8", vod.playGroups[1].lines[0].url)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val item = """{"vod_name":"只有名字"}"""

        val vod = MacCmsParser.parseSearchResponse(macCmsResponse(item)).single()

        assertEquals("只有名字", vod.vodName)
        assertEquals("", vod.vodPic)
        assertEquals("", vod.vodYear)
        assertEquals("", vod.vodArea)
        assertEquals("", vod.vodRemarks)
        assertTrue(vod.playGroups.isEmpty())
    }

    @Test
    fun emptyListReturnsEmpty() {
        assertTrue(MacCmsParser.parseSearchResponse(macCmsResponse()).isEmpty())
        assertTrue(MacCmsParser.parseSearchResponse("""{"code":0,"msg":"无数据"}""").isEmpty())
    }

    @Test
    fun nonJsonReturnsEmpty() {
        assertTrue(MacCmsParser.parseSearchResponse("").isEmpty())
        assertTrue(MacCmsParser.parseSearchResponse("    ").isEmpty())
        assertTrue(MacCmsParser.parseSearchResponse("<html>不是 JSON</html>").isEmpty())
        assertTrue(MacCmsParser.parseSearchResponse("hello world").isEmpty())
    }

    @Test
    fun topLevelArrayAlsoParsed() {
        val text = """[{"vod_name":"甲"},{"vod_name":"乙"}]"""
        val list = MacCmsParser.parseSearchResponse(text)
        assertEquals(2, list.size)
        assertEquals("甲", list[0].vodName)
        assertEquals("乙", list[1].vodName)
    }

    @Test
    fun malformedPlayUrlSkipsBadSegmentsKeepsGood() {
        val item = """
            {"vod_name":"容错","vod_play_from":"正片",
             "vod_play_url":"正片${'$'}https://x/a.m3u8#坏段#第3集${'$'}https://x/c.m3u8#rtmp${'$'}rtmp://bad/1"}
        """.trimIndent()

        val vod = MacCmsParser.parseSearchResponse(macCmsResponse(item)).single()

        // 坏段（空）与 rtmp 非 http 流都被丢弃，只留两条合法 http 流。
        assertEquals(2, vod.allLines.size)
        assertEquals("https://x/a.m3u8", vod.allLines[0].url)
        assertEquals("https://x/c.m3u8", vod.allLines[1].url)
    }

    @Test
    fun bareUrlLineWithoutNameGetsFallbackName() {
        val item = """{"vod_name":"裸地址","vod_play_url":"https://x/a.m3u8#https://x/b.m3u8"}"""

        val vod = MacCmsParser.parseSearchResponse(macCmsResponse(item)).single()

        assertEquals(2, vod.allLines.size)
        assertEquals("第1集", vod.allLines[0].name)
        assertEquals("第2集", vod.allLines[1].name)
    }

    @Test
    fun missingPlayFromLabelFallsBackToLineNumber() {
        val item = """{"vod_name":"无线路名","vod_play_url":"https://x/a.m3u8"}"""

        val vod = MacCmsParser.parseSearchResponse(macCmsResponse(item)).single()

        assertEquals(1, vod.playGroups.size)
        assertEquals("线路1", vod.playGroups[0].label)
    }

    @Test
    fun looksLikeValidResponseRecognizesGoodJson() {
        assertTrue(MacCmsParser.looksLikeValidResponse(macCmsResponse()))
        assertTrue(MacCmsParser.looksLikeValidResponse("""[{"vod_name":"x"}]"""))
        assertFalse(MacCmsParser.looksLikeValidResponse(""))
        assertFalse(MacCmsParser.looksLikeValidResponse("not json"))
        assertFalse(MacCmsParser.looksLikeValidResponse("""{"code":0}"""))
    }
}
