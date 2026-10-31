package com.afeicn.aiplayer

/**
 * 下载测速：用相邻两次字节采样算瞬时速度，再做指数滑动平均。
 *
 * Media3 的 `Download` 不带速度字段，只能自己按采样差分。纯 JVM 类，方便单测。
 * 暂停 / 等待网络时调用方不展示速度，这里保留上一次平滑值，恢复下载后第一帧不会突然归零。
 */
class DownloadSpeedTracker(private val smoothing: Float = 0.35f) {

    private data class Sample(val bytes: Long, val atMs: Long)

    private val samples = mutableMapOf<String, Sample>()
    private val speeds = mutableMapOf<String, Long>()

    /**
     * 记录一次采样。
     *
     * @return 平滑后的字节/秒；首次采样、时间未推进、字节回退时返回上一次的值（可能为 null）。
     */
    @Synchronized
    fun sample(id: String, bytes: Long, nowMs: Long): Long? {
        val previous = samples[id]
        samples[id] = Sample(bytes, nowMs)
        if (previous == null) return speeds[id]
        val elapsed = nowMs - previous.atMs
        val delta = bytes - previous.bytes
        // 时间没走 / 字节回退（重启任务、索引重写）都不算速度，避免出现负值或无穷大。
        if (elapsed <= 0L || delta < 0L) return speeds[id]
        if (delta == 0L && elapsed < 500L) return speeds[id]
        val instant = (delta * 1000.0 / elapsed).toLong()
        val smoothed = speeds[id]?.let { (it * (1f - smoothing) + instant * smoothing).toLong() } ?: instant
        speeds[id] = smoothed
        return smoothed
    }

    @Synchronized
    fun speedOf(id: String): Long? = speeds[id]?.takeIf { it > 0L }

    /** 只保留仍在下载的任务，避免任务结束后 Map 常驻增长。 */
    @Synchronized
    fun retain(ids: Set<String>) {
        samples.keys.retainAll(ids)
        speeds.keys.retainAll(ids)
    }

    @Synchronized
    fun clear() {
        samples.clear()
        speeds.clear()
    }
}
