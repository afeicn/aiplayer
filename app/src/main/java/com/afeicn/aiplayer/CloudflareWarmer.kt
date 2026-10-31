package com.afeicn.aiplayer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 预热能力抽象：让 [HttpPageFetcher] 在纯 JVM 单测里能注入假实现，
 * 不必为了构造 `CloudflareWarmer` 引入 Android `Context`。
 */
fun interface CloudflareWarmup {
    fun warm(host: String): Boolean
}

/**
 * 隐藏 WebView 仅用于「过 Cloudflare 挑战、拿 cookie」。
 *
 * 与 [HiddenWebViewResolver] 的职责分开：后者跑页面找 m3u8，本类只负责把
 * `cf_clearance` / `__cf_bm` 之类的 cookie 拿回来存进 [CloudflareCookieStore]，
 * 供 [HttpPageFetcher] 在普通 HTTP 请求里复用。
 *
 * 线程模型：WebView 必须在带 Looper 的线程（这里固定主线程）创建与销毁；[warm] 本身
 * 会阻塞调用线程轮询结果，调用方必须放在 IO 线程。若发现自己在主线程会直接返回 false，
 * 避免 ANR。
 *
 * 安全约定：WebView 不挂任何视图、不注入远程脚本；只在页面加载完成后用只读 JS
 * 读 `document.title` / 可见文本长度 / 挑战 DOM 标记，用后必定 `destroy()`。
 */
class CloudflareWarmer(private val appContext: Context) : CloudflareWarmup {

    override fun warm(host: String): Boolean = warm(host, DEFAULT_TIMEOUT_MS)

    /**
     * 加载 `https://<host>/`，等待 Cloudflare 挑战通过并把 cookie 存进 [CloudflareCookieStore]。
     *
     * @return true 表示挑战通过且拿到了非空 cookie；任何失败（超时、初始化异常、
     *         cookie 为空、主线程调用）都返回 false，不抛异常。
     */
    fun warm(host: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val normalized = CloudflareCookieStore.normalizeHost(host)
        if (normalized.isEmpty() || timeoutMs <= 0) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "warm() 会阻塞调用线程，不能在主线程调用")
            return false
        }

        val pageUrl = "https://$normalized/"
        val main = Handler(Looper.getMainLooper())
        val webViewRef = AtomicReference<WebView?>(null)
        val pageFinished = AtomicBoolean(false)
        val setupError = AtomicReference<Throwable?>(null)
        // 预热实际使用的 UA：cf_clearance 与 UA 绑定，HttpPageFetcher 必须复用同一个。
        val usedUserAgent = AtomicReference<String?>(null)

        main.post {
            try {
                val webView = WebView(appContext)
                webViewRef.set(webView)
                configureWebView(webView, pageFinished, setupError, usedUserAgent)
                webView.loadUrl(pageUrl)
            } catch (t: Throwable) {
                setupError.set(t)
            }
        }

        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (setupError.get() != null) return false
                if (pageFinished.get() && probePassed(main, webViewRef)) {
                    val cookie = readCookie(pageUrl, main, webViewRef)
                    if (!cookie.isNullOrBlank()) {
                        CloudflareCookieStore.put(
                            host = normalized,
                            rawCookie = cookie,
                            userAgent = usedUserAgent.get()
                        )
                        runCatching { CookieManager.getInstance().flush() }
                        Log.i(TAG, "预热成功：$normalized")
                        return true
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            Log.w(TAG, "预热超时：$normalized（${timeoutMs}ms）")
            return false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "预热失败：$normalized", t)
            return false
        } finally {
            // WebView 必须在创建它的主线程销毁；用后即毁，不泄漏。
            main.post {
                webViewRef.getAndSet(null)?.let { webView ->
                    runCatching { webView.stopLoading() }
                    runCatching { webView.clearCache(true) }
                    runCatching { webView.destroy() }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(
        webView: WebView,
        pageFinished: AtomicBoolean,
        setupError: AtomicReference<Throwable?>,
        usedUserAgent: AtomicReference<String?>
    ) {
        runCatching {
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 移动端 UA：Cloudflare 对移动端 WebView 的挑战通常比桌面严格筛选更容易通过。
            val userAgent = mobileUserAgent(webView.context)
            settings.userAgentString = userAgent
            usedUserAgent.set(userAgent)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished url=$url title=${view.title}")
                    pageFinished.set(true)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    // 只记录主框架错误；挑战页自身返回 403 时会正常渲染，不算加载失败。
                    if (request.isForMainFrame) {
                        Log.d(TAG, "主框架加载错误：${error.description}")
                    }
                }
            }
        }.onFailure { setupError.set(it) }
    }

    /** 组合判断：不是挑战标题 + 不是挑战 DOM + 可见文本 > 500 字符。 */
    private fun probePassed(main: Handler, webViewRef: AtomicReference<WebView?>): Boolean {
        val json = evaluateObject(main, webViewRef, PROBE_SCRIPT) ?: return false
        if (json.optBoolean("challenge", false)) return false
        if (isChallengeTitle(json.optString("title"))) return false
        return json.optInt("length", 0) > MIN_VISIBLE_TEXT_LENGTH
    }

    private fun readCookie(
        pageUrl: String,
        main: Handler,
        webViewRef: AtomicReference<WebView?>
    ): String? {
        val latch = CountDownLatch(1)
        val cookieRef = AtomicReference<String?>(null)
        main.post {
            try {
                cookieRef.set(CookieManager.getInstance().getCookie(pageUrl))
            } catch (t: Throwable) {
                Log.w(TAG, "读取 cookie 失败", t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return cookieRef.get()
    }

    private fun evaluateObject(
        main: Handler,
        webViewRef: AtomicReference<WebView?>,
        script: String
    ): JSONObject? {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<JSONObject?>(null)
        main.post {
            val webView = webViewRef.get()
            if (webView == null) {
                latch.countDown()
                return@post
            }
            runCatching {
                webView.evaluateJavascript(script) { raw ->
                    resultRef.set(runCatching { JSONObject(raw) }.getOrNull())
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        if (!latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return resultRef.get()
    }

    private fun isChallengeTitle(title: String): Boolean {
        val t = title.lowercase()
        return CHALLENGE_TITLE_MARKERS.any { t.contains(it) }
    }

    private fun mobileUserAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MOBILE_UA

    companion object {
        /**
         * 默认 25 秒：实测 WebView 进程首次初始化 + Cloudflare 挑战在冷启动时可超过 15 秒，
         * 正常热启动 5~10 秒即返回，超时值只影响失败时的等待上限。
         */
        const val DEFAULT_TIMEOUT_MS = 25_000L
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PROBE_TIMEOUT_MS = 2_000L
        private const val MIN_VISIBLE_TEXT_LENGTH = 500
        private const val TAG = "CloudflareWarmer"

        private const val DEFAULT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val CHALLENGE_TITLE_MARKERS = listOf(
            "just a moment",
            "attention required",
            "checking your browser",
            "verifying you are human",
            "请稍候",
            "安全验证"
        )

        /**
         * 只读探针：返回标题、可见文本长度、是否还有挑战 DOM。
         *
         * 未 attach 到窗口的 WebView 可能没有布局，`innerText` 会是空，所以为空时退化成
         * 「去掉 script/style 后的 textContent 长度」，避免把已经通过的页面误判成挑战页。
         */
        private val PROBE_SCRIPT = """
            (function () {
              try {
                var title = document.title || '';
                var length = 0;
                var body = document.body;
                if (body) {
                  length = (body.innerText || '').replace(/\s+/g, ' ').trim().length;
                  if (!length) {
                    var clone = body.cloneNode(true);
                    var junk = clone.querySelectorAll('script,style,noscript,template');
                    for (var i = 0; i < junk.length; i++) {
                      if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
                    }
                    length = (clone.textContent || '').replace(/\s+/g, ' ').trim().length;
                  }
                }
                var challenge = !!document.querySelector(
                  '#challenge-running, #cf-challenge-running, #challenge-form, ' +
                  '.cf-browser-verification, .cf-turnstile, #cf-please-wait'
                );
                return { title: title, length: length, challenge: challenge };
              } catch (e) {
                return { title: '', length: 0, challenge: true };
              }
            })();
        """.trimIndent()
    }
}
