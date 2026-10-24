package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载条目命名规则单测（见 [EpisodeNaming]）。
 *
 * 约定：
 *  - 无季 → `剧名-集号`（集号整数不补零）：`我不是大师-1`
 *  - 有季 → `剧名S季-集号`（季从标题识别并剥掉）：`我不是大师S1-1`
 *  - 目录模式 → 只出 `集号` / `S季-集号`
 *  - 排序按「剧名 + 集号数值」，`-1, -2, -10` 不能乱序
 */
class EpisodeNamingTest {

    // ==================== A. 命名格式 ====================

    @Test
    fun 无季_剧名杠集号() {
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师", "第01集", 1))
        assertEquals("狂飙-4", EpisodeNaming.displayName("狂飙", "第4集", 4))
        assertEquals("庆余年-12", EpisodeNaming.displayName("庆余年", "", 12))
    }

    @Test
    fun 有季_季号进S标记且从剧名剥掉() {
        // 「第N季」
        assertEquals("我不是大师S1-1", EpisodeNaming.displayName("我不是大师 第一季", "第01集", 1))
        assertEquals("我不是大师S2-3", EpisodeNaming.displayName("我不是大师第2季", "第03集", 3))
        // 「S1」
        assertEquals("我不是大师S1-1", EpisodeNaming.displayName("我不是大师S1", "第01集", 1))
        assertEquals("我不是大师S2-3", EpisodeNaming.displayName("我不是大师 S02", "", 3))
        // 「Season 1」
        assertEquals("我不是大师S3-2", EpisodeNaming.displayName("我不是大师 Season 3", "第02集", 2))
        assertEquals("我不是大师S3-2", EpisodeNaming.displayName("我不是大师season3", "", 2))
        // 季在括号里：括号一并消失，不残留「（）」
        assertEquals("我不是大师S1-1", EpisodeNaming.displayName("我不是大师（S1）", "第01集", 1))
        assertEquals("我不是大师S1-1", EpisodeNaming.displayName("我不是大师【第一季】", "", 1))
        // 中文数字季
        assertEquals("我不是大师S12-5", EpisodeNaming.displayName("我不是大师第十二季", "", 5))
    }

    @Test
    fun 标题里没有季信息_不加S() {
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师", "", 1))
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师[全集]", "", 1))
        // 名字里的数字不是季（避免「流浪地球2」被当成 S2）
        assertEquals("流浪地球2-1", EpisodeNaming.displayName("流浪地球2", "", 1))
    }

    @Test
    fun 集号补零输入_输出整数不补零() {
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师", "第01集", 1))
        assertEquals("我不是大师-9", EpisodeNaming.displayName("我不是大师", "EP09", 9))
        assertEquals("我不是大师-10", EpisodeNaming.displayName("我不是大师", "第010集", 10))
    }

    @Test
    fun 集名为中文数字_能解析成阿拉伯数字() {
        assertEquals(11, EpisodeNaming.episodeNumber("第十一集"))
        assertEquals("我不是大师-11", EpisodeNaming.displayName("我不是大师", "第十一集", null))
        assertEquals("我不是大师-2", EpisodeNaming.displayName("我不是大师", "第2回", null))
    }

    @Test
    fun 集号优先取入参_入参缺失才从集名解析() {
        // 入参优先（即使集名里写的是别的号）
        assertEquals("我不是大师-7", EpisodeNaming.displayName("我不是大师", "第03集", 7))
        // 入参为 null → 从集名解析
        assertEquals("我不是大师-3", EpisodeNaming.displayName("我不是大师", "第03集", null))
        // 入参与集名都没有 → 退回原集名，不自造集号
        assertEquals("我不是大师-序章", EpisodeNaming.displayName("我不是大师", "序章", null))
        // 什么都没有 → 只剩剧名，绝不出空标题
        assertEquals("我不是大师", EpisodeNaming.displayName("我不是大师", "", null))
        assertEquals("未命名", EpisodeNaming.displayName("", "", null))
    }

    @Test
    fun 标题带状态标注_尾部标注被剥掉() {
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师[全集]", "第01集", 1))
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师【完结】", "", 1))
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师（更新至24集）", "", 1))
        assertEquals("我不是大师-1", EpisodeNaming.displayName("我不是大师 全集", "", 1))
        // 同一个标题叠了多种标注也能剥干净
        assertEquals("我不是大师S1-1", EpisodeNaming.displayName("我不是大师[全集]（第一季）[完结]", "", 1))
    }

    @Test
    fun 标题本身含数字_不被误当集号或季() {
        assertEquals("请回答1988-3", EpisodeNaming.displayName("请回答1988", "第03集", 3))
        assertEquals("1921-1", EpisodeNaming.displayName("1921", "", 1))
        // 作品名里的中文字不是季/集
        assertEquals("三体-2", EpisodeNaming.displayName("三体", "第02集", 2))
    }

    @Test
    fun 剧名整段是集名时不算剧名() {
        // 老数据把集名当剧名落库：不能拼成「第08集-8」
        assertEquals("第08集", EpisodeNaming.displayName("第08集", "", 8))
        assertEquals("8", EpisodeNaming.fileName("第08集", "", 8))
    }

    // ==================== A5. 目录模式 ====================

    @Test
    fun 目录模式_只输出集号() {
        assertEquals("1", EpisodeNaming.fileName("我不是大师", "第01集", 1))
        assertEquals("12", EpisodeNaming.fileName("狂飙", "第12集", 12))
        assertEquals("S1-1", EpisodeNaming.fileName("我不是大师 第一季", "第01集", 1))
        assertEquals("S2-3", EpisodeNaming.fileName("我不是大师S2", "", 3))
        // 目录模式下剧名不参与拼装，但剧名里的季仍然生效
        assertEquals("1", EpisodeNaming.displayName("我不是大师", "第01集", 1, inDirectory = true))
    }

    @Test
    fun 目录模式_没有集号时退回集名() {
        assertEquals("序章", EpisodeNaming.fileName("我不是大师", "序章", null))
        assertEquals("我不是大师", EpisodeNaming.fileName("我不是大师", "", null))
    }

    // ==================== A2/A3. 拆季与清洗 ====================

    @Test
    fun splitSeason_识别多种写法并剥掉() {
        assertEquals(EpisodeNaming.Seasoned(1, "我不是大师"), EpisodeNaming.splitSeason("我不是大师 第一季"))
        assertEquals(EpisodeNaming.Seasoned(1, "我不是大师"), EpisodeNaming.splitSeason("我不是大师S01"))
        assertEquals(EpisodeNaming.Seasoned(4, "我不是大师"), EpisodeNaming.splitSeason("我不是大师 Season 4"))
        assertEquals(EpisodeNaming.Seasoned(null, "我不是大师"), EpisodeNaming.splitSeason("我不是大师"))
    }

    @Test
    fun baseName_清洗脏标题与状态标注() {
        assertEquals("我不是大师", EpisodeNaming.baseName("我不是大师[全集]"))
        assertEquals("我不是大师", EpisodeNaming.baseName("我不是大师[全集]\">我不是大师[全集]"))
        assertEquals("狂飙", EpisodeNaming.baseName(" 狂飙 "))
        assertEquals("", EpisodeNaming.baseName("   "))
    }

    @Test
    fun episodeNumber_各种写法() {
        assertEquals(1, EpisodeNaming.episodeNumber("第01集"))
        assertEquals(3, EpisodeNaming.episodeNumber("EP03"))
        assertEquals(12, EpisodeNaming.episodeNumber("第十二集"))
        assertEquals(7, EpisodeNaming.episodeNumber("粤语07"))
        assertNull(EpisodeNaming.episodeNumber("狂飙"))
        assertNull(EpisodeNaming.episodeNumber("  "))
    }

    // ==================== B. 排序：集号按数值 ====================

    private fun episode(title: String, episodeName: String, number: Int?, id: String = title + episodeName) =
        DownloadedEpisode(
            id = id,
            title = title,
            episodeName = episodeName,
            episodeNumber = number,
            sizeBytes = 0L,
            completedAt = 0L,
            cover = "",
            item = OfflineItem(id = id, title = title, url = "https://example.com/$id.m3u8", episodeName = episodeName)
        )

    @Test
    fun 排序按集号数值而不是字符串() {
        val list = listOf(
            episode("我不是大师", "第10集", 10),
            episode("我不是大师", "第2集", 2),
            episode("我不是大师", "第1集", 1)
        ).sortedWith(EpisodeNaming.EPISODE_ORDER)
        assertEquals(listOf(1, 2, 10), list.map { it.episodeNumber })
        assertEquals(
            listOf("我不是大师-1", "我不是大师-2", "我不是大师-10"),
            list.map { OfflineLibrary.displayTitle(it) }
        )
    }

    @Test
    fun 排序先剧名再集号() {
        val list = listOf(
            episode("B剧", "第10集", 10),
            episode("A剧", "第3集", 3),
            episode("A剧", "第1集", 1)
        ).sortedWith(EpisodeNaming.EPISODE_ORDER)
        assertEquals(listOf("A剧", "A剧", "B剧"), list.map { it.title })
        assertEquals(listOf(1, 3, 10), list.map { it.episodeNumber })
    }

    @Test
    fun 没有集号的排在后面() {
        val list = listOf(
            episode("我不是大师", "序章", null),
            episode("我不是大师", "第02集", 2),
            episode("我不是大师", "第01集", 1)
        ).sortedWith(EpisodeNaming.EPISODE_ORDER)
        assertEquals(listOf(1, 2, null), list.map { it.episodeNumber })
    }

    @Test
    fun 剧名聚合仍按清洗后的剧名且不受集号影响() {
        val downloads = listOf(
            OfflineDownload(
                item = OfflineItem(id = "1", title = "我不是大师[全集]", url = "https://a/1.m3u8", episodeName = "第01集"),
                state = DownloadTaskState.COMPLETED, downloadedBytes = 1, totalBytes = 1,
                percent = 100f, failure = OfflineFailureReason.NONE, completedAt = 1
            ),
            OfflineDownload(
                item = OfflineItem(id = "2", title = "我不是大师[全集]", url = "https://a/2.m3u8", episodeName = "第02集"),
                state = DownloadTaskState.COMPLETED, downloadedBytes = 1, totalBytes = 1,
                percent = 100f, failure = OfflineFailureReason.NONE, completedAt = 1
            )
        )
        val groups = OfflineLibrary.groupByTitle(OfflineLibrary.episodes(downloads))
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 2), groups[0].episodes.map { it.episodeNumber })
        assertEquals(
            listOf("我不是大师-1", "我不是大师-2"),
            groups[0].episodes.map { OfflineLibrary.displayTitle(it) }
        )
    }
}
