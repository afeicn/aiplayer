package com.afeicn.aiplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条搜索历史：关键词（已去掉首尾空白）+ 最近一次搜索的时间戳。
 *
 * 为什么要有这个类型：在线搜索的关键词是用户手打的原文，重复搜索时不算新条目，而是
 * 把旧条目「提到最前」并刷新时间戳——所以时间戳必须跟着条目走，不能靠列表顺序猜。
 */
data class SearchHistoryEntry(
    val keyword: String,
    val searchedAt: Long
) {
    /** 去重/删除用的归一化键：忽略大小写与首尾空白（`DeepSeek` 与 `deepseek` 视为同一条）。 */
    val key: String get() = keyword.trim().lowercase()
}

/**
 * 搜索历史的纯逻辑：记录（去重 + 提到最前 + 截断上限）、单条/批量删除、撤销插回。
 * 不碰 SharedPreferences、不碰 Compose，全部可在纯 JVM 单测里锁定行为。
 *
 * 上限取 10 条：用户明确要求「最多保留 10 条」，超出丢弃最旧的。
 */
object SearchHistoryRules {

    /** 默认最多保留的历史条数（用户明确要求 10）。 */
    const val MAX_ENTRIES: Int = 10

    /**
     * 归一化关键词：去首尾空白，空白/纯空格返回 null（表示「不该记进历史」）。
     * 中间的空格保留——「流浪 地球」和「流浪地球」是两次不同的搜索。
     */
    fun sanitize(keyword: String): String? = keyword.trim().takeIf { it.isNotEmpty() }

    /** 空历史时整块「搜索历史」区域不显示（不要显示空区块）。 */
    fun isVisible(history: List<SearchHistoryEntry>): Boolean = history.isNotEmpty()

    /**
     * 记录一次真实发起的搜索：
     *  1. 空白关键词直接原样返回（不新增、不打乱既有顺序）；
     *  2. 同一关键词（忽略大小写）只保留一条，并提到最前、刷新时间戳；
     *  3. 超过 [limit] 条时丢弃最旧的（列表尾部）。
     */
    fun record(
        history: List<SearchHistoryEntry>,
        keyword: String,
        now: Long,
        limit: Int = MAX_ENTRIES
    ): List<SearchHistoryEntry> {
        val clean = sanitize(keyword) ?: return history
        val key = clean.lowercase()
        val rest = history.filterNot { it.key == key }
        return (listOf(SearchHistoryEntry(clean, now)) + rest).take(limit.coerceAtLeast(1))
    }

    /** 删除单条（按归一化键匹配）；不存在时原样返回。 */
    fun remove(history: List<SearchHistoryEntry>, keyword: String): List<SearchHistoryEntry> {
        val key = keyword.trim().lowercase()
        if (key.isEmpty()) return history
        return history.filterNot { it.key == key }
    }

    /** 批量删除（管理模式的「删除选中」）；不存在的关键词忽略，其余保持原顺序。 */
    fun removeAll(history: List<SearchHistoryEntry>, keywords: Collection<String>): List<SearchHistoryEntry> {
        val keys = keywords.mapNotNull { sanitize(it)?.lowercase() }.toSet()
        if (keys.isEmpty()) return history
        return history.filterNot { it.key in keys }
    }

    /**
     * 撤销删除：把条目按时间戳插回原位置（时间戳倒序），再做一次去重与截断。
     * 单条删除用「撤销」而不是确认弹窗，所以插回必须回到原来那一位，不能直接塞到末尾。
     */
    fun restore(
        history: List<SearchHistoryEntry>,
        entry: SearchHistoryEntry,
        limit: Int = MAX_ENTRIES
    ): List<SearchHistoryEntry> {
        val merged = history.filterNot { it.key == entry.key } + entry
        return merged.sortedByDescending { it.searchedAt }.take(limit.coerceAtLeast(1))
    }

    /** 多选删除的选中关键词（只保留仍在历史里的，避免删完留下幽灵计数）。 */
    fun selectedKeywords(history: List<SearchHistoryEntry>, selected: Set<String>): List<String> {
        val keys = selected.map { it.trim().lowercase() }.toSet()
        if (keys.isEmpty()) return emptyList()
        return history.filter { it.key in keys }.map { it.keyword }
    }
}

/**
 * 历史多选的勾选集合：直接复用 [OfflineSelection] 的不可变 id 集合语义
 * （`toggle` / `selectAll` / `toggleAll` / `isAllSelected` / `count`），历史项以关键词当 id。
 * 不另造一份是因为「勾选删除」的规则两边完全一致，重复实现只会让两处慢慢跑偏。
 */
typealias SearchHistorySelection = OfflineSelection

/**
 * [SearchHistoryEntry] 的 JSON 编解码（纯函数，不碰 SharedPreferences，方便纯 JVM 单测）。
 * 坏数据整段丢掉（返回空列表），单条缺字段按缺省值补，不因为一条脏数据让历史全部消失。
 */
object SearchHistoryCodec {

    fun encode(entries: List<SearchHistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("keyword", entry.keyword)
                    put("searchedAt", entry.searchedAt)
                }
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<SearchHistoryEntry> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val keyword = obj.optString("keyword")
                if (keyword.isBlank()) continue
                add(SearchHistoryEntry(keyword = keyword, searchedAt = obj.optLong("searchedAt")))
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * 搜索历史持久化（SharedPreferences + JSON，与 [OfflineStore] 同一套风格，不引入新依赖）。
 * 读的时候统一按时间戳倒序，保证「最近搜索的在最前」不依赖写入顺序。
 */
class SearchHistoryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun entries(): List<SearchHistoryEntry> =
        prefs.getString(KEY, null)
            ?.let { SearchHistoryCodec.decode(it) }
            ?.sortedByDescending { it.searchedAt }
            ?: emptyList()

    /** 记录一次真实搜索，返回记录后的完整历史（UI 直接拿去渲染）。 */
    fun record(keyword: String, now: Long = System.currentTimeMillis()): List<SearchHistoryEntry> =
        SearchHistoryRules.record(entries(), keyword, now).also(::persist)

    fun remove(keyword: String): List<SearchHistoryEntry> =
        SearchHistoryRules.remove(entries(), keyword).also(::persist)

    fun removeAll(keywords: Collection<String>): List<SearchHistoryEntry> =
        SearchHistoryRules.removeAll(entries(), keywords).also(::persist)

    /** 撤销删除：把条目插回时间戳对应的位置。 */
    fun restore(entry: SearchHistoryEntry): List<SearchHistoryEntry> =
        SearchHistoryRules.restore(entries(), entry).also(::persist)

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun persist(list: List<SearchHistoryEntry>) {
        prefs.edit().putString(KEY, SearchHistoryCodec.encode(list)).apply()
    }

    private companion object {
        const val KEY = "entries_v1"
    }
}
