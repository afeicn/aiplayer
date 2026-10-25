package com.afeicn.aiplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条离线缓存任务的元数据（片名、请求头、海报等）。Media3 的 DownloadManager 只持久化
 * url/进度/流键，不含这些展示字段，所以单独落一份，App 被杀重启后仍能还原片名与来源。
 */
data class OfflineItem(
    val id: String,
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val cover: String = "",
    val extension: String = "",
    val sourceName: String = "",
    val episodeName: String = "",
    val kind: MediaKind = MediaKind.VIDEO,
    val createdAt: Long = System.currentTimeMillis(),
    /** 该剧已知总集数（选集下载时记录；0 表示未知，用于「共 N 集，已下载 M 集」标注）。 */
    val totalEpisodes: Int = 0,
    /** 非空表示这是一条「获取地址中」占位/失败任务：待解析的详情页地址。 */
    val resolveDetailUrl: String = "",
    /** 占位任务对应的集号（供选集网格定位状态；0 表示非占位任务）。 */
    val resolveIndex: Int = 0,
    /**
     * 这部剧的详情页地址（来源线索）。
     *
     * 「继续下载」靠它重新抓一次集数列表，不用回到搜索页从头搜；
     * 老数据（本字段引入前落库的条目）该值为空，表示「没有来源信息」，此时不提供继续下载入口。
     */
    val detailUrl: String = "",
    /**
     * 季号（0 表示标题里没有季）。
     *
     * 命名规则要求季只出现在 `S{季}` 里、剧名里不再带「第二季」，所以迁移时把季从标题剥出来
     * 单独落库；展示层（[OfflineLibrary.displayTitle]）据此拼出 `剧名S2-1`。
     */
    val season: Int = 0,
    /**
     * 结构化集号（0 表示未知）。
     *
     * 老数据只有 `episodeName`（`第01集`）没有集号字段，迁移时解析后落库，供排序、去重、
     * 选集角标直接使用，不用每次展示再解析一遍字符串。
     */
    val episodeNumber: Int = 0,
    /**
     * 剧集简介（下载时从详情页 HTML 提取，见 [VodBlurbParser]；空表示没有）。
     *
     * 老数据没有这个字段，读出来是空串；离线详情页将来靠它离线展示简介。
     */
    val blurb: String = ""
) {
    val isHls: Boolean get() =
        extension == "m3u8" || extension == "m3u" || url.substringBefore('?').endsWith(".m3u8")

    /** 音频直链（音频源分流到普通文件下载，不走 HLS）。 */
    val isAudio: Boolean get() = kind == MediaKind.AUDIO

    /** 转成播放链路用的 MediaEntry：离线播放强制走缓存（offlineOnly），标题先洗掉 HTML 残渣。 */
    fun toMediaEntry(): MediaEntry = MediaEntry(
        uri = Uri.parse(url),
        name = MediaTitle.clean(title),
        kind = kind,
        folder = if (sourceName.isBlank()) "离线缓存" else "离线缓存 · $sourceName",
        extension = extension.ifBlank { url.substringBefore('?').substringAfterLast('.', "") },
        sizeBytes = 0L,
        lastModified = createdAt,
        title = MediaTitle.clean(episodeName),
        networkHeaders = headers,
        offlineOnly = true,
        // 优先用落库的结构化集号；老数据没有时从占位集号 / 集名兜底解析。
        episodeNumber = this.episodeNumber.takeIf { it > 0 }
            ?: resolveIndex.takeIf { it > 0 }
            ?: EpisodeNaming.episodeNumber(episodeName)
    )
}

/** 合并 Media3 Download 与本地元数据的 UI 模型。 */
data class OfflineDownload(
    val item: OfflineItem,
    val state: DownloadTaskState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Float,
    val failure: OfflineFailureReason,
    /** 下载最后更新时间（已完成任务约等于完成时间；Media3 Download.updateTimeMs）。 */
    val completedAt: Long = 0L
)

/** 元数据持久化（SharedPreferences + JSON），与 DownloadManager 的 SQLite 索引互补。 */
class OfflineStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("offline_downloads", Context.MODE_PRIVATE)

    @Synchronized
    fun save(item: OfflineItem) {
        // 新写入的条目也走一遍规整：存储层始终是「清洗后剧名 + 结构化季/集号」，
        // 不依赖展示层每次再解析（迁移器是幂等的，对已规整条目无副作用）。
        val normalized = OfflineItemMigrator.migrate(item)
        persist(items().filterNot { it.id == normalized.id } + normalized)
    }

    fun item(id: String): OfflineItem? = items().firstOrNull { it.id == id }

    fun items(): List<OfflineItem> =
        prefs.getString(KEY, null)?.let { OfflineItemCodec.decode(it) } ?: emptyList()

    @Synchronized
    fun remove(id: String) = persist(items().filterNot { it.id == id })

    /** 批量改写（如把兜底定位到的详情页回写到这部剧的所有条目）；没有变化就不写盘。 */
    @Synchronized
    fun updateAll(transform: (OfflineItem) -> OfflineItem) {
        val before = items()
        val after = before.map { OfflineItemMigrator.migrate(transform(it)) }
        if (after != before) persist(after)
    }

    @Synchronized
    fun clear() { prefs.edit().remove(KEY).apply() }

    /** 当前数据版本（0 = 本字段引入前的老库，需要跑一次存量迁移）。 */
    fun schemaVersion(): Int = prefs.getInt(KEY_SCHEMA_VERSION, 0)

    /**
     * 存量下载数据规整：旧版本 → 新命名规则（`剧名-集号` / `剧名S季-集号`）。
     *
     * 只跑一次：prefs 里的 [KEY_SCHEMA_VERSION] 达到 [OfflineItemMigrator.SCHEMA_VERSION] 后直接跳过，
     * 不会每次启动都重算。调用方应放在后台线程（App 启动时由 [OfflineDownloadManager] 触发）。
     *
     * 安全性：
     *  - 先在内存里把整份 JSON 算好，再一次性写回（`items_v1` 与版本号在同一个 editor 里提交）；
     *  - 单条坏数据由 [OfflineItemMigrator] 原样保留，不影响其它条目；
     *  - 整段 JSON 解析失败时**不写盘、不推进版本**，原数据保持原样，下次启动还能再试。
     *
     * @return 是否真的改写了数据（调用方据此刷新 UI）。
     */
    @Synchronized
    fun migrateIfNeeded(): Boolean {
        val storedVersion = schemaVersion()
        val raw = prefs.getString(KEY, null)
        val outcome = OfflineItemMigrator.migrateIfNeeded(storedVersion, raw) ?: return false
        // 整段坏 JSON：保留原数据与旧版本号，等下次启动/后续版本再处理。
        if (outcome.failed) return false
        val editor = prefs.edit()
        if (outcome.changed) outcome.raw?.let { editor.putString(KEY, it) }
        editor.putInt(KEY_SCHEMA_VERSION, OfflineItemMigrator.SCHEMA_VERSION)
        // commit 而不是 apply：迁移只跑一次，必须确认写成功后才认为版本已推进。
        val written = editor.commit()
        return written && outcome.changed
    }

    private fun persist(list: List<OfflineItem>) {
        prefs.edit().putString(KEY, OfflineItemCodec.encode(list)).apply()
    }

    private companion object {
        const val KEY = "items_v1"
        const val KEY_SCHEMA_VERSION = "schema_version"
    }
}

/**
 * [OfflineItem] 的 JSON 编解码（纯函数，不碰 SharedPreferences，方便纯 JVM 单测）。
 *
 * 读写要做到「旧库能读、新库能认」：
 *  - 老数据没有 `detailUrl` 字段 → 读出来是空串（表示没有来源信息），不抛异常、不丢条目；
 *  - 新数据总是写出 `detailUrl`，老版本 App 读到未知字段会忽略。
 */
object OfflineItemCodec {

    fun encode(items: List<OfflineItem>): String {
        val array = JSONArray()
        items.forEach { item -> array.put(encodeItem(item)) }
        return array.toString()
    }

    /** 单条编码（迁移器逐条读写用，保证坏条目可以原样跳过）。 */
    fun encodeItem(item: OfflineItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        // 标题落库前统一清洗：历史脏标题（`X">X` / 带 `<font>`）不再进入新数据。
        put("title", MediaTitle.clean(item.title))
        put("url", item.url)
        put("cover", item.cover)
        put("extension", item.extension)
        put("sourceName", item.sourceName)
        put("episodeName", MediaTitle.clean(item.episodeName))
        put("kind", item.kind.name)
        put("createdAt", item.createdAt)
        put("totalEpisodes", item.totalEpisodes)
        put("resolveDetailUrl", item.resolveDetailUrl)
        put("resolveIndex", item.resolveIndex)
        put("detailUrl", item.detailUrl)
        // 结构化季/集号：老版本读到未知字段会忽略；新版本据此排序与拼命名。
        put("season", item.season)
        put("episodeNumber", item.episodeNumber)
        // 简介：老版本读到未知字段会忽略；老数据缺这个字段时读出来是空串。
        put("blurb", item.blurb)
        put("headers", JSONObject().apply { item.headers.forEach { (key, value) -> put(key, value) } })
    }

    /** 解码；整段坏了返回空列表（与旧实现一致），单条缺字段则按缺省值补。 */
    fun decode(raw: String): List<OfflineItem> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                add(decodeItem(obj))
            }
        }
    }.getOrDefault(emptyList())

    /** 单条解码：老数据没有 `season` / `episodeNumber` 时补 0，不抛异常、不丢条目。 */
    fun decodeItem(obj: JSONObject): OfflineItem {
        val headersObj = obj.optJSONObject("headers")
        val headers = if (headersObj == null) emptyMap() else buildMap {
            headersObj.keys().forEach { key -> put(key, headersObj.optString(key)) }
        }
        return OfflineItem(
            id = obj.optString("id"),
            title = obj.optString("title"),
            url = obj.optString("url"),
            headers = headers,
            cover = obj.optString("cover"),
            extension = obj.optString("extension"),
            sourceName = obj.optString("sourceName"),
            episodeName = obj.optString("episodeName"),
            kind = runCatching { MediaKind.valueOf(obj.optString("kind")) }.getOrDefault(MediaKind.VIDEO),
            createdAt = obj.optLong("createdAt"),
            totalEpisodes = obj.optInt("totalEpisodes"),
            resolveDetailUrl = obj.optString("resolveDetailUrl"),
            resolveIndex = obj.optInt("resolveIndex"),
            detailUrl = obj.optString("detailUrl"),
            season = obj.optInt("season"),
            episodeNumber = obj.optInt("episodeNumber"),
            blurb = obj.optString("blurb")
        )
    }
}
