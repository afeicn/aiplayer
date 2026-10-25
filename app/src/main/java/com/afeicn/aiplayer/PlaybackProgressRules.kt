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
}
