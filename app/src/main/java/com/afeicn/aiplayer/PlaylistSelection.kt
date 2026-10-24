package com.afeicn.aiplayer

/**
 * 「粘贴网页链接自动解析」第二层兜底（隐藏 WebView + 请求拦截）的候选选择纯逻辑。
 *
 * 全部函数只依赖字符串，不碰 Android / 网络，便于纯 JVM 单测：测「哪个 URL 算播放列表」
 * 「多个候选怎么选」「怎么排除广告」。WebView 拦截到的候选先在这里跑选择策略，再交给
 * [HiddenWebViewResolver] 组装成 [NetworkStream]。
 */

/** 拦截到的一条候选流：绝对地址 + 触发时的 Referer / UA + 观测到的分片信息。 */
data class PlaylistCandidate(
    val url: String,
    val referer: String = "",
    val userAgent: String = "",
    /** 播放列表正文的条目数（分片 / 变体）；未抓取或非播放列表为 null。 */
    val segmentCount: Int? = null,
    /** 触发顺序（越大越晚触发，用于「最后触发」优先）。 */
    val order: Int = 0
)

object PlaylistSelection {

    /** URL 是否「像是 HLS 播放列表」：路径 / 查询含 .m3u8 / .m3u / mpegurl。 */
    fun isHlsUrl(url: String): Boolean {
        val lower = url.substringBefore('#').lowercase()
        return lower.contains(".m3u8") || lower.contains(".m3u") || lower.contains("mpegurl")
    }

    /** URL 路径是否像「主播放列表」：含 index.m3u8 / master / playlist 特征。 */
    fun looksLikeMaster(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        val file = path.substringAfterLast('/')
        return file.contains("index.m3u8") ||
            file.contains("master") ||
            path.contains("/master") ||
            file.contains("playlist")
    }

    /** URL 路径是否明显是广告：ad / ads / preroll / advert / promo 等词。 */
    fun isAdPath(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return AD_PATH.containsMatchIn(path)
    }

    private val AD_PATH = Regex("""(^|[/_.\-])(ad|ads|preroll|advert|advertise|advertisement|promo)([/_.\-]|$)""")

    /** 播放列表正文里的条目数：非空、非 `#` 注释的 URI 行（分片或变体）。 */
    fun countEntries(body: String): Int = body.lineSequence()
        .map { it.trim() }
        .count { it.isNotEmpty() && !it.startsWith("#") }

    /** 是否媒体播放列表：带 #EXTINF 分片，或只有 TARGETDURATION 而没有 STREAM-INF。 */
    fun isMediaPlaylist(body: String): Boolean =
        body.contains("#EXTINF", ignoreCase = true) ||
            (body.contains("#EXT-X-TARGETDURATION", ignoreCase = true) &&
                !body.contains("#EXT-X-STREAM-INF", ignoreCase = true))

    /**
     * 一条候选是否要当广告排除：
     *  1. 路径带 ad / ads / preroll 等广告词；
     *  2. 「1~2 个条目的短清单」且路径不像主播放列表（短清单常是插播广告）。
     */
    fun shouldExcludeAsAd(candidate: PlaylistCandidate): Boolean {
        if (isAdPath(candidate.url)) return true
        val count = candidate.segmentCount
        return count != null && count in 1..2 && !looksLikeMaster(candidate.url)
    }

    /**
     * 从多个候选中挑最好的一条：
     *  1. 先排除广告（路径 ad/…，或 1~2 条目的短清单）；
     *  2. 优先「路径像主播放列表」的候选；
     *  3. 同层按「条目数最多 → 最后触发」排序（maxWithOrNull 取排序最大的）。
     */
    fun selectBest(candidates: List<PlaylistCandidate>): PlaylistCandidate? {
        val viable = candidates.filterNot { shouldExcludeAsAd(it) }
        if (viable.isEmpty()) return null
        val masters = viable.filter { looksLikeMaster(it.url) }
        val pool = if (masters.isNotEmpty()) masters else viable
        return pool.maxWithOrNull(
            compareBy<PlaylistCandidate> { it.segmentCount ?: -1 }
                .thenBy { it.order }
        )
    }
}
