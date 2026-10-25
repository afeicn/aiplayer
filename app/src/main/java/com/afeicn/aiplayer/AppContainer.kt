package com.afeicn.aiplayer

import android.app.Application
import android.content.Context

/**
 * 全局依赖容器：所有 Store / Engine 在这里按 applicationContext 建一次。
 * 不引 DI 框架，保持 v1 简单。
 */
object AppContainer {
    lateinit var appContext: Context
        private set

    val sourceStore: OnlineSourceStore by lazy { OnlineSourceStore(appContext) }
    val searchHistory: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }
    val searchEngine: OnlineSearchEngine by lazy { OnlineSearchEngine(sourceStore) }
    val episodeProgress: EpisodeProgressStore by lazy { EpisodeProgressStore(appContext) }
    val downloadPrefs: DownloadPreferences by lazy { DownloadPreferences(appContext) }
    val playbackProgress: PlaybackProgressStore by lazy { PlaybackProgressStore(appContext) }
    val aiSettings: AiSettingsStore by lazy { AiSettingsStore(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 初始化下载核心（SimpleCache + DownloadManager），杀进程后任务自动恢复。
        OfflineDownloadManager.ensureInitialized(appContext)
    }

    /** 已开启且配了 Key 才返回客户端；否则 null（不调模型）。 */
    fun aiClientOrNull(): DeepSeekClient? =
        if (aiSettings.usable()) DeepSeekClient(HttpLlmTransport(), aiSettings.apiKey(), aiSettings.model()) else null

    /** 搜索兜底：包一层 [aiClientOrNull]，不可用返回 null。 */
    fun aiSearchFallbackOrNull(): AiSearchFallback? = aiClientOrNull()?.let { client ->
        AiSearchFallback { keyword, html, base -> client.extractResults(keyword, html, base) }
    }
}

class AIPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}

/**
 * 播放进度持久化：按条目 uri 记续播位置；另记最近播放的剧名，供首页「最近播放」大卡。
 */
class PlaybackProgressStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    /** 集号进度的读写规则独立出来，纯 JVM 单测可直接验证 key/覆盖/越界。 */
    private val episodes = EpisodeIndexStore(object : IntPreferences {
        override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
        override fun putInt(key: String, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    })

    fun savePosition(uri: String, positionMs: Long) {
        if (positionMs > 5_000L) prefs.edit().putLong("pos:$uri", positionMs).apply()
    }

    fun positionOf(uri: String): Long = prefs.getLong("pos:$uri", 0L)

    fun saveLastSeries(title: String) {
        prefs.edit().putString("last_series", MediaTitle.clean(title)).apply()
    }

    fun lastSeriesTitle(): String? = prefs.getString("last_series", null)

    /**
     * 按剧名记上次播到第几集（首页/选集面板的续播用；0 = 没有记录）。
     * [totalEpisodes] > 0 时顺带记总集数，供之后判断历史值是否越界。
     */
    fun saveLastEpisode(title: String, episodeIndex: Int, totalEpisodes: Int = 0) {
        episodes.save(title, episodeIndex, totalEpisodes)
    }

    fun lastEpisodeOf(title: String): Int = episodes.lastOf(title)

    /** 已知总集数时读取：越界脏值归零并回写，同时记住总集数。 */
    fun validatedLastEpisodeOf(title: String, totalEpisodes: Int): Int =
        episodes.sanitized(title, totalEpisodes)

    /** 播过一集就把作品记进「最近浏览」：带续播地址与详情页地址，新记录置顶、同名去重、封顶 12 条。 */
    fun recordPlay(title: String, cover: String, playUrl: String, timeMillis: Long, detailUrl: String = "") {
        val merged = RecentPlayLog.merge(
            RecentPlayLog.decode(prefs.getString("recent", "").orEmpty()),
            RecentPlay(title, cover, timeMillis, playUrl, detailUrl)
        )
        prefs.edit().putString("recent", RecentPlayLog.encode(merged)).apply()
    }

    /** 点进过详情页就记进「最近浏览」：记上详情页地址（供下次直达），只更新时间置顶，不冲掉已有的续播地址。 */
    fun recordView(title: String, cover: String, timeMillis: Long, detailUrl: String = "") {
        recordPlay(title, cover, "", timeMillis, detailUrl)
    }

    fun recentPlays(): List<RecentPlay> =
        RecentPlayLog.decode(prefs.getString("recent", "").orEmpty())

    /** 最近浏览海报自愈：给同名且封面为空的记录补封面（详情页/搜索结果抓到海报时顺带调用）。 */
    fun patchRecentCover(title: String, cover: String) {
        val current = recentPlays()
        val patched = RecentPlayLog.patchCover(current, title, cover)
        if (patched != current) prefs.edit().putString("recent", RecentPlayLog.encode(patched)).apply()
    }
}
