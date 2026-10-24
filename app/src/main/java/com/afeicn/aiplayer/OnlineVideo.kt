package com.afeicn.aiplayer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「在线影视搜索」的纯逻辑层：MacCMS V10 接口解析、多源同名聚合、源健康冷却状态机、
 * 播放地址提取。全部只依赖字符串与 org.json，不碰 Android 框架，方便纯 JVM 单测。
 *
 * 数据流：
 *   OnlineVideoSource（源） -> MacCmsParser（解析 list[]） -> VodItem（影片 + 线路/集）
 *   -> VodAggregator（同名合并） -> AggregatedVod（一张卡，N 个源）
 *   -> onlineStream / onlineStreamEntry（挑一条线路 -> 网络流 -> MediaEntry，走现有播放链路）
 */

/** 在线搜索条目在 MediaEntry.folder 里的标记，播放页靠它识别「可一键换源」。 */
const val ONLINE_SEARCH_FOLDER = "在线搜索"

data class OnlineVideoSource(
    val id: String,
    val name: String,
    val base: String,
    val referer: String = "",
    val userAgent: String = "",
    val enabled: Boolean = true,
    val type: SourceType = SourceType.VIDEO,
    val searchRule: SearchRule? = null
) {
    /** 请求头只留非空字段，Referer / User-Agent 显式优先（复用第一块的拼装函数）。 */
    val headers: Map<String, String> get() = buildRequestHeaders(referer, userAgent)
    /** 去掉首尾空白和尾部斜杠，拼接口 URL 时不再出现双斜杠。 */
    val normalizedBase: String get() = base.trim().trimEnd('/')
}

/** 一条可播放的线路（某个「线路」下的某一集）。 */
data class VodPlayLine(val name: String, val url: String)

/** 一个「线路」分组（如 正片 / 蓝光 / 备用），内含若干集。 */
data class VodPlayGroup(val label: String, val lines: List<VodPlayLine>)

/** 一部影片：MacCMS `list[]` 里一条的解析结果。 */
data class VodItem(
    val vodName: String,
    val vodPic: String = "",
    val vodYear: String = "",
    val vodArea: String = "",
    val vodRemarks: String = "",
    val blurb: String = "",
    val playGroups: List<VodPlayGroup> = emptyList()
) {
    val allLines: List<VodPlayLine> get() = playGroups.flatMap { it.lines }
    val playFromLabels: List<String> get() = playGroups.map { it.label }
}

/** 一次「某源返回的影片」，聚合时按源拆分；[detailUrl] 非空表示命中来自 HTML 搜索页、需再抓详情页拿集数。 */
data class SourceHit(val source: OnlineVideoSource, val item: VodItem, val detailUrl: String = "")

/** 同名聚合后的一张卡：同名影片来自多个源时合并。 */
data class AggregatedVod(
    val name: String,
    val pic: String,
    val year: String,
    val area: String,
    val remarks: String,
    val hits: List<SourceHit>,
    val blurb: String = ""
) {
    val sourceCount: Int get() = hits.distinctBy { it.source.id }.size

    /** 是否有可直接播放的线路（MacCMS JSON 源有；HTML 搜索结果要再抓详情页拿集数）。 */
    val hasPlayableLines: Boolean get() = hits.any { it.item.playGroups.isNotEmpty() }

    /** HTML 命中的详情页地址（去重；空表示没有需二次抓取的详情页）。 */
    val detailUrls: List<String> get() = hits.map { it.detailUrl }.filter { it.isNotBlank() }.distinct()
}

/** HTML 搜索结果条目 → VodItem（详情页命中没有可播线路，playGroups 留空）。 */
fun SearchResultItem.toVodItem(): VodItem = VodItem(
    vodName = MediaTitle.clean(title),
    vodPic = pic,
    vodYear = year,
    vodArea = area,
    vodRemarks = remarks,
    playGroups = emptyList()
)

object MacCmsParser {

    /**
     * 解析 `{base}/api.php/provide/vod/?ac=detail&wd=关键词` 的响应。
     * 容错：非 JSON、缺字段、空列表、格式异常一律返回空列表而不是抛异常。
     */
    fun parseSearchResponse(text: String): List<VodItem> {
        if (text.isBlank()) return emptyList()
        val items = parseFromObject(text) ?: parseFromArray(text) ?: return emptyList()
        return items.filter { it.vodName.isNotBlank() }
    }

    /** 连通性测试用：拿到的是不是「合法 JSON」（对象带 list 数组，或顶层就是数组）。 */
    fun looksLikeValidResponse(text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching { JSONObject(text).has("list") }.getOrDefault(false) ||
            runCatching { JSONArray(text).length() >= 0 }.getOrDefault(false)
    }

    private fun parseFromObject(text: String): List<VodItem>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val list = root.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val item = runCatching { list.optJSONObject(index) }.getOrNull()
            item?.let(::parseVodItem)
        }
    }

    private fun parseFromArray(text: String): List<VodItem>? {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { index ->
            val item = runCatching { array.optJSONObject(index) }.getOrNull()
            item?.let(::parseVodItem)
        }
    }

    /** 解析单条 list 元素，缺字段给默认值（片名统一做一次 HTML 残渣清洗）。 */
    fun parseVodItem(obj: JSONObject): VodItem = VodItem(
        vodName = MediaTitle.clean(obj.optString("vod_name", "")),
        vodPic = obj.optString("vod_pic", "").trim(),
        vodYear = obj.optString("vod_year", "").trim(),
        vodArea = obj.optString("vod_area", "").trim(),
        vodRemarks = obj.optString("vod_remarks", "").trim(),
        // 简介优先取短简介 vod_blurb，没有再取全文 vod_content；
        // 字段常是「◎译名◎年代…」参数块，交给解析器挖剧情、剔资料块。
        blurb = VodBlurbParser.fromApiField(
            obj.optString("vod_blurb", "").ifBlank { obj.optString("vod_content", "") }
        ),
        playGroups = parsePlayUrl(obj.optString("vod_play_url", ""), obj.optString("vod_play_from", ""))
    )

    /**
     * 解析 `vod_play_url` / `vod_play_from`。
     * - `$$$` 分隔多个「线路」分组（对应 vod_play_from 里的多个线路名）。
     * - `#` 分隔同一线路下的多条（集）。
     * - `$` 分隔单条的「名称」与「地址」。
     * 形如：`正片$https://x/a.m3u8#第2集$https://x/b.m3u8$$$备用$https://y/a.m3u8`
     */
    fun parsePlayUrl(playUrl: String, playFrom: String): List<VodPlayGroup> {
        val urlGroups = playUrl.split("$$$").map { it.trim() }.filter { it.isNotEmpty() }
        if (urlGroups.isEmpty()) return emptyList()
        val fromLabels = playFrom.split("$$$").map { it.trim() }.filter { it.isNotEmpty() }
        return urlGroups.mapIndexed { index, groupRaw ->
            val label = fromLabels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "线路${index + 1}"
            val lines = groupRaw.split("#").mapIndexedNotNull { lineIndex, segment ->
                parseLine(segment, lineIndex)
            }
            VodPlayGroup(label, lines)
        }.filter { it.lines.isNotEmpty() }
    }

    private fun parseLine(segment: String, lineIndex: Int): VodPlayLine? {
        val seg = segment.trim()
        if (seg.isEmpty()) return null
        val parts = seg.split("$", limit = 2)
        val (name, url) = when {
            parts.size >= 2 && parts[1].trim().isNotEmpty() -> parts[0].trim() to parts[1].trim()
            parts.size == 1 -> "" to parts[0].trim()
            else -> parts[0].trim() to parts[1].trim()
        }
        if (url.isEmpty()) return null
        // 只收 http(s) 流，其余（rtmp / 相对路径 / 广告分片）一律丢弃，交给下一版处理。
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return VodPlayLine(name.ifBlank { "第${lineIndex + 1}集" }, url)
    }
}

object VodAggregator {

    /** 把多源返回的影片按「同名」合并成一张卡，按来源数降序、片名升序。 */
    fun aggregate(hits: List<SourceHit>): List<AggregatedVod> = hits
        .groupBy { it.item.vodName.trim().lowercase() }
        .map { (_, group) ->
            val first = group.first().item
            AggregatedVod(
                name = first.vodName.trim(),
                pic = group.firstNotNullOfOrNull { it.item.vodPic.takeIf(String::isNotBlank) }.orEmpty(),
                year = group.firstNotNullOfOrNull { it.item.vodYear.takeIf(String::isNotBlank) }.orEmpty(),
                area = group.firstNotNullOfOrNull { it.item.vodArea.takeIf(String::isNotBlank) }.orEmpty(),
                remarks = group.firstNotNullOfOrNull { it.item.vodRemarks.takeIf(String::isNotBlank) }.orEmpty(),
                blurb = group.firstNotNullOfOrNull { it.item.blurb.takeIf(String::isNotBlank) }.orEmpty(),
                hits = group
            )
        }
        .sortedWith(compareByDescending<AggregatedVod> { it.sourceCount }.thenBy { it.name })
}

/** 选中线路上带来源信息，供换源时定位「同一集在下一个源里的地址」。 */
data class PlayableLine(
    val source: OnlineVideoSource,
    val groupLabel: String,
    val lineIndex: Int,
    val line: VodPlayLine
)

/** 把一张卡里的所有可播线路摊平（源优先、线路优先、集序）。 */
fun AggregatedVod.playableLines(): List<PlayableLine> = hits.flatMap { hit ->
    hit.item.playGroups.flatMap { group ->
        group.lines.mapIndexed { index, line -> PlayableLine(hit.source, group.label, index, line) }
    }
}

/**
 * 由选中的一条线路，构造「换源队列」：每个源一条（优先同线路、同集序，找不到就取第一条）。
 * 选中源排在最前，队列首条即所选线路；点「下一个源」即切到下一条并尽量保住进度。
 */
fun buildSourceQueue(selected: AggregatedVod, chosen: PlayableLine): List<MediaEntry> {
    val sources = (listOf(chosen.source) + selected.hits.map { it.source })
        .distinctBy { it.id }
    return sources.mapNotNull { source ->
        val hit = selected.hits.firstOrNull { it.source.id == source.id } ?: return@mapNotNull null
        val group = hit.item.playGroups.firstOrNull { it.label.equals(chosen.groupLabel, ignoreCase = true) }
            ?: hit.item.playGroups.firstOrNull()
        val line = group?.lines?.getOrNull(chosen.lineIndex)
            ?: group?.lines?.firstOrNull()
            ?: hit.item.allLines.firstOrNull()
        line?.let { onlineStreamEntry(source, it, selected.name) }
    }
}

/** 一条线路 -> 网络流（纯函数，供单测校验播放地址与请求头提取）。 */
fun onlineStream(source: OnlineVideoSource, line: VodPlayLine, title: String): NetworkStream =
    NetworkStream(url = line.url, title = title, headers = source.headers)

/** 一条线路 -> MediaEntry，走第一块的网络播放链路；folder 标记「在线搜索」以便换源识别。 */
fun onlineStreamEntry(source: OnlineVideoSource, line: VodPlayLine, movieTitle: String): MediaEntry {
    val stream = onlineStream(source, line, movieTitle)
    return MediaEntry(
        uri = stream.uri,
        name = movieTitle,
        kind = MediaKind.VIDEO,
        folder = "$ONLINE_SEARCH_FOLDER · ${source.name}",
        extension = stream.extension,
        sizeBytes = 0L,
        lastModified = 0L,
        title = line.name,
        networkHeaders = stream.headers
    )
}

// ==================== 源健康度：连续失败阶梯冷却 ====================

data class SourceHealth(
    val consecutiveFailures: Int = 0,
    val cooldownUntil: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L
)

enum class SourceCooldownTier { ACTIVE, MINUTES_30, HOURS_24, LONG_TERM }

object SourceHealthPolicy {
    const val THRESHOLD_30MIN = 3
    const val THRESHOLD_24H = 6
    const val THRESHOLD_LONG_TERM = 9
    const val COOLDOWN_30MIN_MS = 30L * 60L * 1000L
    const val COOLDOWN_24H_MS = 24L * 60L * 60L * 1000L

    /** 成功一次即清零：连续失败计数与冷却全部复位。 */
    fun recordSuccess(now: Long): SourceHealth = SourceHealth(lastSuccessAt = now)

    /** 失败累加，达到阈值进入对应阶梯冷却；长期冷却用 Long.MAX_VALUE 表示。 */
    fun recordFailure(now: Long, health: SourceHealth): SourceHealth {
        val failures = health.consecutiveFailures + 1
        val cooldownUntil = when {
            failures < THRESHOLD_30MIN -> 0L
            failures < THRESHOLD_24H -> now + COOLDOWN_30MIN_MS
            failures < THRESHOLD_LONG_TERM -> now + COOLDOWN_24H_MS
            else -> Long.MAX_VALUE
        }
        return health.copy(
            consecutiveFailures = failures,
            cooldownUntil = cooldownUntil,
            lastFailureAt = now
        )
    }

    /** 设置页手动恢复：完全清零。 */
    fun recover(): SourceHealth = SourceHealth()

    /** 冷却期内不参与搜索。 */
    fun isCoolingDown(health: SourceHealth, now: Long): Boolean = health.cooldownUntil > now

    fun tier(health: SourceHealth, now: Long): SourceCooldownTier = when {
        health.consecutiveFailures >= THRESHOLD_LONG_TERM -> SourceCooldownTier.LONG_TERM
        !isCoolingDown(health, now) -> SourceCooldownTier.ACTIVE
        health.consecutiveFailures >= THRESHOLD_24H -> SourceCooldownTier.HOURS_24
        else -> SourceCooldownTier.MINUTES_30
    }
}
