package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选集网格选中集合纯计算：全选 / 反选 / 前 N 集 / 清空 / 单格点选切换。 */
class EpisodeSelectionTest {

    @Test
    fun allSelectsEveryEpisodeFromOneToTotal() {
        assertEquals(emptySet<Int>(), EpisodeSelection.all(0))
        assertEquals(emptySet<Int>(), EpisodeSelection.all(-3))
        assertEquals((1..5).toSet(), EpisodeSelection.all(5))
    }

    @Test
    fun firstNSelectsFirstTenClampedToTotal() {
        assertEquals((1..10).toSet(), EpisodeSelection.firstN(20, 10))
        assertEquals((1..5).toSet(), EpisodeSelection.firstN(5, 10))
        assertEquals(emptySet<Int>(), EpisodeSelection.firstN(20, 0))
        assertEquals(emptySet<Int>(), EpisodeSelection.firstN(0, 10))
    }

    @Test
    fun rangeIsClosedIntervalClampedToTotal() {
        assertEquals((11..20).toSet(), EpisodeSelection.range(30, 11, 20))
        // 超出总集数自动裁断；起点越界为空。
        assertEquals((21..30).toSet(), EpisodeSelection.range(30, 21, 99))
        assertEquals(emptySet<Int>(), EpisodeSelection.range(30, 31, 40))
        // from > to 是空区间。
        assertEquals(emptySet<Int>(), EpisodeSelection.range(30, 15, 10))
        assertEquals(emptySet<Int>(), EpisodeSelection.range(0, 1, 5))
    }

    @Test
    fun inverseSelectsEverythingNotSelected() {
        assertEquals((1..5).toSet(), EpisodeSelection.inverse(5, emptySet()))
        assertEquals(setOf(1, 3, 5), EpisodeSelection.inverse(5, setOf(2, 4)))
        assertEquals(emptySet<Int>(), EpisodeSelection.inverse(5, (1..5).toSet()))
    }

    @Test
    fun clearReturnsEmptySet() {
        assertTrue(EpisodeSelection.clear().isEmpty())
        assertEquals(emptySet<Int>(), EpisodeSelection.clear())
    }

    @Test
    fun toggleAddsThenRemoves() {
        assertEquals(setOf(1), EpisodeSelection.toggle(emptySet(), 1))
        assertEquals(setOf(1, 3), EpisodeSelection.toggle(setOf(1), 3))
        assertEquals(setOf(3), EpisodeSelection.toggle(setOf(1, 3), 1))
    }
}
