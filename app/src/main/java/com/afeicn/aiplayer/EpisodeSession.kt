package com.afeicn.aiplayer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「粘贴剧集详情页 → 选集 → 播放」的会话状态与偏好。
 *
 * [EpisodeSession] 是内存里的当前剧集上下文：选集 UI、语音「看第 N 集」、
 * 自动连播都从这取同一份列表，不另开旁路。
 * [EpisodeProgressStore] 落地两样偏好：自动连播开关（默认开）+ 按「剧名+域名」记上次看到第几集。
 * [resolveWebLink] 是「网页 → 网络流」的唯一解析入口（HTTP 源码解析 + WebView 兜底）。
 */
object EpisodeSession {
    data class Context(val title: String, val domain: String, val episodes: List<EpisodeLink>)

    /** 最近一次粘贴出的剧集列表（null 表示当前没有选集上下文）。 */
    var current: Context? = null
    /** 当前正在播的第几集（1 起），自动连播用它找下一集。 */
    var playingIndex: Int = 0
    /** 当前正在播的网络流 uri（用于自动连播时校验「确实是这部剧的这集播完了」）。 */
    var playingStreamUri: String? = null

    fun reset() {
        current = null
        playingIndex = 0
        playingStreamUri = null
    }
}

class EpisodeProgressStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("episode_selection", Context.MODE_PRIVATE)

    fun autoPlayNext(): Boolean = prefs.getBoolean("auto_play_next", true)
    fun setAutoPlayNext(enabled: Boolean) {
        prefs.edit().putBoolean("auto_play_next", enabled).apply()
    }

    fun lastIndex(title: String, domain: String): Int = prefs.getInt(key(title, domain), 0)
    fun saveLastIndex(title: String, domain: String, index: Int) {
        prefs.edit().putInt(key(title, domain), index).apply()
    }

    private fun key(title: String, domain: String): String =
        "last:${domain.trim().lowercase()}:${title.trim().lowercase()}"
}

/** 网页 → 网络流 的唯一解析入口：源码解析不到时用 WebView 兜底渲染。 */
suspend fun resolveWebLink(
    url: String,
    appContext: Context,
    onFallbackHint: (String) -> Unit = {}
): WebLinkResult = withContext(Dispatchers.IO) {
    runCatching {
        WebLinkResolver.resolve(url, HttpPageFetcher()) { pageUrl ->
            onFallbackHint("源码解析不到，正在用内置浏览器渲染页面…")
            HiddenWebViewResolver(appContext).resolve(pageUrl)
        }
    }.getOrElse {
        WebLinkResult.Failure(WebLinkFailureKind.NETWORK, "解析失败：${it.message ?: "未知错误"}")
    }
}
