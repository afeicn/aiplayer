package com.afeicn.aiplayer

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 手机侧投屏代理的 URL / HLS 播放列表重写规则（纯 JVM，单测覆盖）。
 *
 * 为什么需要代理：
 * - 很多源的直链要 Referer / User-Agent / Cookie，电视自己拉会 403；
 * - HLS 的 m3u8 里是相对地址，电视只能拉到代理这一层，必须把所有 URI 也改写成代理地址；
 * - 代理在手机局域网上监听，电视从 `http://手机IP:端口/stream?t=令牌&u=原始地址` 拉流，
 *   手机带上原始请求头转发。
 *
 * 令牌只用于防止同一局域网里的其他设备随便借用代理，不承担加密职责。
 */
internal object CastProxyProtocol {

    const val STREAM_PATH = "/stream"

    private val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    fun proxyUrl(host: String, port: Int, token: String, originalUrl: String): String =
        "http://$host:$port$STREAM_PATH?t=${encode(token)}&u=${encode(originalUrl)}"

    /**
     * 解析电视发来的 `/stream?t=...&u=...` 请求，返回原始媒体地址。
     * 令牌不匹配、路径不对、不是 http(s) 一律返回 null。
     */
    fun parseRequestTarget(target: String, token: String): String? {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        if (uri.path != STREAM_PATH) return null
        val params = parseQuery(uri.rawQuery) ?: return null
        if (params["t"] != token) return null
        val original = params["u"] ?: return null
        return original.takeIf { DlnaProtocol.isCastable(it) }
    }

    /** 是否是 HLS 播放列表：看原始地址后缀或上游 Content-Type。 */
    fun isPlaylist(url: String, contentType: String?): Boolean {
        if (DlnaProtocol.pathExtension(url) in setOf("m3u8", "m3u")) return true
        val type = contentType?.lowercase().orEmpty()
        return "mpegurl" in type || "m3u8" in type || "x-mpegurl" in type
    }

    /**
     * 把 HLS 播放列表里的所有 URI 换成代理地址：
     * - 普通行（分片 / 子播放列表）：按播放列表最终地址解析成绝对地址；
     * - 带 `URI="..."` 的标签（EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA 等）：只替换属性值。
     *
     * 注释行与空行原样保留。
     */
    fun rewritePlaylist(
        text: String,
        baseUrl: String,
        host: String,
        port: Int,
        token: String
    ): String = text.lineSequence().joinToString("\n") { line ->
        rewriteLine(line, baseUrl, host, port, token)
    }

    fun resolveUrl(baseUrl: String, reference: String): String? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null
        // data: / skd: 这类不是网络资源，原样保留，不套代理。
        if (!trimmed.contains("://") && trimmed.substringBefore(':').lowercase() in setOf("data", "skd")) {
            return trimmed
        }
        return runCatching {
            val ref = URI(trimmed)
            if (ref.isAbsolute) ref.toString() else URI(baseUrl).resolve(ref).toString()
        }.getOrNull()
    }

    /** 上游没给 Content-Type 时，按后缀兜底。 */
    fun contentTypeFor(url: String): String = when (DlnaProtocol.pathExtension(url)) {
        "m3u8", "m3u" -> "application/vnd.apple.mpegurl"
        "html", "htm" -> "text/html; charset=utf-8"
        "json" -> "application/json"
        "mp4", "m4v" -> "video/mp4"
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    fun parseQuery(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrBlank()) return null
        return runCatching {
            rawQuery.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val index = pair.indexOf('=')
                val key = if (index >= 0) pair.substring(0, index) else pair
                val value = if (index >= 0) pair.substring(index + 1) else ""
                decode(key) to decode(value)
            }
        }.getOrNull()
    }

    private fun rewriteLine(
        line: String,
        baseUrl: String,
        host: String,
        port: Int,
        token: String
    ): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return line
        if (!trimmed.startsWith("#")) {
            val absolute = resolveUrl(baseUrl, trimmed) ?: return line
            return proxyUrl(host, port, token, absolute)
        }
        if (!trimmed.contains("URI=\"", ignoreCase = true)) return line
        return URI_ATTRIBUTE.replace(line) { match ->
            val raw = match.groupValues[1]
            val absolute = resolveUrl(baseUrl, raw) ?: return@replace match.value
            "URI=\"${proxyUrl(host, port, token, absolute)}\""
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
