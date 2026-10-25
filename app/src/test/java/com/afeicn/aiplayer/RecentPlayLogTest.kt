package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 最近播放列表：新记录置顶、同名去重、封顶截断、行式编解码往返。 */
class RecentPlayLogTest {

    private fun play(title: String, time: Long, url: String = "") = RecentPlay(title, "http://c/$title", time, url)

    @Test
    fun mergePutsNewestFirstAndDropsSameTitleOlder() {
        val old = listOf(play("三体", 100), play("庆余年", 200))
        val merged = RecentPlayLog.merge(old, play("三体", 300, "http://p/1"))
        assertEquals(listOf("三体", "庆余年"), merged.map { it.title })
        assertEquals(300L, merged.first().timeMillis)
        assertEquals("http://p/1", merged.first().playUrl)
    }

    @Test
    fun mergeDedupesByCleanedTitle() {
        // 清洗后同名（空格/大小写差异）视为同一部剧。
        val merged = RecentPlayLog.merge(listOf(play("三体 ", 100)), play("三体", 200))
        assertEquals(1, merged.size)
    }

    @Test
    fun reViewKeepsEarlierPlayUrl() {
        // 播过之后再进详情页（浏览记录不带地址）：只更新时间置顶，续播地址不能丢。
        val played = listOf(play("三体", 100, "http://p/1"))
        val merged = RecentPlayLog.merge(played, play("三体", 300))
        assertEquals(1, merged.size)
        assertEquals(300L, merged.first().timeMillis)
        assertEquals("http://p/1", merged.first().playUrl)
    }

    @Test
    fun emptyCoverKeepsEarlierCover() {
        // 部分源搜索结果没海报：新记录 cover 为空时保留旧海报，不越记越空。
        val withCover = listOf(RecentPlay("我不是大师", "http://c/poster.jpg", 100, ""))
        val merged = RecentPlayLog.merge(withCover, RecentPlay("我不是大师", "", 300, "http://p/1"))
        assertEquals(1, merged.size)
        assertEquals("http://c/poster.jpg", merged.first().cover)
        assertEquals("http://p/1", merged.first().playUrl)
        // 新记录带海报则正常覆盖。
        val replaced = RecentPlayLog.merge(withCover, RecentPlay("我不是大师", "http://c/new.jpg", 300))
        assertEquals("http://c/new.jpg", replaced.first().cover)
    }

    @Test
    fun mergeCapsAtMaxAndIgnoresBlankTitle() {
        val seed = (1..15).map { play("剧$it", it.toLong()) }
        var list = emptyList<RecentPlay>()
        seed.forEach { list = RecentPlayLog.merge(list, it) }
        assertEquals(RecentPlayLog.MAX, list.size)
        assertEquals("剧15", list.first().title)
        // 空标题不产生记录。
        assertEquals(list.size, RecentPlayLog.merge(list, play("", 999)).size)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val entries = listOf(
            play("三体", 100, "http://p/1.html"),
            RecentPlay("庆余年|第二季", "http://c/a", 200, "")
        )
        val decoded = RecentPlayLog.decode(RecentPlayLog.encode(entries))
        assertEquals(2, decoded.size)
        assertEquals("三体", decoded[0].title)
        assertEquals("http://p/1.html", decoded[0].playUrl)
        // 字段里的 | 被换掉，不会撕裂行格式。
        assertEquals("庆余年 第二季", decoded[1].title)
        assertEquals("", decoded[1].playUrl)
    }

    @Test
    fun decodeSkipsGarbageLines() {
        val decoded = RecentPlayLog.decode("\n坏行\n三体|cover|abc\n")
        assertEquals(1, decoded.size)
        assertEquals(0L, decoded.first().timeMillis)
        assertTrue(RecentPlayLog.decode("").isEmpty())
    }

    // ==================== detailUrl：老记录兼容 + 直达详情页 ====================

    @Test
    fun decode_老记录没有detailUrl段也兼容() {
        // 升级前的行只有 4 段（title|cover|time|playUrl）：detailUrl 留空，其余字段照常。
        val decoded = RecentPlayLog.decode("三体|http://c/s.jpg|100|http://p/1.html")
        assertEquals(1, decoded.size)
        assertEquals("三体", decoded.first().title)
        assertEquals("http://p/1.html", decoded.first().playUrl)
        assertEquals("", decoded.first().detailUrl)
    }

    @Test
    fun encodeDecodeKeepsDetailUrl() {
        val entry = RecentPlay("我不是大师", "http://c/a.jpg", 100, "http://p/1.html", "https://v.x.com/detail/9.html")
        val decoded = RecentPlayLog.decode(RecentPlayLog.encode(listOf(entry)))
        assertEquals("https://v.x.com/detail/9.html", decoded.single().detailUrl)
    }

    @Test
    fun reViewKeepsEarlierDetailUrl() {
        // 起播记过详情页地址后，再进详情页（浏览不带地址）不能把它冲掉。
        val played = listOf(RecentPlay("三体", "http://c/s.jpg", 100, "http://p/1", "https://x/detail/1.html"))
        val merged = RecentPlayLog.merge(played, RecentPlay("三体", "http://c/s.jpg", 300))
        assertEquals(1, merged.size)
        assertEquals("https://x/detail/1.html", merged.first().detailUrl)
    }

    // ==================== 搜索结果匹配：带标记标题剥标记再比 ====================

    private fun card(name: String, detailUrl: String = "") = AggregatedVod(
        name, "", "", "", "",
        if (detailUrl.isEmpty()) emptyList()
        else listOf(SourceHit(OnlineVideoSource("s", "测试源", "https://s.example.com"), VodItem(name), detailUrl))
    )

    @Test
    fun hasMatch_带标记标题按剥标记后的剧名认作品() {
        // 带标记的关键词在源上模糊命中无关作品：不算命中，得换下一个关键词继续搜。
        assertFalse(RecentPlayLog.hasMatch(listOf(card("庆余年")), "我不是大师[全集]"))
        // 源返回清洗后的剧名、记录标题带标记：剥标记后同名，算命中。
        assertTrue(RecentPlayLog.hasMatch(listOf(card("我不是大师")), "我不是大师[全集]"))
    }

    @Test
    fun matchCard_同名卡优先否则第一张兜底() {
        val cards = listOf(card("别的剧"), card("我不是大师"))
        assertEquals("我不是大师", RecentPlayLog.matchCard(cards, "我不是大师[全集]")?.name)
        assertEquals("别的剧", RecentPlayLog.matchCard(cards, "搜不到的剧")?.name)
        assertNull(RecentPlayLog.matchCard(emptyList(), "三体"))
    }

    @Test
    fun directVod_凭详情页地址构造详情卡跳过搜索() {
        val r = RecentPlay("我不是大师[全集]", "http://c/a.jpg", 100, "", "https://v.x.com/detail/9.html")
        val configured = listOf(OnlineVideoSource("x", "X站", "https://v.x.com/", referer = "https://v.x.com/"))
        val built = RecentPlayLog.directVod(r, configured)!!
        assertEquals("我不是大师[全集]", built.name)
        assertEquals(listOf("https://v.x.com/detail/9.html"), built.detailUrls)
        // 同来源的已配置源优先（请求头、referer 齐全）。
        assertEquals("x", built.hits.single().source.id)
        // 没有同来源已配置源：按 URL 来源构造轻量源。
        val fallback = RecentPlayLog.directVod(r, emptyList())!!
        assertEquals("https://v.x.com", fallback.hits.single().source.base)
        // 没有详情页地址、或地址不是可识别的 http(s) 来源：返回 null 交回搜索链路。
        assertNull(RecentPlayLog.directVod(r.copy(detailUrl = ""), configured))
        assertNull(RecentPlayLog.directVod(r.copy(detailUrl = "javascript:void(0)"), configured))
    }
}
