package com.afeicn.aiplayer

import java.net.URL

/**
 * 「用户粘贴的地址」→ 「站点基址 + 该抓哪些页」的纯逻辑层。
 *
 * 用户添加源时粘的可能是首页、列表页、详情页或播放页，而搜索入口只认站点根。
 * [normalizedBase] 只去尾部斜杠，不会把 `https://x.com/html/tv/20830.html` 收敛成 `https://x.com`，
 * 于是所有候选规则都拼成 `…/20830.html/e/search/?keyboard=…` 这种垃圾地址，探测必然全失败。
 * 这里先把站点根算出来，探测才有意义；页面类型只用于决定抓哪几页和怎么向用户解释。
 */

/** 用户粘贴的地址可能是哪一类页面。 */
enum class PageKind(val label: String) {
    HOME("首页"),
    LIST("列表页"),
    DETAIL("详情页"),
    PLAY("播放页"),
    OTHER("其它页面")
}

/**
 * 一次「添加源」的地址结论。
 *
 * [directoryLike] 表示粘的地址是「目录型」（以 `/` 结尾或 index 页）——
 * 只有这种地址的所在目录才可能是一个装在子目录的站点根。
 */
data class SourceAddress(
    val original: String,
    val siteBase: String,
    val pageKind: PageKind,
    val directoryLike: Boolean
) {
    /** 粘贴地址的路径部分（不含前后斜杠）。 */
    private val path: String = original.removePrefix(siteBase).trim('/')

    /** 粘贴的就是个可浏览的站点根（根目录或 index 页）。 */
    val isSiteRoot: Boolean get() = directoryLike

    /**
     * 装在子目录的站点（`https://x.com/tv/`）要靠这个 base 兜底。
     *
     * 只有粘的是目录型地址才把它当候选：粘 `/vodplay/1-1-1.html` 时所在目录是内容路径，
     * 拿它拼搜索入口没有意义，多加一轮候选只会白跑请求。
     */
    val directoryBase: String
        get() {
            if (!directoryLike) return siteBase
            // 目录型地址：目录就是路径本身；带 index 页时去掉最后一段。
            val last = path.substringAfterLast('/')
            val dir = if (last == "index.html" || last == "index.htm" || last == "index.php") {
                path.substringBeforeLast('/', "")
            } else {
                path
            }
            return if (dir.isBlank()) siteBase else "$siteBase/${dir.trim('/')}"
        }

    /**
     * 探测该抓的页面（按候选 base 索引）：每个 base 抓它的目录页。
     *
     * 按 base 而不是按抓的 URL 索引，是为了让「生成候选规则」那一步能直接查到对应 HTML。
     */
    fun basePagesToFetch(): Map<String, String> = candidateBases().associateWith { "$it/" }

    /** 用户粘的那一页（粘站点根时为空，避免重复抓）。 */
    fun pastedPageToFetch(): String? = original.takeIf { it != siteBase }

    /** 生成候选规则时依次尝试的 base：站点根优先，子目录站点次之。 */
    fun candidateBases(): List<String> = listOf(siteBase, directoryBase).distinct()
}

object SourceAddressRules {

    /**
     * 把任意地址收敛成站点根 `scheme://host[:port]`。
     *
     * 非法地址原样返回（让上层给出可读提示，而不是悄悄存个空 base）。
     */
    fun siteBase(address: String): String {
        val trimmed = address.trim()
        if (trimmed.isBlank()) return trimmed
        val withScheme = if (trimmed.startsWith("//")) "https:$trimmed"
        else if (!trimmed.contains("://")) "https://$trimmed"
        else trimmed
        return runCatching {
            val url = URL(withScheme)
            val port = if (url.port in 0..65534 && !isDefaultPort(url.protocol, url.port)) ":${url.port}" else ""
            "${url.protocol.lowercase()}://${url.host.lowercase()}$port"
        }.getOrDefault(trimmed.trimEnd('/'))
    }

    /** 按路径猜页面类型。猜错不影响正确性——探测靠真实请求验证，这里只用于抓页面和提示文案。 */
    fun pageKind(address: String): PageKind {
        val path = runCatching { URL(if (address.contains("://")) address else "https://${address.trim()}").path }
            .getOrDefault("").lowercase().trim('/')
        if (path.isBlank() || path == "index.html" || path == "index.php") return PageKind.HOME
        val segments = path.split('/')
        return when {
            segments.any { it.contains("vodplay") || it.contains("playid") || it == "play" } -> PageKind.PLAY
            segments.any { it.contains("voddetail") || it == "detail" } -> PageKind.DETAIL
            segments.any { it.contains("vodtype") || it.contains("vodlist") || it == "type" || it == "list" } -> PageKind.LIST
            else -> PageKind.OTHER
        }
    }

    fun analyze(address: String): SourceAddress {
        val raw = address.trim()
        val base = siteBase(raw)
        val trimmed = raw.trimEnd('/')
        val path = trimmed.removePrefix(base).trim('/')
        val lastSegment = path.substringAfterLast('/')
        val directoryLike = raw.endsWith("/") || path.isBlank() ||
            lastSegment == "index.html" || lastSegment == "index.htm" || lastSegment == "index.php"
        return SourceAddress(
            original = trimmed,
            siteBase = base,
            pageKind = pageKind(raw),
            directoryLike = directoryLike
        )
    }

    /** 两个地址是否同一站点（同 scheme+host+port）。 */
    fun sameSite(a: String, b: String): Boolean = siteBase(a) == siteBase(b)

    private fun isDefaultPort(protocol: String, port: Int): Boolean = when (protocol.lowercase()) {
        "http" -> port == 80
        "https" -> port == 443
        else -> false
    }
}
