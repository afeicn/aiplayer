package com.afeicn.aiplayer

import android.content.Context

/**
 * 业务代码里的「默认网络抓取器」统一入口。
 *
 * 所有需要抓网页 / 搜索源的地方都从这里拿，而不是 `HttpPageFetcher()` / `HttpSearchTransport()`：
 *  - 请求自动带上 [CloudflareCookieStore] 里预热好的 cookie，并复用预热时的 UA
 *    （`cf_clearance` 与 UA 绑定，换 UA 会被重新挑战）；
 *  - 遇到 Cloudflare 403 时通过 [GuardedCloudflareWarmer] 预热一次，成功后重试；
 *  - 预热失败由门禁标记 `challenge_failed`，30 分钟内不再重试。
 *
 * 构造本身不碰 Android 框架：Context 只在真正触发预热时才用到，纯 JVM 单测
 * 注入自己的 [PageFetcher] / [SearchTransport] 时不走这里。
 */
object DefaultFetchers {

    /** 带 Cloudflare 预热的页面抓取器。[context] 为空时用 [AppContainer] 的 Application Context。 */
    fun pageFetcher(context: Context? = null): PageFetcher = HttpPageFetcher(
        cookieStore = CloudflareCookieStore,
        warmer = warmupFactory(context)
    )

    /** 带 Cloudflare 预热的搜索 transport（搜索 / 探测 / 详情页定位共用）。 */
    fun searchTransport(context: Context? = null): SearchTransport = HttpSearchTransport(
        cookieStore = CloudflareCookieStore,
        warmer = warmupFactory(context)
    )

    /**
     * 每次预热时才构造：Context 为空就走全局 [AppContainer]（App 启动时已初始化），
     * 避免纯逻辑测试仅仅因为构造抓取器就触发 Android 依赖。
     */
    private fun warmupFactory(context: Context?): () -> CloudflareWarmup? = {
        val appContext = context?.applicationContext
        if (appContext != null) {
            GuardedCloudflareWarmer(
                delegate = CloudflareWarmer(appContext),
                status = SourceStatusStore(appContext)
            )
        } else {
            AppContainer.cloudflareWarmer
        }
    }
}

/** 语法糖：业务代码直接 `defaultPageFetcher()`。 */
fun defaultPageFetcher(context: Context? = null): PageFetcher = DefaultFetchers.pageFetcher(context)

/** 语法糖：业务代码直接 `defaultSearchTransport()`。 */
fun defaultSearchTransport(context: Context? = null): SearchTransport =
    DefaultFetchers.searchTransport(context)
