package com.afeicn.aiplayer

/**
 * 首页「最近浏览」的一条记录：点进过详情页的作品就进这里。
 * [playUrl] 是在线作品最后一集的播放页地址（大卡续播凭它重新解析流地址）；
 * 只浏览没播放、或离线剧时留空。
 */
data class RecentPlay(
    val title: String,
    val cover: String,
    val timeMillis: Long,
    val playUrl: String = "",
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
     */
    fun merge(existing: List<RecentPlay>, entry: RecentPlay, max: Int = MAX): List<RecentPlay> {
        if (entry.title.isBlank()) return existing
        val previous = existing.firstOrNull { OfflineLibrary.sameTitle(it.title, entry.title) }
        val kept = if (entry.playUrl.isBlank() && previous != null)
            entry.copy(playUrl = previous.playUrl) else entry
        val fresh = listOf(kept) + existing.filter { !OfflineLibrary.sameTitle(it.title, entry.title) }
        return fresh.take(max.coerceAtLeast(1))
    }

    /** 字段里的 | 和换行会破坏行式格式，写入前统一换成空格。 */
    private fun sanitize(field: String): String =
        field.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(entries: List<RecentPlay>): String =
        entries.joinToString("\n") { "${sanitize(it.title)}|${sanitize(it.cover)}|${it.timeMillis}|${sanitize(it.playUrl)}" }

    fun decode(raw: String): List<RecentPlay> =
        raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            RecentPlay(
                title = parts[0],
                cover = parts[1],
                timeMillis = parts[2].toLongOrNull() ?: 0L,
                playUrl = if (parts.size >= 4) parts[3] else ""
            )
        }.toList()
}
