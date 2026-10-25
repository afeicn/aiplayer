package com.afeicn.aiplayer

import android.net.Uri
import androidx.media3.common.MediaItem

/**
 * 一条网络媒体流（HLS 点播/直播、直链 MP4）。
 *
 * 走现有 ExoPlayer 播放链路（[NetworkStream] -> [networkStreamEntry] -> MediaEntry ->
 * buildMediaItem -> controller），不另建播放器。headers 是可选的按条目传入的请求头
 * （部分源要 Referer / User-Agent）。
 */
data class NetworkStream(
    val url: String,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val isLive: Boolean = false
) {
    val uri: Uri get() = Uri.parse(url)
    /** 从 URL 尾部取一个可读名字，作为标题兜底。 */
    val displayTitle: String get() = title.ifBlank {
        url.substringBefore('?').substringAfterLast('/').ifBlank { "网络媒体" }
    }
    val extension: String get() = url
        .substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
    val isHls: Boolean get() = extension == "m3u8" || extension == "m3u"
    val isMp4: Boolean get() = extension == "mp4"
}

/**
 * 挂在 [MediaItem.LocalConfiguration.tag] 上的网络播放标记：自定义请求头 + 是否只读离线缓存。
 * [NetworkMediaSourceFactory.createMediaSource] 读到它，就给这一条的 DataSource 注入这些头
 * （并且走 CacheDataSource，离线时 BLOCK_ON_CACHE）。
 */
data class NetworkRequestHeaders(
    val headers: Map<String, String>,
    val offlineOnly: Boolean = false
)

/**
 * 拼装一条网络流的请求头：extra 先写入，显式 Referer / User-Agent 后写入（优先），
 * 空白值一律丢弃。纯函数，方便单测。
 */
fun buildRequestHeaders(
    referer: String? = null,
    userAgent: String? = null,
    extra: Map<String, String> = emptyMap()
): Map<String, String> = buildMap {
    extra.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) put(key.trim(), value.trim())
    }
    userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
    referer?.trim()?.takeIf { it.isNotEmpty() }?.let { put("Referer", it) }
}

/**
 * 把网络流转成现有播放链路用的 [MediaEntry]（不落库，只做一次播放会话）。
 * 解析播放页时调用方知道所属剧名与集号，就把它们传进来：播放页据此持久化观看进度，
 * 不依赖 [NetworkStream.title]（可能只是网页标题，无法可靠拆出集号）。
 */
fun networkStreamEntry(
    stream: NetworkStream,
    seriesTitle: String? = null,
    episodeNumber: Int? = null
): MediaEntry = MediaEntry(
    uri = stream.uri,
    name = seriesTitle?.takeIf { it.isNotBlank() } ?: stream.displayTitle,
    kind = MediaKind.VIDEO,
    folder = "网络地址",
    extension = stream.extension,
    sizeBytes = 0L,
    lastModified = 0L,
    title = stream.title,
    networkHeaders = stream.headers,
    isLive = stream.isLive,
    episodeNumber = episodeNumber
)

/** 拉取 m3u / m3u8 播放列表文本（用于频道列表解析），带可选自定义请求头。 */
object NetworkPlaylistFetcher {
    fun fetch(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): String {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
