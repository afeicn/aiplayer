package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
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
}
