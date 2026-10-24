package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「确认范围」集数范围解析单测：全下 / 列表 / 前 N / 区间 / 单个，含中文数字，越界夹取、去重。
 */
class EpisodeRangeParserTest {

    @Test
    fun parsesDownloadAll() {
        assertEquals((1..30).toList(), EpisodeRangeParser.parse("全下", 30))
        assertEquals((1..30).toList(), EpisodeRangeParser.parse("全部", 30))
        assertEquals((1..30).toList(), EpisodeRangeParser.parse("所有", 30))
        assertEquals((1..8).toList(), EpisodeRangeParser.parse("都下", 8))
    }

    @Test
    fun parsesList() {
        assertEquals(listOf(3, 5), EpisodeRangeParser.parse("第3、5集", 30))
        assertEquals(listOf(3, 5), EpisodeRangeParser.parse("3,5", 30))
        assertEquals(listOf(1, 2, 7), EpisodeRangeParser.parse("第1、2、7集", 30))
    }

    @Test
    fun parsesFirstN() {
        assertEquals((1..10).toList(), EpisodeRangeParser.parse("前10集", 30))
        assertEquals((1..10).toList(), EpisodeRangeParser.parse("前10", 30))
        assertEquals((1..3).toList(), EpisodeRangeParser.parse("前三集", 30))
    }

    @Test
    fun parsesRange() {
        assertEquals((2..8).toList(), EpisodeRangeParser.parse("第2集到第8集", 30))
        assertEquals((2..8).toList(), EpisodeRangeParser.parse("2-8", 30))
        assertEquals((2..8).toList(), EpisodeRangeParser.parse("2到8", 30))
    }

    @Test
    fun parsesSingle() {
        assertEquals(listOf(3), EpisodeRangeParser.parse("第3集", 30))
        assertEquals(listOf(3), EpisodeRangeParser.parse("3", 30))
        assertEquals(listOf(12), EpisodeRangeParser.parse("第十二集", 30))
    }

    @Test
    fun clampsOutOfRangeAndDedupes() {
        assertEquals((1..30).toList(), EpisodeRangeParser.parse("前100集", 30))
        assertEquals(listOf(1), EpisodeRangeParser.parse("第0集", 30))
        assertEquals(listOf(30), EpisodeRangeParser.parse("第99集", 30))
        assertEquals(listOf(3), EpisodeRangeParser.parse("第3、3集", 30))
    }

    @Test
    fun invalidRangeReturnsEmpty() {
        assertEquals(emptyList<Int>(), EpisodeRangeParser.parse("随便看看", 30))
        assertEquals(emptyList<Int>(), EpisodeRangeParser.parse("", 30))
        assertEquals(emptyList<Int>(), EpisodeRangeParser.parse("第2集到第8集", 0))
        assertTrue(!EpisodeRangeParser.isRangeRequest("不知道"))
        assertTrue(EpisodeRangeParser.isRangeRequest("全下"))
        assertTrue(EpisodeRangeParser.isRangeRequest("3-5"))
    }
}
