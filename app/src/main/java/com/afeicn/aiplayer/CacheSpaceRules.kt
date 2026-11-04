package com.afeicn.aiplayer

/**
 * 缓存占用统计的刷新节奏（纯 JVM，单测覆盖）。
 *
 * 下载目录是 SimpleCache 的分片树，一部剧就可能上千个文件，遍历一次是毫秒级到百毫秒级；
 * 首页/下载页的轮询是 800ms 一次，绝不能每次都扫盘。这里按 TTL 去重，并且同一时间只跑一个统计。
 */
internal object CacheSpaceRules {

    /** 缓存占用重算间隔。 */
    const val TTL_MS = 15_000L

    fun shouldRefresh(
        nowMs: Long,
        lastComputedAtMs: Long,
        running: Boolean,
        force: Boolean
    ): Boolean {
        // 同一时间只允许一个统计任务，force 也不能叠第二个。
        if (running) return false
        // lastComputedAtMs = 0 表示本次进程还没算过（elapsedRealtime 本身可能小于 TTL）。
        if (lastComputedAtMs == 0L) return true
        return force || nowMs - lastComputedAtMs >= TTL_MS
    }
}
