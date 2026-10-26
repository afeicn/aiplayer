package com.afeicn.aiplayer

internal object PlaybackProgressRules {
    fun resumePosition(sameMedia: Boolean, livePosition: Long, savedPosition: Long): Long =
        (if (sameMedia) livePosition else savedPosition).coerceAtLeast(0L)

    fun saveOutgoing(oldId: String?, newId: String?): Boolean = oldId != null && oldId != newId

    /**
     * 集号合法性：非正数一律视为没有记录；已知总集数时，超过总集数的历史脏值也归零。
     * [totalEpisodes] <= 0 表示总量未知，此时无法判断上界，只校验下界。
     */
    fun validEpisode(raw: Int, totalEpisodes: Int): Int = when {
        raw <= 0 -> 0
        totalEpisodes > 0 && raw > totalEpisodes -> 0
        else -> raw
    }

    /**
     * 离线队列的起播位置（下标，队列与三个列表按同一顺序一一对应）：
     * 优先落在「上次播到第几集」记录对应的那一集；该集没下载就退到它之前最近的一集；
     * 没有集号记录（或都对不上）才用断点记录兜底——取第一条有断点的集；都没有从第一集起播。
     * 断点兜底不能取「最后一条断点」：断点没有时效，回看重播会把旧记录顶成新进度。
     */
    fun offlineResumeIndex(
        episodeNumbers: List<Int?>,
        lastEpisode: Int,
        positions: List<Long>
    ): Int {
        if (lastEpisode > 0) {
            val exact = episodeNumbers.indexOfFirst { it == lastEpisode }
            if (exact >= 0) return exact
            val before = episodeNumbers.indices
                .filter { (episodeNumbers[it] ?: 0) in 1 until lastEpisode }
                .maxByOrNull { episodeNumbers[it]!! }
            if (before != null) return before
        }
        return positions.indexOfFirst { it > 0L }.coerceAtLeast(0)
    }
}
