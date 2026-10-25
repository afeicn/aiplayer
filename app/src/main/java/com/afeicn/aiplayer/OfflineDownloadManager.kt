package com.afeicn.aiplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 离线缓存核心：SimpleCache + DownloadManager + 播放用 CacheDataSource。
 *
 * - 下载：Media3 官方 DownloadManager + [ParallelHlsDownloader]（HLS 并发分片）/ ProgressiveDownloader（MP4）。
 * - 持久化：DownloadManager 用 DefaultDownloadIndex（SQLite），App 被杀后任务可恢复；
 *   片名/请求头等展示字段另存于 [OfflineStore]。
 * - 播放：网络条目统一包一层 CacheDataSource，在线命中缓存不重复下载，离线 BLOCK_ON_CACHE 只读缓存。
 * - 磁盘空间：入队前用 StatFs 预判，不足则不开始；下载中 ENOSPC 也会被 classifyFailure 标成「空间不足」。
 */
@OptIn(UnstableApi::class)
object OfflineDownloadManager {

    private val _downloads = MutableStateFlow<List<OfflineDownload>>(emptyList())
    val downloads: StateFlow<List<OfflineDownload>> = _downloads.asStateFlow()

    /** 单条删除的「撤销窗口」内待真正删除的 id 集合；UI 层据此隐藏条目并显示撤销条。 */
    private val _pendingRemoval = MutableStateFlow<Set<String>>(emptySet())
    val pendingRemoval: StateFlow<Set<String>> = _pendingRemoval.asStateFlow()

    /** 「获取地址中」占位任务的 id 集合（无 Media3 记录，由 [SubscriptionPipeline] 后台解析推进）。 */
    private val _resolvingIds = MutableStateFlow<Set<String>>(emptySet())

    private val removalJobs = mutableMapOf<String, Job>()

    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null
    private var index: DefaultDownloadIndex? = null
    private var store: OfflineStore? = null
    private var prefs: DownloadPreferences? = null
    private val failureByDownloadId = mutableMapOf<String, Throwable>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var migrationJob: Job? = null

    /** 单条删除的撤销窗口时长。 */
    const val UNDO_WINDOW_MS = 5_000L

    @Synchronized
    fun ensureInitialized(context: Context): DownloadManager {
        manager?.let { return it }
        val ctx = context.applicationContext
        val database = StandaloneDatabaseProvider(ctx)
        val cacheDir = File(ctx.filesDir, "downloads")
        val simpleCache = SimpleCache(cacheDir, NoOpCacheEvictor(), database)
        cache = simpleCache
        val localStore = OfflineStore(ctx)
        store = localStore
        val localIndex = DefaultDownloadIndex(database)
        index = localIndex
        val downloaderFactory = OfflineDownloaderFactory(ctx, simpleCache, localStore)
        val dm = DownloadManager(ctx, localIndex, downloaderFactory)
        dm.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) failureByDownloadId[download.request.id] = finalException
                else failureByDownloadId.remove(download.request.id)
                refresh()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                failureByDownloadId.remove(download.request.id)
                refresh()
            }
            override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) = refresh()
            override fun onIdle(downloadManager: DownloadManager) = refresh()
        })
        dm.resumeDownloads()
        manager = dm
        prefs = DownloadPreferences(ctx)
        NetworkStatusMonitor.start(ctx)
        // WiFi ↔ 移动数据切换时自动暂停/恢复排队任务（不打断用户手动暂停）。
        monitorJob?.cancel()
        monitorJob = scope.launch {
            NetworkStatusMonitor.current.collect {
                applyNetworkGate()
                refresh()
            }
        }
        applyNetworkGate()
        refresh()
        // 存量数据规整：后台线程跑（不阻塞首屏），跑完有变更再刷新一次列表。
        // 只跑一次（prefs 里的 schema_version 闸门），已是最新版本时直接跳过。
        migrationJob?.cancel()
        migrationJob = scope.launch {
            val changed = withContext(Dispatchers.IO) {
                runCatching { localStore.migrateIfNeeded() }.getOrDefault(false)
            }
            if (changed) refresh()
        }
        return dm
    }

    fun cache(context: Context): Cache? {
        ensureInitialized(context)
        return cache
    }

    fun manager(context: Context): DownloadManager = ensureInitialized(context)

    /** 入队一个下载任务。重复任务复用、磁盘不足不开始、非法地址拒绝，结果以 [EnqueueResult] 表达。 */
    fun enqueue(context: Context, item: OfflineItem): EnqueueResult {
        if (item.url.isBlank() || !item.url.startsWith("http")) return EnqueueResult.INVALID_URL
        ensureInitialized(context)
        val id = OfflineDownloadRules.downloadIdFor(item.url)
        if (downloadIds().contains(id)) return EnqueueResult.DUPLICATE
        if (!OfflineDownloadRules.hasEnoughSpace(availableBytes(context), 0L)) return EnqueueResult.NO_SPACE
        store?.save(item.copy(id = id))
        val allowed = DownloadNetworkGate.isAllowed(NetworkStatusMonitor.currentKind(), prefs?.allowMobileData() ?: false)
        val stopReason = if (allowed) Download.STOP_REASON_NONE else OfflineDownloadRules.STOP_REASON_WAITING_NETWORK
        DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, buildRequest(item.copy(id = id)), stopReason, true)
        refresh()
        return if (allowed) EnqueueResult.STARTED else EnqueueResult.WAITING_NETWORK
    }

    /**
     * 「立即反馈」阶段：为选中的每集落一条「获取地址中」占位记录（无 Media3 记录），并立即刷新。
     * 真实地址由 [SubscriptionPipeline] 后台协程解析，成功后调用 [promoteResolved]、失败调 [failResolving]。
     */
    fun registerResolving(context: Context, items: List<OfflineItem>) {
        if (items.isEmpty()) return
        ensureInitialized(context)
        items.forEach { store?.save(it) }
        _resolvingIds.value = _resolvingIds.value + items.map { it.id }.toSet()
        refresh()
    }

    /** 解析成功：移除占位记录，转成真实下载任务入队（内部去重 + 落库 + 建 Media3 请求）。 */
    fun promoteResolved(context: Context, placeholderId: String, realItem: OfflineItem) {
        ensureInitialized(context)
        _resolvingIds.value = _resolvingIds.value - placeholderId
        store?.remove(placeholderId)
        enqueue(context, realItem)
        // 无论入队结果如何（含重复/非法/无空间）都立即刷新，确保占位记录不再展示为「获取地址中」。
        refresh()
    }

    /** 解析失败：占位记录仍在库里，离开 [ _resolvingIds ] 后即以 FAILED（获取地址失败）展示。 */
    fun failResolving(placeholderId: String) {
        _resolvingIds.value = _resolvingIds.value - placeholderId
        refresh()
    }

    fun pause(context: Context, id: String) {
        ensureInitialized(context)
        DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, OfflineDownloadRules.STOP_REASON_PAUSED, true)
    }

    fun resume(context: Context, id: String) {
        ensureInitialized(context)
        val allowed = DownloadNetworkGate.isAllowed(NetworkStatusMonitor.currentKind(), prefs?.allowMobileData() ?: false)
        val stopReason = if (allowed) Download.STOP_REASON_NONE else OfflineDownloadRules.STOP_REASON_WAITING_NETWORK
        DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, stopReason, true)
    }

    /** 设置页开关变化后立即重估闸门：恢复或暂停队列中的任务。 */
    fun onNetworkPreferenceChanged() {
        applyNetworkGate()
        refresh()
    }

    /** 失败任务重试：终态任务不在 currentDownloads，需直接移除后以相同 id 重新入队。 */
    fun retry(context: Context, id: String) {
        ensureInitialized(context)
        val item = store?.item(id) ?: return
        val dm = manager ?: return
        dm.removeDownload(id)
        val allowed = DownloadNetworkGate.isAllowed(NetworkStatusMonitor.currentKind(), prefs?.allowMobileData() ?: false)
        val stopReason = if (allowed) Download.STOP_REASON_NONE else OfflineDownloadRules.STOP_REASON_WAITING_NETWORK
        dm.addDownload(buildRequest(item), stopReason)
        failureByDownloadId.remove(id)
        refresh()
    }

    fun remove(context: Context, id: String) {
        ensureInitialized(context)
        DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, id, true)
        store?.remove(id)
        failureByDownloadId.remove(id)
        _resolvingIds.value = _resolvingIds.value - id
        refresh()
    }

    /**
     * 单条删除（可撤销）：先进入 5 秒撤销窗口，期间条目从列表隐藏并显示「撤销」；
     * 超时或用户没点撤销才真正删除。撤销窗口内重复删除同一 id 会重置计时。
     */
    fun removeWithUndo(context: Context, id: String) {
        ensureInitialized(context)
        removalJobs[id]?.cancel()
        _pendingRemoval.value = _pendingRemoval.value + id
        removalJobs[id] = scope.launch {
            kotlinx.coroutines.delay(UNDO_WINDOW_MS)
            doRemove(context, id)
            removalJobs.remove(id)
            _pendingRemoval.value = _pendingRemoval.value - id
        }
    }

    /** 撤销窗口内点「撤销」：取消待删除，条目重新出现在列表里。 */
    fun undoRemove(id: String) {
        removalJobs.remove(id)?.cancel()
        _pendingRemoval.value = _pendingRemoval.value - id
        refresh()
    }

    private fun doRemove(context: Context, id: String) {
        DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, id, true)
        store?.remove(id)
        failureByDownloadId.remove(id)
        refresh()
    }

    /** 设置页「清理全部」：移除所有任务、清元数据、删缓存目录并停前台服务。 */    fun clearAll(context: Context) {
        val ctx = context.applicationContext
        val localManager = manager
        val localIndex = index
        val localCache = cache
        if (localManager != null && localIndex != null) {
            val ids = downloadIds()
            localManager.release()
            ids.forEach { runCatching { localIndex.removeDownload(it) } }
            store?.clear()
            localCache?.release()
        } else {
            OfflineStore(ctx).clear()
        }
        failureByDownloadId.clear()
        manager = null
        cache = null
        index = null
        store = null
        _resolvingIds.value = emptySet()
        val dir = File(ctx.filesDir, "downloads")
        runCatching { SimpleCache.delete(dir, StandaloneDatabaseProvider(ctx)) }
        if (dir.exists()) runCatching { dir.deleteRecursively() }
        runCatching { ctx.stopService(Intent(ctx, OfflineDownloadService::class.java)) }
        _downloads.value = emptyList()
    }

    /**
     * 兜底定位成功后把详情页回写到这部剧的所有条目（老数据下次就不用再搜了）。
     * 剧名比对用清洗后的归一化名字，历史脏标题也能命中。
     */
    fun backfillDetailUrl(context: Context, title: String, detailUrl: String, sourceName: String = "") {
        val url = detailUrl.trim()
        if (url.isEmpty()) return
        ensureInitialized(context)
        val localStore = store ?: return
        localStore.updateAll { item -> ContinueDownloadRules.patchDetailUrl(item, title, url, sourceName) }
        refresh()
    }

    /** 海报回填：详情页/搜索结果拿到海报 URL 后，补进这部剧所有海报为空的下载记录。 */
    fun backfillCover(context: Context, title: String, cover: String) {
        if (cover.isBlank()) return
        ensureInitialized(context)
        val localStore = store ?: return
        localStore.updateAll { item -> OfflineDownloadRules.patchCover(item, title, cover) }
        refresh()
    }

    /**
     * 简介自愈：简介为空但存了详情页地址的老下载记录，抓详情页提取简介回填
     * （每次最多 [maxTitles] 部，节奏同海报自愈；启动调度由 UI 层接线调用）。
     * 同一部剧的多集按归一化剧名归组，只抓一次详情页、整部一起补。
     */
    suspend fun backfillMissingBlurbs(context: Context, maxTitles: Int = 5, fetcher: PageFetcher = HttpPageFetcher()) {
        ensureInitialized(context)
        val localStore = store ?: return
        val targets = localStore.items()
            .filter { it.blurb.isBlank() && it.detailUrl.isNotBlank() }
            .groupBy { MediaTitle.normalize(MediaTitle.core(it.title)) }
            .filterKeys { it.isNotEmpty() }
            .values.take(maxTitles)
        for (group in targets) {
            val detailUrl = group.firstNotNullOfOrNull { it.detailUrl.trim().takeIf(String::isNotEmpty) } ?: continue
            val referer = group.firstNotNullOfOrNull { it.headers["Referer"]?.takeIf { value -> value.isNotBlank() } }
            val blurb = runCatching {
                withContext(Dispatchers.IO) { VodBlurbParser.extract(fetcher.fetch(detailUrl, referer).body) }
            }.getOrDefault("")
            if (blurb.isBlank()) continue
            val title = group.first().title
            localStore.updateAll { item -> OfflineDownloadRules.patchBlurb(item, title, blurb) }
            refresh()
        }
    }

    /** 缓存占用（直接统计缓存目录文件大小，含在线播放顺手写入的缓存分片）。 */
    fun cacheSpaceBytes(context: Context): Long {
        val dir = File(context.applicationContext.filesDir, "downloads")
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun cacheSizeText(context: Context): String = OfflineDownloadRules.formatBytes(cacheSpaceBytes(context))

    fun downloadIds(): Set<String> {
        val active = manager?.currentDownloads?.map { it.request.id }.orEmpty()
        val terminal = runCatching {
            index?.getDownloads(Download.STATE_COMPLETED, Download.STATE_FAILED)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getDownload().request.id) }
            }.orEmpty()
        }.getOrDefault(emptyList())
        return (active + terminal).toSet()
    }

    private fun availableBytes(context: Context): Long =
        StatFs(context.applicationContext.filesDir.absolutePath).availableBytes

    private fun buildRequest(item: OfflineItem): DownloadRequest {
        val mimeType = when {
            item.isHls -> MimeTypes.APPLICATION_M3U8
            item.extension == "mp4" || item.url.substringBefore('?').endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
        val builder = DownloadRequest.Builder(item.id, Uri.parse(item.url))
        mimeType?.let { builder.setMimeType(it) }
        return builder.build()
    }

    /** 立即重读 DownloadManager 当前任务并刷新 UI 状态（进度每 800ms 轮询一次）。 */
    fun refresh() {
        doRefresh()
    }

    /** 网络闸门：WiFi/开启开关时恢复等待中的任务；移动数据且关闭开关时暂停排队与下载中的任务。 */
    private fun applyNetworkGate() {
        val dm = manager ?: return
        val allowed = DownloadNetworkGate.isAllowed(NetworkStatusMonitor.currentKind(), prefs?.allowMobileData() ?: false)
        if (allowed) {
            dm.currentDownloads.forEach { download ->
                if (download.stopReason == OfflineDownloadRules.STOP_REASON_WAITING_NETWORK ||
                    download.stopReason == OfflineDownloadRules.STOP_REASON_WAITING_WIFI
                ) {
                    dm.setStopReason(download.request.id, Download.STOP_REASON_NONE)
                }
            }
        } else {
            dm.currentDownloads.forEach { download ->
                when {
                    download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_RESTARTING -> {
                        if (download.stopReason == Download.STOP_REASON_NONE) {
                            dm.setStopReason(download.request.id, OfflineDownloadRules.STOP_REASON_WAITING_WIFI)
                        }
                    }
                    download.state == Download.STATE_QUEUED -> {
                        if (download.stopReason == Download.STOP_REASON_NONE) {
                            dm.setStopReason(download.request.id, OfflineDownloadRules.STOP_REASON_WAITING_NETWORK)
                        }
                    }
                }
            }
        }
    }

    private fun doRefresh() {
        val dm = manager ?: return
        val localStore = store ?: return
        val items = localStore.items().associateBy { it.id }
        // 活动任务（排队/下载/暂停/重启）在 currentDownloads 里；完成/失败是终态，需单独从索引读。
        val active = dm.currentDownloads.filter { it.state != Download.STATE_REMOVING }
        val terminal = runCatching {
            index?.getDownloads(Download.STATE_COMPLETED, Download.STATE_FAILED)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getDownload()) }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val merged = (active + terminal).distinctBy { it.request.id }
        val result = merged.map { download ->
            val item = items[download.request.id] ?: OfflineItem(
                id = download.request.id,
                title = download.request.uri.toString().substringAfterLast('/').ifBlank { "未命名下载" },
                url = download.request.uri.toString()
            )
            OfflineDownload(
                item = item,
                state = OfflineDownloadRules.mapState(download.state, download.stopReason),
                downloadedBytes = download.bytesDownloaded,
                totalBytes = download.contentLength,
                percent = download.percentDownloaded,
                failure = failureByDownloadId[download.request.id]
                    ?.let { OfflineDownloadRules.classifyFailure(it) }
                    ?: if (download.state == Download.STATE_FAILED) OfflineFailureReason.UNKNOWN
                    else OfflineFailureReason.NONE,
                completedAt = download.updateTimeMs
            )
        }
        // 「获取地址中 / 获取地址失败」的占位任务没有 Media3 记录，靠 [OfflineStore] 里的
        // resolveDetailUrl 标记识别：[ _resolvingIds ] 内为获取地址中，否则为获取地址失败。
        val resolvingIds = _resolvingIds.value
        val resolveItems = localStore.items().filter { it.resolveDetailUrl.isNotBlank() }
        val resolveDownloads = resolveItems.map { item ->
            val resolving = item.id in resolvingIds
            OfflineDownload(
                item = item,
                state = if (resolving) DownloadTaskState.RESOLVING else DownloadTaskState.FAILED,
                downloadedBytes = 0L,
                totalBytes = 0L,
                percent = 0f,
                failure = if (resolving) OfflineFailureReason.NONE else OfflineFailureReason.RESOLVE_FAILED,
                completedAt = item.createdAt
            )
        }
        _downloads.value = result + resolveDownloads
    }
}

/** 按 URL 类型路由到 HLS / 渐进式下载器，并注入该任务的 Referer / User-Agent 请求头。 */
@OptIn(UnstableApi::class)
class OfflineDownloaderFactory(
    private val context: Context,
    private val cache: SimpleCache,
    private val store: OfflineStore
) : DownloaderFactory {
    override fun createDownloader(request: DownloadRequest): Downloader {
        val headers = store.item(request.id)?.headers.orEmpty()
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
        val mediaItem = request.toMediaItem()
        val isHls = request.mimeType == MimeTypes.APPLICATION_M3U8 ||
            request.uri.toString().substringBefore('?').endsWith(".m3u8") ||
            request.uri.toString().substringBefore('?').endsWith(".m3u")
        return if (isHls) {
            // 官方 HlsDownloader 单连接逐片串行，这类 CDN 按连接限速会慢一个数量级；换并发分片下载器。
            ParallelHlsDownloader(request, cache, upstream)
        } else {
            ProgressiveDownloader(mediaItem, cacheFactory)
        }
    }
}
