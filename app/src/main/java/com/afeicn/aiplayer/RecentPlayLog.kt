package com.afeicn.aiplayer

/**
 * 首页「最近浏览」的一条记录：点进过详情页的作品就进这里。
 * [playUrl] 是在线作品最后一集的播放页地址（大卡续播凭它重新解析流地址）；
 * 只浏览没播放、或离线剧时留空。
 * [detailUrl] 是作品的详情页地址：记上了，点最近浏览条目就直接进详情页，
 * 不再按标题搜索（带「[全集]」等标记的标题常在源上搜不到）。
 */
data class RecentPlay(
    val title: String,
    val cover: String,
    val timeMillis: Long,
    val playUrl: String = "",
    val detailUrl: String = "",
    /** 上次播到第几集：不落库，由 ViewModel 发布时从进度存储补齐，界面显示用。 */
    val lastEpisode: Int = 0
)

/**
 * 最近浏览列表的纯计算：新记录置顶、按清洗后剧名去重、封顶截断，
 * 外加 SharedPreferences 用的行式编解码（一行一条，字段用 | 分隔）。
 * 不碰 Android 框架，方便纯 JVM 单测。
 */
object RecentPlayLog {

    const val MAX = 12

    /**
     * 新记录置顶；同名（清洗后）旧记录剔除；超过 [max] 截尾。
     * 再浏览（playUrl 为空）不冲掉旧的续播地址：浏览只更新时间，播放才带地址。
     * 海报同理：新记录海报为空（部分源搜索结果没图）时保留旧海报，不越记越空。
     */
    fun merge(existing: List<RecentPlay>, entry: RecentPlay, max: Int = MAX): List<RecentPlay> {
        if (entry.title.isBlank()) return existing
        val previous = existing.firstOrNull { OfflineLibrary.sameTitle(it.title, entry.title) }
        var kept = entry
        if (previous != null) {
            if (kept.playUrl.isBlank()) kept = kept.copy(playUrl = previous.playUrl)
            if (kept.cover.isBlank()) kept = kept.copy(cover = previous.cover)
            if (kept.detailUrl.isBlank()) kept = kept.copy(detailUrl = previous.detailUrl)
        }
        val fresh = listOf(kept) + existing.filter { !OfflineLibrary.sameTitle(it.title, entry.title) }
        return fresh.take(max.coerceAtLeast(1))
    }

    /** 字段里的 | 和换行会破坏行式格式，写入前统一换成空格。 */
    private fun sanitize(field: String): String =
        field.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')

    /**
     * 海报自愈：给同名且封面为空的记录补上封面，不动顺序/时间/播放地址；
     * 已有封面不覆盖。全空或没有同名记录时原样返回。
     */
    fun patchCover(existing: List<RecentPlay>, title: String, cover: String): List<RecentPlay> {
        if (cover.isBlank() || title.isBlank()) return existing
        return existing.map {
            if (OfflineLibrary.sameTitle(it.title, title) && it.cover.isBlank()) it.copy(cover = cover) else it
        }
    }

    /** 第 5 段 detailUrl 是后加的：老记录只有 4 段，解码时留空即可（向后兼容）。 */
    fun encode(entries: List<RecentPlay>): String =
        entries.joinToString("\n") {
            "${sanitize(it.title)}|${sanitize(it.cover)}|${it.timeMillis}|${sanitize(it.playUrl)}|${sanitize(it.detailUrl)}"
        }

    fun decode(raw: String): List<RecentPlay> =
        raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            RecentPlay(
                title = parts[0],
                cover = parts[1],
                timeMillis = parts[2].toLongOrNull() ?: 0L,
                playUrl = if (parts.size >= 4) parts[3] else "",
                detailUrl = if (parts.size >= 5) parts[4] else ""
            )
        }.toList()

    /** 这批搜索结果里有没有这部剧本身（剥掉「[全集]」等附加标记后再比剧名）。 */
    fun hasMatch(cards: List<AggregatedVod>, title: String): Boolean =
        cards.any { OfflineLibrary.sameTitle(it.name, title) }

    /** 从搜索结果里挑这部剧的卡：同名（剥标记）优先，否则第一张兜底。 */
    fun matchCard(cards: List<AggregatedVod>, title: String): AggregatedVod? =
        cards.firstOrNull { OfflineLibrary.sameTitle(it.name, title) } ?: cards.firstOrNull()

    private val URL_ORIGIN = Regex("^(https?)://([^/\\s]+)", RegexOption.IGNORE_CASE)

    /** URL 的 `scheme://host` 来源（小写）；解析不出返回 null。 */
    private fun originOf(url: String): String? =
        URL_ORIGIN.find(url.trim())
            ?.let { "${it.groupValues[1].lowercase()}://${it.groupValues[2].lowercase()}" }

    /**
     * 凭记录里的 [RecentPlay.detailUrl] 直接构造详情页卡，跳过按标题搜索：
     * 优先复用同来源的已配置源（请求头、referer 齐全），没有就按 URL 来源构造轻量源
     * （抓集数只需详情页地址 + 站点根做 referer，见 EpisodeSheetViewModel.loadDetail）。
     * 没有详情页地址、或地址不是可识别的 http(s) 来源时返回 null，交回搜索链路兜底。
     */
    fun directVod(entry: RecentPlay, sources: List<OnlineVideoSource> = emptyList()): AggregatedVod? {
        val url = entry.detailUrl.trim()
        if (url.isEmpty() || entry.title.isBlank()) return null
        val origin = originOf(url) ?: return null
        val source = sources.firstOrNull { originOf(it.base) == origin }
            ?: OnlineVideoSource(
                id = "recent-direct",
                name = origin.substringAfter("//"),
                base = origin,
                referer = "$origin/"
            )
        return AggregatedVod(
            name = entry.title,
            pic = entry.cover,
            year = "",
            area = "",
            remarks = "",
            hits = listOf(SourceHit(source, VodItem(vodName = entry.title, vodPic = entry.cover), detailUrl = url))
        )
    }
}
