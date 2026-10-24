package com.afeicn.aiplayer

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * 「确定性解析失败 → 把 HTML 交给 DeepSeek 提结构化链接」的纯逻辑层。
 *
 * 只做两件事：① 组给模型的 prompt（含 HTML 预处理省 token）；② 把模型返回的 JSON 解析、
 * 校验成与现有解析器同构的链接（搜索结果 [ExtractedResult] / 剧集 [ExtractedEpisode]）。
 * 不碰网络、不碰 Android 框架，纯 JVM 可单测（注入假模型输出）。
 *
 * 校验原则：标题/集名非空、URL 能按 base 补成绝对地址、host 与 base 同域——不满足直接丢弃，
 * 不盲信模型产出的地址。
 */

/** 一条 AI 从搜索结果页提出来的影片：标题 + 详情页绝对地址。 */
data class ExtractedResult(val title: String, val url: String)

/** 一条 AI 从详情页提出来的剧集：集名 + 播放页绝对地址。 */
data class ExtractedEpisode(val name: String, val url: String)

object DeepSeekPrompt {
    /** 送模型的 HTML 上限（字符），超出截断，省 token、控延迟。 */
    const val MAX_HTML_CHARS = 10_000

    private val SCRIPT = Regex("""<script\b.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE = Regex("""<style\b.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("""<!--.*?-->""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val WS = Regex("""\s{2,}""")

    /** 剥脚本/样式/注释、折叠空白、截断——保留链接与文本骨架即可，不必送完整页面。 */
    fun preprocessHtml(html: String): String {
        val stripped = COMMENT.replace(STYLE.replace(SCRIPT.replace(html, " "), " "), " ")
        val collapsed = WS.replace(stripped, " ").trim()
        return if (collapsed.length <= MAX_HTML_CHARS) collapsed else collapsed.substring(0, MAX_HTML_CHARS)
    }

    /** 搜索结果页：让模型提「匹配关键词的影片详情页链接」。 */
    fun searchSystem(): String =
        "你是网页解析助手。用户给你一段视频站点的搜索结果页 HTML 和一个关键词。" +
            "找出该页里对应该关键词（或高度相关）的影片的【详情页链接】。" +
            "只输出 JSON 对象，形如 {\"items\":[{\"title\":\"影片名\",\"url\":\"详情页绝对或相对链接\"}]}，" +
            "不要解释、不要多余文本。没有匹配就输出 {\"items\":[]}。url 必须是页面里 href 的真实值。"

    fun searchUser(keyword: String, html: String): String =
        "关键词：$keyword\n\n搜索结果页 HTML：\n${preprocessHtml(html)}"

    /** 详情页：让模型提「每一集的播放页链接」。 */
    fun episodeSystem(): String =
        "你是网页解析助手。用户给你一段视频站点的影片详情页 HTML 和影片名。" +
            "找出该页里每一集的【播放页链接】（点开能播放的那种页面 URL，如 /vodplay/… 或带 pathid 的播放地址）。" +
            "按集数顺序，只输出 JSON 对象，形如 {\"items\":[{\"name\":\"第1集\",\"url\":\"播放页链接\"}]}，" +
            "不要解释、不要多余文本。没有就输出 {\"items\":[]}。url 必须是页面里 href 的真实值。"

    fun episodeUser(title: String, html: String): String =
        "影片名：$title\n\n详情页 HTML：\n${preprocessHtml(html)}"

    /**
     * 用户粘贴的那一页（首页/列表页/详情页/播放页都可能）→ 让模型提「站内搜索入口的 URL 模板」。
     *
     * 模型只负责提候选，不判断可用性——[SourceProber.probeWithAi] 会逐条真发请求验证。
     * 因此这里要的是「带 {kw} 占位符的地址规律」，而不是「这个站能不能用」的结论。
     */
    fun searchRuleSystem(): String =
        "你是网站结构分析助手。用户给你一段视频站点的页面 HTML 和它的地址。" +
            "这个页面可能是首页、分类列表页、影片详情页或播放页，不一定是首页。" +
            "你的任务是找出该【站点】的站内搜索入口，写成带 {kw} 占位符的 URL 模板，{kw} 会被替换成 URL 编码后的关键词。" +
            "优先给页面里真实出现的搜索表单 action（通常在页头/页尾，播放页和详情页上也有）或站内链接规律；" +
            "若是苹果CMS/MacCMS 站点，常见形如 https://站点根/index.php/vod/search.html?wd={kw} 或 /vodsearch/{kw}----------.html；" +
            "若是帝国CMS，常见形如 /e/search/?keyboard={kw}。" +
            "只输出 JSON 对象，形如 {\"items\":[{\"url\":\"绝对地址，必须含 {kw}\",\"keywordParam\":\"wd\"}]}，" +
            "url 必须属于该页面所在的站点（同一个域名），按最可能可用排序，最多 5 条，不要解释。" +
            "看不出搜索入口就输出 {\"items\":[]}。不要编造页面里没有依据的地址。"

    /** [pageUrl] 是用户粘的地址，[html] 是把该页与站点根抓到的正文拼在一起。 */
    fun searchRuleUser(pageUrl: String, html: String): String =
        "页面地址：$pageUrl\n\n页面 HTML：\n${preprocessHtml(html)}"

    /** 站点页面 → 简短站名。名称纯展示，不参与任何判定，模型认不出就退回标题/域名。 */
    fun siteNameSystem(): String =
        "你是视频站点助手。用户给你一段视频站点的页面 HTML 和它的地址，给出这个站点简短的名称" +
            "（不超过 12 个字，去掉「官网」「最新」「在线观看」之类的营销后缀，优先用页面里的品牌名）。" +
            "只输出 JSON 对象 {\"name\":\"...\"}，认不出就输出 {\"name\":\"\"}，不要解释。"

    fun siteNameUser(pageUrl: String, html: String): String =
        "页面地址：$pageUrl\n\n页面 HTML（截取）：\n${preprocessHtml(html).take(4000)}"
}

object DeepSeekResponseParser {

    /** 解析搜索结果：返回校验通过的 [ExtractedResult]，按 url 去重。 */
    fun parseResults(raw: String, base: String): List<ExtractedResult> =
        parseItems(raw, base, titleKey = "title", urlKey = "url")
            .map { ExtractedResult(it.first, it.second) }

    /** 解析剧集：返回校验通过的 [ExtractedEpisode]，按 url 去重。 */
    fun parseEpisodes(raw: String, base: String): List<ExtractedEpisode> =
        parseItems(raw, base, titleKey = "name", urlKey = "url")
            .map { ExtractedEpisode(it.first, it.second) }

    /**
     * 解析「搜索入口模板」：只保留带 `{kw}` 占位符、能按 base 补成绝对地址、且与 base 同域的候选。
     *
     * 同域这条是硬闸门——模型常顺手给出别的站点的地址（甚至它记得的知名 CMS 公共接口），
     * 那种地址即便能返回结果也不是用户添加的这个源。跨域候选一律丢弃。
     */
    fun parseSearchRules(raw: String, base: String): List<SearchRule> {
        val array = locateArray(raw) ?: return emptyList()
        val out = LinkedHashMap<String, SearchRule>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val template = normalizeTemplate(obj.optString("url"))
            if (template.isBlank() || !template.contains("{kw}")) continue
            val abs = absolutize(template, base)
            if (abs.isBlank() || !sameHost(base, abs)) continue
            val keywordParam = obj.optString("keywordParam").trim().ifBlank { "kw" }
            out.putIfAbsent(
                abs,
                SearchRule(
                    kind = SearchRuleKind.CUSTOM,
                    method = SearchMethod.GET,
                    urlTemplate = abs,
                    keywordParam = keywordParam,
                    resultParser = "auto"
                )
            )
        }
        return out.values.toList()
    }

    /** `{keyword}` 与 `{kw}` 同义（手填模板两种都收），顺手把首尾空白清掉。 */
    private fun normalizeTemplate(raw: String): String = raw.trim().replace("{keyword}", "{kw}")

    /** 解析站名：空串、超长（模型开始写作文）都算认不出。 */
    fun parseSiteName(raw: String): String? {
        val obj = runCatching { JSONObject(stripCodeFence(raw)) }.getOrNull() ?: return null
        val name = obj.optString("name").trim()
        return name.takeIf { it.isNotBlank() && it.length <= 30 }
    }

    /**
     * 按 base 补成绝对地址。先临时替掉 `{kw}` 再走 [java.net.URL] 解析——花括号不是 URL 合法字符，
     * 某些解析路径会在这里翻车，替掉再放回来最稳。
     */
    private fun absolutize(template: String, base: String): String {
        val standIn = template.replace("{kw}", "probe")
        val abs = runCatching { WebLinkParser.resolveUrl(base, standIn) }.getOrDefault("")
        return abs.replace("probe", "{kw}")
    }

    private fun parseItems(raw: String, base: String, titleKey: String, urlKey: String): List<Pair<String, String>> {
        val array = locateArray(raw) ?: return emptyList()
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val title = obj.optString(titleKey).trim()
            val href = obj.optString(urlKey).trim()
            if (title.isBlank() || href.isBlank()) continue
            val abs = runCatching { WebLinkParser.resolveUrl(base, href) }.getOrDefault("")
            if (abs.isBlank() || !sameHost(base, abs)) continue
            out.putIfAbsent(abs, title to abs)
        }
        return out.values.toList()
    }

    /**
     * 从模型输出里定位 JSON：优先取 `{"items":[...]}` 里的数组；否则取第一个平衡的 `[...]`。
     * 容忍 ```json 代码围栏与前后夹带的解释文字。
     */
    private fun locateArray(raw: String): JSONArray? {
        val cleaned = stripCodeFence(raw)
        // 先试 {"items": [...]}
        runCatching { JSONObject(cleaned).optJSONArray("items") }.getOrNull()?.let { return it }
        // 退回：扫第一个平衡的数组子串
        val start = cleaned.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until cleaned.length) {
            val ch = cleaned[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '[' -> depth++
                !inString && ch == ']' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONArray(cleaned.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun stripCodeFence(raw: String): String {
        val fence = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
        fence.find(raw)?.let { return it.groupValues[1].trim() }
        return raw.trim()
    }

    private fun sameHost(base: String, url: String): Boolean = runCatching {
        URL(base).host.equals(URL(url).host, ignoreCase = true)
    }.getOrDefault(false)
}
