package com.afeicn.aiplayer

import java.net.URL
import java.net.URLEncoder

/**
 * 订阅源「搜索入口自动探测」的纯逻辑层。
 *
 * 用户只给站点主页/域名，本模块从首页 HTML 里发现搜索能力：
 *  1. 找到首页里的第一个 `<form>`（action + method + 隐藏字段 + 关键词输入框 name），按真实表单
 *     构造搜索规则（帝国CMS 表单 POST → 302 → GET 结果页）；
 *  2. 找不到表单时，按标准路径依次生成候选规则：帝国CMS GET → MacCMS → WordPress。
 * 候选规则按优先级排好，由 [SourceProber] 逐个试探直到命中。
 * 全部只依赖字符串与 java.net.URL，不碰 Android 框架，方便纯 JVM 单测。
 */

/** 从首页解析出的搜索表单。 */
data class DetectedForm(
    val action: String,
    val method: SearchMethod,
    val keywordParam: String,
    val hiddenFields: Map<String, String>
)

object SearchRuleDetector {

    /** 探测用关键词：足够常见、几乎所有站点都有结果。 */
    const val PROBE_KEYWORD = "测试"

    private val FORM_TAG = Regex("""<form\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val INPUT_TAG = Regex("""<input\b[^>]*/?>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z-]+)\s*=\s*["']([^"']*)["']""")

    /** 从首页 HTML 解析出第一个搜索表单（action + method + 隐藏字段 + 关键词输入框 name）。 */
    fun parseForm(html: String): DetectedForm? {
        val formMatch = FORM_TAG.find(html) ?: return null
        val region = html.substring(formMatch.range.first).substringBefore("</form>", "")
        val formAttrs = parseAttrs(formMatch.value)
        val action = formAttrs["action"] ?: return null
        val method = if (formAttrs["method"].equals("post", ignoreCase = true)) SearchMethod.POST else SearchMethod.GET
        val hidden = linkedMapOf<String, String>()
        var keywordParam = "keyboard"
        INPUT_TAG.findAll(region).forEach { match ->
            val attrs = parseAttrs(match.value)
            val name = attrs["name"] ?: return@forEach
            val type = (attrs["type"] ?: "text").lowercase()
            when {
                type == "hidden" -> hidden[name] = attrs["value"].orEmpty()
                type in setOf("submit", "button", "image", "reset") -> return@forEach
                else -> keywordParam = name
            }
        }
        return DetectedForm(action, method, keywordParam, hidden)
    }

    /**
     * 按优先级生成候选规则：首页表单（若有）→ 帝国CMS GET → MacCMS → WordPress。
     * 表单不存在时 [homepageHtml] 可为空，直接给标准路径。
     */
    fun candidateRules(baseUrl: String, homepageHtml: String): List<SearchRule> {
        val base = baseUrl.trim().trimEnd('/')
        val rules = mutableListOf<SearchRule>()
        parseForm(homepageHtml)?.let { form ->
            val action = resolve(base, form.action)
            rules += if (form.method == SearchMethod.POST) {
                SearchRule(
                    kind = SearchRuleKind.EMPIRE_FORM,
                    method = SearchMethod.POST,
                    urlTemplate = action,
                    keywordParam = form.keywordParam,
                    staticParams = form.hiddenFields,
                    followRedirectAsGet = true,
                    resultParser = "empire"
                )
            } else {
                SearchRule(
                    kind = SearchRuleKind.CUSTOM,
                    method = SearchMethod.GET,
                    urlTemplate = action,
                    keywordParam = form.keywordParam,
                    staticParams = form.hiddenFields,
                    resultParser = "auto"
                )
            }
        }
        rules += listOf(
            SearchRule(SearchRuleKind.EMPIRE_GET, keywordParam = "keyboard", resultParser = "empire",
                urlTemplate = "$base/e/search/?keyboard={kw}"),
            SearchRule(SearchRuleKind.EMPIRE_GET, keywordParam = "keyboard", resultParser = "empire",
                urlTemplate = "$base/e/search/index.php?keyboard={kw}"),
            SearchRule(SearchRuleKind.EMPIRE_GET, keywordParam = "keyboard", resultParser = "empire",
                urlTemplate = "$base/search.php?keyboard={kw}"),
            SearchRule(SearchRuleKind.MACCMS, keywordParam = "wd", resultParser = "maccms",
                urlTemplate = "$base/index.php/vod/search.html?wd={kw}"),
            SearchRule(SearchRuleKind.MACCMS, keywordParam = "wd", resultParser = "maccms",
                urlTemplate = "$base/vodsearch/-------------.html?wd={kw}"),
            SearchRule(SearchRuleKind.WORDPRESS, keywordParam = "s", resultParser = "wordpress",
                urlTemplate = "$base/?s={kw}")
        )
        return rules.distinctBy { it.urlTemplate + it.method.name + it.staticParams.toString() }
    }

    /** 由规则构造 GET 请求地址（替换 `{kw}`，GET 表单的隐藏字段拼到 query 上）。 */
    fun buildGetUrl(rule: SearchRule, baseUrl: String, keyword: String): String {
        val url = substituteTemplate(rule.urlTemplate, baseUrl, keyword)
        if (rule.method == SearchMethod.GET && rule.staticParams.isNotEmpty()) {
            val query = rule.staticParams.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            return if (url.contains('?')) "$url&$query" else "$url?$query"
        }
        return url
    }

    /** 由规则构造 POST 请求体：隐藏字段 + 关键词字段。 */
    fun buildPostBody(rule: SearchRule, keyword: String): String =
        (rule.staticParams + (rule.keywordParam to keyword)).entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }

    /** 手填模板替换：`{base}` 站点基址、`{kw}`/`{keyword}` 关键词（URL 编码）。 */
    fun substituteTemplate(template: String, baseUrl: String, keyword: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return template
            .replace("{base}", base)
            .replace("{kw}", encoded)
            .replace("{keyword}", encoded)
    }

    /** 相对地址按基址补全成绝对地址（对绝对地址幂等）。 */
    fun resolve(baseUrl: String, href: String): String =
        runCatching { URL(URL(baseUrl), href.trim()).toString() }.getOrDefault(href)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parseAttrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
}
