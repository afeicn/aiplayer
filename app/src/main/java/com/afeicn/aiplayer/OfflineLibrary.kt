package com.afeicn.aiplayer

/**
 * 已完成的离线下载 → 媒体库条目的纯映射层。
 *
 * 离线缓存的内容是分片缓存（SimpleCache + DownloadManager），不是单个 mp4 文件，
 * 所以不能靠扫文件进媒体库。这里把 [OfflineDownloadManager.downloads] 里状态为
 * COMPLETED 的下载索引映射成媒体条目，供「已下载」分组展示、选集「已下载」角标、
 * 去重与已下载判定使用。全部是纯函数（只依赖字符串与数据类），不碰 Android 框架。
 */

/** 一条已下载的媒体（映射到媒体库展示；播放复用 [OfflineItem.toMediaEntry] 的离线链路）。 */
data class DownloadedEpisode(
    val id: String,
    val title: String,
    val episodeName: String,
    val episodeNumber: Int?,
    val sizeBytes: Long,
    val completedAt: Long,
    val cover: String,
    val item: OfflineItem,
    /** 该剧已知总集数（选集下载时记录；0 表示未知）。 */
    val totalEpisodes: Int = 0,
    /** 季号（0 表示没有季；存量迁移后来自 [OfflineItem.season]）。 */
    val season: Int = 0
) {
    /** 播放用的媒体条目（离线只读缓存；Uri 由 UI 层解析，保持本类为纯数据）。 */
    fun mediaEntry(): MediaEntry = item.toMediaEntry()
}

/** 按剧名折叠的一张卡：同一部剧的多集收在一起。 */
data class DownloadedSeries(
    val title: String,
    val episodes: List<DownloadedEpisode>,
    val cover: String = "",
    /** 这部剧的来源详情页地址（「继续下载」凭它重抓集数；老数据为空）。 */
    val detailUrl: String = "",
    /** 来源名（重建 [OnlineVideoSource] 用）。 */
    val sourceName: String = "",
    /** 媒体类型（音频剧走音频解析路径）。 */
    val kind: MediaKind = MediaKind.VIDEO
) {
    val downloadedCount: Int get() = episodes.size

    /** 带来源线索的那一条（「继续下载」的取数对象）；空表示整张卡都是没有来源信息的老数据。 */
    val sourceItem: OfflineItem? get() = episodes.firstOrNull { ContinueDownloadRules.canContinue(it.item.detailUrl) }?.item

    /**
     * 能否「继续下载」：只要这部剧有已下载的集就显示入口。
     * 老数据没有 [OfflineItem.detailUrl] 时点了会先按剧名 + 站点兜底定位详情页。
     */
    val canContinueDownload: Boolean get() = episodes.isNotEmpty()

    /** 已知总集数（选集下载时记录，0 表示未知）。 */
    val totalEpisodes: Int get() = episodes.map { it.totalEpisodes }.maxOrNull() ?: 0

    val totalSizeBytes: Long get() = episodes.sumOf { it.sizeBytes }

    val newestCompletedAt: Long get() = episodes.maxOfOrNull { it.completedAt } ?: 0L

    /** 卡上标注文案：知道总集数且还没下全时用「共 N 集，已下载 M 集」，否则只写「已下载 M 集」。 */
    val countLabel: String get() =
        if (totalEpisodes > downloadedCount) "共 $totalEpisodes 集，已下载 $downloadedCount 集"
        else "已下载 $downloadedCount 集"
}

object OfflineLibrary {

    /** 只保留已完成的下载任务。 */
    fun completed(downloads: List<OfflineDownload>): List<OfflineDownload> =
        downloads.filter { it.state == DownloadTaskState.COMPLETED }

    /** 从集名（或标题）解析集号：「第01集」→1、「EP01」→1、「音频3」→3、中文数字「第十一集」→11。 */
    fun episodeNumber(name: String): Int? = EpisodeNaming.episodeNumber(name)

    /** 单条已完成下载 → 媒体条目；剧名先做 HTML 残渣清洗（历史脏数据也能正常展示与聚合）。 */
    fun toEpisode(download: OfflineDownload): DownloadedEpisode {
        val item = download.item
        val title = MediaTitle.clean(item.title)
        val episodeName = MediaTitle.clean(item.episodeName)
        // 迁移后的结构化字段优先；老数据（字段为 0）继续从集名/标题兜底解析。
        val number = item.episodeNumber.takeIf { it > 0 }
            ?: episodeNumber(episodeName)
            ?: episodeNumber(title)
        val season = item.season.takeIf { it > 0 }
            ?: EpisodeNaming.splitSeason(title).number
            ?: 0
        return DownloadedEpisode(
            id = item.id,
            title = title,
            episodeName = episodeName,
            episodeNumber = number,
            sizeBytes = download.downloadedBytes.takeIf { it > 0 } ?: download.totalBytes,
            completedAt = download.completedAt.takeIf { it > 0 } ?: item.createdAt,
            cover = item.cover,
            item = item,
            totalEpisodes = item.totalEpisodes,
            season = season
        )
    }

    /**
     * 下载条目的显示标题（统一命名规则，见 [EpisodeNaming.displayName]）：
     *
     *  - 无季：「剧名-集号」（集号整数不补零）→「我不是大师-1」；
     *  - 有季：「剧名S季-集号」→「我不是大师S1-1」（季从标题识别并剥掉）；
     *  - 剧名清洗沿用 [MediaTitle]，并去掉 `[全集]`/`[完结]`/`[更新至24集]` 这类尾部状态标注；
     *  - 没有集号时退回原集名，剧名与集名都空时退回「未命名」，绝不出空标题。
     *
     * 视频与音频用同一套规则（音频不再单独写「音频 · 第 N 段」）。
     * [kind] 仅为调用点兼容保留，不影响命名。
     */
    fun displayTitle(title: String, episodeName: String, episodeNumber: Int?, kind: MediaKind): String =
        EpisodeNaming.displayName(title, episodeName, episodeNumber)

    /** [DownloadedEpisode] 的显示标题，见 [displayTitle]。 */
    fun displayTitle(episode: DownloadedEpisode, inDirectory: Boolean = false): String =
        EpisodeNaming.displayName(
            episode.title,
            episode.episodeName,
            episode.episodeNumber,
            inDirectory,
            season = episode.season.takeIf { it > 0 }
        )

    /** 目录模式（文件已在以剧名命名的目录里）的文件名：只出 `{集号}` 或 `S{季}-{集号}`。 */
    fun fileName(episode: DownloadedEpisode): String = EpisodeNaming.fileName(
        episode.title, episode.episodeName, episode.episodeNumber, episode.season.takeIf { it > 0 }
    )

    /** 已完成下载 → 媒体条目列表（可选按媒体类型过滤），去重后按「剧名 + 集号数值」排序。 */
    fun episodes(downloads: List<OfflineDownload>, kind: MediaKind? = null): List<DownloadedEpisode> {
        val mapped = completed(downloads)
            .filter { kind == null || it.item.kind == kind }
            .map(::toEpisode)
        return dedupe(mapped).sortedWith(EpisodeNaming.EPISODE_ORDER)
    }

    /** 去重：先按下载 id 去重，再按（剧名 + 集号）去重（同一集多源只留第一条）。 */
    fun dedupe(episodes: List<DownloadedEpisode>): List<DownloadedEpisode> {
        val byId = LinkedHashMap<String, DownloadedEpisode>()
        episodes.forEach { byId.putIfAbsent(it.id, it) }
        val byEpisode = LinkedHashMap<String, DownloadedEpisode>()
        byId.values.forEach { e ->
            val key = "${MediaTitle.clean(e.title).lowercase()}\u0000${e.episodeNumber}"
            byEpisode.putIfAbsent(key, e)
        }
        return byEpisode.values.toList()
    }

    /** 按剧名分组折叠：同一部剧的多集收成一张卡（剧名先清洗，历史脏标题也能聚到一起）。 */
    fun groupByTitle(episodes: List<DownloadedEpisode>): List<DownloadedSeries> =
        episodes.groupBy { MediaTitle.clean(it.title) }
            .map { (rawTitle, list) ->
                val title = MediaTitle.clean(rawTitle)
                val ordered = list.sortedWith(EpisodeNaming.WITHIN_SERIES)
                // 来源线索取「有详情页」的那一条：同一部剧多集下载时它们写的都是同一个详情页。
                val source = list.firstOrNull { ContinueDownloadRules.canContinue(it.item.detailUrl) }
                    ?: list.firstOrNull()
                DownloadedSeries(
                    title = title,
                    episodes = ordered,
                    cover = list.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
                    detailUrl = source?.item?.detailUrl?.trim().orEmpty(),
                    sourceName = source?.item?.sourceName.orEmpty(),
                    kind = source?.item?.kind ?: MediaKind.VIDEO
                )
            }
            .sortedBy { it.title.lowercase() }

    /** 已下载 URL 集合（归一化 trim 后精确匹配，供「已下载」标记 / 判定）。 */
    fun downloadedUrls(downloads: List<OfflineDownload>): Set<String> =
        completed(downloads).map { it.item.url.trim() }.toSet()

    /** 判定某条 URL 是否已下载。 */
    fun isDownloaded(url: String, downloadedUrls: Set<String>): Boolean =
        url.trim() in downloadedUrls

    /** 某部剧已下载的集号集合（供选集网格打「已下载」角标）。 */
    fun downloadedEpisodeNumbers(downloads: List<OfflineDownload>, title: String): Set<Int> =
        episodes(downloads).filter { sameTitle(it.title, title) }.mapNotNull { it.episodeNumber }.toSet()

    /**
     * 某部剧每集在下载体系里的「当前状态」（供选集网格角标与防重复）。
     *
     * 集号优先取占位记录里的 [OfflineItem.resolveIndex]（获取地址中/失败任务没有真实地址，靠它定位），
     * 否则从集名/标题解析。同一集多源并存时，取「更靠前」的状态（已完成 > 下载中 > 获取地址中 >
     * 排队 > 暂停等待 > 失败），保证「已下载」优先于「还在下」优先于「可重下」。
     */
    fun episodeStatuses(downloads: List<OfflineDownload>, title: String): Map<Int, DownloadTaskState> {
        val result = mutableMapOf<Int, DownloadTaskState>()
        for (download in downloads) {
            if (!sameTitle(download.item.title, title)) continue
            val index = (download.item.resolveIndex.takeIf { it > 0 })
                ?: (download.item.episodeNumber.takeIf { it > 0 })
                ?: episodeNumber(download.item.episodeName)
                ?: episodeNumber(download.item.title)
                ?: continue
            result[index] = mergeState(result[index], download.state)
        }
        return result
    }

    /** 同集多源并存时取更靠前的状态（已完成 > 下载中 > 获取地址中 > 排队 > 暂停等待 > 失败）。 */
    private fun mergeState(prev: DownloadTaskState?, next: DownloadTaskState): DownloadTaskState {
        if (prev == null) return next
        if (prev == DownloadTaskState.COMPLETED || next == DownloadTaskState.COMPLETED) return DownloadTaskState.COMPLETED
        return if (stateRank(next) >= stateRank(prev)) next else prev
    }

    private fun stateRank(state: DownloadTaskState): Int = when (state) {
        DownloadTaskState.COMPLETED -> 6
        DownloadTaskState.DOWNLOADING -> 5
        DownloadTaskState.RESOLVING -> 4
        DownloadTaskState.QUEUED -> 3
        DownloadTaskState.PAUSED -> 2
        DownloadTaskState.WAITING_NETWORK, DownloadTaskState.WAITING_WIFI -> 1
        DownloadTaskState.FAILED, DownloadTaskState.REMOVED -> 0
    }

    /**
     * 某部剧某一集的已下载条目（未下载返回 null）：优先按集号，集号缺失时按集名回退。
     * 选集面板点已下载集时用它直接离线播放，不再回网络解析。
     */
    fun downloadedEpisode(
        downloads: List<OfflineDownload>,
        title: String,
        index: Int,
        episodeName: String = ""
    ): DownloadedEpisode? =
        episodes(downloads).firstOrNull { e ->
            sameTitle(e.title, title) &&
                ((e.episodeNumber != null && e.episodeNumber == index) ||
                    (episodeName.isNotBlank() && e.episodeName == episodeName))
        }

    /** 某部剧的某一集是否已下载：优先按集号，集号缺失时按集名回退。 */
    fun isEpisodeDownloaded(
        downloads: List<OfflineDownload>,
        title: String,
        index: Int,
        episodeName: String = ""
    ): Boolean = downloadedEpisode(downloads, title, index, episodeName) != null

    /** 剧名归一化比对（先洗掉 HTML 残渣，再忽略空白与大小写）。 */
    fun sameTitle(a: String, b: String): Boolean {
        val left = MediaTitle.normalize(a)
        val right = MediaTitle.normalize(b)
        return left.isNotEmpty() && left == right
    }
}
