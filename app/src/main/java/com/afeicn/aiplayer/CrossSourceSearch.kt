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

/** 真实 HTTP 实现：桌面 UA + Referer，POST/GET 均可，不自动跟随重定向。 */
class HttpSearchTransport(
    private val timeoutMs: Int = 10_000
) : SearchTransport {
    override fun execute(request: SearchHttpRequest): SearchHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", DEFAULT_DESKTOP_UA)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (request.referer.isNotBlank()) connection.setRequestProperty("Referer", request.referer)
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

/** 单条规则的试探结果。`body` 是抓到的原始正文，供确定性失败时喂给 AI 兜底。 */
data class SearchAttempt(
    val success: Boolean,
    val latencyMs: Long,
    val items: List<SearchResultItem> = emptyList(),
    val error: String? = null,
    val body: String = ""
)

/** 确定性解析失败时的 AI 兜底：从结果页 HTML 提影片链接。 */
fun interface AiSearchFallback {
    fun extractResults(keyword: String, html: String, base: String): List<ExtractedResult>
}

/**
 * 探测期间抓到的页面。
 *
 * [byBase] 按候选 base 索引（生成候选规则时要按 base 查对应 HTML，按抓的 URL 索引会查不到）；
 * [pastedPage] 是用户粘的那一页，喂给 AI 判断搜索入口用。
 */
data class ProbePages(val byBase: Map<String, String> = emptyMap(), val pastedPage: String = "") {

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
        var lastBody = ""
        for (base in address.candidateBases()) {
            val rules = SearchRuleDetector.candidateRules(base, pages.byBase[base].orEmpty())
            for (rule in rules) {
                onEvent?.invoke(ProbeEvent("试搜 ${rule.summary}…", ProbeStepStatus.RUNNING))
                val attempt = attemptRule(rule, base, keyword, transport)
                if (attempt.success) {
                    onEvent?.invoke(ProbeEvent("命中 ${attempt.items.size} 条，用时 ${attempt.latencyMs}ms", ProbeStepStatus.OK))
                    return SourceProbeOutcome(source, true, rule, attempt.latencyMs, attempt.items.size,
                        attempt.items, pages = pages)
                }
                onEvent?.invoke(ProbeEvent(attempt.error ?: NO_ENTRY, ProbeStepStatus.FAIL))
                lastError = attempt.error ?: NO_ENTRY
                if (attempt.body.isNotBlank()) lastBody = attempt.body
            }
        }
        return SourceProbeOutcome(source, false, error = lastError, lastBody = lastBody, pages = pages)
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
        val referer = "${address.siteBase}/"
        fun fetch(url: String): String? {
            val response = runCatching { transport.execute(SearchHttpRequest("GET", url, referer = referer)) }.getOrNull()
            return response?.body?.takeIf { response.status in 200..299 && it.isNotBlank() }
        }
        val byBase = address.basePagesToFetch().mapNotNull { (base, url) ->
            fetch(url)?.let { base to it }
        }.toMap()
        val pasted = address.pastedPageToFetch()?.let(::fetch).orEmpty()
        return ProbePages(byBase, pasted)
    }

    /** 抓首页 HTML；抓不到返回空串（让标准路径仍有直接命中的机会）。 */
    fun fetchHomepage(source: OnlineVideoSource, transport: SearchTransport): String {
        val homepage = runCatching {
            transport.execute(SearchHttpRequest("GET", "${source.normalizedBase}/", referer = ""))
        }.getOrNull()
        return if (homepage != null && homepage.status in 200..299) homepage.body else ""
    }

    /** 抓首页并生成候选规则；首页抓不到也退回标准路径，让标准路径有机会直接命中。 */
    fun detectCandidates(source: OnlineVideoSource, transport: SearchTransport): List<SearchRule> =
        SearchRuleDetector.candidateRules(source.normalizedBase, fetchHomepage(source, transport))

    /** 试探单条规则：构造请求 → 发请求（POST 302 用 GET 跟随）→ 解析结果。 */
    fun attemptRule(rule: SearchRule, baseUrl: String, keyword: String, transport: SearchTransport): SearchAttempt {
        val start = System.currentTimeMillis()
        val body = executeSearch(rule, baseUrl, keyword, transport)
        val latency = System.currentTimeMillis() - start
        if (body == null) return SearchAttempt(false, latency, error = "网络失败或响应异常")
        val items = SearchResultsParser.extract(body, baseUrl)
        val keywordHit = SearchResultsParser.containsKeyword(body, keyword)
        return if (items.isNotEmpty() && keywordHit) {
            SearchAttempt(true, latency, items, body = body)
        } else {
            SearchAttempt(false, latency, items,
                if (items.isEmpty()) "返回页面里没有可点开的详情链接" else "返回的不是搜索结果页", body = body)
        }
    }

    /**
     * 按规则真正发一次搜索请求，返回结果页正文；网络失败 / 非 2xx 返回 null。
     * POST 规则（帝国CMS 表单）302 后用 GET 跟随 Location（不重发 POST，避免 411）。
     */
    fun executeSearch(rule: SearchRule, baseUrl: String, keyword: String, transport: SearchTransport): String? {
        val referer = baseUrl.trim().trimEnd('/') + "/"
        if (rule.method == SearchMethod.POST) {
            val url = SearchRuleDetector.resolve(baseUrl, rule.urlTemplate)
            val body = SearchRuleDetector.buildPostBody(rule, keyword)
            var resp = transport.execute(SearchHttpRequest("POST", url, body = body, referer = referer))
            var hops = 0
            while (resp.status in 300..399 && resp.location.isNotBlank() && hops < 5) {
                resp = transport.execute(SearchHttpRequest("GET", SearchRuleDetector.resolve(resp.url, resp.location), referer = referer))
                hops++
            }
            return resp.body.takeIf { resp.status in 200..299 }
        }
        val url = SearchRuleDetector.buildGetUrl(rule, baseUrl, keyword)
        var resp = transport.execute(SearchHttpRequest("GET", url, referer = referer))
        var hops = 0
        while (resp.status in 300..399 && resp.location.isNotBlank() && hops < 5) {
            resp = transport.execute(SearchHttpRequest("GET", SearchRuleDetector.resolve(resp.url, resp.location), referer = referer))
            hops++
        }
        return resp.body.takeIf { resp.status in 200..299 }
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

    /** 并行搜启用源（跳过冷却期）；返回「没结果 vs 网络失败」可区分的逐源结果。 */
    suspend fun search(
        store: SourceRegistry,
        keyword: String,
        transport: SearchTransport,
        now: Long = System.currentTimeMillis(),
        aiFallback: AiSearchFallback? = null
    ): List<CrossSearchOutcome> {
        val active = store.sources().filter { source ->
            source.enabled && source.normalizedBase.isNotBlank() &&
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
            val network = attempt.error == "网络失败或响应异常"
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
