package com.afeicn.aiplayer

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「继续下载更多集」的纯逻辑单测。
 *
 * 覆盖：来源详情页 [OfflineItem.detailUrl] 的落库/读取（含老数据缺字段兼容）、下载入队时
 * 一路透传、入口显示条件、选集面板里已下载集的过滤、音频剧的集数映射与提示文案。
 * 不联网、不碰 Android 框架（[OfflineItemCodec] 只用 org.json）。
 */
class ContinueDownloadTest {

    private val detailUrl = "https://cms.example.com/dianshiju/guoju/12320.html"

    private fun item(
        id: String = "d1",
        title: String = "庆余年",
        episodeName: String = "第01集",
        url: String = "https://cdn.example.com/1.m3u8",
        kind: MediaKind = MediaKind.VIDEO,
        detailUrl: String = "",
        sourceName: String = "6v电影",
        totalEpisodes: Int = 0,
        headers: Map<String, String> = emptyMap()
    ) = OfflineItem(
        id = id,
        title = title,
        url = url,
        headers = headers,
        sourceName = sourceName,
        episodeName = episodeName,
        kind = kind,
        totalEpisodes = totalEpisodes,
        detailUrl = detailUrl
    )

    private fun dl(
        item: OfflineItem,
        state: DownloadTaskState = DownloadTaskState.COMPLETED
    ) = OfflineDownload(
        item = item,
        state = state,
        downloadedBytes = 1024L,
        totalBytes = 2048L,
        percent = 100f,
        failure = OfflineFailureReason.NONE,
        completedAt = 2000L
    )

    // ==================== A. 记住来源 ====================

    @Test
    fun detailUrl_落库后原样读回() {
        val original = listOf(
            item(id = "e1", episodeName = "第01集", detailUrl = detailUrl, totalEpisodes = 24),
            item(id = "e2", episodeName = "第02集", detailUrl = detailUrl, totalEpisodes = 24)
        )
        val restored = OfflineItemCodec.decode(OfflineItemCodec.encode(original))
        assertEquals(2, restored.size)
        assertEquals(listOf(detailUrl, detailUrl), restored.map { it.detailUrl })
        assertEquals(original, restored)
    }

    @Test
    fun detailUrl_老数据缺字段时为空串且不丢条目() {
        // 老版本落库的 JSON：完全没有 detailUrl 字段。
        val legacy = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "old1")
                    put("title", "狂飙")
                    put("url", "https://cdn.example.com/old1.m3u8")
                    put("cover", "")
                    put("extension", "m3u8")
                    put("sourceName", "6v电影")
                    put("episodeName", "第01集")
                    put("kind", "VIDEO")
                    put("createdAt", 1000L)
                    put("totalEpisodes", 39)
                    put("resolveDetailUrl", "")
                    put("resolveIndex", 0)
                    put("headers", JSONObject())
                }
            )
            put(
                JSONObject().apply {
                    put("id", "old2")
                    put("title", "狂飙")
                    put("url", "https://cdn.example.com/old2.m3u8")
                    put("kind", "AUDIO")
                }
            )
            put(
                JSONObject().apply {
                    put("id", "old3")
                    put("title", "狂飙")
                    put("url", "https://cdn.example.com/old3.m3u8")
                }
            )
        }.toString()

        val restored = OfflineItemCodec.decode(legacy)
        assertEquals(3, restored.size)
        assertTrue("老条目 detailUrl 必须是空串", restored.all { it.detailUrl.isEmpty() })
        assertEquals("狂飙", restored[0].title)
        assertEquals(MediaKind.AUDIO, restored[1].kind)
        // 连 kind 都缺的极老条目 → 默认 VIDEO，不能崩。
        assertEquals(MediaKind.VIDEO, restored[2].kind)
    }

    @Test
    fun 坏掉的JSON返回空列表不崩() {
        assertEquals(emptyList<OfflineItem>(), OfflineItemCodec.decode("not-json"))
        assertEquals(emptyList<OfflineItem>(), OfflineItemCodec.decode(""))
    }

    @Test
    fun 下载入队时detailUrl一路透传到可视化条目() = runBlocking {
        val page = """
            <div>
              <a href="/e/DownSys/play/?classid=6&id=12320&pathid1=0&bf=0">第01集</a>
              <a href="/e/DownSys/play/?classid=6&id=12320&pathid1=1&bf=0">第02集</a>
            </div>
        """.trimIndent()
        val fetcher = object : PageFetcher {
            override fun fetch(url: String, referer: String?): FetchedPage = FetchedPage(
                url = url,
                contentType = "text/html",
                body = when {
                    url == detailUrl -> page
                    url.contains("/e/DownSys/play/") -> """<iframe src="https://player.example.com/play/Xe0Z2W3b"></iframe>"""
                    else -> """<video src="https://player.example.com/play/Xe0Z2W3b/index.m3u8"></video>"""
                }
            )
        }
        val source = OnlineVideoSource(id = "empirecms", name = "6v电影", base = "https://cms.example.com")
        val items = SubscriptionPipeline.resolveItems("庆余年", source, detailUrl, listOf(1, 2), fetcher)
        assertEquals(2, items.size)
        assertTrue("每集都要记住详情页，才能继续下载", items.all { it.detailUrl == detailUrl })
        assertEquals(2, items.map { it.totalEpisodes }.distinct().single())
    }

    @Test
    fun 音频剧入队时detailUrl同样落库() = runBlocking {
        val audioDetail = "https://audio.example.com/album.html"
        val fetcher = object : PageFetcher {
            override fun fetch(url: String, referer: String?): FetchedPage = FetchedPage(
                url = url,
                contentType = "text/html",
                body = """<audio src="/music/song1.mp3"></audio><audio src="/music/song2.mp3"></audio>"""
            )
        }
        val source = OnlineVideoSource(
            id = "a", name = "音频站", base = "https://audio.example.com", type = SourceType.AUDIO
        )
        val items = SubscriptionPipeline.resolveItems("某专辑", source, audioDetail, listOf(1), fetcher)
        assertEquals(1, items.size)
        assertEquals(audioDetail, items[0].detailUrl)
        assertEquals(MediaKind.AUDIO, items[0].kind)
    }

    // ==================== B. 继续下载入口 ====================

    @Test
    fun 入口显示条件_有detailUrl才显示() {
        assertTrue(ContinueDownloadRules.canContinue(detailUrl))
        assertFalse(ContinueDownloadRules.canContinue(""))
        assertFalse(ContinueDownloadRules.canContinue("   "))
        assertFalse(ContinueDownloadRules.canContinue(null as String?))
        // 老数据（没有来源信息）→ 不显示入口，也就不会「点了没反应」。
        assertFalse(ContinueDownloadRules.canContinue(item(detailUrl = "")))
        assertTrue(ContinueDownloadRules.canContinue(item(detailUrl = detailUrl)))
    }

    @Test
    fun 按钮文案_抓取中变获取集数并禁止重复点() {
        assertEquals("继续下载", ContinueDownloadRules.buttonLabel(false))
        assertEquals("获取集数…", ContinueDownloadRules.buttonLabel(true))
        assertTrue(ContinueDownloadRules.enabled(false))
        assertFalse(ContinueDownloadRules.enabled(true))
    }

    @Test
    fun 抓取失败与空结果给出可操作文案() {
        assertEquals("这部剧的页面打不开了，可能已下架，需要重新搜索", ContinueDownloadRules.fetchMessage(RuntimeException("404")))
        assertEquals("这部剧的页面没解析到集数，可能已下架，需要重新搜索", ContinueDownloadRules.fetchMessage(null))
        assertTrue(ContinueDownloadRules.isEmptyResult(null))
        assertTrue(ContinueDownloadRules.isEmptyResult(emptyList()))
        assertFalse(ContinueDownloadRules.isEmptyResult(listOf(EpisodeLink(1, "第01集", "u"))))
    }

    @Test
    fun 目标可以从剧卡里取到来源线索() {
        val series = DownloadedSeries(
            title = "庆余年",
            episodes = listOf(
                OfflineLibrary.toEpisode(dl(item(id = "e1", episodeName = "第01集", detailUrl = detailUrl))),
                // 老数据：没有来源信息
                OfflineLibrary.toEpisode(dl(item(id = "e0", episodeName = "第00集", detailUrl = "")))
            )
        )
        assertTrue(series.canContinueDownload)
        val target = ContinueDownloadRules.targetOf(series)
        assertNotNull(target)
        assertEquals(detailUrl, target!!.detailUrl)
        assertEquals("庆余年", target.title)
        assertEquals("6v电影", target.sourceName)
        assertEquals(MediaKind.VIDEO, target.kind)
    }

    @Test
    fun 老数据剧卡没有来源线索时依然产生目标并走兜底定位() {
        val series = DownloadedSeries(
            title = "狂飙",
            episodes = listOf(OfflineLibrary.toEpisode(dl(item(id = "o1", title = "狂飙", detailUrl = ""))))
        )
        // 入口始终显示：老数据没有详情页也能点，点了先按剧名 + 站点兜底定位来源。
        assertTrue(series.canContinueDownload)
        assertNull(series.sourceItem)
        val target = ContinueDownloadRules.targetOf(series)
        assertNotNull(target)
        assertEquals("狂飙", target!!.title)
        assertEquals("", target.detailUrl)
        assertTrue(ContinueDownloadRules.needsLookup(target.detailUrl))
        assertTrue(ContinueDownloadRules.canLookup(target.title))
        // 完全没有条目才没有目标（没有剧名就无从搜起）。
        assertNull(ContinueDownloadRules.targetOf(emptyList()))
    }

    @Test
    fun 目标能重建出正确的源类型() {
        val video = ContinueDownloadRules.targetOf(listOf(item(detailUrl = detailUrl)))!!
        assertEquals(SourceType.VIDEO, ContinueDownloadRules.sourceOf(video).type)
        val audio = ContinueDownloadRules.targetOf(
            listOf(item(kind = MediaKind.AUDIO, detailUrl = detailUrl, headers = mapOf("Referer" to "https://a.com")))
        )!!
        val audioSource = ContinueDownloadRules.sourceOf(audio)
        assertEquals(SourceType.AUDIO, audioSource.type)
        assertEquals("https://a.com", audioSource.referer)
        assertTrue(audio.isAudio)
    }

    @Test
    fun 剧名过滤只挑这部剧的线索() {
        val items = listOf(
            item(id = "a", title = "庆余年", detailUrl = ""),
            item(id = "b", title = "狂飙", detailUrl = detailUrl)
        )
        // 限定「庆余年」时它自己没有 detailUrl → 目标是空的 detailUrl（走兜底定位），
        // 绝不能拿别的剧的详情页顶。
        val qing = ContinueDownloadRules.targetOf(items, "庆余年")
        assertNotNull(qing)
        assertEquals("庆余年", qing!!.title)
        assertEquals("", qing.detailUrl)
        assertTrue(ContinueDownloadRules.needsLookup(qing.detailUrl))
        assertEquals(detailUrl, ContinueDownloadRules.targetOf(items, "狂飙")!!.detailUrl)
        // 完全不在这份列表里的剧 → 没有目标。
        assertNull(ContinueDownloadRules.targetOf(items, "三体"))
    }

    // ==================== C. 面板里已下载集的过滤 ====================

    @Test
    fun 已下载下载中获取地址中的集都不可选() {
        val statuses = mapOf(
            1 to DownloadTaskState.COMPLETED,
            2 to DownloadTaskState.RESOLVING,
            3 to DownloadTaskState.DOWNLOADING,
            4 to DownloadTaskState.QUEUED,
            5 to DownloadTaskState.PAUSED,
            6 to DownloadTaskState.FAILED,
            8 to DownloadTaskState.COMPLETED
        )
        val selectable = ContinueDownloadRules.selectableIndices(10, statuses)
        // 失败可重下、7/9/10 没有记录 → 可选。
        assertEquals(setOf(6, 7, 9, 10), selectable)
    }

    @Test
    fun 面板过滤与选集提交规则同源() {
        val statuses = mapOf(1 to DownloadTaskState.COMPLETED, 2 to DownloadTaskState.DOWNLOADING)
        assertEquals(
            EpisodeDownloadRules.availableIndices(5, statuses),
            ContinueDownloadRules.selectableIndices(5, statuses)
        )
        val partition = EpisodeDownloadRules.partition(setOf(1, 2, 3), statuses)
        assertEquals(setOf(3), partition.addable)
    }

    @Test
    fun 面板副标题写着总集数与已下载数() {
        assertEquals("共 24 集，已下载 3 集", ContinueDownloadRules.summary(24, 3))
        assertEquals("已加入 2 集，正在获取播放地址…", ContinueDownloadRules.enqueuedNotice(2))
    }

    // ==================== D. 音频剧 ====================

    @Test
    fun 音频直链映射成可选集() {
        val episodes = ContinueDownloadRules.audioEpisodes(
            listOf("https://a.com/1.mp3", "https://a.com/2.m4a")
        )
        assertEquals(listOf(1, 2), episodes.map { it.index })
        assertEquals(listOf("音频1", "音频2"), episodes.map { it.name })
        assertEquals("https://a.com/2.m4a", episodes[1].url)
        assertTrue(ContinueDownloadRules.audioEpisodes(emptyList()).isEmpty())
    }

    // ==================== E. 剧卡来源线索的聚合 ====================

    @Test
    fun 剧卡聚合时优先取带detailUrl的那一集() {
        val downloads = listOf(
            dl(item(id = "x1", episodeName = "第01集", detailUrl = "")),
            dl(item(id = "x2", episodeName = "第02集", detailUrl = detailUrl))
        )
        val series = OfflineLibrary.groupByTitle(OfflineLibrary.episodes(downloads))
        assertEquals(1, series.size)
        assertEquals(detailUrl, series[0].detailUrl)
        assertEquals("6v电影", series[0].sourceName)
        assertEquals(MediaKind.VIDEO, series[0].kind)
        assertEquals(2, series[0].downloadedCount)
    }
}
