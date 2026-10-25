package com.afeicn.aiplayer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 片名/集名清洗单测。
 *
 * 样本来自真机事故数据（`shared_prefs/offline_downloads.xml`，《我不是大师》1-5 集）：
 *   落库标题 = `我不是大师[全集]">我不是大师[全集]`
 *   根因 = 搜索页把高亮标签塞进 title 属性（`title="<font color='red'>我不是大师</font>[全集]"`），
 *          `<a>` 的属性扫描在属性值里的 `>` 处提前结束，属性尾巴被当成正文。
 *
 * 这里覆盖：真实脏标题、HTML 实体、标签、正常标题、含引号标题、属性尾巴、重复内容，
 * 以及清洗后的剧名能否正常参与「按剧名聚合」与「同一部剧判定」。
 */
class MediaTitleTest {

    /** 真机落库的真实脏标题（复制自 offline_downloads.xml）。 */
    private val realDirty = "我不是大师[全集]\">我不是大师[全集]"

    private val expected = "我不是大师[全集]"

    // ==================== A. 清洗本身 ====================

    @Test
    fun 真机脏标题只留一份() {
        assertEquals(expected, MediaTitle.clean(realDirty))
    }

    @Test
    fun 带HTML实体的脏标题同样清洗() {
        assertEquals(expected, MediaTitle.clean("我不是大师[全集]&quot;&gt;我不是大师[全集]"))
        assertEquals("我不是大师[全集]", MediaTitle.clean("我不是大师[全集]&#34;&#62;我不是大师[全集]"))
        assertEquals("我不是大师[全集]", MediaTitle.clean("我不是大师[全集]&#x22;&#x3e;我不是大师[全集]"))
    }

    @Test
    fun 带标签的标题取纯文本() {
        assertEquals(expected, MediaTitle.clean("<font color='red'>我不是大师</font>[全集]"))
        assertEquals(expected, MediaTitle.clean("<span class=\"hl\">我不是大师</span>[全集]"))
        // 高亮标签塞在 title 属性里（真实页面的写法）
        assertEquals(expected, MediaTitle.clean("<font color='red'>我不是大师</font>[全集]\">我不是大师[全集]"))
    }

    @Test
    fun 正常标题原样保留() {
        assertEquals("庆余年", MediaTitle.clean("庆余年"))
        assertEquals("流浪地球2", MediaTitle.clean("流浪地球2"))
        assertEquals("我不是大师[全集]", MediaTitle.clean("我不是大师[全集]"))
        assertEquals("狂飙 · 第 4 集", MediaTitle.clean("狂飙 · 第 4 集"))
        assertEquals("", MediaTitle.clean(""))
        assertEquals("", MediaTitle.clean("   "))
    }

    @Test
    fun 含引号的合法标题不误伤() {
        assertEquals("我不是\"大师\"", MediaTitle.clean("我不是\"大师\""))
        assertEquals("书名'测试'", MediaTitle.clean("书名'测试'"))
        // 实体引号在标题中间也保留
        assertEquals("我不是\"大师\"", MediaTitle.clean("我不是&quot;大师&quot;"))
    }

    @Test
    fun 属性尾巴与重复内容都去掉() {
        assertEquals(expected, MediaTitle.clean("$expected\" />"))
        assertEquals(expected, MediaTitle.clean("title=\"$expected\">$expected"))
        assertEquals(expected, MediaTitle.clean("$expected $expected"))
        assertEquals(expected, MediaTitle.clean("$expected$expected"))
    }

    @Test
    fun 首尾空白与连续空白归一() {
        assertEquals("我不是大师[全集]", MediaTitle.clean("  我不是大师[全集]  \n\t "))
        assertEquals("我不是大师 [全集]", MediaTitle.clean("我不是大师    [全集]"))
    }

    @Test
    fun 数值实体会还原成中文() {
        assertEquals("我不是大师", MediaTitle.clean("&#25105;&#19981;&#26159;大师"))
    }

    @Test
    fun 清洗是幂等的() {
        val once = MediaTitle.clean(realDirty)
        assertEquals(once, MediaTitle.clean(once))
        assertEquals(expected, once)
    }

    // ==================== B. 归一化与同一部剧判定 ====================

    @Test
    fun 归一化忽略空白与大小写() {
        assertEquals("我不是大师[全集]", MediaTitle.normalize(" 我不是大师 [全集] "))
        assertEquals("abc", MediaTitle.normalize(" A B C "))
        assertNotEquals(MediaTitle.normalize("狂飙"), MediaTitle.normalize("庆余年"))
    }

    @Test
    fun 脏标题与干净标题判定为同一部剧() {
        assertTrue(MediaTitle.same(realDirty, expected))
        assertTrue(MediaTitle.same(realDirty, " 我不是大师[全集] "))
        assertFalse(MediaTitle.same(realDirty, "庆余年"))
        // 空标题不与任何东西「相同」，避免把一堆未知标题聚成一部剧。
        assertFalse(MediaTitle.same("", ""))
    }

    @Test
    fun 剧名主干去掉全集等附加标记() {
        assertEquals("我不是大师", MediaTitle.core("我不是大师[全集]"))
        assertEquals("我不是大师", MediaTitle.core(realDirty))
        assertEquals("庆余年", MediaTitle.core("庆余年（第一季）"))
        assertEquals("庆余年", MediaTitle.core("庆余年【高清】"))
    }

    // ==================== C. 展示端 / 聚合端向后兼容 ====================

    private fun download(title: String, episodeName: String, id: String = title + episodeName) = OfflineDownload(
        item = OfflineItem(
            id = id,
            title = title,
            url = "https://cdn.example.com/$id.m3u8",
            episodeName = episodeName,
            sourceName = "https://cms.example.com/",
            totalEpisodes = 24
        ),
        state = DownloadTaskState.COMPLETED,
        downloadedBytes = 1024L,
        totalBytes = 2048L,
        percent = 100f,
        failure = OfflineFailureReason.NONE,
        completedAt = 2000L
    )

    @Test
    fun 历史脏标题展示时已清洗() {
        val episode = OfflineLibrary.toEpisode(download(realDirty, "第01集"))
        assertEquals(expected, episode.title)
        assertEquals("我不是大师-1", OfflineLibrary.displayTitle(episode))
        // 直接调 displayTitle（其它入口）也要清洗
        assertEquals("我不是大师-2", OfflineLibrary.displayTitle(realDirty, "第02集", 2, MediaKind.VIDEO))
    }

    @Test
    fun 历史脏标题能参与按剧名聚合() {
        // 老数据全是脏标题
        val legacy = (1..5).map { download(realDirty, "第0${it}集", id = "old$it") }
        // 新数据是干净标题
        val fresh = download(expected, "第06集", id = "new6")
        val groups = OfflineLibrary.groupByTitle(OfflineLibrary.episodes(legacy + fresh))
        assertEquals(1, groups.size)
        assertEquals(expected, groups[0].title)
        assertEquals(6, groups[0].downloadedCount)
    }

    @Test
    fun 脏标题与干净标题算同一部剧() {
        val legacy = download(realDirty, "第01集", id = "old1")
        val fresh = download(expected, "第02集", id = "new2")
        val downloads = listOf(legacy, fresh)
        assertTrue(OfflineLibrary.sameTitle(legacy.item.title, fresh.item.title))
        assertTrue(OfflineLibrary.isEpisodeDownloaded(downloads, expected, 1))
        assertEquals(setOf(1, 2), OfflineLibrary.downloadedEpisodeNumbers(downloads, expected))
    }

    @Test
    fun 脏标题落库前自动清洗() {
        val encoded = OfflineItemCodec.encode(
            listOf(
                OfflineItem(id = "d1", title = realDirty, url = "https://cdn.example.com/1.m3u8", episodeName = "第01集"),
                OfflineItem(id = "d2", title = "庆余年", url = "https://cdn.example.com/2.m3u8", episodeName = "第02集")
            )
        )
        val restored = OfflineItemCodec.decode(encoded)
        assertEquals(listOf(expected, "庆余年"), restored.map { it.title })
        // 再编码一次结果不变（幂等）
        assertEquals(encoded, OfflineItemCodec.encode(restored))
        // 旧数据（直接读出来的脏标题）本身不会被改写，展示端负责洗
        val legacyJson = JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("id", "old")
                put("title", realDirty)
                put("url", "https://cdn.example.com/old.m3u8")
            })
        }.toString()
        assertEquals(realDirty, OfflineItemCodec.decode(legacyJson).single().title)
    }

    @Test
    fun 搜索关键词候选逐级变短且去重() {
        // 真机场景：「庆余年 第二季全」整标题在站上搜不到，候选里要有能搜到的短词。
        val keys = MediaTitle.searchKeys("庆余年 第二季全")
        assertEquals("庆余年 第二季全", keys.first())
        assertTrue(keys.contains("庆余年 第二季"))
        assertTrue(keys.contains("庆余年"))
        // 候选去重、不含空串。
        assertEquals(keys.distinct(), keys)
        assertTrue(keys.none { it.isBlank() })
        // 括号标记与画质尾巴也进候选。
        val keys2 = MediaTitle.searchKeys("三体[全集] 1080P")
        assertTrue(keys2.contains("三体"))
        assertTrue(keys2.contains("三体[全集]"))
        // 空格后段也进候选：「斗罗大陆剧场版 剑道尘心」里「剑道尘心」常才是源上的主标题。
        val keys3 = MediaTitle.searchKeys("斗罗大陆剧场版 剑道尘心")
        assertTrue(keys3.contains("斗罗大陆剧场版"))
        assertTrue(keys3.contains("剑道尘心"))
        // 空标题没有候选。
        assertTrue(MediaTitle.searchKeys("  ").isEmpty())
    }
}
