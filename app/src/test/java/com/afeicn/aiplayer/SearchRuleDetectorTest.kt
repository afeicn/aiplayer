package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索规则自动探测的纯逻辑单测：empirecms 帝国CMS 表单解析、候选规则优先级、
 * 手填 {kw} 模板替换、POST body / GET URL 构造、相对地址补全。不联网。
 */
class SearchRuleDetectorTest {

    private val empirecmsHome = """
        <html><head><title>6v电影</title></head><body>
        <form onsubmit="return checkSearchForm()" method="post" action="/e/search/so.php">
            <input type="hidden" name="show" value="title">
            <input type="hidden" name="tempid" value="1">
            <input type="hidden" name="tbname" value="article">
            <input type="hidden" name="mid" value="1">
            <input type="hidden" name="dopost" value="search">
            <input name="keyboard" type="text" class="input" id="keyboard" placeholder="请输入电影名">
            <input type="submit" value="搜索">
        </form>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesEmpireCmsEmpireCmsSearchForm() {
        val form = SearchRuleDetector.parseForm(empirecmsHome)
        assertNotNull(form)
        form!!
        assertEquals("/e/search/so.php", form.action)
        assertEquals(SearchMethod.POST, form.method)
        assertEquals("keyboard", form.keywordParam)
        assertEquals("title", form.hiddenFields["show"])
        assertEquals("article", form.hiddenFields["tbname"])
        assertEquals("1", form.hiddenFields["mid"])
        assertEquals("search", form.hiddenFields["dopost"])
    }

    @Test
    fun candidateRulesPutFormFirstThenStandardPaths() {
        val rules = SearchRuleDetector.candidateRules("https://cms.example.com", empirecmsHome)
        assertTrue(rules.isNotEmpty())
        val first = rules.first()
        assertEquals(SearchRuleKind.EMPIRE_FORM, first.kind)
        assertEquals(SearchMethod.POST, first.method)
        assertEquals("https://cms.example.com/e/search/so.php", first.urlTemplate)
        assertTrue(first.followRedirectAsGet)
        assertEquals("empire", first.resultParser)
        // 后面跟着标准路径候选，且不重复
        val kinds = rules.map { it.kind }
        assertTrue(kinds.contains(SearchRuleKind.EMPIRE_GET))
        assertTrue(kinds.contains(SearchRuleKind.MACCMS))
        assertTrue(kinds.contains(SearchRuleKind.WORDPRESS))
        assertEquals(rules.size, rules.distinctBy { it.urlTemplate + it.method }.size)
    }

    @Test
    fun noFormGivesStandardPathsOnly() {
        val rules = SearchRuleDetector.candidateRules("https://example.com", "<html></html>")
        assertTrue(rules.none { it.kind == SearchRuleKind.EMPIRE_FORM })
        assertTrue(rules.any { it.kind == SearchRuleKind.EMPIRE_GET })
        assertTrue(rules.any { it.kind == SearchRuleKind.MACCMS })
    }

    @Test
    fun manualTemplateSubstitutesKwAndBase() {
        val rule = SearchRule.manual("https://my.vod.com/search?q={kw}")
        val url = SearchRuleDetector.substituteTemplate(rule.urlTemplate, "https://my.vod.com", "流浪地球")
        assertEquals("https://my.vod.com/search?q=" + java.net.URLEncoder.encode("流浪地球", "UTF-8"), url)

        val baseUrl = SearchRuleDetector.substituteTemplate("{base}/s/{kw}", "https://x.com/", "测试")
        assertEquals("https://x.com/s/" + java.net.URLEncoder.encode("测试", "UTF-8"), baseUrl)
    }

    @Test
    fun buildPostBodyIncludesHiddenFieldsAndKeyword() {
        val rule = SearchRule(
            kind = SearchRuleKind.EMPIRE_FORM,
            method = SearchMethod.POST,
            keywordParam = "keyboard",
            staticParams = mapOf("show" to "title", "tempid" to "1", "tbname" to "article", "mid" to "1", "dopost" to "search")
        )
        val body = SearchRuleDetector.buildPostBody(rule, "流浪地球")
        assertTrue(body.contains("keyboard=" + java.net.URLEncoder.encode("流浪地球", "UTF-8")))
        assertTrue(body.contains("show=title"))
        assertTrue(body.contains("tbname=article"))
        assertTrue(body.contains("dopost=search"))
    }

    @Test
    fun getRuleWithStaticParamsAppendsQuery() {
        val rule = SearchRule(
            kind = SearchRuleKind.CUSTOM,
            method = SearchMethod.GET,
            urlTemplate = "https://x.com/search?q={kw}",
            staticParams = mapOf("type" to "1")
        )
        val url = SearchRuleDetector.buildGetUrl(rule, "https://x.com", "测试")
        assertTrue(url.startsWith("https://x.com/search?q="))
        assertTrue(url.contains("type=1"))
    }

    @Test
    fun resolveRelativeAgainstBase() {
        assertEquals(
            "https://cms.example.com/e/search/result/?searchid=18708",
            SearchRuleDetector.resolve("https://cms.example.com/e/search/so.php", "result/?searchid=18708")
        )
        // 绝对地址幂等
        assertEquals("https://a.com/x", SearchRuleDetector.resolve("https://a.com", "https://a.com/x"))
    }

    @Test
    fun parseFormAbsentWhenNoForm() {
        assertNull(SearchRuleDetector.parseForm("<html><body>no form</body></html>"))
    }
}
