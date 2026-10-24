package com.afeicn.aiplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存量下载数据迁移（[OfflineItemMigrator]）的单测。
 *
 * 样本取自真机 `shared_prefs/offline_downloads.xml`：5 条《我不是大师》1-5 集，
 * `title = 我不是大师[全集]`、`episodeName = 第01集…第05集`、`detailUrl = empirecms 详情页`、
 * `totalEpisodes = 24`，**没有** `season` / `episodeNumber` 字段。
 */
class OfflineItemMigratorTest {

    private val detailUrl = "https://cms.example.com/dianshiju/guoju/29683.html"

    /** 真机一条老数据（1-5 集）。 */
    private fun realItem(
        index: Int,
        title: String = "我不是大师[全集]",
        episodeName: String? = null,
        totalEpisodes: Int = 24
    ) = OfflineItem(
        id = "real-$index",
        title = title,
        url = "http://10.0.2.2:8777/ep$index.mp4",
        cover = "",
        extension = "mp4",
        sourceName = "网络地址",
        episodeName = episodeName ?: "第%02d集".format(index),
        kind = MediaKind.VIDEO,
        createdAt = 1792814737066L + (index - 1) * 100_000L,
        totalEpisodes = totalEpisodes,
        detailUrl = detailUrl
    )

    private fun realItems(): List<OfflineItem> = (1..5).map { realItem(it) }

    private fun download(item: OfflineItem) = OfflineDownload(
        item = item,
        state = DownloadTaskState.COMPLETED,
        downloadedBytes = 40779L,
        totalBytes = 40779L,
        percent = 100f,
        failure = OfflineFailureReason.NONE,
        completedAt = item.createdAt
    )

    // ---------- 真机数据 ----------

    @Test
    fun 真机五条_迁移后剧名季集号全部规整() {
        realItems().forEachIndexed { offset, item ->
            val migrated = OfflineItemMigrator.migrate(item)
            val index = offset + 1
            assertEquals("我不是大师", migrated.title)
            assertEquals(0, migrated.season)
            assertEquals(index, migrated.episodeNumber)
            assertEquals("第${index}集", migrated.episodeName)
            // 其它字段不能动：继续下载靠 detailUrl，选集标注靠 totalEpisodes。
            assertEquals(detailUrl, migrated.detailUrl)
            assertEquals(24, migrated.totalEpisodes)
            assertEquals(MediaKind.VIDEO, migrated.kind)
            assertEquals(item.url, migrated.url)
            assertEquals(item.id, migrated.id)
        }
    }

    @Test
    fun 迁移后显示名是剧名杠集号_排序按集号数值_五条仍聚合成一部剧() {
        val downloads = realItems().map { download(OfflineItemMigrator.migrate(it)) }
        val episodes = OfflineLibrary.episodes(downloads)

        assertEquals(
            listOf("我不是大师-1", "我不是大师-2", "我不是大师-3", "我不是大师-4", "我不是大师-5"),
            episodes.map { OfflineLibrary.displayTitle(it) }
        )
        assertEquals(listOf(1, 2, 3, 4, 5), episodes.map { it.episodeNumber })
        assertEquals(listOf(0, 0, 0, 0, 0), episodes.map { it.season })

        val groups = OfflineLibrary.groupByTitle(episodes)
        assertEquals(1, groups.size)
        assertEquals("我不是大师", groups.single().title)
        assertEquals(5, groups.single().downloadedCount)
        assertEquals(24, groups.single().totalEpisodes)
        assertEquals(detailUrl, groups.single().detailUrl)
    }

    @Test
    fun 迁移后集号数值排序_1_2_10_不错位() {
        val downloads = listOf(10, 2, 1).map { index ->
            download(realItem(index).copy(title = "狂飙"))
        }
        val episodes = OfflineLibrary.episodes(downloads)
        assertEquals(listOf(1, 2, 10), episodes.map { it.episodeNumber })
        assertEquals(listOf("狂飙-1", "狂飙-2", "狂飙-10"), episodes.map { OfflineLibrary.displayTitle(it) })
    }

    // ---------- 幂等 ----------

    @Test
    fun 幂等_真机五条跑两遍结果完全相同() {
        realItems().forEach { item ->
            val once = OfflineItemMigrator.migrate(item)
            val twice = OfflineItemMigrator.migrate(once)
            assertEquals(once, twice)
        }
        val once = OfflineItemMigrator.migrateAll(realItems())
        assertEquals(once, OfflineItemMigrator.migrateAll(once))
    }

    @Test
    fun 幂等_脏数据变体跑两遍结果完全相同() {
        val dirty = listOf(
            realItem(1, title = "我不是大师[全集]\">我不是大师[全集]"),
            realItem(2, title = "我不是大师 第二季[完结]", episodeName = "第十一集"),
            realItem(3, title = "第08集", episodeName = ""),
            realItem(4, title = "流浪地球2", episodeName = ""),
            OfflineItem(id = "", title = "   ", url = "", episodeName = "  ")
        )
        dirty.forEach { item ->
            val once = OfflineItemMigrator.migrate(item)
            assertEquals(once, OfflineItemMigrator.migrate(once))
        }
    }

    @Test
    fun 幂等_整份JSON跑两遍第二遍不再变更() {
        val raw = OfflineItemCodec.encode(realItems())
        val first = OfflineItemMigrator.migrateEncoded(raw)
        assertTrue(first.changed)
        assertFalse(first.failed)
        assertEquals(5, first.migrated)
        assertEquals(0, first.preserved)

        val second = OfflineItemMigrator.migrateEncoded(first.raw)
        assertFalse(second.changed)
        assertFalse(second.failed)
        assertEquals(5, second.migrated)
        assertEquals(first.raw, second.raw)
    }

    // ---------- 脏数据降级 ----------

    @Test
    fun 脏标题_HTML残渣与状态标注都剥掉() {
        val migrated = OfflineItemMigrator.migrate(
            realItem(1, title = "我不是大师[全集]\">我不是大师[全集]")
        )
        assertEquals("我不是大师", migrated.title)
        assertEquals(1, migrated.episodeNumber)
        assertEquals("我不是大师-1", OfflineLibrary.displayTitle(OfflineLibrary.toEpisode(download(migrated))))
    }

    @Test
    fun 集名是中文数字_解析成整数并统一成第N集() {
        val migrated = OfflineItemMigrator.migrate(
            realItem(1, title = "我不是大师[更新至24集]", episodeName = "第十一集")
        )
        assertEquals("我不是大师", migrated.title)
        assertEquals(11, migrated.episodeNumber)
        assertEquals("第11集", migrated.episodeName)
    }

    @Test
    fun 标题带季_季落库且从剧名剥掉_显示成S季集号() {
        val migrated = OfflineItemMigrator.migrate(
            realItem(1, title = "我不是大师 第二季[全集]", episodeName = "第03集")
        )
        assertEquals("我不是大师", migrated.title)
        assertEquals(2, migrated.season)
        assertEquals(3, migrated.episodeNumber)
        assertEquals("第3集", migrated.episodeName)

        val episode = OfflineLibrary.toEpisode(download(migrated))
        assertEquals(2, episode.season)
        assertEquals("我不是大师S2-3", OfflineLibrary.displayTitle(episode))
        assertEquals("S2-3", OfflineLibrary.fileName(episode))
    }

    @Test
    fun 标题里S01也识别成季() {
        val migrated = OfflineItemMigrator.migrate(
            realItem(1, title = "我不是大师S01[全24集]", episodeName = "第01集")
        )
        assertEquals("我不是大师", migrated.title)
        assertEquals(1, migrated.season)
        assertEquals(1, migrated.episodeNumber)
        assertEquals("我不是大师S1-1", OfflineLibrary.displayTitle(OfflineLibrary.toEpisode(download(migrated))))
    }

    @Test
    fun 字段缺失_不崩不丢_降级成干净的空值() {
        val migrated = OfflineItemMigrator.migrate(OfflineItem(id = "", title = "我不是大师", url = ""))
        assertEquals("我不是大师", migrated.title)
        assertEquals("", migrated.episodeName)
        assertEquals(0, migrated.season)
        assertEquals(0, migrated.episodeNumber)
        assertEquals(migrated, OfflineItemMigrator.migrate(migrated))
    }

    @Test
    fun 标题本身就是集名_从标题取集号但不当成剧名() {
        val migrated = OfflineItemMigrator.migrate(realItem(1, title = "第08集", episodeName = ""))
        assertEquals("第08集", migrated.title)
        assertEquals(8, migrated.episodeNumber)
        assertEquals("第8集", migrated.episodeName)
        assertEquals("第8集", OfflineLibrary.displayTitle(OfflineLibrary.toEpisode(download(migrated))))
    }

    @Test
    fun 作品名里的数字不当集号() {
        val migrated = OfflineItemMigrator.migrate(realItem(1, title = "流浪地球2", episodeName = ""))
        assertEquals("流浪地球2", migrated.title)
        assertEquals(0, migrated.episodeNumber)
        assertEquals("", migrated.episodeName)
    }

    @Test
    fun 已有集号字段优先于集名() {
        val migrated = OfflineItemMigrator.migrate(
            realItem(1).copy(episodeNumber = 7, episodeName = "第03集")
        )
        assertEquals(7, migrated.episodeNumber)
        assertEquals("第7集", migrated.episodeName)
    }

    @Test
    fun 音频条目也走同一套迁移() {
        val migrated = OfflineItemMigrator.migrate(
            realItem(1, title = "睡前故事", episodeName = "音频4").copy(kind = MediaKind.AUDIO)
        )
        assertEquals("睡前故事", migrated.title)
        assertEquals(4, migrated.episodeNumber)
        assertEquals("第4集", migrated.episodeName)
        assertEquals("睡前故事-4", OfflineLibrary.displayTitle(OfflineLibrary.toEpisode(download(migrated))))
    }

    // ---------- 版本闸门 ----------

    @Test
    fun schemaVersion_落后才迁移_已是最新不重复迁移() {
        val raw = OfflineItemCodec.encode(realItems())

        assertTrue(OfflineItemMigrator.needsMigration(0))
        assertTrue(OfflineItemMigrator.needsMigration(OfflineItemMigrator.SCHEMA_VERSION - 1))
        assertFalse(OfflineItemMigrator.needsMigration(OfflineItemMigrator.SCHEMA_VERSION))
        assertFalse(OfflineItemMigrator.needsMigration(OfflineItemMigrator.SCHEMA_VERSION + 1))

        assertNull(OfflineItemMigrator.migrateIfNeeded(OfflineItemMigrator.SCHEMA_VERSION, raw))
        val outcome = OfflineItemMigrator.migrateIfNeeded(0, raw)
        assertNotNull(outcome)
        assertTrue(outcome!!.changed)
        assertEquals(5, outcome.migrated)
    }

    // ---------- 坏数据不连坐 ----------

    @Test
    fun 坏条目原样保留_不影响其它条目() {
        val validDirty = OfflineItemCodec.encodeItem(realItem(1))
        val validDirty2 = OfflineItemCodec.encodeItem(realItem(2, title = "庆余年[全集]", episodeName = "第03集"))
        val raw = JSONArray().apply {
            put(validDirty)
            put("broken-item")
            put(validDirty2)
        }.toString()

        val outcome = OfflineItemMigrator.migrateEncoded(raw)
        assertFalse(outcome.failed)
        assertTrue(outcome.changed)
        assertEquals(2, outcome.migrated)
        assertEquals(1, outcome.preserved)

        val array = JSONArray(outcome.raw)
        assertEquals(3, array.length())
        assertEquals("broken-item", array.optString(1))

        val decoded = OfflineItemCodec.decode(outcome.raw!!)
        assertEquals(2, decoded.size)
        assertEquals("我不是大师", decoded[0].title)
        assertEquals(1, decoded[0].episodeNumber)
        assertEquals("庆余年", decoded[1].title)
        assertEquals(3, decoded[1].episodeNumber)
    }

    @Test
    fun 整段JSON坏了_不写回不推进版本() {
        val broken = "[{\"id\":\"x\","
        val outcome = OfflineItemMigrator.migrateEncoded(broken)
        assertTrue(outcome.failed)
        assertFalse(outcome.changed)
        assertEquals(broken, outcome.raw)
        assertEquals(0, outcome.migrated)

        val gate = OfflineItemMigrator.migrateIfNeeded(0, broken)
        assertNotNull(gate)
        assertTrue(gate!!.failed)
    }

    @Test
    fun 空数据直接跳过() {
        listOf(null, "", "   ").forEach { raw ->
            val outcome = OfflineItemMigrator.migrateEncoded(raw)
            assertFalse(outcome.failed)
            assertFalse(outcome.changed)
            assertEquals(0, outcome.migrated)
            assertEquals(0, outcome.preserved)
            assertEquals(raw, outcome.raw)
        }
    }

    // ---------- 编解码兼容 ----------

    @Test
    fun 编解码保留结构化字段_老JSON缺字段补0() {
        val migrated = OfflineItemMigrator.migrate(realItem(2, title = "我不是大师 第二季"))
        val restored = OfflineItemCodec.decode(OfflineItemCodec.encode(listOf(migrated))).single()
        assertEquals(migrated, restored)
        assertEquals(2, restored.season)
        assertEquals(2, restored.episodeNumber)

        val legacy = JSONObject().apply {
            put("id", "legacy")
            put("title", "我不是大师[全集]")
            put("url", "https://example.com/legacy.m3u8")
            put("episodeName", "第05集")
        }
        val decoded = OfflineItemCodec.decodeItem(legacy)
        assertEquals(0, decoded.season)
        assertEquals(0, decoded.episodeNumber)
        assertEquals(5, OfflineItemMigrator.migrate(decoded).episodeNumber)
    }
}
