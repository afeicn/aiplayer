package com.afeicn.aiplayer

import org.json.JSONObject

/**
 * 订阅源的「搜索规则」数据模型。
 *
 * 一个源怎么被搜索、结果怎么解析，全由 [SearchRule] 描述；既支持「自动探测」得到的规则，
 * 也支持用户手填的 `{kw}` 模板。只依赖字符串与 org.json，便于纯 JVM 单测。
 */

/** 源的类型：视频源走 HLS/流解析，音频源解析直链（.mp3/.m4a/.flac/.aac 等）。 */
enum class SourceType(val label: String) { VIDEO("视频"), AUDIO("音频") }

/** 搜索规则的类型，决定「怎么发请求、用什么解析结果」。 */
enum class SearchRuleKind(val label: String) {
    /** 帝国CMS 首页表单（`/e/search/so.php` POST → 302 → GET result）。 */
    EMPIRE_FORM("帝国CMS表单"),
    /** 帝国CMS 标准 GET 搜索路径（`/e/search/?keyboard={kw}` 等）。 */
    EMPIRE_GET("帝国CMS"),
    /** MacCMS HTML 搜索页（`/index.php/vod/search.html?wd={kw}`）。 */
    MACCMS("MacCMS"),
    /** WordPress 站内搜索（`/?s={kw}`）。 */
    WORDPRESS("WordPress"),
    /** MacCMS V10 JSON 接口（`/api.php/provide/vod/?ac=detail&wd={kw}`），走旧 JSON 解析。 */
    MAC_CMS_API("MacCMS接口"),
    /** 用户手填的 `{kw}` 模板。 */
    CUSTOM("自定义模板")
}

enum class SearchMethod { GET, POST }

/**
 * 一条搜索规则。
 *
 * - GET 规则：[urlTemplate] 里带 `{kw}` 占位符（或 `{base}` 站点基址），直接替换关键词。
 * - POST 规则（帝国CMS 表单）：[urlTemplate] 是表单 action，[keywordParam] 是关键词输入框的
 *   name，[staticParams] 是表单隐藏字段；POST 返回 302 时用 GET 跟随 Location 取结果页
 *   （对 302 直接重发 POST 会被服务器以 411 Length Required 拒绝）。
 */
data class SearchRule(
    val kind: SearchRuleKind,
    val method: SearchMethod = SearchMethod.GET,
    val urlTemplate: String = "",
    val keywordParam: String = "wd",
    val staticParams: Map<String, String> = emptyMap(),
    val followRedirectAsGet: Boolean = false,
    val resultParser: String = "auto"
) {
    /** 手填模板规则（含 `{kw}` 占位符）。 */
    val isManual: Boolean get() = kind == SearchRuleKind.CUSTOM

    /** 展示用的一句话描述（设置页 / 探测结果）。 */
    val summary: String get() = when (kind) {
        SearchRuleKind.CUSTOM -> "自定义模板：${urlTemplate}"
        SearchRuleKind.MAC_CMS_API -> "MacCMS 接口（JSON）"
        else -> "${kind.label}（${method.name}${if (urlTemplate.isNotBlank()) " $urlTemplate" else ""}）"
    }

    companion object {
        fun manual(template: String): SearchRule = SearchRule(
            kind = SearchRuleKind.CUSTOM,
            method = SearchMethod.GET,
            urlTemplate = template.trim(),
            keywordParam = "kw",
            resultParser = "auto"
        )

        /** MacCMS JSON 接口的固定规则（历史行为，兼容旧源）。 */
        fun macCmsApi(): SearchRule = SearchRule(
            kind = SearchRuleKind.MAC_CMS_API,
            method = SearchMethod.GET,
            urlTemplate = "{base}/api.php/provide/vod/?ac=detail&wd={kw}",
            keywordParam = "wd",
            resultParser = "json"
        )

        fun toJson(rule: SearchRule?): String? {
            rule ?: return null
            val static = JSONObject()
            rule.staticParams.forEach { (k, v) -> static.put(k, v) }
            return JSONObject()
                .put("kind", rule.kind.name)
                .put("method", rule.method.name)
                .put("urlTemplate", rule.urlTemplate)
                .put("keywordParam", rule.keywordParam)
                .put("staticParams", static)
                .put("followRedirectAsGet", rule.followRedirectAsGet)
                .put("resultParser", rule.resultParser)
                .toString()
        }

        fun fromJson(raw: String?): SearchRule? {
            raw ?: return null
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val kind = runCatching { SearchRuleKind.valueOf(obj.optString("kind")) }.getOrNull() ?: return null
            val method = runCatching { SearchMethod.valueOf(obj.optString("method")) }.getOrElse { SearchMethod.GET }
            val staticObj = obj.optJSONObject("staticParams")
            val static = if (staticObj == null) emptyMap() else buildMap {
                staticObj.keys().forEach { key -> put(key, staticObj.optString(key)) }
            }
            return SearchRule(
                kind = kind,
                method = method,
                urlTemplate = obj.optString("urlTemplate"),
                keywordParam = obj.optString("keywordParam").ifBlank { "wd" },
                staticParams = static,
                followRedirectAsGet = obj.optBoolean("followRedirectAsGet"),
                resultParser = obj.optString("resultParser").ifBlank { "auto" }
            )
        }
    }
}
