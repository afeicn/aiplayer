package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三类 CMS「搜索页 → 结果条目」解析单测：帝国CMS（empirecms 的 li.post）、MacCMS HTML、
 * WordPress；外加详情链接判定与关键词回显判定。注入假 HTML，不联网。
 */
class SearchResultsParserTest {

    private val base = "https://cms.example.com"

    private val empireHtml = """
        <div class="list">
          <li class="post box row fixed-hight">
            <div class="article">
              <h2><a href="/kehuanpian/20830.html" rel="bookmark" title="流浪地球2">流浪地球2</a></h2>
              <div class="entry_post"><p>◎年　　代　2023◎产　　地　中国大陆</p></div>
            </div>
          </li>
          <li class="post box row fixed-hight">
            <div class="article">
              <h2><a href="/dianshiju/guoju/12320.html" rel="bookmark" title="庆余年">庆余年</a></h2>
              <div class="entry_post"><p>◎年　　代　2019◎产　　地　中国大陆</p></div>
            </div>
          </li>
        </div>
    """.trimIndent()

    @Test
    fun parsesEmpireCmsLiPostBlocks() {
        val items = SearchResultsParser.extract(empireHtml, base)
        assertEquals(2, items.size)
        assertEquals("流浪地球2", items[0].title)
        assertEquals("$base/kehuanpian/20830.html", items[0].url)
        assertEquals("2023", items[0].year)
        assertEquals("中国大陆", items[0].area)
        assertEquals("庆余年", items[1].title)
        assertEquals("$base/dianshiju/guoju/12320.html", items[1].url)
    }

    @Test
    fun parsesMacCmsHtmlLinks() {
        val html = """
            <ul class="search-list">
              <li><a href="/index.php/vod/detail/id/123.html">某剧</a></li>
              <li><a href="/voddetail/456.html">另一剧</a></li>
              <li><a href="/index.html">首页（应被过滤）</a></li>
            </ul>
        """.trimIndent()
        val items = SearchResultsParser.extractMacCms(html, "https://mac.example.com")
        assertEquals(2, items.size)
        assertEquals("某剧", items[0].title)
        assertEquals("https://mac.example.com/index.php/vod/detail/id/123.html", items[0].url)
        assertEquals("另一剧", items[1].title)
    }

    @Test
    fun parsesWordPressEntryTitles() {
        val html = """
            <article>
              <h2 class="entry-title"><a href="https://wp.example.com/2024/01/movie-a.html">电影A</a></h2>
            </article>
            <h2 class="entry-title"><a href="/2024/02/movie-b.html">电影B</a></h2>
        """.trimIndent()
        val items = SearchResultsParser.extractWordPress(html, "https://wp.example.com")
        assertEquals(2, items.size)
        assertEquals("电影A", items[0].title)
        assertEquals("电影B", items[1].title)
        assertEquals("https://wp.example.com/2024/02/movie-b.html", items[1].url)
    }

    @Test
    fun looksLikeDetailLinkFiltersNavAndAssets() {
        assertTrue(SearchResultsParser.looksLikeDetailLink("/kehuanpian/20830.html"))
        assertTrue(SearchResultsParser.looksLikeDetailLink("/voddetail/456.html"))
        assertFalse(SearchResultsParser.looksLikeDetailLink("/kehuanpian/"))
        assertFalse(SearchResultsParser.looksLikeDetailLink("/static/app.js"))
        assertFalse(SearchResultsParser.looksLikeDetailLink("javascript:void(0)"))
        assertFalse(SearchResultsParser.looksLikeDetailLink("https://cdn.example.com/logo.png"))
    }

    @Test
    fun containsKeywordHandlesFontHighlightSplit() {
        val html = "搜索到 <font color='red'>流浪</font>地球 相关结果"
        assertTrue(SearchResultsParser.containsKeyword(html, "流浪地球"))
        assertFalse(SearchResultsParser.containsKeyword(html, "三体"))
    }

    @Test
    fun extractReturnsEmptyForBlankHtml() {
        assertEquals(emptyList<SearchResultItem>(), SearchResultsParser.extract("", base))
    }

    @Test
    fun 真实empirecms搜索结果页里的脏标题只取纯文本() {
        // 真机事故来源：搜索页把高亮标签塞进 title 属性，旧正则会在属性值里的 `>` 处截断，
        // 把 `[全集]">…` 当成标题存进下载记录。这里用真实结果页做回归。
        val html = javaClass.getResourceAsStream("/empirecms-search-result.html")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val items = SearchResultsParser.extract(html, base)
        assertTrue(items.isNotEmpty())
        val target = items.first { it.url.contains("29683") }
        assertEquals("我不是大师[全集]", target.title)
        assertEquals("$base/dianshiju/guoju/29683.html", target.url)
        // 不允许任何条目带 HTML 残渣
        assertTrue(items.none { it.title.contains("<") || it.title.contains("\">") })
    }

    @Test
    fun title属性带高亮标签时取纯文本且优先于封面图内文() {
        val html = """
            <li class="post">
              <div class="thumbnail">
                <a href="/dianshiju/guoju/29683.html" title="<font color='red'>我不是大师</font>[全集]">
                  <img src="https://tu.example.com/1.jpg" alt="<font color='red'>我不是大师</font>[全集]" />
                </a>
              </div>
              <div class="article">
                <h2><a href="/dianshiju/guoju/29683.html" title="<font color='red'>我不是大师</font>[全集]"><font color='red'>我不是大师</font>[全集]</a></h2>
              </div>
            </li>
        """.trimIndent()
        val items = SearchResultsParser.extract(html, base)
        assertEquals(1, items.size)
        assertEquals("我不是大师[全集]", items[0].title)
        assertEquals("$base/dianshiju/guoju/29683.html", items[0].url)
    }
}
