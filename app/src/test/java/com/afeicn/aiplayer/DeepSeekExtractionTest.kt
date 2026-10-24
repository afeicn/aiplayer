package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 兜底层的纯逻辑单测：模型输出 → 校验后的链接/规则。
 *
 * 重点是校验闸门：模型产出的地址不满足条件就丢弃，不盲信。
 */
class DeepSeekExtractionTest {

    private val base = "https://cms.example.com"

    @Test
    fun searchRulesKeepOnlyTemplatesWithKeywordPlaceholder() {
        val raw = """
            {"items":[
              {"url":"https://cms.example.com/vodsearch/{kw}----------.html","keywordParam":"kw"},
              {"url":"https://cms.example.com/index.php/vod/search.html?wd={keyword}"},
              {"url":"https://cms.example.com/index.php/vod/search.html"}
            ]}
        """.trimIndent()

        val rules = DeepSeekResponseParser.parseSearchRules(raw, base)

        // 没有 {kw}/{keyword} 占位符的模板没法替换关键词，丢弃。
        assertEquals(2, rules.size)
        assertEquals("{kw}", rules[0].urlTemplate.substringAfter("vodsearch/").substringBefore("-"))
        assertTrue(rules[1].urlTemplate.contains("{kw}"))
    }

    @Test
    fun relativeTemplatesAreAbsolutizedAgainstSiteBase() {
        val raw = """{"items":[{"url":"/e/search/?keyboard={kw}"}]}"""

        val rules = DeepSeekResponseParser.parseSearchRules(raw, base)

        assertEquals(1, rules.size)
        assertEquals("https://cms.example.com/e/search/?keyboard={kw}", rules[0].urlTemplate)
    }

    @Test
    fun crossHostTemplatesAreRejected() {
        // 模型常顺手给出它记得的公共 CMS 接口域名——那不是用户添加的这个源。
        val raw = """
            {"items":[
              {"url":"https://other.example.com/vodsearch/{kw}.html"},
              {"url":"https://cms.example.com/vodsearch/{kw}.html"}
            ]}
        """.trimIndent()

        val rules = DeepSeekResponseParser.parseSearchRules(raw, base)

        assertEquals(1, rules.size)
        assertTrue(rules[0].urlTemplate.startsWith(base))
    }

    @Test
    fun malformedOrEmptyModelOutputYieldsNoRules() {
        assertTrue(DeepSeekResponseParser.parseSearchRules("", base).isEmpty())
        assertTrue(DeepSeekResponseParser.parseSearchRules("模型开始解释了：我认为这个站不能用。", base).isEmpty())
        assertTrue(DeepSeekResponseParser.parseSearchRules("""{"items":[]}""", base).isEmpty())
    }

    @Test
    fun codeFencedJsonIsStillParsed() {
        val raw = "```json\n{\"items\":[{\"url\":\"$base/vodsearch/{kw}.html\"}]}\n```"

        assertEquals(1, DeepSeekResponseParser.parseSearchRules(raw, base).size)
    }

    @Test
    fun duplicateTemplatesAreDeduped() {
        val raw = """
            {"items":[
              {"url":"$base/vodsearch/{kw}.html"},
              {"url":"$base/vodsearch/{kw}.html"}
            ]}
        """.trimIndent()

        assertEquals(1, DeepSeekResponseParser.parseSearchRules(raw, base).size)
    }

    @Test
    fun parsedRulesAreManualKindAndCarryKeywordParam() {
        val raw = """{"items":[{"url":"$base/search.php?searchword={kw}","keywordParam":"searchword"}]}"""

        val rule = DeepSeekResponseParser.parseSearchRules(raw, base).single()

        assertEquals(SearchRuleKind.CUSTOM, rule.kind)
        assertEquals(SearchMethod.GET, rule.method)
        assertEquals("searchword", rule.keywordParam)
    }

    @Test
    fun blankApiKeyNeverCallsTransport() {
        var calls = 0
        val client = DeepSeekClient(
            transport = LlmTransport { _, _, _, _ -> calls++; "{}" }, apiKey = "", model = "deepseek-chat"
        )

        assertTrue(client.suggestSearchRules("https://x.com", "<html></html>", base).isEmpty())
        assertTrue(client.extractResults("三体", "<html></html>", base).isEmpty())
        assertNull(client.suggestSiteName("https://x.com", "<html></html>"))
        assertEquals(0, calls)
    }

    @Test
    fun siteNameParsesValidName() {
        assertEquals("6v电影", DeepSeekResponseParser.parseSiteName("""{"name":"6v电影"}"""))
        assertEquals("片吧", DeepSeekResponseParser.parseSiteName("```json\n{\"name\":\" 片吧 \"}\n```"))
    }

    @Test
    fun siteNameRejectsBlankOversizedAndNonJson() {
        assertNull(DeepSeekResponseParser.parseSiteName("""{"name":""}"""))
        assertNull(DeepSeekResponseParser.parseSiteName("""{"name":"${"很".repeat(31)}"}"""))
        assertNull(DeepSeekResponseParser.parseSiteName("这个站叫6v电影"))
    }

    @Test
    fun suggestSiteNameUsesTransportWhenKeySet() {
        val client = DeepSeekClient(
            transport = LlmTransport { _, _, _, _ -> """{"name":"6v电影"}""" },
            apiKey = "k", model = "deepseek-chat"
        )
        assertEquals("6v电影", client.suggestSiteName("https://cms.example.com", "<html><title>x</title></html>"))
        assertNull(client.suggestSiteName("https://cms.example.com", ""))
    }
}
