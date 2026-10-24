package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线搜索历史纯逻辑单测：记录（去重 / 提到最前 / 上限 / 空白过滤）、单条与批量删除、
 * 撤销插回、多选集合、JSON 编解码，以及这两类删除操作登记的防护机制。
 */
class SearchHistoryTest {

    private fun historyOf(vararg keywords: String): List<SearchHistoryEntry> =
        keywords.mapIndexed { index, keyword ->
            // 越靠前的越新（history 恒为「最近的在最前」）。
            SearchHistoryEntry(keyword, 1_000L - index)
        }

    private fun keywords(history: List<SearchHistoryEntry>): List<String> = history.map { it.keyword }

    // ==================== 记录：新增 ====================

    @Test
    fun recordAddsNewKeywordToFront() {
        val after = SearchHistoryRules.record(historyOf("旧词"), "新词", now = 2_000L)
        assertEquals(listOf("新词", "旧词"), keywords(after))
        assertEquals(2_000L, after.first().searchedAt)
    }

    @Test
    fun recordIntoEmptyHistory() {
        val after = SearchHistoryRules.record(emptyList(), "流浪地球", now = 5L)
        assertEquals(listOf("流浪地球"), keywords(after))
    }

    // ==================== 记录：去重 + 提到最前 ====================

    @Test
    fun recordDeduplicatesAndMovesToFront() {
        val before = historyOf("第三个", "第二个", "第一个")
        val after = SearchHistoryRules.record(before, "第三个", now = 9_000L)
        assertEquals(listOf("第三个", "第二个", "第一个"), keywords(after))
        // 只有一条「第三个」，且时间戳被刷新
        assertEquals(1, after.count { it.keyword == "第三个" })
        assertEquals(9_000L, after.first().searchedAt)
    }

    @Test
    fun recordDeduplicatesIgnoringCaseAndSurroundingSpaces() {
        val after = SearchHistoryRules.record(historyOf("DeepSeek"), "  deepseek ", now = 7L)
        assertEquals(1, after.size)
        // 保留最新一次的写法（用户当次输入）
        assertEquals("deepseek", after.first().keyword)
    }

    @Test
    fun recordKeepsInnerSpacesDistinct() {
        val after = SearchHistoryRules.record(historyOf("流浪地球"), "流浪 地球", now = 7L)
        assertEquals(2, after.size)
    }

    // ==================== 记录：上限 ====================

    @Test
    fun recordKeepsAtMostTenEntriesByDefault() {
        var history = emptyList<SearchHistoryEntry>()
        repeat(15) { index -> history = SearchHistoryRules.record(history, "关键词$index", now = index.toLong()) }
        assertEquals(SearchHistoryRules.MAX_ENTRIES, history.size)
        assertEquals(10, history.size)
        // 最旧的 5 条（关键词0..关键词4）被丢弃，只剩最近的 10 条且最近的在最前
        assertEquals("关键词14", history.first().keyword)
        assertEquals("关键词5", history.last().keyword)
        assertFalse(keywords(history).contains("关键词4"))
    }

    @Test
    fun recordRespectsCustomLimit() {
        var history = emptyList<SearchHistoryEntry>()
        repeat(60) { index -> history = SearchHistoryRules.record(history, "词$index", now = index.toLong(), limit = 50) }
        assertEquals(50, history.size)
        assertEquals("词59", history.first().keyword)
        assertEquals("词10", history.last().keyword)
    }

    @Test
    fun recordWithLimitOneKeepsOnlyLatest() {
        val after = SearchHistoryRules.record(historyOf("a", "b"), "c", now = 1L, limit = 1)
        assertEquals(listOf("c"), keywords(after))
    }

    // ==================== 记录：空白过滤 ====================

    @Test
    fun sanitizeRejectsBlankKeywords() {
        assertNull(SearchHistoryRules.sanitize(""))
        assertNull(SearchHistoryRules.sanitize("   "))
        assertNull(SearchHistoryRules.sanitize("\t\n "))
        assertEquals("流浪地球", SearchHistoryRules.sanitize(" 流浪地球 "))
    }

    @Test
    fun recordIgnoresBlankKeywordsAndKeepsOrder() {
        val before = historyOf("a", "b")
        val after = SearchHistoryRules.record(before, "   ", now = 9L)
        assertEquals(keywords(before), keywords(after))
        assertEquals(before, after)
    }

    // ==================== 删除：单条 ====================

    @Test
    fun removeDropsOnlyThatEntry() {
        val after = SearchHistoryRules.remove(historyOf("a", "b", "c"), "b")
        assertEquals(listOf("a", "c"), keywords(after))
    }

    @Test
    fun removeIsCaseInsensitiveAndTolerant() {
        val after = SearchHistoryRules.remove(historyOf("DeepSeek", "b"), " deepseek ")
        assertEquals(listOf("b"), keywords(after))
    }

    @Test
    fun removeUnknownKeywordLeavesHistoryUntouched() {
        val before = historyOf("a", "b")
        assertEquals(before, SearchHistoryRules.remove(before, "不存在"))
        assertEquals(before, SearchHistoryRules.remove(before, "  "))
    }

    // ==================== 删除：批量 ====================

    @Test
    fun removeAllDropsEverySelectedEntry() {
        val after = SearchHistoryRules.removeAll(historyOf("a", "b", "c", "d"), listOf("b", "d"))
        assertEquals(listOf("a", "c"), keywords(after))
    }

    @Test
    fun removeAllWithEmptySelectionKeepsAll() {
        val before = historyOf("a", "b")
        assertEquals(before, SearchHistoryRules.removeAll(before, emptyList()))
        assertEquals(before, SearchHistoryRules.removeAll(before, listOf("  ")))
    }

    @Test
    fun removeAllCanEmptyHistory() {
        val before = historyOf("a", "b")
        assertTrue(SearchHistoryRules.removeAll(before, listOf("a", "b")).isEmpty())
    }

    // ==================== 撤销插回 ====================

    @Test
    fun restorePutsEntryBackAtSamePosition() {
        val before = historyOf("a", "b", "c")
        val removed = before[1]
        val afterRemove = SearchHistoryRules.remove(before, removed.keyword)
        assertEquals(listOf("a", "c"), keywords(afterRemove))
        // 撤销后回到原来的第 2 位（而不是排到末尾）
        assertEquals(keywords(before), keywords(SearchHistoryRules.restore(afterRemove, removed)))
    }

    @Test
    fun restoreDoesNotDuplicateExistingEntry() {
        // 重复撤销对同一个条目不会生出两条（按归一化键去重，位置由时间戳决定）。
        val history = listOf(SearchHistoryEntry("a", 2_000L), SearchHistoryEntry("b", 1_000L))
        val after = SearchHistoryRules.restore(history, SearchHistoryEntry("b", 1_000L))
        assertEquals(2, after.size)
        assertEquals(listOf("a", "b"), keywords(after))
    }

    // ==================== 多选集合 ====================

    @Test
    fun selectionToggleAndSelectAllWork() {
        val entries = historyOf("a", "b", "c")
        val ids = entries.map { it.keyword }
        var selection = SearchHistorySelection()
        selection = selection.toggle("b")
        assertEquals(1, selection.count)
        selection = selection.toggleAll(ids)
        assertTrue(selection.isAllSelected(ids))
        assertEquals(3, selection.count)
        assertEquals(0, selection.toggleAll(ids).count)
    }

    @Test
    fun selectedKeywordsFiltersGhostIds() {
        val entries = historyOf("a", "b")
        val selected = setOf("a", "已被删掉的词")
        assertEquals(listOf("a"), SearchHistoryRules.selectedKeywords(entries, selected))
        assertTrue(SearchHistoryRules.selectedKeywords(entries, emptySet()).isEmpty())
    }

    @Test
    fun selectionCountDrivesDeleteButtonEnablement() {
        val entries = historyOf("a", "b")
        val selection = SearchHistorySelection().selectAll(entries.map { it.keyword })
        val keywords = SearchHistoryRules.selectedKeywords(entries, selection.selected)
        assertEquals(2, keywords.size)
        assertTrue(SearchHistoryRules.removeAll(entries, keywords).isEmpty())
    }

    // ==================== 展示口径 ====================

    @Test
    fun historyIsHiddenWhenEmpty() {
        assertFalse(SearchHistoryRules.isVisible(emptyList()))
        assertTrue(SearchHistoryRules.isVisible(historyOf("a")))
    }

    // ==================== JSON 编解码 ====================

    @Test
    fun codecRoundTripsEntries() {
        val entries = historyOf("流浪地球", "DeepSeek")
        assertEquals(entries, SearchHistoryCodec.decode(SearchHistoryCodec.encode(entries)))
    }

    @Test
    fun codecReturnsEmptyOnBrokenJson() {
        assertTrue(SearchHistoryCodec.decode("not json").isEmpty())
        assertTrue(SearchHistoryCodec.decode("").isEmpty())
    }

    @Test
    fun codecSkipsEntriesWithoutKeyword() {
        val raw = """[{"keyword":"","searchedAt":1},{"searchedAt":2},{"keyword":"ok","searchedAt":3}]"""
        val decoded = SearchHistoryCodec.decode(raw)
        assertEquals(listOf("ok"), decoded.map { it.keyword })
    }

    // ==================== 破坏性操作防护登记 ====================

    @Test
    fun singleHistoryDeleteUsesUndo() {
        assertTrue(DestructiveActionPolicy.usesUndo(DestructiveOp.DELETE_SINGLE_SEARCH_HISTORY))
        assertFalse(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.DELETE_SINGLE_SEARCH_HISTORY))
    }

    @Test
    fun batchHistoryDeleteRequiresConfirmDialog() {
        assertTrue(DestructiveActionPolicy.requiresConfirmDialog(DestructiveOp.DELETE_SELECTED_SEARCH_HISTORY))
        assertFalse(DestructiveActionPolicy.usesUndo(DestructiveOp.DELETE_SELECTED_SEARCH_HISTORY))
    }

    @Test
    fun historyDestructiveCopyStatesCountAndIsNotScary() {
        assertEquals("删除 3 条搜索历史？", DestructiveCopy.deleteSelectedHistoryTitle(3))
        assertEquals(
            "只删除本机的搜索关键词记录，不影响离线缓存、片源和播放历史。",
            DestructiveCopy.deleteSelectedHistoryBody()
        )
        assertEquals("已删除 3 条搜索历史", DestructiveCopy.deletedHistoryCountToast(3))
        assertEquals("已删除「甄嬛传」，5 秒内可撤销", DestructiveCopy.singleDeleteHistoryUndoMessage("甄嬛传"))
    }
}
