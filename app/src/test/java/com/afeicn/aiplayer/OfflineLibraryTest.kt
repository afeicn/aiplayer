package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已完成离线下载 → 媒体库条目」纯映射层的单测。
 * 不扫盘、不读缓存、不碰 Android 框架（Uri 只在 UI 层解析，见 DownloadedEpisode.mediaEntry）。
 */
class OfflineLibraryTest {

    private fun item(
        id: String,
        title: String,
        url: String = "https://example.com/$id.m3u8",
        episodeName: String = "",
        kind: MediaKind = MediaKind.VIDEO,
        cover: String = "",
        createdAt: Long = 1000L,
        totalEpisodes: Int = 0
    ) = OfflineItem(
        id = id,
        title = title,
        url = url,
        episodeName = episodeName,
        kind = kind,
        cover = cover,
        createdAt = createdAt,
        totalEpisodes = totalEpisodes
    )

    private fun dl(
        item: OfflineItem,
        state: DownloadTaskState = DownloadTaskState.COMPLETED,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        completedAt: Long = 0L
    ) = OfflineDownload(
        item = item,
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        percent = 100f,
        failure = OfflineFailureReason.NONE,
        completedAt = completedAt
    )

    @Test
    fun completed_只保留已完成任务() {
        val list = listOf(
            dl(item("1", "狂飙"), DownloadTaskState.COMPLETED),
            dl(item("2", "狂飙"), DownloadTaskState.DOWNLOADING),
            dl(item("3", "狂飙"), DownloadTaskState.FAILED),
            dl(item("4", "狂飙"), DownloadTaskState.QUEUED)
        )
        val result = OfflineLibrary.completed(list)
        assertEquals(listOf("1"), result.map { it.item.id })
    }

    @Test
    fun episodeNumber_解析各种写法() {
        assertEquals(1, OfflineLibrary.episodeNumber("第01集"))
        assertEquals(3, OfflineLibrary.episodeNumber("EP03"))
        assertEquals(3, OfflineLibrary.episodeNumber("EP 3"))
        assertEquals(12, OfflineLibrary.episodeNumber("第十二集"))
        assertEquals(7, OfflineLibrary.episodeNumber("粤语07"))
        assertEquals(7, OfflineLibrary.episodeNumber("音频7"))
        assertEquals(2, OfflineLibrary.episodeNumber("第2回"))
        assertNull(OfflineLibrary.episodeNumber(""))
        assertNull(OfflineLibrary.episodeNumber("   "))
        assertNull(OfflineLibrary.episodeNumber("狂飙"))
    }

    @Test
    fun toEpisode_集号优先集名回退标题() {
        val byName = OfflineLibrary.toEpisode(dl(item("1", "狂飙", episodeName = "第05集")))
        assertEquals(5, byName.episodeNumber)
        assertEquals("狂飙", byName.title)

        val byTitle = OfflineLibrary.toEpisode(dl(item("2", "第08集", episodeName = "")))
        assertEquals(8, byTitle.episodeNumber)
    }

    @Test
    fun toEpisode_大小与完成时间() {
        val episode = OfflineLibrary.toEpisode(
            dl(item("1", "狂飙", episodeName = "第01集", createdAt = 999L), downloadedBytes = 123, totalBytes = 999, completedAt = 888L)
        )
        assertEquals(123L, episode.sizeBytes)
        assertEquals(888L, episode.completedAt)

        val fallback = OfflineLibrary.toEpisode(
            dl(item("2", "狂飙", episodeName = "第02集", createdAt = 999L), downloadedBytes = 0, totalBytes = 456, completedAt = 0L)
        )
        assertEquals(456L, fallback.sizeBytes)
        assertEquals(999L, fallback.completedAt)
    }

    @Test
    fun displayTitle_有剧名有集号_拼成剧名杠集号() {
        assertEquals("狂飙-4", OfflineLibrary.displayTitle("狂飙", "第4集", 4, MediaKind.VIDEO))
    }

    @Test
    fun displayTitle_只有剧名_只显示剧名() {
        assertEquals("狂飙", OfflineLibrary.displayTitle("狂飙", "", null, MediaKind.VIDEO))
    }

    @Test
    fun displayTitle_只有集号_退回集名() {
        assertEquals("第4集", OfflineLibrary.displayTitle("", "第4集", 4, MediaKind.VIDEO))
    }

    @Test
    fun displayTitle_剧名集名都空_退回未命名不出空标题() {
        assertEquals("未命名", OfflineLibrary.displayTitle("  ", "", null, MediaKind.VIDEO))
    }

    @Test
    fun displayTitle_音频条目_用同一套命名规则() {
        assertEquals("睡前故事-4", OfflineLibrary.displayTitle("睡前故事", "音频4", 4, MediaKind.AUDIO))
    }

    @Test
    fun displayTitle_下载条目重载_透传字段与媒体类型() {
        val episode = OfflineLibrary.toEpisode(dl(item("1", "狂飙", episodeName = "第4集")))
        assertEquals("狂飙-4", OfflineLibrary.displayTitle(episode))
        assertEquals("4", OfflineLibrary.fileName(episode))
    }

    @Test
    fun episodes_按类型过滤并按剧名集号排序() {
        val downloads = listOf(
            dl(item("a", "狂飙", episodeName = "第03集")),
            dl(item("b", "狂飙", episodeName = "第01集")),
            dl(item("c", "庆余年", episodeName = "第02集")),
            dl(item("d", "睡前故事", episodeName = "音频1", kind = MediaKind.AUDIO))
        )
        val videos = OfflineLibrary.episodes(downloads, MediaKind.VIDEO)
        assertEquals(listOf("庆余年", "狂飙", "狂飙"), videos.map { it.title })
        assertEquals(listOf(2, 1, 3), videos.map { it.episodeNumber })

        val audios = OfflineLibrary.episodes(downloads, MediaKind.AUDIO)
        assertEquals(listOf("睡前故事"), audios.map { it.title })

        val all = OfflineLibrary.episodes(downloads)
        assertEquals(4, all.size)
    }

    @Test
    fun episodes_忽略非完成任务() {
        val downloads = listOf(
            dl(item("1", "狂飙", episodeName = "第01集"), DownloadTaskState.DOWNLOADING),
            dl(item("2", "狂飙", episodeName = "第02集"))
        )
        assertEquals(listOf("2"), OfflineLibrary.episodes(downloads).map { it.id })
    }

    @Test
    fun dedupe_同id只留一条() {
        val a = OfflineLibrary.toEpisode(dl(item("same", "狂飙", episodeName = "第01集")))
        val b = OfflineLibrary.toEpisode(dl(item("same", "狂飙", episodeName = "第01集")))
        val result = OfflineLibrary.dedupe(listOf(a, b))
        assertEquals(1, result.size)
    }

    @Test
    fun dedupe_同剧同集号只留第一条() {
        val a = OfflineLibrary.toEpisode(dl(item("x", "狂飙", episodeName = "第01集", ), downloadedBytes = 1))
        val b = OfflineLibrary.toEpisode(dl(item("y", "狂飙", episodeName = "第01集"), downloadedBytes = 2))
        val c = OfflineLibrary.toEpisode(dl(item("z", "狂飙", episodeName = "第02集"), downloadedBytes = 3))
        val result = OfflineLibrary.dedupe(listOf(b, a, c))
        assertEquals(listOf("y", "z"), result.map { it.id })
    }

    @Test
    fun groupByTitle_按剧名折叠并标注集数() {
        val downloads = listOf(
            dl(item("1", "狂飙", episodeName = "第01集", totalEpisodes = 5)),
            dl(item("2", "狂飙", episodeName = "第03集", totalEpisodes = 5)),
            dl(item("3", "狂飙", episodeName = "第05集", totalEpisodes = 5)),
            dl(item("4", "庆余年", episodeName = "第02集"))
        )
        val groups = OfflineLibrary.groupByTitle(OfflineLibrary.episodes(downloads))
        assertEquals(listOf("庆余年", "狂飙"), groups.map { it.title })

        val kuangbiao = groups.first { it.title == "狂飙" }
        assertEquals(3, kuangbiao.downloadedCount)
        assertEquals(5, kuangbiao.totalEpisodes)
        assertEquals("共 5 集，已下载 3 集", kuangbiao.countLabel)
        assertEquals(listOf(1, 3, 5), kuangbiao.episodes.map { it.episodeNumber })

        val qing = groups.first { it.title == "庆余年" }
        assertEquals(1, qing.downloadedCount)
        assertEquals(0, qing.totalEpisodes)
        assertEquals("已下载 1 集", qing.countLabel)
    }

    @Test
    fun downloadedUrls_与判定() {
        val downloads = listOf(
            dl(item("1", "狂飙", url = "https://a.com/1.m3u8")),
            dl(item("2", "狂飙", url = "https://a.com/2.m3u8"), DownloadTaskState.DOWNLOADING)
        )
        val urls = OfflineLibrary.downloadedUrls(downloads)
        assertEquals(setOf("https://a.com/1.m3u8"), urls)
        assertTrue(OfflineLibrary.isDownloaded(" https://a.com/1.m3u8 ", urls))
        assertFalse(OfflineLibrary.isDownloaded("https://a.com/2.m3u8", urls))
    }

    @Test
    fun downloadedEpisodeNumbers_按剧名取已下载集号() {
        val downloads = listOf(
            dl(item("1", "狂飙", episodeName = "第01集")),
            dl(item("2", "狂飙", episodeName = "第04集")),
            dl(item("3", "庆余年", episodeName = "第09集"))
        )
        assertEquals(setOf(1, 4), OfflineLibrary.downloadedEpisodeNumbers(downloads, "狂飙"))
        assertEquals(setOf(9), OfflineLibrary.downloadedEpisodeNumbers(downloads, "庆余年"))
        assertEquals(emptySet<Int>(), OfflineLibrary.downloadedEpisodeNumbers(downloads, "不存在的剧"))
    }

    @Test
    fun isEpisodeDownloaded_按集号与集名() {
        val downloads = listOf(
            dl(item("1", "狂飙", episodeName = "第01集")),
            dl(item("2", "狂飙", episodeName = "第03集"))
        )
        assertTrue(OfflineLibrary.isEpisodeDownloaded(downloads, "狂飙", 1))
        assertTrue(OfflineLibrary.isEpisodeDownloaded(downloads, "狂飙", 3))
        assertFalse(OfflineLibrary.isEpisodeDownloaded(downloads, "狂飙", 2))
        assertFalse(OfflineLibrary.isEpisodeDownloaded(downloads, "庆余年", 1))
    }

    @Test
    fun isEpisodeDownloaded_集号缺失时按集名回退() {
        val downloads = listOf(
            dl(item("1", "狂飙", episodeName = "剧场版特别篇"))
        )
        assertTrue(OfflineLibrary.isEpisodeDownloaded(downloads, "狂飙", 0, episodeName = "剧场版特别篇"))
        assertFalse(OfflineLibrary.isEpisodeDownloaded(downloads, "狂飙", 0, episodeName = "别的集"))
    }

    @Test
    fun sameTitle_忽略首尾空白与大小写() {
        assertTrue(OfflineLibrary.sameTitle("狂飙", " 狂飙 "))
        assertTrue(OfflineLibrary.sameTitle("Naruto", "naruto"))
        assertFalse(OfflineLibrary.sameTitle("狂飙", "庆余年"))
    }

    // ==================== episodeStatuses：选集网格防重复聚合 ====================

    private fun resolvingItem(id: String, title: String, detailUrl: String, index: Int) = OfflineItem(
        id = id,
        title = title,
        url = "",
        episodeName = "第${index}集",
        resolveDetailUrl = detailUrl,
        resolveIndex = index
    )

    @Test
    fun episodeStatuses_占位任务按resolveIndex定位并过滤其他剧() {
        val downloads = listOf(
            dl(resolvingItem("p1", "狂飙", "https://x/detail", 2), DownloadTaskState.RESOLVING),
            dl(resolvingItem("p2", "狂飙", "https://x/detail", 5), DownloadTaskState.FAILED),
            dl(resolvingItem("p3", "庆余年", "https://x/detail", 2), DownloadTaskState.RESOLVING)
        )
        val statuses = OfflineLibrary.episodeStatuses(downloads, "狂飙")
        assertEquals(2, statuses.size)
        assertEquals(DownloadTaskState.RESOLVING, statuses[2])
        assertEquals(DownloadTaskState.FAILED, statuses[5])
    }

    @Test
    fun episodeStatuses_集号回退到集名() {
        val downloads = listOf(
            dl(item("1", "狂飙", episodeName = "第03集"), DownloadTaskState.DOWNLOADING)
        )
        assertEquals(DownloadTaskState.DOWNLOADING, OfflineLibrary.episodeStatuses(downloads, "狂飙")[3])
    }

    @Test
    fun episodeStatuses_同集多源取更靠前状态() {
        val downloads = listOf(
            dl(item("a", "狂飙", episodeName = "第01集"), DownloadTaskState.DOWNLOADING),
            dl(item("b", "狂飙", episodeName = "第01集"), DownloadTaskState.COMPLETED),
            dl(item("c", "狂飙", episodeName = "第02集"), DownloadTaskState.QUEUED),
            dl(item("d", "狂飙", episodeName = "第02集"), DownloadTaskState.RESOLVING),
            dl(item("e", "狂飙", episodeName = "第03集"), DownloadTaskState.FAILED),
            dl(item("f", "狂飙", episodeName = "第03集"), DownloadTaskState.PAUSED)
        )
        val statuses = OfflineLibrary.episodeStatuses(downloads, "狂飙")
        // 已完成 > 下载中；获取地址中 > 排队；暂停 > 失败。
        assertEquals(DownloadTaskState.COMPLETED, statuses[1])
        assertEquals(DownloadTaskState.RESOLVING, statuses[2])
        assertEquals(DownloadTaskState.PAUSED, statuses[3])
    }
}
