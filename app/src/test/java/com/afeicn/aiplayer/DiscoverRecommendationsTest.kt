package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverRecommendationsTest {

    private fun source(id: String) =
        OnlineVideoSource(id = id, name = id, base = "http://$id.example.com", referer = "", userAgent = "")

    private fun card(name: String, pic: String = "", year: String = "", vararg sourceIds: String) =
        AggregatedVod(
            name = name,
            pic = pic,
            year = year,
            area = "",
            remarks = "",
            hits = sourceIds.map { SourceHit(source(it), VodItem(vodName = name, vodPic = pic, vodYear = year)) }
        )

    @Test
    fun hotSearchListIsCuratedAndBounded() {
        val display = HotSearchCatalog.display()

        assertEquals(HotSearchCatalog.DISPLAY_COUNT, display.size)
        assertTrue(display.all { it.isNotBlank() })
        assertEquals(display.size, display.distinct().size)
        // 预取条数不能超过展示条数，否则首屏会为看不见的关键词去压源。
        assertTrue(HotSearchCatalog.PREFETCH_COUNT <= display.size)
        assertEquals(display.take(HotSearchCatalog.PREFETCH_COUNT), HotSearchCatalog.prefetch())
    }

    @Test
    fun noEnabledSourcesSkipsPrefetch() {
        assertFalse(RecommendationRules.shouldPrefetch(0))
        assertTrue(RecommendationRules.shouldPrefetch(1))
    }

    @Test
    fun mergeDedupesSameTitleAcrossKeywords() {
        // 「庆余年」和「庆余年 第二季」两次搜索命中同一部剧，源数不能重复计数。
        val groupA = listOf(card("庆余年", pic = "https://p/a.jpg", year = "2024", sourceIds = arrayOf("s1", "s2")))
        val groupB = listOf(card("庆余年", pic = "https://p/a.jpg", year = "2024", sourceIds = arrayOf("s2", "s3")))

        val merged = RecommendationRules.merge(listOf(groupA, groupB))

        assertEquals(1, merged.size)
        assertEquals(3, merged[0].sourceCount)
    }

    @Test
    fun mergePutsCoveredCardsFirst() {
        val rich = card("有封面", pic = "https://p/a.jpg", year = "2020", sourceIds = arrayOf("s1"))
        val bare = card("无封面", pic = "", year = "2026", sourceIds = arrayOf("s1", "s2", "s3"))

        val merged = RecommendationRules.merge(listOf(listOf(bare), listOf(rich)))

        assertEquals(listOf("有封面", "无封面"), merged.map { it.name })
    }

    @Test
    fun mergeSortsBySourceCountThenYear() {
        val few = card("少源", pic = "https://p/1.jpg", year = "2026", sourceIds = arrayOf("s1"))
        val oldMany = card("多源旧", pic = "https://p/2.jpg", year = "2015", sourceIds = arrayOf("s1", "s2"))
        val newMany = card("多源新", pic = "https://p/3.jpg", year = "2025", sourceIds = arrayOf("s1", "s2"))

        val merged = RecommendationRules.merge(listOf(listOf(few, oldMany, newMany)))

        assertEquals(listOf("多源新", "多源旧", "少源"), merged.map { it.name })
    }

    @Test
    fun mergeCapsAtLimitAndIgnoresEmptyGroups() {
        val groups = (1..20).map { i -> listOf(card("剧$i", pic = "https://p/$i.jpg", year = "2020", sourceIds = arrayOf("s1"))) }

        val merged = RecommendationRules.merge(groups)

        assertEquals(RecommendationRules.MAX_CARDS, merged.size)
        assertTrue(RecommendationRules.merge(emptyList()).isEmpty())
        assertTrue(RecommendationRules.merge(listOf(emptyList(), emptyList())).isEmpty())
    }

    // ==================== matchByTitle：搜索落空时的推荐流本地匹配 ====================

    @Test
    fun matchByTitle_带标记同名精确命中() {
        // 真机场景：推荐卡「三体[第一季全]」，用户搜「三体[第一季全]」源上搜不到。
        val recs = listOf(card("庆余年"), card("三体[第一季全]"))
        assertEquals(
            listOf("三体[第一季全]"),
            RecommendationRules.matchByTitle(recs, "三体[第一季全]").map { it.name }
        )
        // 剥标记后同名也算：搜「三体」主干命中带标记卡。
        assertEquals(
            listOf("三体[第一季全]"),
            RecommendationRules.matchByTitle(recs, "三体（第一季）").map { it.name }
        )
    }

    @Test
    fun matchByTitle_空格分段全含即命中() {
        // 「斗罗大陆 剑道尘心」不是任何卡片的同名，但两段都出现在「斗罗大陆剧场版 剑道尘心」里。
        val recs = listOf(card("斗罗大陆剧场版 剑道尘心"), card("斗罗大陆"), card("剑来"))
        assertEquals(
            listOf("斗罗大陆剧场版 剑道尘心"),
            RecommendationRules.matchByTitle(recs, "斗罗大陆 剑道尘心").map { it.name }
        )
    }

    @Test
    fun matchByTitle_分段不全或空输入不命中() {
        val recs = listOf(card("斗罗大陆剧场版 剑道尘心"))
        // 只命中一段不算，避免「斗罗大陆」把整条推荐流都捞出来。
        assertTrue(RecommendationRules.matchByTitle(recs, "斗罗大陆 佩曦").isEmpty())
        assertTrue(RecommendationRules.matchByTitle(recs, "").isEmpty())
        assertTrue(RecommendationRules.matchByTitle(emptyList(), "斗罗大陆").isEmpty())
    }
}
