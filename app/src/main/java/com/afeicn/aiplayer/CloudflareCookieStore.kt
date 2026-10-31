package com.afeicn.aiplayer

import java.util.concurrent.ConcurrentHashMap

/**
 * Cloudflare 挑战通过后拿到的 cookie（一般是 `cf_clearance` / `__cf_bm`）的进程内缓存。
 *
 * 只存内存、不落盘：`cf_clearance` 与出口 IP、UA 绑定，重启后重新预热只要几秒，
 * 不值得为此引入加密存储。过期由 [cookieFor] 判定（[TTL_MS]），拿到 403 时由调用方
 * 重新预热覆盖。
 *
 * 域名 key 统一去掉前导 `www.`，这样 `www.booksru.net` 与 `booksru.net` 共用同一份 cookie。
 */
object CloudflareCookieStore {

    /** cookie 有效期：8 小时（`cf_clearance` 实际通常 30 分钟到几小时，这里取宽松值）。 */
    const val TTL_MS = 8 * 60 * 60 * 1000L

    private data class Entry(val cookie: String, val warmedAt: Long, val userAgent: String?)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 时钟单独抽出来，纯 JVM 单测可注入假时间验证过期逻辑。 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 取该域名当前可用的 cookie；没有或已过期返回 null。 */
    fun cookieFor(host: String): String? {
        val entry = entries[normalizeHost(host)] ?: return null
        if (clock() - entry.warmedAt >= TTL_MS) return null
        return entry.cookie
    }

    /**
     * 取预热时使用的 User-Agent。
     *
     * Cloudflare 的 `cf_clearance` 与客户端 IP + UA 绑定：用另一条 UA 发请求会被重新挑战。
     * 所以带 cookie 的请求必须复用预热时的 UA。没有记录时返回 null，调用方回退桌面 UA。
     */
    fun userAgentFor(host: String): String? {
        val entry = entries[normalizeHost(host)] ?: return null
        if (clock() - entry.warmedAt >= TTL_MS) return null
        return entry.userAgent?.takeIf { it.isNotBlank() }
    }

    /** 存一份 cookie（覆盖旧值并刷新预热时间）。空 cookie 忽略。[userAgent] 供带 cookie 请求复用。 */
    fun put(host: String, rawCookie: String, userAgent: String? = null) {
        val key = normalizeHost(host)
        val cookie = rawCookie.trim()
        if (key.isEmpty() || cookie.isEmpty()) return
        entries[key] = Entry(
            cookie = cookie,
            warmedAt = clock(),
            userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /** 挑战失败 / cookie 失效时清掉，下次请求重新触发预热。 */
    fun invalidate(host: String) {
        entries.remove(normalizeHost(host))
    }

    /** 上次预热成功的时间戳（毫秒）；从未预热过返回 0。过期的条目仍返回原始时间，供调试显示。 */
    fun lastWarmedAt(host: String): Long = entries[normalizeHost(host)]?.warmedAt ?: 0L

    /**
     * 域名归一化：小写、剥掉协议 / 路径 / 端口、去掉前导 `www.` 和尾点。
     * `https://WWW.Booksru.net:443/some/path` → `booksru.net`。
     */
    fun normalizeHost(host: String): String {
        var h = host.trim().lowercase()
        h = h.substringAfter("://", h)
        h = h.substringBefore('/')
        h = h.substringBefore('?')
        h = h.substringBefore(':')
        return h.removePrefix("www.").trimEnd('.')
    }

    /** 仅供单测隔离全局状态。 */
    internal fun clearAll() {
        entries.clear()
    }

    internal fun resetClock() {
        clock = { System.currentTimeMillis() }
    }
}
