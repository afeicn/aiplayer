package com.afeicn.aiplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 各页面的状态层：把迁移过来的业务模块（搜索引擎、下载管理、离线库）接到 Compose UI。
 * UI 只消费这里的 StateFlow 并回调动作，不直接碰业务类。
 */

// ==================== 搜索 ====================

data class SearchUiState(
    val searching: Boolean = false,
    val results: List<AggregatedVod> = emptyList(),
    val history: List<SearchHistoryEntry> = emptyList(),
    /** 网络失败的源数（与「没结果」区分展示）。 */
    val failedSources: Int = 0,
    val noEnabledSources: Boolean = false,
    /** 本次结果里是否有源走了 AI 兜底解析。 */
    val aiAssisted: Boolean = false,
    val error: String? = null,
    /** 发现页无关键词时的推荐流：真跑跨源搜索得到，不是内置假数据。 */
    val recommendations: List<AggregatedVod> = emptyList(),
    val recommendationsLoading: Boolean = false
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        SearchUiState(
            history = AppContainer.searchHistory.entries(),
            noEnabledSources = AppContainer.sourceStore.sources().none { it.enabled }
        )
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var recommendJob: Job? = null

    fun search(rawKeyword: String) {
        val keyword = SearchHistoryRules.sanitize(rawKeyword) ?: return
        val history = AppContainer.searchHistory.record(keyword)
        val enabled = AppContainer.sourceStore.sources().count { it.enabled }
        if (enabled == 0) {
            _state.value = _state.value.copy(
                searching = false, results = emptyList(), history = history,
                noEnabledSources = true, error = null
            )
            return
        }
        _state.value = _state.value.copy(searching = true, history = history, error = null, noEnabledSources = false)
        viewModelScope.launch {
            val fallback = AppContainer.aiSearchFallbackOrNull()
            val report = runCatching { withContext(Dispatchers.IO) { AppContainer.searchEngine.search(keyword, aiFallback = fallback) } }
            report.fold(
                onSuccess = { result ->
                    val cards = VodAggregator.aggregate(result.hits)
                    _state.value = _state.value.copy(
                        searching = false,
                        results = cards,
                        failedSources = result.outcomes.count { !it.success },
                        aiAssisted = result.outcomes.any { it.aiAssisted },
                        error = null
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        searching = false, results = emptyList(),
                        error = "搜索失败：${e.message ?: "未知错误"}"
                    )
                }
            )
        }
    }

    /**
     * 进入发现页、还没有关键词时预取推荐流。
     *
     * 用热搜榜前几条关键词各跑一次跨源搜索再合并，所以卡片上的封面、年份、「N 个源」都是真数据。
     * 这里刻意不挂 AI 兜底：推荐是锦上添花，不值得为它花模型额度；用户主动搜索时才走兜底。
     */
    fun loadRecommendations() {
        val enabled = AppContainer.sourceStore.sources().count { it.enabled }
        if (!RecommendationRules.shouldPrefetch(enabled)) {
            recommendJob?.cancel()
            _state.value = _state.value.copy(
                recommendations = emptyList(), recommendationsLoading = false,
                noEnabledSources = enabled == 0
            )
            return
        }
        _state.value = _state.value.copy(noEnabledSources = false)
        if (_state.value.recommendationsLoading || _state.value.recommendations.isNotEmpty()) return
        recommendJob = viewModelScope.launch {
            _state.value = _state.value.copy(recommendationsLoading = true)
            val groups = withContext(Dispatchers.IO) {
                HotSearchCatalog.prefetch().map { keyword ->
                    runCatching {
                        VodAggregator.aggregate(AppContainer.searchEngine.search(keyword).hits)
                    }.getOrDefault(emptyList())
                }
            }
            _state.value = _state.value.copy(
                recommendations = RecommendationRules.merge(groups),
                recommendationsLoading = false
            )
        }
    }

    fun removeHistory(keyword: String) {
        _state.value = _state.value.copy(history = AppContainer.searchHistory.remove(keyword))
    }

    fun clearHistory() {
        AppContainer.searchHistory.clear()
        _state.value = _state.value.copy(history = emptyList())
    }
}

// ==================== 选集面板 ====================

data class EpisodeSheetUiState(
    val loading: Boolean = false,
    /** 老数据没有详情页，正在按剧名兜底定位来源（「查找来源…」）。 */
    val locating: Boolean = false,
    val title: String = "",
    val episodes: List<EpisodeLink> = emptyList(),
    val statuses: Map<Int, DownloadTaskState> = emptyMap(),
    /** 这部剧上次播到第几集（0 = 没有记录），播放模式显示「继续上次播放」。 */
    val resumeEpisode: Int = 0,
    /** 正在解析播放地址的集号（null = 没有），播放模式该格显示转圈。 */
    val resolvingEpisode: Int? = null,
    val notice: String? = null,
    val error: String? = null
)

/**
 * 选集面板的三种打开方式（对应三种取数路径）：
 *  - MacCMS 在线结果：线路即集数，播放/下载都不再解析，直接用线路地址；
 *  - HTML 在线结果：先抓详情页拿集数，播放/下载前逐集解析真实地址；
 *  - 离线剧「继续下载」：先定位（老数据兜底搜索）详情页，再走 HTML 路径。
 */
class EpisodeSheetViewModel : ViewModel() {

    private val _state = MutableStateFlow(EpisodeSheetUiState())
    val state: StateFlow<EpisodeSheetUiState> = _state.asStateFlow()

    /** MacCMS 直链模式：非空表示线路地址可直接播放/入队。 */
    private var directSource: OnlineVideoSource? = null
    private var directLines: List<VodPlayLine> = emptyList()
    /** 详情页模式：抓集数与占位解析都要源与详情页地址。 */
    private var pageSource: OnlineVideoSource? = null
    private var pageDetailUrl: String = ""

    /** 在线搜索结果卡：有线路走直链，否则抓第一个详情页。 */
    fun load(vod: AggregatedVod) {
        val hit = vod.hits.firstOrNull { it.item.playGroups.isNotEmpty() }
        if (hit != null) {
            val group = hit.item.playGroups.first()
            directSource = hit.source
            directLines = group.lines
            pageSource = null
            pageDetailUrl = ""
            _state.value = EpisodeSheetUiState(
                title = vod.name,
                episodes = group.lines.mapIndexed { offset, line -> EpisodeLink(offset + 1, line.name, line.url) },
                statuses = OfflineLibrary.episodeStatuses(OfflineDownloadManager.downloads.value, vod.name),
                resumeEpisode = AppContainer.playbackProgress.lastEpisodeOf(vod.name)
            )
            return
        }
        val detailUrl = vod.detailUrls.firstOrNull()
        if (detailUrl == null) {
            _state.value = EpisodeSheetUiState(title = vod.name, error = "这个结果没有可播线路，也没有详情页，换一个源试试")
            return
        }
        loadDetail(vod.name, detailUrl, vod.hits.first().source)
    }

    /** 离线剧「继续下载」：先定位详情页（老数据按剧名+站点兜底搜索），再抓集数。 */
    fun loadForSeries(series: DownloadedSeries) {
        val target = ContinueDownloadRules.targetOf(series)
        if (target == null) {
            _state.value = EpisodeSheetUiState(title = series.title, error = ContinueDownloadRules.NO_SOURCE)
            return
        }
        _state.value = EpisodeSheetUiState(
            title = series.title,
            locating = ContinueDownloadRules.needsLookup(target.detailUrl)
        )
        viewModelScope.launch {
            var detailUrl = target.detailUrl.trim()
            var source = ContinueDownloadRules.sourceOf(target)
            if (detailUrl.isEmpty()) {
                val located = runCatching {
                    withContext(Dispatchers.IO) {
                        ContinueSourceLocator.locate(target, HttpSearchTransport(), AppContainer.sourceStore.sources())
                    }
                }.getOrNull()
                if (located == null || located.detailUrl.isBlank()) {
                    _state.value = _state.value.copy(
                        locating = false,
                        error = ContinueDownloadRules.lookupMessage(false)
                    )
                    return@launch
                }
                detailUrl = located.detailUrl
                val base = ContinueSourceLocator.baseUrlOf(located.baseUrl.ifBlank { located.sourceName })
                source = OnlineVideoSource(
                    id = "continue-lookup",
                    name = located.sourceName,
                    base = base,
                    referer = base.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty(),
                    type = if (target.isAudio) SourceType.AUDIO else SourceType.VIDEO
                )
                // 定位成功即回写这部剧的所有条目，下次点开就不用再搜了。
                OfflineDownloadManager.backfillDetailUrl(AppContainer.appContext, series.title, detailUrl, located.sourceName)
            }
            loadDetail(series.title, detailUrl, source)
        }
    }

    private fun loadDetail(title: String, detailUrl: String, source: OnlineVideoSource) {
        directSource = null
        directLines = emptyList()
        pageSource = source
        pageDetailUrl = detailUrl
        _state.value = EpisodeSheetUiState(title = title, loading = true)
        viewModelScope.launch {
            val ai = AppContainer.aiClientOrNull()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SubscriptionPipeline.fetchEpisodesWithFallback(detailUrl, title, source, HttpPageFetcher(), ai)
                }
            }
            result.fold(
                onSuccess = { (list, aiAssisted) ->
                    if (list.isEmpty()) {
                        _state.value = EpisodeSheetUiState(title = title, error = ContinueDownloadRules.FETCH_EMPTY)
                    } else {
                        _state.value = EpisodeSheetUiState(
                            title = title,
                            episodes = list,
                            statuses = OfflineLibrary.episodeStatuses(OfflineDownloadManager.downloads.value, title),
                            resumeEpisode = AppContainer.playbackProgress.lastEpisodeOf(title),
                            notice = if (aiAssisted) "AI 智能解析出的集数，个别集可能不准" else null
                        )
                    }
                },
                onFailure = { e ->
                    _state.value = EpisodeSheetUiState(title = title, error = "抓集数失败：${e.message ?: "网络异常"}")
                }
            )
        }
    }

    /** 播放一集：已下载的直接离线播（只读缓存，不再回网络）；否则直链模式同步出条目、详情页模式先解析（失败回调 null）。 */
    fun play(title: String, episode: EpisodeLink, onResult: (MediaEntry?) -> Unit) {
        // 记「这部剧上次播到第几集」，下次进播放面板给「继续上次播放」。
        AppContainer.playbackProgress.saveLastEpisode(title, episode.index)
        _state.value = _state.value.copy(resumeEpisode = episode.index)
        // 已下载的集优先离线播：同一部剧同一集只播一次，不再回网络解析起播。
        OfflineLibrary.downloadedEpisode(
            OfflineDownloadManager.downloads.value, title, episode.index, episode.name
        )?.let { onResult(it.mediaEntry()); return }
        val source = directSource
        if (source != null) {
            val line = directLines.getOrNull(episode.index - 1)
            onResult(line?.let { onlineStreamEntry(source, it, title) })
            return
        }
        _state.value = _state.value.copy(resolvingEpisode = episode.index)
        viewModelScope.launch {
            val stream = runCatching {
                withContext(Dispatchers.IO) { SubscriptionPipeline.resolveStream(episode.url) }
            }.getOrNull()
            _state.value = _state.value.copy(resolvingEpisode = null)
            onResult(stream?.let { networkStreamEntry(it) })
        }
    }

    /** 确认范围下载：直链模式直接入队；详情页模式落「获取地址中」占位、后台逐集解析。 */
    fun download(title: String, selected: Set<Int>) {
        val statuses = OfflineLibrary.episodeStatuses(OfflineDownloadManager.downloads.value, title)
        val partition = EpisodeDownloadRules.partition(selected, statuses)
        if (partition.addable.isEmpty()) {
            _state.value = _state.value.copy(notice = partition.hints.firstOrNull() ?: "所选的集都已在下载体系里")
            return
        }
        val source = directSource
        if (source != null) {
            var started = 0
            var duplicates = 0
            partition.addable.sorted().forEach { index ->
                val line = directLines.getOrNull(index - 1) ?: return@forEach
                val episode = EpisodeLink(index, line.name, line.url)
                val item = SubscriptionPipeline.offlineItemForEpisode(
                    title, source, episode, onlineStream(source, line, title),
                    totalEpisodes = directLines.size
                )
                when (OfflineDownloadManager.enqueue(AppContainer.appContext, item)) {
                    EnqueueResult.STARTED, EnqueueResult.WAITING_NETWORK -> started++
                    EnqueueResult.DUPLICATE -> duplicates++
                    else -> Unit
                }
            }
            _state.value = _state.value.copy(
                notice = "已提交 $started 集下载" +
                    if (duplicates > 0) "；$duplicates 集已在下载体系" else "",
                statuses = statuses + partition.addable.associateWith { DownloadTaskState.QUEUED }
            )
            return
        }
        val page = pageSource ?: return
        val count = SubscriptionPipeline.enqueueRangeResolving(
            AppContainer.appContext, title, page, pageDetailUrl, partition.addable.sorted()
        )
        _state.value = _state.value.copy(
            notice = "已提交 $count 集，正在获取下载地址" +
                if (partition.skipped.isNotEmpty()) "；跳过 ${partition.skipped.size} 集（已在下载体系）" else "",
            statuses = statuses + partition.addable.associateWith { DownloadTaskState.RESOLVING }
        )
    }

    /** 刷新每集下载状态（下载推进后角标跟着变）。 */
    fun refreshStatuses(title: String) {
        if (_state.value.episodes.isEmpty()) return
        _state.value = _state.value.copy(
            statuses = OfflineLibrary.episodeStatuses(OfflineDownloadManager.downloads.value, title)
        )
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}

// ==================== 下载管理 ====================

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    val downloads: StateFlow<List<OfflineDownload>> = OfflineDownloadManager.downloads
    val pendingRemoval: StateFlow<Set<String>> = OfflineDownloadManager.pendingRemoval

    private val _allowMobileData = MutableStateFlow(AppContainer.downloadPrefs.allowMobileData())
    val allowMobileData: StateFlow<Boolean> = _allowMobileData.asStateFlow()

    private var pollJob: Job? = null

    /** 进度轮询：800ms 刷一次 DownloadManager 快照（进度条、状态角标都靠它推进）。 */
    fun startPolling() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            while (true) {
                OfflineDownloadManager.refresh()
                delay(800)
            }
        }
    }

    fun pause(id: String) = OfflineDownloadManager.pause(AppContainer.appContext, id)
    fun resume(id: String) = OfflineDownloadManager.resume(AppContainer.appContext, id)

    /** 失败任务重试：「获取地址失败」的占位任务没有 Media3 记录，走重新解析；其余走 DownloadManager 重入队。 */
    fun retry(task: OfflineDownload) {
        if (task.failure == OfflineFailureReason.RESOLVE_FAILED) {
            SubscriptionPipeline.reResolve(AppContainer.appContext, task.item)
        } else {
            OfflineDownloadManager.retry(AppContainer.appContext, task.item.id)
        }
    }

    fun remove(id: String) = OfflineDownloadManager.removeWithUndo(AppContainer.appContext, id)
    fun undoRemove(id: String) = OfflineDownloadManager.undoRemove(id)

    fun setAllowMobileData(value: Boolean) {
        AppContainer.downloadPrefs.setAllowMobileData(value)
        _allowMobileData.value = value
        OfflineDownloadManager.onNetworkPreferenceChanged()
    }

    fun cacheSizeText(): String = OfflineDownloadManager.cacheSizeText(AppContainer.appContext)
}

// ==================== 首页（离线库） ====================

data class HomeUiState(
    val series: List<DownloadedSeries> = emptyList(),
    val lastSeriesTitle: String? = null,
    val cacheSizeText: String = "",
    /** 播过一集就进这里；首页大卡「正在播放」取第一条，下方右栏整列展示。 */
    val recent: List<RecentPlay> = emptyList()
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init { publish() }

    /** 下载进度轮询：800ms 刷一次 DownloadManager 快照（进度条、状态角标都靠它推进）。 */
    fun startPolling() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            while (true) {
                OfflineDownloadManager.refresh()
                publish()
                delay(800)
            }
        }
    }

    private fun publish() {
        val episodes = OfflineLibrary.episodes(OfflineDownloadManager.downloads.value)
        _state.value = HomeUiState(
            series = OfflineLibrary.groupByTitle(episodes),
            lastSeriesTitle = AppContainer.playbackProgress.lastSeriesTitle(),
            cacheSizeText = OfflineDownloadManager.cacheSizeText(AppContainer.appContext),
            recent = AppContainer.playbackProgress.recentPlays()
                .map { it.copy(lastEpisode = AppContainer.playbackProgress.lastEpisodeOf(it.title)) }
        )
    }

    /**
     * 首页「正在播放」的在线续播：凭上次记录的播放页地址重新解析流。
     * 解析结果通过回调返回（null = 解析失败，界面提示换一集/换个源）。
     */
    fun resolveRecent(play: RecentPlay, onResult: (MediaEntry?) -> Unit) {
        if (play.playUrl.isBlank()) { onResult(null); return }
        viewModelScope.launch {
            val stream = runCatching {
                withContext(Dispatchers.IO) { SubscriptionPipeline.resolveStream(play.playUrl) }
            }.getOrNull()
            onResult(stream?.let { networkStreamEntry(it) })
        }
    }

    /**
     * 首页大卡点主体：优先吃 [recentVodCache] 缓存（首页加载时已预取），秒开进详情；
     * 缓存没有才后台按剧名搜索（原标题搜不到就逐级换更短的关键词）。
     * 回调返回最匹配的结果卡；null = 哪一级都没搜到，界面退回发现页兜底。
     */
    fun findRecentVod(title: String, onResult: (AggregatedVod?) -> Unit) {
        cachedRecentVod(title)?.let { onResult(it); return }
        searchRecentVod(title) { cards ->
            onResult(cacheRecentVod(title, cards))
        }
    }

    /**
     * 最近观看的作品卡缓存：标题（归一化）→ 聚合卡。
     * 首页一有「最近浏览」就后台预取（[prefetchRecentVods]），点大卡时不再等搜索、不弹提示。
     */
    private val recentVodCache = java.util.concurrent.ConcurrentHashMap<String, AggregatedVod>()

    private fun cachedRecentVod(title: String): AggregatedVod? =
        recentVodCache[MediaTitle.normalize(title)]

    private fun cacheRecentVod(title: String, cards: List<AggregatedVod>): AggregatedVod? {
        val vod = cards.firstOrNull { OfflineLibrary.sameTitle(it.name, title) } ?: cards.firstOrNull()
        if (vod != null) recentVodCache[MediaTitle.normalize(title)] = vod
        return vod
    }

    /** 静默预热：对最近观看逐条后台搜索入缓存，无任何界面反馈；已缓存的跳过。 */
    fun prefetchRecentVods(recent: List<RecentPlay>) {
        recent.forEach { play ->
            if (cachedRecentVod(play.title) == null) {
                searchRecentVod(play.title) { cards -> cacheRecentVod(play.title, cards) }
            }
        }
    }

    /** 大卡续播兜底的结果：定位到的作品卡、目标集的可播条目、该集播放页地址（直链模式为空）。 */
    data class RecentResume(val vod: AggregatedVod, val entry: MediaEntry, val playUrl: String)

    /**
     * 首页大卡在线续播兜底：没记到播放页地址（或解析失败）时，凭缓存/搜索定位作品，
     * 找到上次看的那一集（没记录就第一集），解析出流后回调；顺带回写「上次播到第几集」。
     */
    fun resumeRecentBySearch(play: RecentPlay, onResult: (RecentResume?) -> Unit) {
        cachedRecentVod(play.title)?.let { resumeFromVod(play, it, onResult); return }
        searchRecentVod(play.title) { cards ->
            val vod = cacheRecentVod(play.title, cards)
            if (vod == null) onResult(null) else resumeFromVod(play, vod, onResult)
        }
    }

    /** 已拿到作品卡后的续播解析：MacCMS 直链直接取对应线路；详情页模式抓集数→解析目标集。 */
    private fun resumeFromVod(play: RecentPlay, vod: AggregatedVod, onResult: (RecentResume?) -> Unit) {
        run {
            val target = AppContainer.playbackProgress.lastEpisodeOf(play.title)
            // MacCMS 直链：线路即集数，不用解析，直接取对应线路。
            val direct = vod.hits.firstOrNull { it.item.playGroups.isNotEmpty() }
            if (direct != null) {
                val lines = direct.item.playGroups.first().lines
                val line = lines.getOrNull(target - 1) ?: lines.firstOrNull()
                onResult(line?.let { RecentResume(vod, onlineStreamEntry(direct.source, it, vod.name), "") })
                return@run
            }
            // 详情页模式：抓集数 → 取目标集 → 解析真实地址。
            val detailUrl = vod.detailUrls.firstOrNull()
            val source = vod.hits.firstOrNull()?.source
            if (detailUrl == null || source == null) { onResult(null); return@run }
            viewModelScope.launch {
                val resume = runCatching {
                    withContext(Dispatchers.IO) {
                        val (episodes, _) = SubscriptionPipeline.fetchEpisodesWithFallback(
                            detailUrl, vod.name, source, HttpPageFetcher(), AppContainer.aiClientOrNull()
                        )
                        val ep = episodes.firstOrNull { it.index == target } ?: episodes.firstOrNull()
                            ?: return@withContext null
                        val stream = SubscriptionPipeline.resolveStream(ep.url) ?: return@withContext null
                        AppContainer.playbackProgress.saveLastEpisode(vod.name, ep.index)
                        RecentResume(vod, networkStreamEntry(stream), ep.url)
                    }
                }.getOrNull()
                onResult(resume)
            }
        }
    }

    /** 按 MediaTitle.searchKeys 的候选关键词逐级跨源搜索，第一个有结果的关键词胜出。 */
    private fun searchRecentVod(title: String, onResult: (List<AggregatedVod>) -> Unit) {
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) {
                for (keyword in MediaTitle.searchKeys(title)) {
                    val found = runCatching {
                        VodAggregator.aggregate(AppContainer.searchEngine.search(keyword).hits)
                    }.getOrDefault(emptyList())
                    if (found.isNotEmpty()) return@withContext found
                }
                emptyList()
            }
            onResult(cards)
        }
    }
}

// ==================== 订阅源管理 ====================

data class SourceRowUi(
    val source: OnlineVideoSource,
    val healthy: Boolean,
    val healthText: String
)

/** AI 智能解析的设置回显（暂时支持 DeepSeek；Key 明文存本地，界面已提示）。 */
data class AiSettingsUi(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = AiSettingsStore.DEFAULT_MODEL
)

/** 一次探测的会话流：事件按到达顺序追加，结束后带结论。 */
data class ProbeSession(
    val sourceName: String,
    val events: List<ProbeEvent> = emptyList(),
    val done: Boolean = false,
    val success: Boolean = false,
    val conclusion: String = ""
)

data class SourcesUiState(
    val rows: List<SourceRowUi> = emptyList(),
    /** 正在探测的站点名（null 表示没有探测在跑）。 */
    val probing: String? = null,
    /** 当前/最近一次探测的会话流（UI 弹底部面板逐步展示）。 */
    val probeSession: ProbeSession? = null,
    val notice: String? = null,
    val ai: AiSettingsUi = AiSettingsUi(),
    /** 管理模式（长按源行进入）：显示勾选框，隐藏开关与恢复/重探按钮。 */
    val managing: Boolean = false,
    /** 管理模式下的勾选集合。 */
    val selection: SourceSelection = SourceSelection()
)

class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        SourcesUiState(
            ai = AiSettingsUi(
                enabled = AppContainer.aiSettings.enabled(),
                apiKey = AppContainer.aiSettings.apiKey(),
                model = AppContainer.aiSettings.model()
            )
        )
    )
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init { refresh() }

    /** 保存 AI 智能解析设置（暂时支持 DeepSeek）；关掉或 Key 留空时，探测和搜索都不会调模型。 */
    fun saveAiSettings(enabled: Boolean, apiKey: String, model: String) {
        AppContainer.aiSettings.setEnabled(enabled)
        AppContainer.aiSettings.setApiKey(apiKey)
        AppContainer.aiSettings.setModel(model)
        _state.value = _state.value.copy(
            ai = AiSettingsUi(
                enabled = AppContainer.aiSettings.enabled(),
                apiKey = AppContainer.aiSettings.apiKey(),
                model = AppContainer.aiSettings.model()
            ),
            notice = if (AppContainer.aiSettings.usable()) "AI 智能解析已开启" else "AI 智能解析未开启（需要填 API Key）"
        )
    }

    fun refresh() {
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(
            rows = AppContainer.sourceStore.sources().map { source ->
                val health = AppContainer.sourceStore.health(source.id)
                val tier = SourceHealthPolicy.tier(health, now)
                SourceRowUi(
                    source = source,
                    healthy = tier == SourceCooldownTier.ACTIVE,
                    healthText = when {
                        !source.enabled -> "已停用"
                        tier == SourceCooldownTier.LONG_TERM -> "长期不可用 · 连续失败 ${health.consecutiveFailures} 次"
                        SourceHealthPolicy.isCoolingDown(health, now) -> "网络异常 · 连续失败 ${health.consecutiveFailures} 次"
                        health.lastSuccessAt > 0 -> "已启用 · 运行正常"
                        else -> "未检测"
                    }
                )
            }
        )
    }

    /**
     * 添加站点：只需要地址。后台自动探测搜索入口与连通性
     * （确定性候选失败后，配了 DeepSeek 就让它从页面提候选入口，再逐条实测），
     * 同时让 DeepSeek 从页面认站点名称（认不出退回页面标题，再退回域名）。
     * 探测不通过也会保存为未启用源，可稍后「重新探测」。
     *
     * 存下来的 base 一律是收敛后的站点根：用户粘播放页、列表页也能加成功，
     * 后续搜索拼 URL 才不会拼出 `…/20830.html/e/search/?keyboard=…` 这种地址。
     */
    fun add(address: String) {
        val raw = address.trim()
        if (raw.isBlank()) return
        val addressInfo = SourceAddressRules.analyze(raw)
        val base = addressInfo.siteBase
        val source = OnlineVideoSource(
            id = java.util.UUID.randomUUID().toString(),
            name = hostOf(base),
            base = base
        )
        _state.value = _state.value.copy(
            probing = source.name,
            probeSession = ProbeSession(source.name)
        )
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    // 探测用原始地址跑（粘的是哪一页就抓哪一页），落库用站点根。
                    SourceProber.probeWithAi(
                        source.copy(base = addressInfo.original),
                        HttpSearchTransport(),
                        AppContainer.aiClientOrNull()
                    ) { event -> appendProbeEvent(event) }
                }
            }.getOrNull()
            val finalName = resolveSiteName(addressInfo, outcome)
            if (finalName != source.name) {
                appendProbeEvent(ProbeEvent("站点名称认出来了：$finalName", ProbeStepStatus.OK))
                _state.update { it.copy(probeSession = it.probeSession?.copy(sourceName = finalName)) }
            }
            saveProbed(source.copy(name = finalName), outcome, addressInfo, isNew = true)
            refresh()
        }
    }

    /** 站点名称：DeepSeek 从页面认 → 页面 `<title>` 品牌段 → 域名。 */
    private fun resolveSiteName(addressInfo: SourceAddress, outcome: SourceProbeOutcome?): String {
        val evidence = outcome?.pages?.evidence().orEmpty()
        AppContainer.aiClientOrNull()?.suggestSiteName(addressInfo.original, evidence)?.let { return it }
        return SiteNameRules.titleFromHtml(evidence) ?: hostOf(addressInfo.siteBase)
    }

    /**
     * 重新探测一个已添加但还没有搜索规则的源（多半是当初探测失败被存成未启用的）。
     *
     * 老版本把用户粘的播放页原样存进了 base，这里顺手把它收敛成站点根，
     * 否则重新探测还是会在同一个坑里失败。
     */
    fun reprobe(id: String) {
        val stored = AppContainer.sourceStore.sources().firstOrNull { it.id == id } ?: return
        if (state.value.probing != null) return
        val addressInfo = SourceAddressRules.analyze(stored.base)
        val normalized = stored.copy(base = addressInfo.siteBase)
        _state.value = _state.value.copy(
            probing = stored.name,
            probeSession = ProbeSession(stored.name)
        )
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    SourceProber.probeWithAi(
                        stored.copy(base = addressInfo.original),
                        HttpSearchTransport(),
                        AppContainer.aiClientOrNull()
                    ) { event -> appendProbeEvent(event) }
                }
            }.getOrNull()
            saveProbed(normalized, outcome, addressInfo, isNew = false)
            refresh()
        }
    }

    /** 探测结论落库：成功存规则并启用；失败存成未启用源并给可读原因（新添加的照样入库，可稍后重探）。 */
    private fun saveProbed(
        source: OnlineVideoSource,
        outcome: SourceProbeOutcome?,
        addressInfo: SourceAddress,
        isNew: Boolean
    ) {
        if (outcome != null && outcome.success && outcome.rule != null) {
            AppContainer.sourceStore.save(source.copy(searchRule = outcome.rule, enabled = true))
            val from = if (outcome.aiAssisted) "AI 提的入口，实测返回 ${outcome.itemCount} 条"
            else "试搜返回 ${outcome.itemCount} 条"
            val note = if (addressInfo.isSiteRoot) "" else "（已按站点根 ${addressInfo.siteBase} 保存）"
            _state.value = _state.value.copy(
                probing = null,
                probeSession = _state.value.probeSession?.copy(
                    done = true, success = true,
                    conclusion = "探测通过，已启用「${source.name}」（${from}）$note"
                ),
                notice = "已探测到「${source.name}」的搜索入口（$from）$note"
            )
        } else {
            AppContainer.sourceStore.save(source.copy(enabled = false))
            val reason = outcome?.error ?: SourceProber.NO_ENTRY
            val conclusion = if (isNew) {
                "已添加「${source.name}」，但这次探测没通过：$reason 已存为未启用，可稍后点「重新探测」。"
            } else {
                "这个源不可用：$reason"
            }
            _state.value = _state.value.copy(
                probing = null,
                probeSession = _state.value.probeSession?.copy(
                    done = true, success = false,
                    conclusion = conclusion
                ),
                notice = "「${source.name}」$reason"
            )
        }
    }

    /** 会话流追加一步：结果事件顶掉最后一条「进行中」，其余按序追加。 */
    private fun appendProbeEvent(event: ProbeEvent) {
        _state.update { s ->
            val session = s.probeSession ?: return@update s
            val events = if (event.status != ProbeStepStatus.RUNNING &&
                session.events.lastOrNull()?.status == ProbeStepStatus.RUNNING
            ) session.events.dropLast(1) + event else session.events + event
            s.copy(probeSession = session.copy(events = events))
        }
    }

    /** 关掉探测会话流面板。 */
    fun dismissProbe() {
        _state.update { it.copy(probeSession = null) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        AppContainer.sourceStore.setEnabled(id, enabled)
        refresh()
    }

    // 删除只走管理模式（长按源行进入 → 多选 → 删除选中 + 确认弹窗），
    // 不提供单行删除按钮——一键误删整个源太危险。

    /** 长按源行进入管理模式，并把这一行预先勾上。 */
    fun enterManage(id: String) {
        _state.value = _state.value.copy(
            managing = true,
            selection = _state.value.selection.toggle(id)
        )
    }

    fun exitManage() {
        _state.value = _state.value.copy(managing = false, selection = SourceSelection())
    }

    fun toggleSelect(id: String) {
        _state.value = _state.value.copy(selection = _state.value.selection.toggle(id))
    }

    fun toggleSelectAll() {
        val ids = _state.value.rows.map { it.source.id }
        _state.value = _state.value.copy(selection = _state.value.selection.toggleAll(ids))
    }

    /** 删除全部勾选项，退出管理模式并给条数提示。 */
    fun deleteSelected() {
        val ids = _state.value.selection.selected
        if (ids.isEmpty()) return
        ids.forEach { AppContainer.sourceStore.delete(it) }
        _state.value = _state.value.copy(managing = false, selection = SourceSelection())
        noticeDeletedSources(ids.size)
        refresh()
    }

    /** 删除完成后的提示文案（管理模式已退出，refresh 不会覆盖 notice）。 */
    private fun noticeDeletedSources(n: Int) {
        _state.value = _state.value.copy(notice = DestructiveCopy.deletedSourcesToast(n))
    }

    /** 冷却期手动恢复。 */
    fun recover(id: String) {
        AppContainer.sourceStore.recover(id)
        refresh()
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun hostOf(address: String): String =
        address.substringAfter("://").substringBefore('/').ifBlank { address }
}

// ==================== 播放器 ====================

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    /** 当前播放队列（离线整剧 / 在线整条线路）与游标；单条播放就是长度为 1 的队列。 */
    private var queue: List<MediaEntry> = emptyList()
    private var index: Int = -1

    /** 当前正在播的条目（null 表示没有播放会话）。 */
    fun currentEntry(): MediaEntry? = queue.getOrNull(index)

    fun hasNext(): Boolean = index in queue.indices && index < queue.lastIndex
    fun hasPrev(): Boolean = index > 0

    /** 起播一条媒体（在线流 offlineOnly=false；离线缓存条目 offlineOnly=true 断网可播）。 */
    fun play(entry: MediaEntry) {
        queue = listOf(entry)
        index = 0
        startCurrent()
    }

    /** 起播一个队列（离线剧全部已下载集 / 在线作品一条线路的全部集）。 */
    fun playQueue(entries: List<MediaEntry>, startIndex: Int) {
        if (entries.isEmpty()) return
        queue = entries
        index = startIndex.coerceIn(0, entries.lastIndex)
        startCurrent()
    }

    fun next() {
        if (hasNext()) {
            index++
            startCurrent()
        }
    }

    fun prev() {
        if (hasPrev()) {
            index--
            startCurrent()
        }
    }

    private fun startCurrent() {
        saveProgress()
        val entry = queue[index]
        val player = PlayerEngine.player(AppContainer.appContext)
        player.setMediaItem(entry.toMediaItem())
        player.seekTo(AppContainer.playbackProgress.positionOf(entry.uri.toString()))
        player.prepare()
        player.playWhenReady = true
        // 剧名取 name（在线搜索条目 name 是片名、title 是集名；离线条目 name 就是清洗后剧名）。
        if (entry.kind == MediaKind.VIDEO) AppContainer.playbackProgress.saveLastSeries(entry.name)
    }

    fun saveProgress() {
        val entry = currentEntry() ?: return
        val player = PlayerEngine.player(AppContainer.appContext)
        AppContainer.playbackProgress.savePosition(entry.uri.toString(), player.currentPosition)
    }

    fun pause() {
        PlayerEngine.player(AppContainer.appContext).pause()
        saveProgress()
    }

    override fun onCleared() {
        saveProgress()
        PlayerEngine.release()
        queue = emptyList()
        index = -1
    }
}

/** MediaEntry → Media3 MediaItem：请求头与「只读缓存」标记挂在 tag 上，由 [NetworkMediaSourceFactory] 读取。 */
fun MediaEntry.toMediaItem(): androidx.media3.common.MediaItem =
    androidx.media3.common.MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(displayTitle).build()
        )
        .setTag(NetworkRequestHeaders(networkHeaders, offlineOnly))
        .build()
