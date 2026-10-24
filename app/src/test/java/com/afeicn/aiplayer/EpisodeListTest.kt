package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「粘剧集详情页 → 解析出集数 → 选集播放」纯逻辑单测：全部用假 HTML 字符串，不联网。
 */
class EpisodeListTest {

    private class FakeFetcher(private val pages: Map<String, FetchedPage>) : PageFetcher {
        override fun fetch(url: String, referer: String?): FetchedPage =
            pages[url] ?: throw WebLinkFetchException("HTTP 404", 404)
    }

    private fun page(url: String, html: String) =
        FetchedPage(url = url, contentType = "text/html", body = html)

    // ==================== 集号解析 ====================

    @Test
    fun episodeNumberParsesArabicAndChinese() {
        assertEquals(1, EpisodeNumber.parse("01"))
        assertEquals(1, EpisodeNumber.parse("1"))
        assertEquals(12, EpisodeNumber.parse("12"))
        assertEquals(1, EpisodeNumber.parse("一"))
        assertEquals(10, EpisodeNumber.parse("十"))
        assertEquals(11, EpisodeNumber.parse("十一"))
        assertEquals(21, EpisodeNumber.parse("二十一"))
        assertEquals(101, EpisodeNumber.parse("一百零一"))
        assertNull(EpisodeNumber.parse("0"))
        assertNull(EpisodeNumber.parse(""))
        assertNull(EpisodeNumber.parse("粤语"))
    }

    @Test
    fun indexFromNameParsesChineseEpisodeLabels() {
        assertEquals(1, EpisodeListParser.indexFromName("第01集"))
        assertEquals(1, EpisodeListParser.indexFromName("第 1 集"))
        assertEquals(1, EpisodeListParser.indexFromName("第1话"))
        assertEquals(1, EpisodeListParser.indexFromName("第1期"))
        assertEquals(11, EpisodeListParser.indexFromName("第十一集"))
    }

    @Test
    fun indexFromNameParsesEpPrefixAndBareNumber() {
        assertEquals(1, EpisodeListParser.indexFromName("EP01"))
        assertEquals(2, EpisodeListParser.indexFromName("EP 02"))
        assertEquals(3, EpisodeListParser.indexFromName("E03"))
        assertEquals(1, EpisodeListParser.indexFromName("1"))
        assertEquals(1, EpisodeListParser.indexFromName("粤语01"))
        assertNull(EpisodeListParser.indexFromName("高清"))
    }

    @Test
    fun indexFromHrefParsesSequenceNumbers() {
        assertEquals(5, EpisodeListParser.indexFromHref("/vodplay/12345-1-5.html"))
        assertEquals(3, EpisodeListParser.indexFromHref("/play/ep-3.html"))
        assertEquals(1, EpisodeListParser.indexFromHref("/e/DownSys/play/?classid=6&id=29804&pathid1=0&bf=0"))
        assertEquals(6, EpisodeListParser.indexFromHref("/e/DownSys/play/?classid=8&id=29827&pathid3=5"))
        // 裸 id.html 是影片详情页、_N.html 是分页，都不是集数。
        assertNull(EpisodeListParser.indexFromHref("/dianshiju/guoju/29804.html"))
        assertNull(EpisodeListParser.indexFromHref("/play/3.html"))
        assertNull(EpisodeListParser.indexFromHref("/dianshiju/index_2.html"))
        assertNull(EpisodeListParser.indexFromHref("/category/action/"))
    }

    // ==================== 帝国CMS 结构 ====================

    @Test
    fun empireCmsPlaylistContainerIsExtracted() {
        val html = """
            <html><body>
            <div class="playlist"><ul>
              <li><a href="/p/1.html">第01集</a></li>
              <li><a href="/p/2.html">第02集</a></li>
              <li><a href="/p/3.html">第03集</a></li>
            </ul></div>
            </body></html>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/detail.html")

        assertEquals(3, episodes.size)
        assertEquals(listOf("第01集", "第02集", "第03集"), episodes.map { it.name })
        assertEquals(listOf(1, 2, 3), episodes.map { it.index })
        assertEquals("https://site.com/p/1.html", episodes[0].url)
    }

    @Test
    fun empireCmsMatchesContainerWithPlaylistInIdOrClass() {
        val html = """
            <div id="playlist_box"><a href="/a/1.html">第01集</a><a href="/a/2.html">第02集</a><a href="/a/3.html">第03集</a></div>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/x.html")
        assertEquals(3, episodes.size)
    }

    // ==================== MacCMS 结构 ====================

    @Test
    fun macCmsVodplayLinksAreExtracted() {
        val html = """
            <a href="/vodplay/12345-1-1.html">第01集</a>
            <a href="/vodplay/12345-1-2.html">第02集</a>
            <a href="/vodplay/12345-1-3.html">第03集</a>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/voddetail/12345.html")

        assertEquals(3, episodes.size)
        assertEquals(listOf(1, 2, 3), episodes.map { it.index })
        assertEquals("https://site.com/vodplay/12345-1-1.html", episodes[0].url)
    }

    @Test
    fun macCmsUsesHrefSequenceWhenNameHasNoNumber() {
        val html = """
            <a href="/vodplay/12345-1-5.html">高清</a>
            <a href="/vodplay/12345-1-6.html">高清</a>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/voddetail/12345.html")
        assertEquals(listOf(5, 6), episodes.map { it.index })
    }

    @Test
    fun voddetailLinksAreNotTreatedAsEpisodes() {
        val html = """
            <a href="/voddetail/29804.html">璀璨之城</a>
            <a href="/voddetail/29835.html">嫁给钱</a>
        """.trimIndent()

        assertTrue(EpisodeListParser.extract(html, "https://site.com/index.html").isEmpty())
    }

    // ==================== 通用兜底 ====================

    @Test
    fun genericSameDomainEpisodeLinksAreExtracted() {
        val html = """
            <a href="/play/1.html">第01集</a>
            <a href="/play/2.html">EP02</a>
            <a href="/play/3.html">第03集</a>
            <a href="https://other.com/play/9.html">第09集</a>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/detail.html")

        assertEquals(3, episodes.size) // 异域的第09集被同域名过滤掉
        assertEquals(listOf(1, 2, 3), episodes.map { it.index })
        assertEquals(listOf("第01集", "EP02", "第03集"), episodes.map { it.name })
    }

    @Test
    fun empirecmsDetailPageWithPathidGroupsIsExtractedAndDeduplicated() {
        // 真实 empirecms 详情页：widget box row + 4 组「播放地址」，每组 16 集、组间集名重复。
        val groups = (1..4).joinToString("") { g ->
            (0 until 16).joinToString("") { n ->
                val label = (n + 1).toString().padStart(2, '0')
                """<a href="/e/DownSys/play/?classid=6&id=29804&pathid$g=$n&bf=0">粤语$label</a>"""
            }
        }
        val html = """<div class="widget box row"><h3>播放地址（无插件 极速播放）</h3>$groups</div>"""

        val episodes = EpisodeListParser.extract(html, "https://cms.example.com/dianshiju/guoju/29804.html")

        assertEquals(16, episodes.size)
        assertEquals((1..16).toList(), episodes.map { it.index })
        assertEquals(
            "https://cms.example.com/e/DownSys/play/?classid=6&id=29804&pathid1=0&bf=0",
            episodes[0].url
        )
    }

    // ==================== 顺序与去重 ====================

    @Test
    fun episodesAreSortedByIndexRegardlessOfDocumentOrder() {
        val html = """
            <a href="/p/2.html">第02集</a>
            <a href="/p/1.html">第01集</a>
            <a href="/p/3.html">第03集</a>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/d.html")
        assertEquals(listOf(1, 2, 3), episodes.map { it.index })
    }

    @Test
    fun duplicateUrlsAreDeduplicated() {
        val html = """
            <a href="/p/1.html">第01集</a>
            <a href="/p/1.html">第01集</a>
            <a href="/p/2.html">第02集</a>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/d.html")
        assertEquals(2, episodes.size)
    }

    @Test
    fun sameIndexFromMultipleSourcesKeepsFirstOnly() {
        val html = """
            <a href="/s1/1.html">第01集</a>
            <a href="/s2/1.html">第01集</a>
            <a href="/s1/2.html">第02集</a>
        """.trimIndent()

        val episodes = EpisodeListParser.extract(html, "https://site.com/d.html")
        assertEquals(2, episodes.size)
        assertEquals(listOf(1, 2), episodes.map { it.index })
        assertEquals("https://site.com/s1/1.html", episodes[0].url)
    }

    // ==================== 页面类型识别 ====================

    @Test
    fun resolveReturnsEpisodeListWhenThreeOrMorePlayLinks() {
        val html = """
            <div class="playlist">
              <a href="/p/1.html">第01集</a><a href="/p/2.html">第02集</a><a href="/p/3.html">第03集</a>
            </div>
        """.trimIndent()
        val fetcher = FakeFetcher(mapOf("https://site.com/d.html" to page("https://site.com/d.html", html)))

        val result = WebLinkResolver.resolve("https://site.com/d.html", fetcher)

        assertTrue(result is WebLinkResult.EpisodeList)
        val list = result as WebLinkResult.EpisodeList
        assertEquals(3, list.episodes.size)
    }

    @Test
    fun oneOrTwoPlayLinksAreNotClassifiedAsEpisodeList() {
        val html = """
            <a href="/p/1.html">第01集</a>
            <a href="/p/2.html">第02集</a>
        """.trimIndent()
        val fetcher = FakeFetcher(mapOf("https://site.com/d.html" to page("https://site.com/d.html", html)))

        val result = WebLinkResolver.resolve("https://site.com/d.html", fetcher)

        assertTrue(result is WebLinkResult.Failure)
        assertEquals(WebLinkFailureKind.NO_STREAM, (result as WebLinkResult.Failure).kind)
    }

    @Test
    fun homepageWithManyMovieLinksIsRecognized() {
        val links = (1..8).joinToString("") { n ->
            """<a href="/dianshiju/guoju/$n.html">影片$n</a>"""
        }
        val fetcher = FakeFetcher(mapOf("https://site.com/dianshiju/" to page("https://site.com/dianshiju/", links)))

        val result = WebLinkResolver.resolve("https://site.com/dianshiju/", fetcher)

        assertTrue(result is WebLinkResult.Failure)
        val failure = result as WebLinkResult.Failure
        assertEquals(WebLinkFailureKind.HOME_PAGE, failure.kind)
        assertEquals("这是站点首页，请给我某个剧的页面", failure.message)
    }

    @Test
    fun homepagePaginationLinksAreNotTreatedAsEpisodes() {
        // 真实 empirecms 首页：影片链接 + 分页（index_N.html + 纯数字页码），都不能当集数。
        val movies = (1..8).joinToString("") { """<a href="/dianshiju/guoju/$it.html">影片$it</a>""" }
        val paging = """<a href="/dianshiju/index_2.html">2</a><a href="/dianshiju/index_3.html">3</a><a href="/dianshiju/index_405.html">尾页</a>"""
        val fetcher = FakeFetcher(mapOf("https://site.com/dianshiju/" to page("https://site.com/dianshiju/", movies + paging)))

        val result = WebLinkResolver.resolve("https://site.com/dianshiju/", fetcher)

        assertTrue(result is WebLinkResult.Failure)
        assertEquals(WebLinkFailureKind.HOME_PAGE, (result as WebLinkResult.Failure).kind)
    }

    @Test
    fun looksLikeHomepageNeedsEnoughDistinctContentLinks() {
        val many = (1..6).joinToString("") { """<a href="/m/$it.html">片$it</a>""" }
        assertTrue(EpisodeListParser.looksLikeHomepage(many, "https://site.com/", emptySet()))

        val few = """<a href="/m/1.html">片1</a><a href="/m/2.html">片2</a>"""
        assertFalse(EpisodeListParser.looksLikeHomepage(few, "https://site.com/", emptySet()))
    }

    @Test
    fun dramaTitleStripsSiteSuffix() {
        assertEquals("璀璨之城", EpisodeListParser.dramaTitle("璀璨之城-新版6v电影（旧版66影视）- 免费电影下载"))
        assertEquals("流浪地球2", EpisodeListParser.dramaTitle("流浪地球2|高清在线观看"))
        assertEquals("某剧", EpisodeListParser.dramaTitle("某剧"))
    }

    @Test
    fun episodeName属性里的高亮标签不会污染集名() {
        val html = """
            <html><body>
            <div class="playlist"><ul>
              <li><a href="/p/1.html" title="<font color='red'>第01集</font>"><font color='red'>第01集</font></a></li>
              <li><a href="/p/2.html" title="第02集">第02集</a></li>
              <li><a href="/p/3.html">第03集</a></li>
            </ul></div>
            </body></html>
        """.trimIndent()
        val episodes = EpisodeListParser.extract(html, "https://x.example.com/dianshiju/1.html")
        assertEquals(listOf("第01集", "第02集", "第03集"), episodes.map { it.name })
        assertEquals(listOf(1, 2, 3), episodes.map { it.index })
    }

    @Test
    fun dramaTitle会先洗掉HTML残渣() {
        assertEquals("我不是大师[全集]", EpisodeListParser.dramaTitle("我不是大师[全集]\">我不是大师[全集] - 6v电影"))
        assertEquals("我不是大师[全集]", EpisodeListParser.dramaTitle("<font color='red'>我不是大师</font>[全集] | 6v电影"))
    }
}
