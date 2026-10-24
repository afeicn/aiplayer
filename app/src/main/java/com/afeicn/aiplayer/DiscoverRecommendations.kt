package com.afeicn.aiplayer

/**
 * 发现页「还没输入关键词」时的推荐内容。
 *
 * 分两层，来源不同，别混为一谈：
 * ① [HotSearchCatalog] 热门搜索榜——内置精选关键词，不依赖网络、不依赖订阅源，点了就发起真搜索；
 * ② [RecommendationRules] 推荐流——拿榜单前几条关键词真跑一次跨源搜索再聚合，
 *    卡片上的封面、年份、「N 个源」全部来自真实结果，没有启用中的源时这一层为空，由 UI 显示引导。
 */

/** 内置热搜榜。影视/动漫向，与 App 只做视频的范围一致。 */
object HotSearchCatalog {

    /** 榜单关键词，按热度序（榜单第 1 名在前）。 */
    val keywords: List<String> = listOf(
        "庆余年", "斗罗大陆", "三体", "凡人修仙传", "进击的巨人", "玫瑰的故事"
    )

    /** 榜单展示条数（两列三行）。 */
    const val DISPLAY_COUNT = 6

    /** 用于预取推荐的关键词条数：再多会拖慢首屏、给订阅源加压。 */
    const val PREFETCH_COUNT = 3

    fun display(): List<String> = keywords.take(DISPLAY_COUNT)

    fun prefetch(): List<String> = keywords.take(PREFETCH_COUNT)
}

object RecommendationRules {

    /** 推荐流卡片上限。 */
    const val MAX_CARDS = 8

    /** 没有启用中的源就不去白跑网络，UI 改显示引导。 */
    fun shouldPrefetch(enabledSources: Int): Boolean = enabledSources > 0

    /**
     * 把多个关键词各自的聚合结果合成一条推荐流。
     *
     * 不同关键词可能命中同一部剧（如「三体」与「三体 第二季」各自命中同名结果），
     * 这里摊平 hit 后交给 [VodAggregator] 重新按同名合并，源数才不会重复计数。
     * 排序上把「有封面」排在前面——推荐流里全是占位图等于没推荐。
     */
    fun merge(groups: List<List<AggregatedVod>>, limit: Int = MAX_CARDS): List<AggregatedVod> {
        val hits = groups.flatMap { card -> card.flatMap { it.hits } }
        if (hits.isEmpty()) return emptyList()
        return VodAggregator.aggregate(hits)
            .filter { it.name.isNotBlank() }
            .sortedWith(
                compareByDescending<AggregatedVod> { it.pic.isNotBlank() }
                    .thenByDescending { it.sourceCount }
                    .thenByDescending { it.year.toIntOrNull() ?: 0 }
                    .thenBy { it.name }
            )
            .take(limit)
    }
}
