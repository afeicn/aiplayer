package com.afeicn.aiplayer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 「粘贴网页链接自动解析」第二层兜底：隐藏 WebView + 网络请求拦截。
 *
 * 很多影视站是 JS 动态渲染 / 加密的，HTTP 源码里没有 m3u8（[WebLinkResolver] 会失败）。
 * 这里真的把页面跑起来：开 JS / DOM Storage / 混内容 / 桌面 UA，用 `shouldInterceptRequest`
 * 拦下每个请求，凡是 URL 含 .m3u8（或响应头 Content-Type 是 HLS/mpegurl）就记下 URL + 当时的
 * Referer + UA；`onPageFinished` 后再用 `evaluateJavascript` 注入只读 JS 扫一遍 DOM
 * （video/source 的 src、window 上的播放器配置变量），通过返回值把结果拿回来。整个流程
 * 15 秒封顶，超时放弃并回报原因。
 *
 * 安全约定：
 *  - WebView 永不挂到任何可见视图（不可见），加载即用、用后 `destroy()` + 清缓存；
 *  - 不注入任何远程脚本；注入的 JS 是固定只读扫描片段，只取 DOM 属性 / window 变量，绝不 eval 用户内容；
 *  - 解析期间禁掉弹窗（setSupportMultipleWindows(false) + 拦 onCreateWindow）。
 *
 * 注意：本类依赖 Android WebView 框架，无法在纯 JVM 单测里跑，选择策略抽到了
 * [PlaylistSelection]（纯函数）单独单测；本类只做编排。
 */
class HiddenWebViewResolver(private val appContext: Context) {

    /**
     * 阻塞式解析：在「调用线程」上等结果（调用方应放到 IO 线程）。
     *
     * 关键线程模型：WebView 必须跑在一个带 Looper 的独立线程（HandlerThread）上，且
     * 它的回调（onPageFinished / evaluateJavascript / onReceivedError）都由那个 Looper
     * 投递。因此「等待 + 超时」不能写在同一线程上 sleep，否则会堵死 WebView 自己的回调。
     * 这里只在调用线程上轮询共享状态，WebView 线程负责真正的加载与扫描。
     */
    fun resolve(pageUrl: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): WebLinkResult {
        val thread = HandlerThread("hidden-webview-resolver")
        thread.start()
        val handler = Handler(thread.looper)
        val collector = CandidateCollector(pageUrl)
        val pageFinished = AtomicBoolean(false)
        val loadError = AtomicReference<String?>(null)
        val title = AtomicReference<String?>(null)
        val setupError = AtomicReference<String?>(null)
        val webViewRef = java.util.concurrent.atomic.AtomicReference<WebView>()
        try {
            handler.post {
                try {
                    val webView = WebView(appContext)
                    webViewRef.set(webView)
                    setupWebView(webView, pageUrl, collector, pageFinished, loadError, title)
                    webView.loadUrl(pageUrl)
                } catch (t: Throwable) {
                    setupError.set(t.message ?: "未知错误")
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            // 阶段一：等页面加载完成，或已拦到「像主播放列表」的候选，或初始化失败，或超时。
            while (System.currentTimeMillis() < deadline && !pageFinished.get()) {
                if (collector.hasMasterCandidate()) break
                if (setupError.get() != null) break
                sleep(100)
            }
            // 阶段二：页面加载完（或已找到候选）后，再注入一次扫描，给 DOM 一段宽限等它回报。
            if (pageFinished.get()) {
                handler.post { webViewRef.get()?.let { injectScanScript(it, collector) } }
            }
            val graceEnd = minOf(deadline, System.currentTimeMillis() + SCAN_GRACE_MS)
            while (System.currentTimeMillis() < graceEnd) {
                if (collector.hasCandidate()) break
                sleep(100)
            }

            return if (setupError.get() != null) {
                WebLinkResult.Failure(WebLinkFailureKind.NETWORK, "WebView 初始化失败：${setupError.get()}")
            } else {
                buildResult(collector, pageUrl, title.get(), loadError.get())
            }
        } finally {
            // WebView 必须在创建它的线程上销毁；post 一个销毁消息，quitSafely 会先把队列里的消息处理完。
            webViewRef.get()?.let { wv ->
                handler.post { runCatching { wv.stopLoading(); wv.clearCache(true); wv.destroy() } }
            }
            thread.quitSafely()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        webView: WebView,
        pageUrl: String,
        collector: CandidateCollector,
        pageFinished: AtomicBoolean,
        loadError: AtomicReference<String?>,
        title: AtomicReference<String?>
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = DEFAULT_DESKTOP_UA
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.blockNetworkLoads = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                collector.inspect(request)
                return null // 让 WebView 正常继续加载，不改写任何请求。
            }

            override fun onPageFinished(view: WebView, url: String) {
                title.set(view.title?.takeIf { it.isNotBlank() })
                pageFinished.set(true)
                injectScanScript(view, collector)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    loadError.compareAndSet(
                        null,
                        error.description?.toString() ?: "error ${error.errorCode}"
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // 拦掉一切弹窗：解析期间绝不 new window。
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean = false
        }
    }

    /** 组装最终结果：挑最好候选 → 拼成 [NetworkStream]；没有就报「没解析出可播放地址」。 */
    private fun buildResult(
        collector: CandidateCollector,
        pageUrl: String,
        pageTitle: String?,
        loadError: String?
    ): WebLinkResult {
        // 分片数是「分片最多」选择策略的关键，先按需抓候选播放列表正文数一下。
        val withCounts = collector.snapshot().map { candidate ->
            val count = if (PlaylistSelection.isHlsUrl(candidate.url)) {
                fetchEntryCount(candidate)
            } else null
            candidate.copy(segmentCount = count)
        }
        val best = PlaylistSelection.selectBest(withCounts)
        if (best == null) {
            val message = if (!loadError.isNullOrBlank()) {
                "这个页面没解析出可播放地址（页面加载失败：$loadError）"
            } else {
                "这个页面没解析出可播放地址"
            }
            return WebLinkResult.Failure(WebLinkFailureKind.NO_STREAM, message)
        }
        val referer = best.referer.ifBlank { WebLinkParser.refererOrigin(pageUrl) }
        val stream = NetworkStream(
            url = best.url,
            title = pageTitle.orEmpty(),
            headers = buildRequestHeaders(referer = referer, userAgent = best.userAgent.ifBlank { DEFAULT_DESKTOP_UA })
        )
        return WebLinkResult.Success(stream)
    }

    /** 抓一条 HLS 候选的正文数条目数（分片/变体）。失败返回 null，不影响候选参与排序。 */
    private fun fetchEntryCount(candidate: PlaylistCandidate): Int? {
        return try {
            val conn = URL(candidate.url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = COUNT_FETCH_TIMEOUT_MS
                conn.readTimeout = COUNT_FETCH_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", candidate.userAgent.ifBlank { DEFAULT_DESKTOP_UA })
                if (candidate.referer.isNotBlank()) conn.setRequestProperty("Referer", candidate.referer)
                val code = conn.responseCode
                if (code !in 200..299) return null
                val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
                PlaylistSelection.countEntries(body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 注入只读扫描脚本，通过 evaluateJavascript 的返回值把找到的候选拿回来。 */
    private fun injectScanScript(view: WebView, collector: CandidateCollector) {
        view.evaluateJavascript(SCAN_SCRIPT) { result ->
            collector.ingestScanResult(result)
        }
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    /**
     * 线程安全的候选收集器：请求拦截在 WebView 的 IO 线程跑、DOM 扫描回报在 WebView 线程跑、
     * 结算在当前线程跑，三方并发写，所以这里全部走并发容器。
     */
    private class CandidateCollector(private val pageUrl: String) {
        private val order = AtomicInteger(0)
        private val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val candidates = ConcurrentLinkedQueue<PlaylistCandidate>()
        private val peekBudget = AtomicInteger(MAX_CONTENT_TYPE_PEEKS)

        fun inspect(request: WebResourceRequest) {
            val url = request.url.toString()
            // 1) URL 直接含 .m3u8 / .m3u / mpegurl → 记下。
            if (PlaylistSelection.isHlsUrl(url)) {
                add(url, refererOf(request), userAgentOf(request))
                return
            }
            // 2) 动态端点（无静态资源扩展名）→ 探测响应头 Content-Type，是 HLS 也记下。
            if (!request.isForMainFrame && peekBudget.getAndDecrement() > 0 && mightBeDynamicMedia(url)) {
                peekExecutor.execute {
                    val ct = peekContentType(url, refererOf(request), userAgentOf(request))
                    if (ct != null && isHlsContentType(ct)) {
                        add(url, refererOf(request), userAgentOf(request))
                    }
                }
            }
        }

        private fun add(url: String, referer: String, userAgent: String) {
            val key = url.substringBefore('#')
            if (!seen.add(key)) return
            candidates.add(PlaylistCandidate(url = url, referer = referer, userAgent = userAgent, order = order.incrementAndGet()))
        }

        /**
         * 解析扫描脚本的返回值（evaluateJavascript 回传的 JSON）：
         * `urls` 是 video/source 里读到的绝对 src；`blobs` 是含 .m3u8 的播放器配置变量字符串化。
         */
        fun ingestScanResult(jsonResult: String?) {
            val obj = runCatching { JSONObject(jsonResult ?: return) }.getOrNull() ?: return
            val urls = obj.optJSONArray("urls")
            for (i in 0 until (urls?.length() ?: 0)) {
                val url = urls.optString(i).trim()
                if (url.isEmpty()) continue
                val abs = runCatching { WebLinkParser.resolveUrl(pageUrl, url) }.getOrNull()
                if (!abs.isNullOrBlank() && PlaylistSelection.isHlsUrl(abs)) add(abs, "", "")
            }
            val blobs = obj.optJSONArray("blobs")
            for (i in 0 until (blobs?.length() ?: 0)) {
                val blob = blobs.optString(i)
                M3U8_IN_STRING.findAll(blob).forEach { m ->
                    // 用捕获组（去头尾引号），避免把可选前引号一起带进 URL。
                    val raw = m.groupValues[1].trim()
                    // 配置变量里常是相对 / 协议相对路径，按页面 URL 补全成绝对地址。
                    val abs = runCatching { WebLinkParser.resolveUrl(pageUrl, raw) }.getOrNull()
                    if (!abs.isNullOrBlank() && PlaylistSelection.isHlsUrl(abs)) add(abs, "", "")
                }
            }
        }

        fun hasCandidate(): Boolean = candidates.isNotEmpty()

        fun hasMasterCandidate(): Boolean = candidates.any { PlaylistSelection.looksLikeMaster(it.url) }

        fun snapshot(): List<PlaylistCandidate> = candidates.toList()

        private fun refererOf(request: WebResourceRequest): String =
            request.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value.orEmpty()

        private fun userAgentOf(request: WebResourceRequest): String =
            request.requestHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value.orEmpty()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val SCAN_GRACE_MS = 2_500L
        private const val COUNT_FETCH_TIMEOUT_MS = 5_000
        private const val MAX_CONTENT_TYPE_PEEKS = 12
        const val TIMEOUT_MESSAGE = "这个页面没解析出可播放地址（解析超时）"

        /** 只读 DOM 扫描脚本：取 video/source 的 src，再 dump 常见播放器配置变量，返回值带回结果。 */
        private val SCAN_SCRIPT = """
            (function () {
              function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return u; } }
              var urls = [];
              function push(u) { if (u && urls.indexOf(u) < 0) urls.push(u); }
              var nodes = document.querySelectorAll('video, source');
              for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                push(el.currentSrc || el.src || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-url'));
              }
              var blobs = [];
              var vars = ['player_aaaa','player','playerConfig','player_config','playConfig','play_config','videoConfig','video_config','videoData','video_data','config','GLOBAL_CONFIG','videoInfo','vod'];
              for (var j = 0; j < vars.length; j++) {
                try {
                  var v = window[vars[j]];
                  if (v == null) continue;
                  var s = (typeof v === 'string') ? v : JSON.stringify(v);
                  if (s && s.indexOf('.m3u8') > -1) blobs.push(s);
                } catch (e) {}
              }
              return { urls: urls.map(abs), blobs: blobs };
            })();
        """.trimIndent()

        /** 从字符串化配置里提取 .m3u8 URL（含相对/协议相对路径，之后按页面 URL 补全）。 */
        private val M3U8_IN_STRING = Regex("""["']?([^"'\s]*\.m3u8[^"'\s]*)""")

        private val STATIC_EXTENSIONS = setOf(
            "css", "js", "mjs", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif",
            "woff", "woff2", "ttf", "otf", "eot", "html", "htm", "xml", "txt", "pdf", "zip",
            "gz", "mp4", "mp3", "m4a", "aac", "flv", "webm", "mkv", "ts", "m3u8", "m3u"
        )

        private fun mightBeDynamicMedia(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
            return ext !in STATIC_EXTENSIONS
        }

        private fun isHlsContentType(contentType: String): Boolean {
            val ct = contentType.lowercase()
            return ct.contains("mpegurl") || ct.contains("m3u8") ||
                ct.contains("application/x-mpegurl") || ct.contains("vnd.apple.mpegurl")
        }

        private fun peekContentType(url: String, referer: String, userAgent: String): String? {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 3_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_DESKTOP_UA })
                    if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
                    val code = conn.responseCode
                    if (code in 200..299) conn.contentType else null
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Content-Type 探测走独立线程池，绝不卡 WebView 自己的网络线程。 */
        private val peekExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "weblink-ct-peek").apply { isDaemon = true }
        }
    }
}
