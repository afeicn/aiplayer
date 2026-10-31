package com.afeicn.aiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 「订阅源」跨源搜索的纯逻辑 + 网络编排层。
 *
 * - [SearchTransport] 抽象单次 HTTP 请求（GET/POST，不自动跟随重定向），真实实现
 *   [HttpSearchTransport] 带桌面 UA + 可选 Referer；单测注入假 transport 不联网。
 * - [SourceProber] 是「入口探测状态机」：抓首页 → [SearchRuleDetector.candidateRules] 生成候选
 *   → 按顺序逐个试探，第一个命中（返回含关键词且 ≥1 详情链接）即成功；全部失败给可读提示。
 * - [CrossSourceSearchEngine.search] 并行搜所有启用源，单源独立超时，失败不影响其它源。
 *   成功但 0 命中算「没结果」，网络失败/非法响应算「失败」，两者分开汇报。
 */

data class SearchHttpRequest(
    val method: String,
    val url: String,
    val body: String = "",
    val referer: String = ""
)

data class SearchHttpResponse(
    val url: String,
    val status: Int,
    val body: String = "",
    val contentType: String = "",
    val location: String = ""
)

/** 单次 HTTP 请求抽象（不自动跟随重定向），测试注入假实现。 */
fun interface SearchTransport {
    fun execute(request: SearchHttpRequest): SearchHttpResponse
}

/**
 * 真实 HTTP 实现：桌面 UA + Referer，POST/GET 均可，不自动跟随重定向。
 *
 * Cloudflare 支持（两个参数都可选，默认 null → 与旧行为完全一致）：
 *  - [cookieStore]：每个请求按当前 host 取预热好的 cookie 放进 `Cookie` 头；
 *  - [warmer]：遇到「403 + Cloudflare 特征」时触发一次预热，成功后原请求重试一次。
 *    预热失败由门禁标记 challenge_failed，30 分钟内不会再次阻塞。
 */
class HttpSearchTransport(
    private val timeoutMs: Int = 10_000,
    private val cookieStore: CloudflareCookieStore? = null,
    private val warmer: (() -> CloudflareWarmup?)? = null
) : SearchTransport {
    override fun execute(request: SearchHttpRequest): SearchHttpResponse {
        // 一次请求最多因 Cloudflare 预热重试一次，避免挑战失败时死循环。
        var warmedOnce = false
        while (true) {
            val response = executeOnce(request)
            if (response.status == 403 && CloudflareChallenge.looksLikeChallenge(response.body)) {
                val warmerFactory = warmer
                if (warmerFactory != null && !warmedOnce) {
                    warmedOnce = true
                    val host = runCatching { URL(request.url).host }.getOrNull()
                    val warmed = runCatching {
                        !host.isNullOrBlank() && warmerFactory.invoke()?.warm(host) == true
                    }.getOrDefault(false)
                    if (warmed) continue
                }
            }
            return response
        }
    }

    private fun executeOnce(request: SearchHttpRequest): SearchHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            val host = runCatching { URL(request.url).host }.getOrNull()
            val cookie = host?.let { cookieStore?.cookieFor(it) }?.takeIf { it.isNotBlank() }
            // cf_clearance 与客户端 UA 绑定：带 cookie 时必须复用预热时的 UA，否则会被重新挑战。
            val userAgent = if (cookie != null) {
                host?.let { cookieStore?.userAgentFor(it) }?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_DESKTOP_UA
            } else {
                DEFAULT_DESKTOP_UA
            }
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (request.referer.isNotBlank()) connection.setRequestProperty("Referer", request.referer)
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            if (request.method.equals("POST", ignoreCase = true)) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val location = if (code in 300..399) connection.getHeaderField("Location").orEmpty() else ""
            val contentType = connection.contentType.orEmpty()
            val body = if (isTextContent(contentType)) {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.readBytes()?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF").orEmpty()
            } else ""
            return SearchHttpResponse(url = request.url, status = code, body = body, contentType = contentType, location = location)
        } catch (e: Exception) {
            return SearchHttpResponse(url = request.url, status = -1, body = "", contentType = "", location = "")
        } finally {
            connection.disconnect()
        }
    }

    private fun isTextContent(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.contains("text") || ct.contains("html") || ct.contains("json") ||
            ct.contains("javascript") || ct.contains("xml") || ct.isBlank()
    }
}

/**
 * 单条规则的试探结果。`body` 是抓到的原始正文，供确定性失败时喂给 AI 兜底。
 *
 * [httpStatus] / [finalUrl] 是「最后一跳」的真实状态码与地址（跟随重定向之后），
 * 让失败原因能写成「HTTP 403 / Cloudflare 拦截」而不是含糊的「响应异常」。
 * [networkFailure] 表示这次失败算源故障（连不上、被拦、5xx），404/零结果不算。
 */
data class SearchAttempt(
    val success: Boolean,
    val latencyMs: Long,
    val items: List<SearchResultItem> = emptyList(),
    val error: String? = null,
    val body: String = "",
    val httpStatus: Int = 0,
    val finalUrl: String = "",
    val networkFailure: Boolean = false
)

/** [SourceProber.executeSearch] 的返回：最后一跳的正文 + 状态码 + 最终地址。 */
data class SearchFetch(val body: String, val status: Int, val finalUrl: String)

/** 确定性解析失败时的 AI 兜底：从结果页 HTML 提影片链接。 */
fun interface AiSearchFallback {
    fun extractResults(keyword: String, html: String, base: String): List<ExtractedResult>
}

/**
 * 探测期间抓到的页面。
 *
 * [byBase] 按候选 base 索引（生成候选规则时要按 base 查对应 HTML，按抓的 URL 索引会查不到）；
 * [pastedPage] 是用户粘的那一页，喂给 AI 判断搜索入口用。
 * [baseAliases] 记录「候选 base → 跟随重定向后的真实 base」（用户填 `witzsb.com`，站点 301 到
 * `www.witzsb.com`）：候选规则必须按落地站拼，否则拼出的入口地址在错的域名上。
 */
data class ProbePages(
    val byBase: Map<String, String> = emptyMap(),
    val pastedPage: String = "",
    val baseAliases: Map<String, String> = emptyMap()
) {

    /** 给 AI 的证据：站点页 + 粘的那一页，去重后拼一起。 */
    fun evidence(): String =
        (byBase.values.toList() + pastedPage).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
}

/** 探测过程的一步状态：进行中 / 通过 / 不通过。 */
enum class ProbeStepStatus { RUNNING, OK, FAIL }

/** 探测过程中的一步事件，UI 按到达顺序渲染成会话流。 */
data class ProbeEvent(val label: String, val status: ProbeStepStatus)

/** 一次「入口探测」的结论：成功带上规则与命中数，失败带可读原因与最后抓到的正文。 */
data class SourceProbeOutcome(
    val source: OnlineVideoSource,
    val success: Boolean,
    val rule: SearchRule? = null,
    val latencyMs: Long = 0L,
    val itemCount: Int = 0,
    val items: List<SearchResultItem> = emptyList(),
    val error: String? = null,
    val lastBody: String = "",
    /** 成功时这条规则来自 AI 提的候选（已实测通过），供 UI 说明来源。 */
    val aiAssisted: Boolean = false,
    /** 探测过程中抓到的页面，供 AI 兜底复用，避免再抓一次。 */
    val pages: ProbePages = ProbePages()
)

object SourceProber {

    const val NO_ENTRY = "这个站没探测到搜索入口，源已存下，可稍后点「重新探测」再试。"
    const val AI_NO_HOMEPAGE = "AI 智能解析也没帮上：这个站连首页都抓不到，多半是打不开或被拦了。"
    const val AI_NO_RULE = "AI 也没看出这个站的搜索入口，它可能不是按关键词搜索的站点（比如纯 JS 渲染）。"
    /** 连不上/超时（transport 抛异常或自报 status = -1）时的文案。 */
    const val NETWORK_FAILURE = "网络失败或响应异常（连不上或超时）"

    private const val MAX_PAGE_REDIRECTS = 3
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val CLOUDFLARE_MARKERS = listOf("Cloudflare", "Attention Required", "cf-error", "cf-wrapper")
    private val DETAIL_PATH_MARKERS = listOf("/detail/", "/voddetail/", "/vod/detail/", "/index.php/vod/detail/")
    private val DETAIL_FILE = Regex("""/(?:\d+/)?\d+\.html$""")
    /** 搜索 302 到详情页但正文不含关键词：这个站基本没有关键词搜索，文案要说到点上。 */
    const val DETAIL_REDIRECT_NO_KEYWORD = "搜索跳转到了详情页，但内容不含关键词——这个站可能没有可搜索入口"

    /**
     * 抓页面 → 按站点根生成候选规则 → 逐个试探，第一个命中即返回。
     *
     * 关键：候选规则一律按 [SourceAddressRules.siteBase] 收敛后的站点根拼，不按用户粘的原始地址拼。
     * 用户粘播放页时，`原始地址 + /e/search/?keyboard=` 拼出来必然是 404，这是过去探测全失败的直接原因。
     */
    fun probe(
        source: OnlineVideoSource,
        transport: SearchTransport,
        keyword: String = SearchRuleDetector.PROBE_KEYWORD,
        onEvent: ((ProbeEvent) -> Unit)? = null
    ): SourceProbeOutcome {
        val address = SourceAddressRules.analyze(source.normalizedBase)
        onEvent?.invoke(ProbeEvent("抓取站点页面…", ProbeStepStatus.RUNNING))
        val pages = fetchPages(address, transport)
        val gotPages = pages.byBase.isNotEmpty() || pages.pastedPage.isNotBlank()
        onEvent?.invoke(ProbeEvent(
            if (gotPages) "抓到 ${pages.byBase.size} 个站点页面" else "站点页面抓不到，按常见入口试",
            if (gotPages) ProbeStepStatus.OK else ProbeStepStatus.FAIL
        ))
        var lastError = NO_ENTRY
        var informativeError: String? = null
        var lastBody = ""
        for (base in address.candidateBases()) {
            // 用户填的地址可能被站点 301 到别的 host（`witzsb.com` → `www.witzsb.com`），
            // 候选规则要按落地站拼，否则拼出来的搜索入口在错的域名上。
            val effective = pages.baseAliases[base] ?: base
            val homepage = pages.byBase[effective].orEmpty().ifBlank { pages.byBase[base].orEmpty() }
            val rules = SearchRuleDetector.candidateRules(effective, homepage)
            for (rule in rules) {
                onEvent?.invoke(ProbeEvent("试搜 ${rule.summary}…", ProbeStepStatus.RUNNING))
                val attempt = attemptRule(rule, effective, keyword, transport)
                if (attempt.success) {
                    onEvent?.invoke(ProbeEvent("命中 ${attempt.items.size} 条，用时 ${attempt.latencyMs}ms", ProbeStepStatus.OK))
                    return SourceProbeOutcome(source, true, rule, attempt.latencyMs, attempt.items.size,
                        attempt.items, pages = pages)
                }
                onEvent?.invoke(ProbeEvent(attempt.error ?: NO_ENTRY, ProbeStepStatus.FAIL))
                val failure = attempt.error ?: NO_ENTRY
                // 后面那些标准预估路径几乎都是 404；真正说得清问题的（被 Cloudflare 拦、搜索直接跳详情页）要留下，
                // 否则用户只能看到一句「搜索入口不存在（HTTP 404）」，看不出站的真实行为。
                if (informativeError == null && isInformativeFailure(failure)) informativeError = failure
                lastError = failure
                if (attempt.body.isNotBlank()) lastBody = attempt.body
            }
        }
        return SourceProbeOutcome(source, false, error = informativeError ?: lastError, lastBody = lastBody, pages = pages)
    }

    /**
     * 完整探测：先按确定性候选试；全失败且给了 [ai] 时，让 AI 从用户粘的那一页提候选搜索模板，再逐条实测。
     *
     * 判定标准两步完全一致——真发一次搜索请求、解析出至少一条详情链接、页面里出现关键词。
     * AI 只扩大候选范围（尤其是「粘的不是首页」这类确定性规则覆盖不到的情况），
     * 不代替「可用」的判定，也不因为模型说「这个站能用」就启用源。
     */
    fun probeWithAi(
        source: OnlineVideoSource,
        transport: SearchTransport,
        ai: DeepSeekClient?,
        keyword: String = SearchRuleDetector.PROBE_KEYWORD,
        onEvent: ((ProbeEvent) -> Unit)? = null
    ): SourceProbeOutcome {
        val deterministic = probe(source, transport, keyword, onEvent)
        if (deterministic.success) return deterministic
        if (ai == null) {
            onEvent?.invoke(ProbeEvent("未开启 AI 智能解析，只用常见入口探测", ProbeStepStatus.FAIL))
            return deterministic
        }

        val address = SourceAddressRules.analyze(source.normalizedBase)
        val siteBase = address.siteBase
        val evidence = deterministic.pages.evidence()
        onEvent?.invoke(ProbeEvent("常见入口都没中，请 DeepSeek 读页面找搜索入口…", ProbeStepStatus.RUNNING))
        val candidates = ai.suggestSearchRules(address.original, evidence, siteBase)
        if (candidates.isEmpty()) {
            onEvent?.invoke(ProbeEvent("DeepSeek 没给出可用候选", ProbeStepStatus.FAIL))
            return deterministic.copy(
                error = if (evidence.isBlank()) AI_NO_HOMEPAGE
                else if (address.isSiteRoot) AI_NO_RULE
                else "你粘的是${address.pageKind.label}，AI 也没从这一页看出这个站的搜索入口（多半是纯 JS 渲染的站点）。"
            )
        }
        onEvent?.invoke(ProbeEvent("DeepSeek 提了 ${candidates.size} 个候选入口", ProbeStepStatus.OK))
        var lastError = AI_NO_RULE
        for (rule in candidates) {
            onEvent?.invoke(ProbeEvent("实测 ${rule.summary}…", ProbeStepStatus.RUNNING))
            val attempt = attemptRule(rule, siteBase, keyword, transport)
            if (attempt.success) {
                onEvent?.invoke(ProbeEvent("命中 ${attempt.items.size} 条，用时 ${attempt.latencyMs}ms", ProbeStepStatus.OK))
                return SourceProbeOutcome(
                    source = source, success = true, rule = rule,
                    latencyMs = attempt.latencyMs, itemCount = attempt.items.size, items = attempt.items,
                    aiAssisted = true, pages = deterministic.pages
                )
            }
            onEvent?.invoke(ProbeEvent(attempt.error ?: AI_NO_RULE, ProbeStepStatus.FAIL))
            lastError = attempt.error ?: AI_NO_RULE
        }
        return deterministic.copy(error = "AI 提的搜索入口实测都没返回结果（$lastError）")
    }

    /** 抓探测要用的页面：每个候选 base 的目录页 + 用户粘的那一页。抓不到的页不进结果。 */
    fun fetchPages(address: SourceAddress, transport: SearchTransport): ProbePages {
        val byBase = linkedMapOf<String, String>()
        val aliases = linkedMapOf<String, String>()
        address.basePagesToFetch().forEach { (base, url) ->
            // 跟随 301/302：不带 www 的域名、`/index.php` 这类入口很常见地会跳一下，
            // 丢掉 3xx 的响应体等于丢掉首页 HTML，后面就没有表单可解析了。
            val response = fetchPage(url, transport, referer = "$base/")
            val body = response?.takeIf { it.status in 200..299 }?.body?.takeIf { it.isNotBlank() }
            if (response != null && body != null) {
                byBase[base] = body
                val effective = SourceAddressRules.siteBase(response.url.ifBlank { url })
                if (effective.isNotBlank() && effective != base) {
                    aliases[base] = effective
                    byBase.putIfAbsent(effective, body)
                }
            }
        }
        val pasted = address.pastedPageToFetch()
            ?.let { fetchPage(it, transport, referer = "${address.siteBase}/") }
            ?.takeIf { it.status in 200..299 }
            ?.body?.takeIf { it.isNotBlank() }
            .orEmpty()
        return ProbePages(byBase, pasted, aliases)
    }

    /** 抓首页 HTML；抓不到返回空串（让标准路径仍有直接命中的机会）。 */
    fun fetchHomepage(source: OnlineVideoSource, transport: SearchTransport): String {
        val homepage = fetchPage("${source.normalizedBase}/", transport, referer = "") ?: return ""
        return if (homepage.status in 200..299) homepage.body else ""
    }

    /** GET 抓一个页面，最多跟随 [MAX_PAGE_REDIRECTS] 跳重定向（相对 Location 按当前地址拼）。 */
    private fun fetchPage(url: String, transport: SearchTransport, referer: String): SearchHttpResponse? {
        var current = url
        var hops = 0
        while (true) {
            val response = runCatching {
                transport.execute(SearchHttpRequest("GET", current, referer = referer))
            }.getOrNull() ?: return null
            if (response.status in REDIRECT_CODES && response.location.isNotBlank() && hops < MAX_PAGE_REDIRECTS) {
                current = SearchRuleDetector.resolve(response.url.ifBlank { current }, response.location)
                hops++
                continue
            }
            return response.copy(url = response.url.ifBlank { current })
        }
    }

    /** 抓首页并生成候选规则；首页抓不到也退回标准路径，让标准路径有机会直接命中。 */
    fun detectCandidates(source: OnlineVideoSource, transport: SearchTransport): List<SearchRule> =
        SearchRuleDetector.candidateRules(source.normalizedBase, fetchHomepage(source, transport))

    /**
     * 试探单条规则：构造请求 → 发请求（POST 302 用 GET 跟随）→ 解析结果。
     *
     * 失败原因按「HTTP 状态码 → 详情页兜底 → 结果页」顺序判定，让用户能分清
     * 「被 Cloudflare 拦了」「搜索入口不存在」「关键词没结果」「搜索直接跳到了详情页」。
     */
    fun attemptRule(rule: SearchRule, baseUrl: String, keyword: String, transport: SearchTransport): SearchAttempt {
        val start = System.currentTimeMillis()
        val requestedUrl = if (rule.method == SearchMethod.POST) {
            SearchRuleDetector.resolve(baseUrl, rule.urlTemplate)
        } else {
            SearchRuleDetector.buildGetUrl(rule, baseUrl, keyword)
        }
        val fetched = executeSearch(rule, baseUrl, keyword, transport)
        val latency = System.currentTimeMillis() - start
        if (fetched == null) {
            return SearchAttempt(false, latency, httpStatus = -1, finalUrl = requestedUrl,
                error = NETWORK_FAILURE, networkFailure = true)
        }
        val finalUrl = fetched.finalUrl.ifBlank { requestedUrl }
        if (fetched.status == -1) {
            return SearchAttempt(false, latency, httpStatus = -1, finalUrl = finalUrl,
                error = NETWORK_FAILURE, networkFailure = true)
        }
        if (fetched.status !in 200..299) {
            return SearchAttempt(false, latency, httpStatus = fetched.status, finalUrl = finalUrl,
                error = describeHttpFailure(fetched.status, fetched.body), body = fetched.body,
                networkFailure = isSourceFailure(fetched.status))
        }
        val body = fetched.body
        val keywordHit = SearchResultsParser.containsKeyword(body, keyword)
        // 搜索被 302（或 301）送到详情页：站点没有列表结果，只有一部「就是它」的详情页。
        // 这一步必须排在列表解析前面——详情页自带分类/推荐链接（/show/1.html 这类），
        // 按列表解析会吐出一堆不相关的条目，把真正的结论盖掉。
        if (looksLikeDetailPage(finalUrl, requestedUrl)) {
            val title = SearchResultsParser.detailTitle(body)
            // 只有「标题里就是这个词」才算命中；页面正文到处是关键词（导航、热播榜）时不能算。
            if (titleMatchesKeyword(title, keyword)) {
                return SearchAttempt(true, latency, listOf(SearchResultItem(title = title, url = finalUrl)),
                    httpStatus = fetched.status, finalUrl = finalUrl, body = body)
            }
            return SearchAttempt(false, latency, httpStatus = fetched.status, finalUrl = finalUrl, body = body,
                error = DETAIL_REDIRECT_NO_KEYWORD)
        }
        // 按最后落地的地址解析相对链接：跟随重定向后 base 可能已经变了（比如 301 到 www）。
        val items = SearchResultsParser.extract(body, finalUrl)
        if (items.isNotEmpty() && keywordHit) {
            return SearchAttempt(true, latency, items, httpStatus = fetched.status, finalUrl = finalUrl, body = body)
        }
        return SearchAttempt(false, latency, items, httpStatus = fetched.status, finalUrl = finalUrl,
            error = if (items.isEmpty()) "返回页面里没有可点开的详情链接" else "返回的不是搜索结果页",
            body = body)
    }

    /**
     * 按规则真正发一次搜索请求，返回最后一跳的正文 + 状态码 + 最终地址；连不上/超时返回 null。
     * POST 规则（帝国CMS 表单）302 后用 GET 跟随 Location（不重发 POST，避免 411）。
     */
    fun executeSearch(rule: SearchRule, baseUrl: String, keyword: String, transport: SearchTransport): SearchFetch? {
        val referer = baseUrl.trim().trimEnd('/') + "/"
        val isPost = rule.method == SearchMethod.POST
        var current = if (isPost) {
            SearchRuleDetector.resolve(baseUrl, rule.urlTemplate)
        } else {
            SearchRuleDetector.buildGetUrl(rule, baseUrl, keyword)
        }
        val request = if (isPost) {
            SearchHttpRequest("POST", current, body = SearchRuleDetector.buildPostBody(rule, keyword), referer = referer)
        } else {
            SearchHttpRequest("GET", current, referer = referer)
        }
        var resp = runCatching { transport.execute(request) }.getOrNull() ?: return null
        var hops = 0
        while (resp.status in 300..399 && resp.location.isNotBlank() && hops < 5) {
            current = SearchRuleDetector.resolve(resp.url.ifBlank { current }, resp.location)
            resp = runCatching {
                transport.execute(SearchHttpRequest("GET", current, referer = referer))
            }.getOrNull() ?: return null
            hops++
        }
        return SearchFetch(body = resp.body, status = resp.status, finalUrl = resp.url.ifBlank { current })
    }

    /** 失败文案：Cloudflare 拦截 / 403 / 404 / 5xx 分开说，不再统一说「响应异常」。 */
    private fun describeHttpFailure(status: Int, body: String): String {
        val blocked = (status == 403 || status == 429) &&
            CLOUDFLARE_MARKERS.any { body.contains(it, ignoreCase = true) }
        if (blocked) {
            return "站点拒绝访问（HTTP $status，Cloudflare 拦截）：这台设备或 IP 被站点防护拦了，换成国内网络、关掉代理再试"
        }
        return when {
            status == 403 -> "站点拒绝访问（HTTP 403）"
            status == 404 -> "搜索入口不存在（HTTP 404）"
            status in 500..599 -> "站点服务器出错（HTTP $status）"
            else -> "返回的不是搜索结果页"
        }
    }

    /** 比「入口 404」更能解释站点真实行为的失败：被防护拦、搜索直接跳详情页。 */
    private fun isInformativeFailure(message: String): Boolean =
        message.contains("Cloudflare 拦截") || message.contains(DETAIL_REDIRECT_NO_KEYWORD)

    /** 403/429（被拦）与 5xx（服务器出错）算源故障；404 只说明这个入口不存在，不算。 */
    private fun isSourceFailure(status: Int): Boolean =
        status == 403 || status == 429 || status in 500..599

    /**
     * 详情页标题是否就是这个关键词：整串命中，或多词关键词里任一长度 ≥2 的词命中
     * （`三体 2023` 这种搜法，站点标题通常只写「三体」）。
     */
    private fun titleMatchesKeyword(title: String, keyword: String): Boolean {
        if (title.isBlank() || keyword.isBlank()) return false
        if (SearchResultsParser.containsKeyword(title, keyword)) return true
        val tokens = keyword.split(' ', '\u3000', ',', '，', '|').map { it.trim() }.filter { it.length >= 2 }
        return tokens.any { SearchResultsParser.containsKeyword(title, it) }
    }

    /**
     * 最终地址是否「详情页」。
     * `/detail/`、MacCMS 详情路径这类特征路径无条件算；单纯 `123.html`/`1/123.html`
     * 必须是从初始搜索地址真跳过来才算，否则普通搜索页也会被当成详情页。
     */
    private fun looksLikeDetailPage(finalUrl: String, requestedUrl: String): Boolean {
        val path = runCatching { URL(finalUrl).path }.getOrDefault(finalUrl).lowercase()
        if (DETAIL_PATH_MARKERS.any { path.contains(it) }) return true
        if (finalUrl.trimEnd('/') == requestedUrl.trimEnd('/')) return false
        return DETAIL_FILE.containsMatchIn(path)
    }
}

/** 一次「跨源搜索」的纯结果分类：网络失败与「没结果」分开，便于汇报与健康度记账。 */
data class CrossSearchOutcome(
    val source: OnlineVideoSource,
    val success: Boolean,
    val networkFailure: Boolean,
    val latencyMs: Long,
    val itemCount: Int,
    val hits: List<SourceHit> = emptyList(),
    val error: String? = null,
    /** 本次结果由 AI 兜底解析得到（确定性解析失败后触发）。 */
    val aiAssisted: Boolean = false
)

/**
 * 跨源搜索编排：并行搜所有启用源，单源独立超时，失败不影响整体。
 * 单源路由：有 [SearchRule] 按规则搜（MAC_CMS_API 走旧 JSON 接口）；没规则先探测 HTML 入口，
 * 失败再回退旧 MacCMS JSON 接口（兼容旧源）。确定性路径全部失败且给了 [AiSearchFallback] 时，
 * 用最后抓到的结果页 HTML 走 AI 兜底提链接。
 */
object CrossSourceSearchEngine {

    /**
     * 并行搜启用源（跳过冷却期）；返回「没结果 vs 网络失败」可区分的逐源结果。
     * [requireRule] = true 时只搜已经缓存了搜索规则的源：推荐流这类自动预取不该替用户
     * 对没探测过的站跑一遍探测（抓首页 + 多个候选入口试搜），那是最容易被风控识别的大量请求。
     */
    suspend fun search(
        store: SourceRegistry,
        keyword: String,
        transport: SearchTransport,
        now: Long = System.currentTimeMillis(),
        aiFallback: AiSearchFallback? = null,
        requireRule: Boolean = false,
        /** 非空表示该源本次不参与搜索（站方封锁 / Cloudflare 挑战失败冷却）。 */
        skipReason: (OnlineVideoSource) -> String? = { null }
    ): List<CrossSearchOutcome> {
        val active = store.sources().filter { source ->
            source.enabled && source.normalizedBase.isNotBlank() &&
                (!requireRule || source.searchRule != null) &&
                skipReason(source) == null &&
                !SourceHealthPolicy.isCoolingDown(store.health(source.id), now)
        }
        if (active.isEmpty()) return emptyList()
        return coroutineScope {
            active.map { source ->
                async(Dispatchers.IO) { searchSource(source, store, keyword, transport, aiFallback) }
            }.awaitAll()
        }
    }

    /** 单源搜索：有规则按规则搜；没规则先探测（成功即缓存规则），探测失败回退旧 JSON 接口；全失败再 AI 兜底。 */
    fun searchSource(
        source: OnlineVideoSource,
        store: SourceRegistry,
        keyword: String,
        transport: SearchTransport,
        aiFallback: AiSearchFallback? = null
    ): CrossSearchOutcome {
        val start = System.currentTimeMillis()
        val rule = source.searchRule
        val (outcome, html) = when {
            rule != null && rule.kind == SearchRuleKind.MAC_CMS_API ->
                legacyJsonSearch(source, store, keyword, start) to ""
            rule != null -> htmlSearch(source, store, keyword, rule, start, transport)
            else -> {
                val probe = SourceProber.probe(source, transport)
                if (probe.success && probe.rule != null) {
                    store.save(source.copy(searchRule = probe.rule))
                    htmlSearch(source, store, keyword, probe.rule, start, transport)
                } else {
                    legacyJsonSearch(source, store, keyword, start) to probe.lastBody
                }
            }
        }
        if (!outcome.success && aiFallback != null && html.isNotBlank()) {
            val ai = runCatching { aiFallback.extractResults(keyword, html, source.normalizedBase) }
                .getOrDefault(emptyList())
            if (ai.isNotEmpty()) {
                val hits = ai.map { SourceHit(source, VodItem(vodName = it.title), detailUrl = it.url) }
                return CrossSearchOutcome(source, true, networkFailure = false,
                    System.currentTimeMillis() - start, hits.size, hits, aiAssisted = true)
            }
        }
        return outcome
    }

    private fun htmlSearch(
        source: OnlineVideoSource,
        store: SourceRegistry,
        keyword: String,
        rule: SearchRule,
        start: Long,
        transport: SearchTransport
    ): Pair<CrossSearchOutcome, String> {
        val attempt = SourceProber.attemptRule(rule, source.normalizedBase, keyword, transport)
        val latency = System.currentTimeMillis() - start
        return if (attempt.success) {
            store.recordSuccess(source.id)
            val hits = attempt.items.map { SourceHit(source, it.toVodItem(), detailUrl = it.url) }
            CrossSearchOutcome(source, true, networkFailure = false, latency, attempt.items.size, hits) to attempt.body
        } else {
            val network = attempt.networkFailure
            // 只有网络/HTTP 失败才扣源健康度：搜到 0 条是「这个词没结果」不是源的故障，
            // 否则连搜几个冷门标题就能把好源误判进冷却，之后所有搜索都被跳过。
            if (network) store.recordFailure(source.id)
            CrossSearchOutcome(source, false, networkFailure = network, latency, 0, error = attempt.error ?: "没搜到") to attempt.body
        }
    }

    /** 旧 MacCMS JSON 接口：`{base}/api.php/provide/vod/?ac=detail&wd={kw}`，纯结果分类（不误伤健康度）。 */
    private fun legacyJsonSearch(
        source: OnlineVideoSource,
        store: SourceRegistry,
        keyword: String,
        start: Long
    ): CrossSearchOutcome {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "${source.normalizedBase}/api.php/provide/vod/?ac=detail&wd=$encoded"
        val fetched = runCatching { fetchHttpText(url, source.headers) }
        val latency = System.currentTimeMillis() - start
        val outcome = classifySearchOutcome(source, fetched, latency)
        if (outcome.success) store.recordSuccess(source.id) else store.recordFailure(source.id)
        return CrossSearchOutcome(
            source = source,
            success = outcome.success,
            networkFailure = !outcome.success,
            latencyMs = latency,
            itemCount = outcome.itemCount,
            hits = outcome.items.map { SourceHit(source, it) },
            error = outcome.error
        )
    }
}
