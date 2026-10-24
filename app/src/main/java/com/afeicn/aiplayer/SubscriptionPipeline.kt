package com.afeicn.aiplayer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 「订阅源 + 跨源搜索 + 确认范围 + 离线下载」闭环的编排层，供 UI 与语音共同调用，
 * 不各自开旁路：
 *
 *   搜索（[OnlineSearchEngine.search]，HTML 探测 + MacCMS JSON 回退）
 *     → 命中详情页 [AggregatedVod.detailUrls] → 抓详情页拿集数 [fetchEpisodes]
 *     → 用户确认范围 [EpisodeRangeParser] → 逐集解析流 [resolveStream]（iframe → m3u8）
 *     → 音频直链分流 [fetchAudioLinks] → 批量入队 [EpisodeBatchDownloader]。
 */

object SubscriptionPipeline {

    /** 后台解析协程的常驻作用域：面板关闭后不随 UI 取消，保证「获取地址中」能被推进到底。 */
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 抓详情页并解析集数（帝国CMS 的 `/e/DownSys/play/?…` 已由 [EpisodeListParser] 通用兜底识别）。 */
    fun fetchEpisodes(detailUrl: String, fetcher: PageFetcher = HttpPageFetcher()): List<EpisodeLink> {
        val page = fetcher.fetch(detailUrl, null)
        return EpisodeListParser.extract(page.body, page.url)
    }

    /**
     * 抓详情页取集数，确定性解析为空时走 AI 兜底（[ai] 非空才启用）。返回 (集数, 是否 AI 辅助)。
     * AI 产出的播放页地址逐条回连校验（GET 存活，上限 50 条），死链丢弃；
     * 通过的映射成与确定性解析同构的 [EpisodeLink]，下游 [resolveStream] 照常解真实流。
     */
    suspend fun fetchEpisodesWithFallback(
        detailUrl: String,
        title: String,
        source: OnlineVideoSource,
        fetcher: PageFetcher = HttpPageFetcher(),
        ai: DeepSeekClient? = null
    ): Pair<List<EpisodeLink>, Boolean> = withContext(Dispatchers.IO) {
        val referer = source.referer.takeIf { it.isNotBlank() }
        val page = runCatching { fetcher.fetch(detailUrl, referer) }.getOrNull() ?: return@withContext emptyList<EpisodeLink>() to false
        val deterministic = EpisodeListParser.extract(page.body, page.url)
        if (deterministic.isNotEmpty()) return@withContext deterministic to false
        if (ai == null) return@withContext emptyList<EpisodeLink>() to false
        val extracted = ai.extractEpisodes(title, page.body, source.normalizedBase).take(50)
        val validated = extracted.filter { ep -> runCatching { fetcher.fetch(ep.url, detailUrl) }.isSuccess }
        val episodes = validated.mapIndexed { offset, ep ->
            val index = offset + 1
            EpisodeLink(index, ep.name.ifBlank { "第${index}集" }, ep.url)
        }
        episodes to episodes.isNotEmpty()
    }

    /** 音频源：从页面里找 `<audio src>` 或 `.mp3/.m4a/.flac/.aac` 直链（相对地址补全）。 */
    fun fetchAudioLinks(detailUrl: String, fetcher: PageFetcher = HttpPageFetcher()): List<String> {
        val page = fetcher.fetch(detailUrl, null)
        return AudioDirectLink.findAudioLinks(page.body).map { AudioDirectLink.resolve(page.url, it) }
    }

    /** 解析一条播放页地址到可播流（iframe 递归、m3u8/直链提取）；失败返回 null。 */
    fun resolveStream(episodeUrl: String, fetcher: PageFetcher = HttpPageFetcher()): NetworkStream? {
        val result = runCatching { WebLinkResolver.resolve(episodeUrl, fetcher) }.getOrNull()
        return (result as? WebLinkResult.Success)?.stream
    }

    /** 视频集数 → 离线项（HLS 走 [OfflineItem.isHls]，由 [OfflineDownloaderFactory] 路由到 HlsDownloader）。 */
    fun offlineItemForEpisode(
        dramaTitle: String,
        source: OnlineVideoSource,
        episode: EpisodeLink,
        stream: NetworkStream,
        totalEpisodes: Int = 0,
        detailUrl: String = ""
    ): OfflineItem =
        OfflineItem(
            id = "",
            // 剧名落库前统一清洗：搜索页的 HTML 残渣（`X">X` / `<font>`）不进入下载记录。
            title = MediaTitle.clean(dramaTitle),
            url = stream.url,
            headers = stream.headers,
            cover = "",
            extension = stream.extension.ifBlank { stream.url.substringBefore('?').substringAfterLast('.', "").lowercase() },
            sourceName = source.name,
            episodeName = episode.name,
            kind = MediaKind.VIDEO,
            totalEpisodes = totalEpisodes,
            // 记住这部剧的详情页：「继续下载」凭它重抓集数，不用回搜索页从头搜。
            detailUrl = detailUrl
        )

    /** 音频直链 → 离线项（kind=AUDIO，走 ProgressiveDownloader，不是 HLS）。 */
    fun offlineItemForAudio(
        dramaTitle: String,
        source: OnlineVideoSource,
        index: Int,
        audioUrl: String,
        totalEpisodes: Int = 0,
        detailUrl: String = ""
    ): OfflineItem =
        OfflineItem(
            id = "",
            // 剧名落库前统一清洗：搜索页的 HTML 残渣（`X">X` / `<font>`）不进入下载记录。
            title = MediaTitle.clean(dramaTitle),
            url = audioUrl,
            headers = source.headers,
            cover = "",
            extension = AudioDirectLink.audioExtension(audioUrl).orEmpty(),
            sourceName = source.name,
            episodeName = "音频$index",
            kind = MediaKind.AUDIO,
            totalEpisodes = totalEpisodes,
            detailUrl = detailUrl
        )

    /** 按已确认的集数范围解析出离线项（越界/解析失败的集直接跳过，调用方在汇总里看到数量）。 */
    suspend fun resolveItems(
        dramaTitle: String,
        source: OnlineVideoSource,
        detailUrl: String,
        indices: List<Int>,
        fetcher: PageFetcher = HttpPageFetcher()
    ): List<OfflineItem> = withContext(Dispatchers.IO) {
        if (indices.isEmpty()) return@withContext emptyList()
        if (source.type == SourceType.AUDIO) {
            val links = fetchAudioLinks(detailUrl, fetcher)
            indices.mapNotNull { index ->
                links.getOrNull(index - 1)?.let { offlineItemForAudio(dramaTitle, source, index, it, totalEpisodes = links.size, detailUrl = detailUrl) }
            }
        } else {
            val episodes = fetchEpisodes(detailUrl, fetcher)
            val byIndex = episodes.associateBy { it.index }
            indices.mapNotNull { index ->
                val episode = byIndex[index] ?: return@mapNotNull null
                val stream = resolveStream(episode.url, fetcher) ?: return@mapNotNull null
                offlineItemForEpisode(dramaTitle, source, episode, stream, totalEpisodes = episodes.size, detailUrl = detailUrl)
            }
        }
    }

    /** 确认范围 → 批量下载（2 并发、集间间隔、失败由 [EpisodeBatchDownloader.retryFailedDownloads] 重试）。 */
    suspend fun downloadRange(
        context: Context,
        dramaTitle: String,
        source: OnlineVideoSource,
        detailUrl: String,
        indices: List<Int>,
        fetcher: PageFetcher = HttpPageFetcher()
    ): BatchDownloadSummary {
        val items = resolveItems(dramaTitle, source, detailUrl, indices, fetcher)
        return EpisodeBatchDownloader.enqueueEpisodes(context, items)
    }

    /** 为某集构造「获取地址中」占位记录：url 尚未解析，靠 [OfflineItem.resolveDetailUrl] + [OfflineItem.resolveIndex] 定位与重试。 */
    fun resolvingPlaceholder(dramaTitle: String, source: OnlineVideoSource, detailUrl: String, index: Int): OfflineItem =
        OfflineItem(
            id = OfflineDownloadRules.downloadIdFor("resolve:$detailUrl#$index"),
            title = MediaTitle.clean(dramaTitle),
            url = "",
            headers = source.headers,
            sourceName = source.name,
            episodeName = "第${index}集",
            kind = if (source.type == SourceType.AUDIO) MediaKind.AUDIO else MediaKind.VIDEO,
            totalEpisodes = 0,
            resolveDetailUrl = detailUrl,
            resolveIndex = index
        )

    /**
     * 「立即反馈」版确认范围下载：先为每集落一条「获取地址中」占位记录并立即返回占位数，
     * 再在常驻后台作用域里按 2 并发逐集解析真实地址（成功转 QUEUED、失败转 FAILED）。
     * UI 拿到返回的占位数就能马上关面板 + 弹 Snackbar，不等解析完成。
     */
    fun enqueueRangeResolving(
        context: Context,
        dramaTitle: String,
        source: OnlineVideoSource,
        detailUrl: String,
        indices: List<Int>,
        fetcher: PageFetcher = HttpPageFetcher()
    ): Int {
        if (indices.isEmpty()) return 0
        val placeholders = indices.map { resolvingPlaceholder(dramaTitle, source, detailUrl, it) }
        OfflineDownloadManager.registerResolving(context, placeholders)
        pipelineScope.launch {
            resolveAndPromote(context, dramaTitle, source, detailUrl, indices, fetcher)
        }
        return placeholders.size
    }

    /** 从一条已落库的离线项重建源对象（缺少源 id/base，只保留名称/请求头/媒体类型）；供占位重试与「继续下载」复用。 */
    fun sourceFor(item: OfflineItem): OnlineVideoSource = ContinueDownloadRules.sourceOf(item)

    /** 「获取地址失败」占位任务的重试：重新进入获取地址中并重新解析该集。 */
    fun reResolve(context: Context, item: OfflineItem) {
        if (item.resolveDetailUrl.isBlank()) return
        val source = sourceFor(item)
        OfflineDownloadManager.registerResolving(context, listOf(item))
        pipelineScope.launch {
            resolveAndPromote(context, item.title, source, item.resolveDetailUrl, listOf(item.resolveIndex))
        }
    }

    /** 后台逐集解析并推进占位任务：视频 2 并发（沿用现有 [EpisodeBatchDownloader.DEFAULT_CONCURRENCY]）。 */
    private suspend fun resolveAndPromote(
        context: Context,
        dramaTitle: String,
        source: OnlineVideoSource,
        detailUrl: String,
        indices: List<Int>,
        fetcher: PageFetcher = HttpPageFetcher()
    ) = withContext(Dispatchers.IO) {
        if (indices.isEmpty()) return@withContext
        if (source.type == SourceType.AUDIO) {
            val links = fetchAudioLinks(detailUrl, fetcher)
            for (index in indices) {
                val placeholderId = resolvingPlaceholder(dramaTitle, source, detailUrl, index).id
                val audioUrl = links.getOrNull(index - 1)
                if (audioUrl != null) {
                    OfflineDownloadManager.promoteResolved(
                        context, placeholderId,
                        offlineItemForAudio(dramaTitle, source, index, audioUrl, totalEpisodes = links.size, detailUrl = detailUrl)
                    )
                } else {
                    OfflineDownloadManager.failResolving(placeholderId)
                }
            }
        } else {
            val episodes = fetchEpisodes(detailUrl, fetcher)
            val byIndex = episodes.associateBy { it.index }
            val semaphore = Semaphore(EpisodeBatchDownloader.DEFAULT_CONCURRENCY.coerceAtLeast(1))
            coroutineScope {
                indices.forEach { index ->
                    launch {
                        semaphore.withPermit {
                            val placeholderId = resolvingPlaceholder(dramaTitle, source, detailUrl, index).id
                            val episode = byIndex[index]
                            val stream = episode?.let { resolveStream(it.url, fetcher) }
                            if (episode != null && stream != null) {
                                OfflineDownloadManager.promoteResolved(
                                    context, placeholderId,
                                    offlineItemForEpisode(dramaTitle, source, episode, stream, totalEpisodes = episodes.size, detailUrl = detailUrl)
                                )
                            } else {
                                OfflineDownloadManager.failResolving(placeholderId)
                            }
                        }
                    }
                }
            }
        }
    }
}
