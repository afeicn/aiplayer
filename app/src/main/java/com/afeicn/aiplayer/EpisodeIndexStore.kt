package com.afeicn.aiplayer

/**
 * SharedPreferences 的最小整数读写口。
 * 抽出来只为让「剧名 → 上次看到第几集」的读写能用纯 JVM 单测覆盖，不引入 Robolectric。
 */
internal interface IntPreferences {
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
}

/** 集号进度的 key 规则：读写都先走 [MediaTitle.clean]，中文/空格/HTML 残渣不会造成两套 key。 */
internal object EpisodeProgressKeys {
    fun episode(title: String): String = "ep:${MediaTitle.clean(title)}"
    fun total(title: String): String = "eptotal:${MediaTitle.clean(title)}"
}

/**
 * 「剧名 → 上次看到第几集」的持久化。
 *
 * 写入方：播放页真正起播某一集时；选集面板点播时（它知道集数总量，顺带写总集数）。
 * 读取方：首页继续播放/最近浏览、详情页续播按钮、选集面板高亮。
 *
 * 越界值处理：只要曾记录过该剧总集数，读取时发现集号 > 总集数就按 0（无记录）处理；
 * [sanitized] 还会把这个 0 回写，避免脏值一直留在 prefs 里。
 * 老数据从未记录过总集数时无法判断是否越界，等选集面板拿到集数后会补做一次校验。
 */
internal class EpisodeIndexStore(private val prefs: IntPreferences) {

    /** 保存某剧看到第几集；[totalEpisodes] > 0 时顺带记住总集数，供后续越界校验。 */
    fun save(title: String, episodeIndex: Int, totalEpisodes: Int = 0) {
        val clean = MediaTitle.clean(title)
        if (clean.isEmpty()) return
        prefs.putInt(EpisodeProgressKeys.episode(clean), episodeIndex.coerceAtLeast(0))
        if (totalEpisodes > 0) prefs.putInt(EpisodeProgressKeys.total(clean), totalEpisodes)
    }

    /** 读取上次看到第几集；0 = 没有记录或记录越界。 */
    fun lastOf(title: String): Int {
        val clean = MediaTitle.clean(title)
        if (clean.isEmpty()) return 0
        val raw = prefs.getInt(EpisodeProgressKeys.episode(clean), 0)
        val total = prefs.getInt(EpisodeProgressKeys.total(clean), 0)
        return PlaybackProgressRules.validEpisode(raw, total)
    }

    /**
     * 已知总集数时读取并校验：越界值按 0 处理且回写，顺便记住总集数。
     * 选集面板/首页拿到离线剧总集数时调用，完成「启动后发现脏数据就归零」。
     */
    fun sanitized(title: String, totalEpisodes: Int): Int {
        val clean = MediaTitle.clean(title)
        if (clean.isEmpty() || totalEpisodes <= 0) return lastOf(title)
        if (prefs.getInt(EpisodeProgressKeys.total(clean), 0) != totalEpisodes) {
            prefs.putInt(EpisodeProgressKeys.total(clean), totalEpisodes)
        }
        val raw = prefs.getInt(EpisodeProgressKeys.episode(clean), 0)
        val valid = PlaybackProgressRules.validEpisode(raw, totalEpisodes)
        if (valid != raw) prefs.putInt(EpisodeProgressKeys.episode(clean), valid)
        return valid
    }
}
