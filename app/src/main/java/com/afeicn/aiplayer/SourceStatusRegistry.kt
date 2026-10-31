package com.afeicn.aiplayer

import android.content.Context
import org.json.JSONObject

/**
 * 「源健康状况」的本地记录：域名 → 状态 + 最后检查时间。
 *
 * 与 [SourceHealth]（按源 id 记连续失败阶梯冷却）分开：
 *  - [SourceHealth] 是自动风控，面向「网络抖动 / 站点临时故障」；
 *  - 本文件面向两类**确定性结论**：站方永久封锁（如 `witzsb.com` 全站拒绝访问）与
 *    Cloudflare 挑战失败（30 分钟内不再重试，避免每次搜索都等满 25 秒）。
 *
 * 只按域名记，不碰用户的源配置：用户填的源一条都不删、`enabled` 也不擅自改。
 * 搜索时按 [skipReason] 跳过，源管理页按 [SourceStatusRules] 给出的文案展示原因。
 */

enum class SourceAvailability { OK, BLOCKED, CHALLENGE_FAILED }

data class SourceStatusEntry(
    val availability: SourceAvailability = SourceAvailability.OK,
    val reason: String = "",
    /** 最后一次检查 / 状态变化的时间戳（毫秒）。 */
    val checkedAt: Long = 0L,
    /** 挑战失败的重试时间点；到期后自动视为可用。 */
    val retryAfter: Long = 0L
)

/** 纯规则：内置封锁清单、挑战失败冷却状态机、展示文案。不碰 Android 框架，便于单测。 */
object SourceStatusRules {

    /** 挑战失败后的冷却：30 分钟内不再对该域名发起预热。 */
    const val CHALLENGE_RETRY_MS = 30L * 60L * 1000L

    const val BLOCKED_REASON = "站方封锁，无法访问"
    const val CHALLENGE_REASON = "需要验证，暂时跳过"
    const val CHALLENGE_COOLDOWN_NOTE = "需要验证，暂时跳过（30 分钟内不再重试）"

    /** `booksru.net` 的备注位：能过，但首次要跑 Cloudflare 挑战。 */
    const val CLOUDFLARE_NOTE = "需过 Cloudflare 验证，首次较慢（约 17 秒）"

    /** 站方全站封锁、换 IP 也过不去的域名（只标记，不访问）。 */
    private val BUILT_IN_BLOCKED = mapOf(
        "witzsb.com" to BLOCKED_REASON
    )

    /** 能访问、但需要额外说明的域名。 */
    private val BUILT_IN_NOTES = mapOf(
        "booksru.net" to CLOUDFLARE_NOTE
    )

    /** 域名归一化复用 cookie store 的规则（去协议/端口/www.），两处 key 必须一致。 */
    fun hostOf(base: String): String = CloudflareCookieStore.normalizeHost(base)

    fun builtInBlocked(host: String): String? = lookup(host, BUILT_IN_BLOCKED)

    fun builtInNote(host: String): String? = lookup(host, BUILT_IN_NOTES)

    /** 挑战失败 → 进入 30 分钟冷却。 */
    fun challengeFailed(now: Long): SourceStatusEntry = SourceStatusEntry(
        availability = SourceAvailability.CHALLENGE_FAILED,
        reason = CHALLENGE_REASON,
        checkedAt = now,
        retryAfter = now + CHALLENGE_RETRY_MS
    )

    /** 预热成功 / 手动恢复 → 回到正常。 */
    fun recovered(now: Long): SourceStatusEntry =
        SourceStatusEntry(availability = SourceAvailability.OK, checkedAt = now)

    fun isChallengeCoolingDown(entry: SourceStatusEntry?, now: Long): Boolean =
        entry != null &&
            entry.availability == SourceAvailability.CHALLENGE_FAILED &&
            entry.retryAfter > now

    /**
     * 合并内置结论与本地记录：
     * 内置封锁永远优先（即使本地曾预热成功）；挑战失败冷却到期后自动回到 OK。
     */
    fun effective(host: String, stored: SourceStatusEntry?, now: Long): SourceStatusEntry {
        builtInBlocked(host)?.let { reason ->
            return SourceStatusEntry(
                availability = SourceAvailability.BLOCKED,
                reason = reason,
                checkedAt = stored?.checkedAt ?: 0L
            )
        }
        if (isChallengeCoolingDown(stored, now)) return stored!!
        return stored?.takeIf { it.availability == SourceAvailability.OK } ?: SourceStatusEntry()
    }

    /** 搜索时跳过该源的原因；null = 可以搜。 */
    fun skipReason(host: String, stored: SourceStatusEntry?, now: Long): String? =
        effective(host, stored, now)
            .takeIf { it.availability != SourceAvailability.OK }
            ?.reason
            ?.takeIf { it.isNotBlank() }

    /** 源管理页的备注位（与是否被跳过无关）。 */
    fun noteFor(host: String, stored: SourceStatusEntry?, now: Long): String? {
        val status = effective(host, stored, now)
        if (status.availability != SourceAvailability.OK) return status.reason
        return builtInNote(host)
    }

    private fun lookup(host: String, table: Map<String, String>): String? {
        val normalized = hostOf(host)
        if (normalized.isEmpty()) return null
        table[normalized]?.let { return it }
        // 子域名也算：`m.witzsb.com` 与 `witzsb.com` 同一站方。
        return table.entries.firstOrNull { normalized.endsWith(".${it.key}") }?.value
    }
}

/**
 * 持久化实现：SharedPreferences 按归一化域名存一条 JSON。
 * 读失败 / 脏数据一律当「没有记录」，不阻断搜索。
 */
class SourceStatusStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("source_status_v1", Context.MODE_PRIVATE)

    /** 只读本地记录（不含内置封锁）。 */
    fun stored(base: String): SourceStatusEntry? {
        val key = SourceStatusRules.hostOf(base)
        if (key.isEmpty()) return null
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SourceStatusEntry(
                availability = runCatching {
                    SourceAvailability.valueOf(json.optString("availability"))
                }.getOrDefault(SourceAvailability.OK),
                reason = json.optString("reason"),
                checkedAt = json.optLong("checkedAt", 0L),
                retryAfter = json.optLong("retryAfter", 0L)
            )
        }.getOrNull()
    }

    fun effective(base: String, now: Long = System.currentTimeMillis()): SourceStatusEntry =
        SourceStatusRules.effective(base, stored(base), now)

    fun skipReason(base: String, now: Long = System.currentTimeMillis()): String? =
        SourceStatusRules.skipReason(base, stored(base), now)

    fun skipReason(source: OnlineVideoSource, now: Long = System.currentTimeMillis()): String? =
        skipReason(source.normalizedBase, now)

    /** 站方封锁（只标记、不访问）——用于「添加 / 重新探测」前的拦截。 */
    fun isBlocked(base: String, now: Long = System.currentTimeMillis()): Boolean =
        effective(base, now).availability == SourceAvailability.BLOCKED

    fun noteFor(base: String, now: Long = System.currentTimeMillis()): String? =
        SourceStatusRules.noteFor(base, stored(base), now)

    fun markChallengeFailed(base: String, now: Long = System.currentTimeMillis()) =
        write(base, SourceStatusRules.challengeFailed(now))

    fun markWarmed(base: String, now: Long = System.currentTimeMillis()) =
        write(base, SourceStatusRules.recovered(now))

    fun clear(base: String) {
        val key = SourceStatusRules.hostOf(base)
        if (key.isNotEmpty()) prefs.edit().remove(key).apply()
    }

    private fun write(base: String, entry: SourceStatusEntry) {
        val key = SourceStatusRules.hostOf(base)
        if (key.isEmpty()) return
        prefs.edit().putString(
            key,
            JSONObject().apply {
                put("availability", entry.availability.name)
                put("reason", entry.reason)
                put("checkedAt", entry.checkedAt)
                put("retryAfter", entry.retryAfter)
            }.toString()
        ).apply()
    }
}

/**
 * 预热门禁：包一层 [CloudflareWarmup]，把结果记进 [SourceStatusStore]。
 *
 *  - 已知封锁 / 30 分钟冷却中 → 直接返回 false，不启 WebView、不发任何请求；
 *  - 预热成功 → 清除冷却记录；
 *  - 预热失败（含 Turnstile 要真人点击、超时）→ 标记 challenge_failed，30 分钟内不再重试。
 */
class GuardedCloudflareWarmer(
    private val delegate: CloudflareWarmup,
    private val status: SourceStatusStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) : CloudflareWarmup {

    override fun warm(host: String): Boolean {
        val normalized = SourceStatusRules.hostOf(host)
        if (normalized.isEmpty()) return false
        // 封锁 / 冷却中的站连 WebView 都不启，避免每次都等满 25 秒。
        if (status.skipReason(normalized, now()) != null) return false
        val warmed = runCatching { delegate.warm(normalized) }.getOrDefault(false)
        if (warmed) status.markWarmed(normalized, now()) else status.markChallengeFailed(normalized, now())
        return warmed
    }
}
