package com.afeicn.aiplayer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 存量离线下载数据的「命名规整」迁移（纯函数，不碰 SharedPreferences，方便纯 JVM 单测）。
 *
 * 背景：`EpisodeNaming` 只解决了**显示层**——老数据落库时仍是 `title = 我不是大师[全集]`、
 * `episodeName = 第01集`，没有季/集号字段。本迁移器把**存储层**也规整到同一套规则：
 *
 *  - `title`：`MediaTitle` 清洗（去 HTML 残渣）→ 剥掉 `[全集]`/`[完结]`/`[更新至24集]` 等尾部状态标注；
 *  - `season`：从标题里识别 `第1季` / `S01` / `Season 1` 并**从剧名里剥掉**，单独落库（0 = 没有季）；
 *  - `episodeNumber`：现有字段优先，其次从 `episodeName` 解析（`第01集` → 1），
 *    最后从标题里找（只在标题本身就是「第08集 / EP08」这类强集名信号时，避免把 `流浪地球2` 当成第 2 集）；
 *  - `episodeName`：有集号时统一成 `第N集`（整数不补零），没有集号则保留清洗后的原集名。
 *
 * 幂等：对已经迁移过的条目再跑一次，`OfflineItem` 完全相同（[OfflineItemMigratorTest] 锁死）。
 *
 * 版本化：结果由 [OfflineStore] 用 prefs 里的 `schema_version` 控制只跑一次。
 */
object OfflineItemMigrator {

    /** 当前数据结构版本；老库没有该字段时按 0 处理。 */
    const val SCHEMA_VERSION = 1

    /**
     * 一次迁移的结果。
     *
     * @param raw 迁移后的 JSON；[failed] 为 true 时等于原始值（调用方不得写盘）
     * @param changed 内容是否真的发生变化（用于「有变更才刷新 UI」）
     * @param migrated 成功处理的条目数
     * @param preserved 无法解析、原样保留的条目数（坏 JSON / 非对象）
     * @param failed 整段 JSON 都无法解析（不推进版本、不破坏原数据）
     */
    data class Outcome(
        val raw: String?,
        val changed: Boolean,
        val migrated: Int,
        val preserved: Int,
        val failed: Boolean
    )

    /** 版本是否落后于当前结构（落后才需要迁移）。 */
    fun needsMigration(storedVersion: Int): Boolean = storedVersion < SCHEMA_VERSION

    /**
     * 版本闸门：已是最新版本直接返回 null（**不重复迁移**）；否则返回本次迁移结果。
     *
     * 单独抽成纯函数，便于单测锁定「已是最新 → 跳过」而不用起 Android 环境。
     */
    fun migrateIfNeeded(storedVersion: Int, raw: String?): Outcome? =
        if (!needsMigration(storedVersion)) null else migrateEncoded(raw)

    /** 单条迁移（幂等）。 */
    fun migrate(item: OfflineItem): OfflineItem {
        val cleanedTitle = MediaTitle.clean(item.title)
        val seasoned = EpisodeNaming.splitSeason(cleanedTitle)
        // 标题整段只有季/状态标注（例如 `第1季`）时剥完会变空：宁可保留清洗后的标题，也不丢数据。
        val baseTitle = seasoned.name.ifBlank { cleanedTitle }

        val season = seasoned.number
            ?: item.season.takeIf { it > 0 }
            ?: 0

        val number = item.episodeNumber.takeIf { it > 0 }
            ?: EpisodeNaming.episodeNumber(item.episodeName)
            ?: titleEpisodeNumber(baseTitle)
            ?: titleEpisodeNumber(cleanedTitle)
            ?: 0

        val episodeName = if (number > 0) "第${number}集" else MediaTitle.clean(item.episodeName)

        return item.copy(
            title = baseTitle,
            episodeName = episodeName,
            season = season,
            episodeNumber = number
        )
    }

    /** 批量迁移（纯内存；单条失败不影响其它条目）。 */
    fun migrateAll(items: List<OfflineItem>): List<OfflineItem> = items.map(::migrate)

    /**
     * 整份 JSON 的迁移：逐条读 → 逐条写，**坏条目原样保留**。
     *
     * 整段 JSON 都解析不了时返回 `failed = true` 且 `raw` 为原值，调用方（[OfflineStore]）据此
     * 不写盘、不推进版本，避免把「读不出来」误判成「迁移完成」。
     */
    fun migrateEncoded(raw: String?): Outcome {
        if (raw.isNullOrBlank()) return Outcome(raw, changed = false, migrated = 0, preserved = 0, failed = false)
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return Outcome(raw, changed = false, migrated = 0, preserved = 0, failed = true)

        val result = JSONArray()
        var changed = false
        var migrated = 0
        var preserved = 0
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            val obj = value as? JSONObject
            if (obj == null) {
                // 非对象条目（坏数据）：原样放回，不连坐其它条目。
                result.put(value)
                preserved++
                continue
            }
            val original = runCatching { OfflineItemCodec.decodeItem(obj) }.getOrNull()
            if (original == null) {
                result.put(obj)
                preserved++
                continue
            }
            val after = runCatching { OfflineItemCodec.encodeItem(migrate(original)) }.getOrNull()
            if (after == null) {
                result.put(obj)
                preserved++
                continue
            }
            if (after.toString() != obj.toString()) changed = true
            result.put(after)
            migrated++
        }
        val next = result.toString()
        return Outcome(
            raw = next,
            changed = changed || next != raw,
            migrated = migrated,
            preserved = preserved,
            failed = false
        )
    }

    /**
     * 从标题里找集号：只认强集名信号（`第N集/期/话/回`、`EP N`）。
     * 不用「尾部数字」兜底，避免把 `流浪地球2`、`请回答1988` 这类作品名里的数字当集号。
     */
    private fun titleEpisodeNumber(title: String): Int? {
        val text = title.trim()
        if (text.isEmpty() || !EpisodeListParser.isStrongEpisodeName(text)) return null
        return EpisodeNaming.episodeNumber(text)
    }
}
