package com.afeicn.aiplayer

import java.net.HttpURLConnection
import java.net.URL

/**
 * 「粘贴网页链接自动解析视频流」：把用户粘进来的一个 URL 变成一条可直接播的 [NetworkStream]。
 *
 * 纯逻辑（[WebLinkParser] / [EmpireCmsRule] / [WebLinkResolver]）只依赖字符串与 java.net.URL，
 * 不碰 Android 框架，便于纯 JVM 单测（注入假 HTML、不联网）；网络抓取单独抽象成
 * [PageFetcher]，真实实现 [HttpPageFetcher] 带桌面 UA + 可选 Referer。
 *
 * 解析策略（[WebLinkParser.extractStreamCandidates] 依次尝试）：
 *   a) empirecms 分享页规则（最具体，优先）；
 *   b) HTML 属性 / 引号里的 .m3u8 直链（含相对路径，按页面 URL 补全）；
 *   c) JS 变量里的 .m3u8（url: / src: / const|var|let name = "…"）；
 *   d) `<iframe src>` → 递归抓最多 2 层。
 */

/** 桌面 Chrome UA：解析网页与拉流统一使用。 */
const val DEFAULT_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** iframe 最多递归抓取的层数（超过即放弃）。 */
const val MAX_IFRAME_DEPTH = 2

/** 一次抓取的结果：最终 URL（跟随重定向后）、Content-Type、文本正文。 */
data class FetchedPage(
    val url: String,
    val contentType: String = "",
    val body: String = ""
)

/** 抓取失败（网络 / HTTP 状态码），带状态码以便映射成可读原因。 */
class WebLinkFetchException(message: String, val httpStatus: Int? = null) : Exception(message)

/** 抓取抽象：测试注入假 HTML 字符串，不联网。 */
fun interface PageFetcher {
    fun fetch(url: String, referer: String?): FetchedPage
}

/** 真实抓取实现：HttpURLConnection + 桌面 UA + 可选 Referer，手动跟随重定向。 */
class HttpPageFetcher(
    private val timeoutMs: Int = 10_000,
    private val maxRedirects: Int = 5
) : PageFetcher {
    override fun fetch(url: String, referer: String?): FetchedPage {
        var current = url
        repeat(maxRedirects + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", DEFAULT_DESKTOP_UA)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                if (!referer.isNullOrBlank()) connection.setRequestProperty("Referer", referer)
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw WebLinkFetchException("重定向缺少 Location", code)
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw WebLinkFetchException("HTTP $code", code)
                val contentType = connection.contentType.orEmpty()
                val body = if (isTextContent(contentType)) readBody(connection) else ""
                return FetchedPage(url = current, contentType = contentType, body = body)
            } finally {
                connection.disconnect()
            }
        }
        throw WebLinkFetchException("重定向次数过多")
    }

    private fun isTextContent(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.contains("text") || ct.contains("html") || ct.contains("json") ||
            ct.contains("javascript") || ct.contains("xml") || ct.contains("mpegurl") ||
            ct.contains("m3u8") || ct.isBlank()
    }

    private fun readBody(connection: HttpURLConnection): String =
        connection.inputStream.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
}

/** 解析出的候选流：绝对地址 + 应当携带的 Referer（发现它的页面域名）。 */
data class StreamCandidate(val url: String, val referer: String)

/** 解析结果：成功给一条 [NetworkStream]（含请求头），列表页给 [EpisodeList]，失败给可读原因。 */
sealed class WebLinkResult {
    data class Success(val stream: NetworkStream) : WebLinkResult()
    data class EpisodeList(val title: String, val pageUrl: String, val episodes: List<EpisodeLink>) : WebLinkResult()
    data class Failure(val kind: WebLinkFailureKind, val message: String) : WebLinkResult()
}

enum class WebLinkFailureKind { NO_STREAM, NETWORK, NEEDS_LOGIN, HTTP, HOME_PAGE }

object WebLinkParser {

    private val STREAM_EXTENSIONS =
        setOf("m3u8", "m3u", "mp4", "ts", "flv", "webm", "mkv", "mov", "mp3", "aac", "m4a")

    /** 按扩展名直接识别流地址；网页 URL（如帝国CMS 播放页）返回 false。 */
    fun looksLikeStreamUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return ext in STREAM_EXTENSIONS
    }

    /** Content-Type 含 text/html，或正文以 `<` 开头（先剥 BOM）→ 判定为网页。 */
    fun isHtmlPage(contentType: String, body: String): Boolean =
        contentType.contains("text/html", ignoreCase = true) ||
            body.removePrefix("\uFEFF").trimStart().startsWith("<")

    /** 把一个候选（绝对 / 协议相对 / 根相对 / 相对路径）按页面 URL 补全成绝对地址。 */
    fun resolveUrl(baseUrl: String, candidate: String): String =
        URL(URL(baseUrl), candidate.trim()).toString()

    /** 取页面「来源域」：scheme://host[:port]/，用作拉流 Referer。 */
    fun refererOrigin(pageUrl: String): String {
        val u = URL(pageUrl)
        val port = if (u.port == -1) "" else ":${u.port}"
        return "${u.protocol}://${u.host}$port/"
    }

    /** 提取 `<title>`，作为解析出的流标题兜底。 */
    fun extractTitle(html: String): String =
        TITLE.find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ").orEmpty()

    // ---- a) HTML 属性 / 引号里的 .m3u8 直链 ----

    private val ATTR_M3U8 = Regex(
        """(?:src|href|action|data-src|data-url)\s*=\s*["']([^"']*\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val QUOTED_M3U8 = Regex("""["']([^"']*\.m3u8[^"']*)["']""")

    fun extractDirectM3u8(html: String): List<String> {
        val fromAttrs = ATTR_M3U8.findAll(html).map { it.groupValues[1].trim() }
        val fromQuotes = QUOTED_M3U8.findAll(html).map { it.groupValues[1].trim() }
        return (fromAttrs + fromQuotes).filter { it.isNotBlank() }.distinct().toList()
    }

    // ---- b) JS 变量里的 .m3u8 ----

    private val JS_M3U8 = Regex(
        """(?:url|src|videoUrl|videoSrc|playUrl|file|m3u8Url)\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val JS_CONST_M3U8 = Regex(
        """(?:const|var|let)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*["']([^"']*\.m3u8[^"']*)["']"""
    )

    fun extractJsM3u8(html: String): List<String> =
        (JS_M3U8.findAll(html) + JS_CONST_M3U8.findAll(html))
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    // ---- c) iframe src ----

    private val IFRAME_SRC = Regex(
        """<iframe\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    /** 提取 iframe src（过滤 javascript:/about: 伪协议），按页面 URL 补全成绝对地址。 */
    fun extractIframeSrcs(html: String, baseUrl: String): List<String> =
        IFRAME_SRC.findAll(html).mapNotNull { m ->
            val src = m.groupValues[1].trim()
            if (src.isEmpty() || src.startsWith("javascript:", ignoreCase = true) ||
                src.startsWith("about:", ignoreCase = true)
            ) null else resolveUrl(baseUrl, src)
        }.toList()

    // ---- 组合：按优先级 a) 帝国CMS 规则 → b) 直链 → c) JS 变量 ----

    fun extractStreamCandidates(html: String, pageUrl: String): List<StreamCandidate> {
        val origin = refererOrigin(pageUrl)
        val raw = mutableListOf<String>()
        EmpireCmsRule.extractShareStreamPath(html)?.let { raw += it }
        raw += extractDirectM3u8(html)
        raw += extractJsM3u8(html)
        // 滤掉“裸扩展名”假候选（如 `var ext = ".m3u8"` 会抽出 ".m3u8"，补全成 / .m3u8 这种不可播地址）。
        return raw.map { it.trim() }
            .filter { it.isNotEmpty() && !BARE_EXTENSION.matches(it) }
            .distinct()
            .map { StreamCandidate(resolveUrl(pageUrl, it), origin) }
    }

    private val BARE_EXTENSION = Regex("""^\.[A-Za-z0-9]+$""")

    private val TITLE = Regex(
        """<title[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
}

/** 帝国CMS（Empire CMS）DownSys 播放/分享页的通用适配规则，按 URL 路径模式识别，不绑定任何站点。 */
object EmpireCmsRule {

    /** 分享页里的真实流地址：/YYYYMMDD/<hash>/index.m3u8?sign=<hex>。 */
    val SHARE_STREAM = Regex("""/[0-9]{8}/[0-9a-z_]+/index\.m3u8\?sign=[a-f0-9]+""")

    /** 是不是帝国CMS 的免插件播放页（/e/DownSys/play/?…）。 */
    fun isPlayPage(url: String): Boolean =
        url.contains("/e/DownSys/play/", ignoreCase = true)

    /** 从分享页 HTML 提取相对流路径（如 /20261016/xxx/index.m3u8?sign=…），没有则 null。 */
    fun extractShareStreamPath(html: String): String? = SHARE_STREAM.find(html)?.value
}

/** 把抓取异常映射成可读原因分类。 */
object WebLinkErrors {
    fun classify(e: WebLinkFetchException): WebLinkFailureKind = when (e.httpStatus) {
        401, 403 -> WebLinkFailureKind.NEEDS_LOGIN
        in 400..599 -> WebLinkFailureKind.HTTP
        else -> WebLinkFailureKind.NETWORK
    }

    fun message(e: WebLinkFetchException): String = when (e.httpStatus) {
        401 -> "需要登录（HTTP 401）才能访问这个页面"
        403 -> "服务器拒绝访问（HTTP 403），可能需要登录或访问权限"
        404 -> "页面不存在（HTTP 404）"
        in 400..499 -> "请求被拒绝（${e.message}）"
        in 500..599 -> "服务器出错了（${e.message}）"
        else -> "网络请求失败，请检查网络后重试"
    }
}

/**
 * 解析编排：直链流地址直接播；网页走提取 + iframe 递归；都没有给可读原因。
 *
 * 兜底顺序：HTTP 解析成功 → 直接用（不启 WebView）；HTTP 解析出 NO_STREAM → 交给
 * [webViewFallback]（隐藏 WebView + 请求拦截）再试；WebView 也失败 → 明确提示。
 */
object WebLinkResolver {

    fun resolve(
        inputUrl: String,
        fetcher: PageFetcher,
        webViewFallback: ((String) -> WebLinkResult)? = null
    ): WebLinkResult {
        val trimmed = inputUrl.trim()
        // 直接是流地址（m3u8 / mp4 / ts…）→ 老逻辑直接播，不抓取。
        if (WebLinkParser.looksLikeStreamUrl(trimmed)) {
            return WebLinkResult.Success(NetworkStream(url = trimmed))
        }
        val httpResult = resolvePage(trimmed, refererToSend = null, fetcher = fetcher, iframeDepth = 0)
        // HTTP 层没找到流（动态渲染 / 加密站点）→ 交给 WebView 兜底。
        if (httpResult is WebLinkResult.Failure && httpResult.kind == WebLinkFailureKind.NO_STREAM && webViewFallback != null) {
            return webViewFallback(trimmed)
        }
        return httpResult
    }

    private fun resolvePage(
        pageUrl: String,
        refererToSend: String?,
        fetcher: PageFetcher,
        iframeDepth: Int
    ): WebLinkResult {
        val page = try {
            fetcher.fetch(pageUrl, refererToSend)
        } catch (e: WebLinkFetchException) {
            return WebLinkResult.Failure(WebLinkErrors.classify(e), WebLinkErrors.message(e))
        } catch (e: Exception) {
            return WebLinkResult.Failure(WebLinkFailureKind.NETWORK, "网络请求失败，请检查网络后重试")
        }

        // 返回的不是网页（无扩展名但实际是 m3u8 / mp4 的地址）→ 当作流直接播。
        if (!WebLinkParser.isHtmlPage(page.contentType, page.body)) {
            return WebLinkResult.Success(
                NetworkStream(url = page.url, headers = buildRequestHeaders(userAgent = DEFAULT_DESKTOP_UA))
            )
        }

        // 网页：先试直链 / JS 变量 / 帝国CMS 规则。
        val candidate = WebLinkParser.extractStreamCandidates(page.body, page.url).firstOrNull()
        if (candidate != null) {
            return WebLinkResult.Success(streamFrom(candidate, page.body))
        }

        // 没找到直链 → iframe 递归，最多 MAX_IFRAME_DEPTH 层。
        if (iframeDepth < MAX_IFRAME_DEPTH) {
            val iframes = WebLinkParser.extractIframeSrcs(page.body, page.url)
            for (iframeUrl in iframes) {
                val child = resolvePage(
                    pageUrl = iframeUrl,
                    refererToSend = WebLinkParser.refererOrigin(page.url),
                    fetcher = fetcher,
                    iframeDepth = iframeDepth + 1
                )
                if (child is WebLinkResult.Success) return child
            }
        }

        // 顶层页面依旧没有流 → 判断是否「剧集列表页」：≥3 个像播放页的链接。
        if (iframeDepth == 0) {
            val episodes = EpisodeListParser.extract(page.body, page.url)
            if (episodes.size >= EpisodeListParser.MIN_EPISODES_FOR_LIST) {
                return WebLinkResult.EpisodeList(
                    title = EpisodeListParser.dramaTitle(WebLinkParser.extractTitle(page.body)),
                    pageUrl = page.url,
                    episodes = episodes
                )
            }
            // 没有集数 → 判断是否「站点首页」：大量不同影片的内容链接。
            if (EpisodeListParser.looksLikeHomepage(page.body, page.url, episodes.map { it.url }.toSet())) {
                return WebLinkResult.Failure(WebLinkFailureKind.HOME_PAGE, "这是站点首页，请给我某个剧的页面")
            }
        }
        return noStream()
    }

    private fun streamFrom(candidate: StreamCandidate, pageBody: String): NetworkStream = NetworkStream(
        url = candidate.url,
        title = WebLinkParser.extractTitle(pageBody),
        headers = buildRequestHeaders(referer = candidate.referer, userAgent = DEFAULT_DESKTOP_UA)
    )

    private fun noStream() = WebLinkResult.Failure(
        WebLinkFailureKind.NO_STREAM,
        "没解析出可播放内容"
    )
}
