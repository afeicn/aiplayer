package com.afeicn.aiplayer

/**
 * M3U / M3U8 播放列表（直播频道列表）解析。
 *
 * 目标是把 `#EXTINF` 的 tvg-name / 分组 / URL 拆成可点击的频道列表。
 * 只依赖字符串，不碰 Android 框架，便于纯 JVM 单测。
 *
 * 识别的格式（属性顺序不敏感，属性可缺失）：
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="http://…/cctv1.png" group-title="央视",CCTV-1 综合
 * http://example.com/live/cctv1.m3u8
 * ```
 */
data class M3uChannel(
    val name: String,
    val url: String,
    val group: String = "",
    val tvgName: String = "",
    val logo: String = "",
    /** `#EXTINF:-1`（时长为负）视为直播；无时长信息时按 .m3u8 兜底识别。 */
    val isLive: Boolean
)

object M3uParser {

    private const val EXTINF = "#EXTINF"

    /**
     * 判断拉到的文本是「直播频道列表」还是 HLS 媒体/主播放列表。
     * HLS 播放列表带 `#EXT-X-` 系列标签（#EXT-X-TARGETDURATION / #EXT-X-STREAM-INF…），
     * 应当直接交给播放器，不能拆成频道；频道列表则是 `#EXTINF` + 绝对 http(s) URL。
     */
    fun looksLikeChannelList(text: String): Boolean {
        if (text.lineSequence().any { it.trimStart().startsWith("#EXT-X-") }) return false
        return parse(text).any { it.url.startsWith("http://") || it.url.startsWith("https://") }
    }

    fun parse(text: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isEmpty() || (line.startsWith("#") && !line.startsWith(EXTINF))) {
                index++
                continue
            }
            if (line.startsWith(EXTINF)) {
                val info = parseExtinf(line)
                var urlIndex = index + 1
                var url: String? = null
                while (urlIndex < lines.size) {
                    val candidate = lines[urlIndex].trim()
                    // 又见到下一个 #EXTINF：说明当前条目缺 URL，直接丢弃，交给下一轮处理。
                    if (candidate.startsWith(EXTINF)) break
                    if (candidate.isEmpty() || candidate.startsWith("#")) {
                        urlIndex++
                    } else {
                        url = candidate
                        break
                    }
                }
                if (url != null) {
                    val fallback = url.substringBefore('?').substringAfterLast('/')
                    channels += M3uChannel(
                        name = info.title.ifBlank { info.tvgName.ifBlank { fallback } },
                        url = url,
                        group = info.group,
                        tvgName = info.tvgName,
                        logo = info.logo,
                        isLive = looksLive(url, info.duration)
                    )
                    index = urlIndex + 1
                } else {
                    // 没找到 URL：回到指向下一个 EXTINF（或末尾）的那一行，交给下一轮处理。
                    index = urlIndex
                }
            } else {
                // 没有 #EXTINF 的裸 URL 行：也收进来，给个能点的名字（非 URL 的杂散行直接忽略）。
                if (line.startsWith("http://") || line.startsWith("https://")) {
                    channels += M3uChannel(
                        name = line.substringBefore('?').substringAfterLast('/'),
                        url = line,
                        isLive = looksLive(line, null)
                    )
                }
                index++
            }
        }
        return channels
    }

    /** 解析单行 `#EXTINF`，缺字段时给默认值而不是抛异常。 */
    internal fun parseExtinf(line: String): ExtInf {
        // `#EXTINF:-1 …`：去掉前缀后还剩一个前导冒号，一并剥掉。
        val body = line.removePrefix(EXTINF).trim().removePrefix(":").trim()
        val commaIndex = body.indexOf(',')
        val beforeComma = if (commaIndex >= 0) body.substring(0, commaIndex) else body
        val title = if (commaIndex >= 0) body.substring(commaIndex + 1).trim() else ""

        val trimmed = beforeComma.trim()
        val durationStr = trimmed.takeWhile { !it.isWhitespace() }
        val duration = durationStr.toDoubleOrNull()?.toInt()
        val attributesPart = trimmed.drop(durationStr.length).trim()

        val attributes = linkedMapOf<String, String>()
        // 带引号的属性值（值里允许空格）：group-title="央视 综合"
        QUOTED_ATTR.findAll(attributesPart).forEach { match ->
            attributes[match.groupValues[1]] = match.groupValues[2]
        }
        // 不带引号的属性：tvg-id=cctv1（不覆盖已解析出的引号值）
        BARE_ATTR.findAll(attributesPart).forEach { match ->
            val key = match.groupValues[1]
            if (key !in attributes) attributes[key] = match.groupValues[2]
        }
        return ExtInf(
            duration = duration,
            title = title,
            tvgName = attributes["tvg-name"].orEmpty(),
            logo = attributes["tvg-logo"].orEmpty(),
            group = attributes["group-title"].orEmpty()
        )
    }

    private fun looksLive(url: String, duration: Int?): Boolean = when {
        duration != null -> duration < 0
        else -> url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase() == "m3u8"
    }

    private val QUOTED_ATTR = Regex("""([\w-]+)=["']([^"']*)["']""")
    private val BARE_ATTR = Regex("""([\w-]+)=([^\s"']+)""")

    data class ExtInf(
        val duration: Int?,
        val title: String,
        val tvgName: String,
        val logo: String,
        val group: String
    )
}
