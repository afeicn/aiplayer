package com.afeicn.aiplayer

/**
 * 「继续下载更多集」的纯逻辑层。
 *
 * 背景：离线条目只记住了单集的播放/流地址（[OfflineItem.url]），所以下完第 1 集后想补下
 * 第 2 集，只能回「在线搜索」从头搜一遍。这里引入来源线索 [OfflineItem.detailUrl]——剧的
 * 详情页地址——凭它重新抓一次集数列表（[SubscriptionPipeline.fetchEpisodes]），再复用选集面板。
 *
 * 本文件只做纯计算：入口要不要显示、按钮显示什么字、抓取失败说什么话、面板里哪些集可选、
 * 以及如何从已落库的条目重建 [OnlineVideoSource]。不碰 Android 框架，方便纯 JVM 单测。
 */

/** 「继续下载」按钮的三个阶段：常态 → 查找来源（仅老数据）→ 获取集数。 */
enum class ContinuePhase { IDLE, LOCATING, FETCHING }

/** 「继续下载」要重新抓集数的目标：剧名 + 详情页 + 源信息（音频/视频决定解析路径）。 */
data class ContinueTarget(
    val title: String,
    val detailUrl: String,
    val sourceName: String = "",
    val headers: Map<String, String> = emptyMap(),
    val kind: MediaKind = MediaKind.VIDEO
) {
    /** 音频剧走 [SubscriptionPipeline.fetchAudioLinks] 的解析路径。 */
    val isAudio: Boolean get() = kind == MediaKind.AUDIO
}

object ContinueDownloadRules {

    /** 按钮常态文案。 */
    const val LABEL = "继续下载"

    /** 抓集数中的按钮文案（兼防重复点）。 */
    const val FETCHING_LABEL = "获取集数…"

    /**
     * 老数据没有来源详情页时，按钮先做一步「按剧名 + 站点重新定位详情页」。
     * 定位中的文案（也是防重复点的依据）。
     */
    const val LOOKUP_LABEL = "查找来源…"

    /** 详情页打不开（下架 / 源失效）。 */
    const val FETCH_FAILED = "这部剧的页面打不开了，可能已下架，需要重新搜索"

    /** 详情页能打开但没解析出集数。 */
    const val FETCH_EMPTY = "这部剧的页面没解析到集数，可能已下架，需要重新搜索"

    /**
     * 老数据没有来源详情页、兜底搜索也没找到时的话术。
     * 必须是可操作的（告诉用户下一步做什么），不能点了没反应。
     */
    const val LOOKUP_FAILED = "这部作品的来源页找不到了，请重新搜索后下载"

    /** 保留旧提示（老数据且没有来源信息）。 */
    const val NO_SOURCE = "这条下载没有来源信息，无法继续下载，请重新搜索"

    /** 入口是否显示：只有记住了详情页的条目才能直接重抓集数。 */
    fun canContinue(detailUrl: String?): Boolean = !detailUrl.isNullOrBlank()

    /** 入口是否显示（条目形式）。 */
    fun canContinue(item: OfflineItem?): Boolean = item != null && canContinue(item.detailUrl)

    /**
     * 是否需要先「查找来源」：真实数据里的老条目没有 [OfflineItem.detailUrl]，
     * 但依然要显示入口，点了先按剧名 + 站点重新定位详情页。
     */
    fun needsLookup(detailUrl: String?): Boolean = detailUrl.isNullOrBlank()

    /** 有没有可以拿去搜索的剧名（没有剧名就只能给出重新搜索的提示）。 */
    fun canLookup(title: String): Boolean = MediaTitle.clean(title).isNotEmpty()

    /** 按钮文案：抓取中显示「获取集数…」。 */
    fun buttonLabel(fetching: Boolean): String = if (fetching) FETCHING_LABEL else LABEL

    /** 按钮文案（含「查找来源…」中间态）。 */
    fun buttonLabel(phase: ContinuePhase): String = when (phase) {
        ContinuePhase.IDLE -> LABEL
        ContinuePhase.LOCATING -> LOOKUP_LABEL
        ContinuePhase.FETCHING -> FETCHING_LABEL
    }

    /** 按钮是否可点：抓取中禁止重复点。 */
    fun enabled(fetching: Boolean): Boolean = !fetching

    /** 按钮是否可点：定位/抓集中都禁止重复点。 */
    fun enabled(phase: ContinuePhase): Boolean = phase == ContinuePhase.IDLE

    /** 抓集数结果 → 提示文案；null 表示成功（可以直接开选集面板）。 */
    fun fetchMessage(error: Throwable?): String = if (error == null) FETCH_EMPTY else FETCH_FAILED

    /** 兜底定位失败时的提示文案。 */
    fun lookupMessage(found: Boolean): String? = if (found) null else LOOKUP_FAILED

    /** 抓成功后集数为空也算失败（页面在但没集数）。 */
    fun isEmptyResult(episodes: List<EpisodeLink>?): Boolean = episodes.isNullOrEmpty()

    /** 来源标签：真实数据把站点地址写进了 sourceName，展示时只留域名。 */
    fun sourceLabel(sourceName: String): String {
        val raw = sourceName.trim()
        if (raw.isEmpty()) return ""
        val host = ContinueSourceLocator.baseUrlOf(raw).substringAfter("://")
        return host.ifBlank { raw }
    }

    /**
     * 从一组离线条目里挑出「继续下载」的目标：优先带 [OfflineItem.detailUrl] 的那条；
     * 都为老数据（没有来源线索）时依然返回目标（title/sourceName 够用来兜底定位），
     * 只有完全没有条目时才返回 null。
     */
    fun targetOf(items: List<OfflineItem>, title: String? = null): ContinueTarget? {
        if (items.isEmpty()) return null
        val scoped = if (title == null) items else items.filter { OfflineLibrary.sameTitle(it.title, title) }
        if (scoped.isEmpty()) return null
        val withSource = scoped.firstOrNull { canContinue(it.detailUrl) }
        val clue = withSource ?: scoped.first()
        return ContinueTarget(
            title = MediaTitle.clean(title?.takeIf { it.isNotBlank() } ?: clue.title),
            detailUrl = withSource?.detailUrl?.trim().orEmpty(),
            sourceName = clue.sourceName,
            headers = clue.headers,
            kind = clue.kind
        )
    }

    /** 从剧卡（按剧名聚合的一组已下载集）挑目标：老数据也能拿到（detailUrl 为空 → 走兜底定位）。 */
    fun targetOf(series: DownloadedSeries): ContinueTarget? = targetOf(series.episodes.map { it.item }, series.title)

    /**
     * 兜底定位成功后把 [OfflineItem.detailUrl] 回写到这部剧的所有条目（纯函数，便于单测）。
     * 剧名比对用清洗后的归一化名字，历史脏标题（`X">X`）也能命中。
     */
    fun patchDetailUrl(
        item: OfflineItem,
        title: String,
        detailUrl: String,
        sourceName: String = ""
    ): OfflineItem {
        val url = detailUrl.trim()
        if (url.isEmpty() || !OfflineLibrary.sameTitle(item.title, title)) return item
        return item.copy(
            detailUrl = url,
            sourceName = item.sourceName.ifBlank { sourceName.trim() }
        )
    }

    /** 批量回写（[patchDetailUrl] 的列表版）。 */
    fun withDetailUrl(
        items: List<OfflineItem>,
        title: String,
        detailUrl: String,
        sourceName: String = ""
    ): List<OfflineItem> = items.map { patchDetailUrl(it, title, detailUrl, sourceName) }

    /** 从一条已落库条目重建源对象：只保留名称/请求头/媒体类型（详情页抓取与占位重试够用）。 */
    fun sourceOf(item: OfflineItem): OnlineVideoSource = OnlineVideoSource(
        id = "resolve",
        name = item.sourceName,
        base = "",
        referer = item.headers["Referer"].orEmpty(),
        userAgent = item.headers["User-Agent"].orEmpty(),
        type = if (item.kind == MediaKind.AUDIO) SourceType.AUDIO else SourceType.VIDEO
    )

    /** 目标 → 源对象（选集面板与提交入队都要它）。 */
    fun sourceOf(target: ContinueTarget): OnlineVideoSource = OnlineVideoSource(
        id = "resolve",
        name = target.sourceName,
        base = "",
        referer = target.headers["Referer"].orEmpty(),
        userAgent = target.headers["User-Agent"].orEmpty(),
        type = if (target.isAudio) SourceType.AUDIO else SourceType.VIDEO
    )

    /** 音频剧的直链列表 → 选集面板用的 [EpisodeLink]（「音频1 / 音频2 …」，集号从 1 开始）。 */
    fun audioEpisodes(links: List<String>): List<EpisodeLink> =
        links.mapIndexed { offset, url -> EpisodeLink(index = offset + 1, name = "音频${offset + 1}", url = url) }

    /**
     * 面板里真正可选的集：已下载 / 下载中 / 获取地址中的集一律过滤掉（与上一轮选集规则一致）。
     * 直接把 [EpisodeDownloadRules.availableIndices] 暴露给 UI，保证两处判定同一套规则。
     */
    fun selectableIndices(totalEpisodes: Int, statuses: Map<Int, DownloadTaskState>): Set<Int> =
        EpisodeDownloadRules.availableIndices(totalEpisodes, statuses)

    /** 面板副标题：抓到的总集数 + 这部剧已下载多少集。 */
    fun summary(totalEpisodes: Int, downloadedCount: Int): String =
        "共 $totalEpisodes 集，已下载 $downloadedCount 集"

    /** 提交成功后的提示（与在线搜索面板同一套「立即返回 + Snackbar + 后台解析」体验）。 */
    fun enqueuedNotice(count: Int): String = "已加入 $count 集，正在获取播放地址…"
}

/** 「继续下载」抓集数：视频剧解析详情页集数，音频剧解析直链列表（同一套 [EpisodeLink] 网格）。 */
fun SubscriptionPipeline.fetchContinueEpisodes(
    detailUrl: String,
    audio: Boolean,
    fetcher: PageFetcher = HttpPageFetcher()
): List<EpisodeLink> =
    if (audio) ContinueDownloadRules.audioEpisodes(fetchAudioLinks(detailUrl, fetcher))
    else fetchEpisodes(detailUrl, fetcher)
